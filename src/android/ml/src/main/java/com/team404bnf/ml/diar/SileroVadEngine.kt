package com.team404bnf.ml.diar

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.team404bnf.ml.ModelCatalog
import com.team404bnf.ml.ModelId
import com.team404bnf.ml.OnnxSessionManager
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Silero VAD v6.2.1: 64 context + 512 new samples; state [2,1,128]. */
class SileroVadEngine(private val sessions: OnnxSessionManager) {
    private val model = ModelCatalog.onnxFilesFor(ModelId.SILERO_VAD).single()
    private val env = OrtEnvironment.getEnvironment()

    suspend fun probabilities(mono16k: FloatArray): List<Float> = sessions.withSession(model) { session ->
        compute(session, mono16k)
    }

    private fun compute(session: OrtSession, samples: FloatArray): List<Float> {
        require(samples.size <= 16_000 * 300) { "M3 debug limit is 5 minutes" }
        require(samples.all { it.isFinite() && it in -1.0001f..1.0001f })
        require(session.inputNames.containsAll(setOf("input", "state", "sr"))) { "Unexpected Silero ONNX inputs" }
        var state = FloatArray(256)
        val context = FloatArray(64)
        val block = FloatArray(576)
        val result = ArrayList<Float>((samples.size + 511) / 512)
        var offset = 0
        while (offset < samples.size) {
            context.copyInto(block, destinationOffset = 0)
            val count = minOf(512, samples.size - offset)
            samples.copyInto(block, destinationOffset = 64, startIndex = offset, endIndex = offset + count)
            if (count < 512) java.util.Arrays.fill(block, 64 + count, 576, 0f)
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
            offset += count
        }
        return result
    }
}
