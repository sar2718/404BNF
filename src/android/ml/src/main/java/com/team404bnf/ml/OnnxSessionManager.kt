package com.team404bnf.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serialize access to model sessions so an 8GB mobile device cannot accidentally
 * load multiple large encoders at once. A single task may request two small RNNT
 * decoder/joint sessions together; sessions are always released in finally.
 */
class OnnxSessionManager(private val models: ModelManager) {
    private val sessionLock = Mutex()
    suspend fun <T> withSession(modelAsset: ModelAsset, work: (OrtSession) -> T): T =
        withSessions(listOf(modelAsset)) { work(it.single()) }

    suspend fun <T> withSessions(assets: List<ModelAsset>, work: (List<OrtSession>) -> T): T =
        withContext(Dispatchers.IO) {
            require(assets.isNotEmpty() && assets.size <= 2) { "At most 2 RNNT sessions at once" }
            require(assets.all { it.fileName.endsWith(".onnx") })
            sessionLock.withLock {
                val files = assets.map { models.checkedFile(it) }
                val opened = mutableListOf<OrtSession>()
                try {
                    for (file in files) {
                        OrtSession.SessionOptions().use { options ->
                            options.setIntraOpNumThreads(2)
                            opened += OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
                        }
                    }
                    work(opened)
                } finally {
                    opened.asReversed().forEach { it.close() }
                }
            }
        }
}
