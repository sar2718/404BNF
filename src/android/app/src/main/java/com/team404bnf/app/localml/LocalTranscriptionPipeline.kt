package com.team404bnf.app.localml

import android.content.Context
import com.team404bnf.ml.ModelManager
import com.team404bnf.ml.OnnxSessionManager
import com.team404bnf.ml.asr.GigaamAsrEngine
import com.team404bnf.ml.diar.WespeakerDiarizationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Single locally running M2+M3 inference process, shared by the UI's recordings. */
internal class LocalTranscriptionPipeline(private val context: Context) {
    private val manager = ModelManager(context.applicationContext)
    private val sessions = OnnxSessionManager(manager)

    suspend fun transcribe(file: File, progress: (String) -> Unit): String = gate.withLock {
        progress("Проверяем модели…")
        val missing = manager.verifyInstalled()
        check(missing.isEmpty()) {
            "Модели не готовы (${missing.size} файлов). Нажмите «Скачать модели»."
        }

        val pcmFile = File.createTempFile("audio_decode", ".pcm", context.cacheDir)
        try {
            currentCoroutineContext().ensureActive()
            progress("Читаем аудиофайл…")
            val totalSamples = AudioToMono16k.decode(file, pcmFile)
            val duration = totalSamples / 16_000.0

            progress("Распознаём речь (GigaAM)…")
            val started = System.nanoTime()
            val asr = GigaamAsrEngine(manager, sessions).recognize(pcmFile, totalSamples) { progress(it) }

            currentCoroutineContext().ensureActive()
            progress("Определяем говорящих (Silero + WeSpeaker)…")
            val diar = WespeakerDiarizationEngine(sessions).diarize(pcmFile, totalSamples) { progress(it) }

            currentCoroutineContext().ensureActive()
            progress("Создаём JSON v1…")
            val digest = withContext(Dispatchers.IO) { sha256(file) }
            val elapsed = (System.nanoTime() - started) / 1e9
            val json = LocalTranscriptJson.build(asr, diar, duration, digest, elapsed)

            currentCoroutineContext().ensureActive()
            json
        } finally {
            pcmFile.delete()
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private companion object {
        // Avoid parallel FP32 encoder sessions from multiple playback screens.
        val gate = Mutex()
    }
}
