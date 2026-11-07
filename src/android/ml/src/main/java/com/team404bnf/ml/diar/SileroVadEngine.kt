package com.team404bnf.ml.diar

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.team404bnf.ml.ModelCatalog
import com.team404bnf.ml.ModelId
import com.team404bnf.ml.OnnxSessionManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Silero VAD v6.2.1: 64 context + 512 new samples; state [2,1,128]. */
class SileroVadEngine(private val sessions: OnnxSessionManager) {
    private val model = ModelCatalog.onnxFilesFor(ModelId.SILERO_VAD).single()
    private val env = OrtEnvironment.getEnvironment()

    suspend fun probabilities(pcmFile: File, totalSamples: Long): List<Float> {
        val jobContext = currentCoroutineContext()
        return sessions.withSession(model) { session ->
            compute(session, pcmFile, totalSamples, jobContext)
        }
    }

    private fun compute(
        session: OrtSession, pcmFile: File, totalSamples: Long,
        jobContext: kotlin.coroutines.CoroutineContext
    ): List<Float> {
        require(totalSamples >= 0L) { "Invalid totalSamples" }
        require(session.inputNames.containsAll(setOf("input", "state", "sr"))) { "Unexpected Silero ONNX inputs" }
        var state = FloatArray(256)
        val context = FloatArray(64)
        val block = FloatArray(576)
        val result = ArrayList<Float>(((totalSamples + 511) / 512).toInt())

        val readBufferSize = 65536 // 1MB buffer
        val byteBuffer = ByteArray(readBufferSize * 4)
        val floatBuffer = FloatArray(readBufferSize)

        RandomAccessFile(pcmFile, "r").use { raf ->
            var offset = 0L
            while (offset < totalSamples) {
                jobContext.ensureActive()
                val toRead = minOf(readBufferSize.toLong(), totalSamples - offset).toInt()
                raf.readFully(byteBuffer, 0, toRead * 4)
                ByteBuffer.wrap(byteBuffer, 0, toRead * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floatBuffer, 0, toRead)

                var localOff = 0
                while (localOff < toRead) {
                    context.copyInto(block, destinationOffset = 0)
                    val chunkCount = minOf(512, toRead - localOff)
                    floatBuffer.copyInto(block, destinationOffset = 64, startIndex = localOff, endIndex = localOff + chunkCount)
                    if (chunkCount < 512) java.util.Arrays.fill(block, 64 + chunkCount, 576, 0f)
                    val nextContext = block.copyOfRange(512, 576)

                    OnnxTensor.createTensor(env, FloatBuffer.wrap(block), longArrayOf(1, 576)).use { input ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { st ->
                            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16_000)), longArrayOf()).use { sr ->
                                session.run(mapOf("input" to input, "state" to st, "sr" to sr)).use { out ->
                                    val probBuffer = (out[0] as OnnxTensor).floatBuffer
                                    require(probBuffer.remaining() >= 1) { "Silero probability missing" }
                                    val prob = probBuffer.get(0)
                                    require(prob.isFinite() && prob in 0f..1f) { "Invalid Silero probability" }
                                    val stateBuf = (out[1] as OnnxTensor).floatBuffer
                                    require(stateBuf.remaining() == 256) { "Unexpected Silero state shape" }
                                    state = FloatArray(256).also { stateBuf.get(it) }
                                    result.add(prob)
                                }
                            }
                        }
                    }
                    nextContext.copyInto(context)
                    localOff += chunkCount
                }
                offset += toRead
            }
        }
        return result
    }
}
