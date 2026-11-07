package com.team404bnf.ml.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.team404bnf.ml.ModelAsset
import com.team404bnf.ml.ModelCatalog
import com.team404bnf.ml.ModelId
import com.team404bnf.ml.ModelManager
import com.team404bnf.ml.OnnxSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * GigaAM v3 E2E RNNT FP32 mobile inference.
 * Completely redesigned for arbitrary long file streaming via PCM.
 */
class GigaamAsrEngine(
    private val modelManager: ModelManager,
    private val sessions: OnnxSessionManager,
    private val features: GigaamFeatureExtractor = GigaamFeatureExtractor(),
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder = asset("v3_e2e_rnnt_encoder.onnx")
    private val decoder = asset("v3_e2e_rnnt_decoder.onnx")
    private val joint = asset("v3_e2e_rnnt_joint.onnx")
    private val vocabAsset = ModelCatalog.filesFor(ModelId.GIGAAM_FP32).single {
        it.fileName == "v3_e2e_rnnt_vocab.txt"
    }

    private fun asset(name: String): ModelAsset = ModelCatalog.onnxFilesFor(ModelId.GIGAAM_FP32)
        .single { it.fileName == name }

    suspend fun recognize(
        pcmFile: File, totalSamples: Long,
        onStage: (String) -> Unit = {},
    ): AsrOutput = withContext(Dispatchers.Default) {
        require(totalSamples > 0) { "Empty audio" }
        val vocab = RnntText.Vocab(modelManager.checkedFile(vocabAsset))
        val chunks = QuietPointChunker.split(pcmFile, totalSamples)

        class EncodedChunk(
            val offsetSamples: Long,
            val durationSamples: Int,
            val channels: Int,
            val time: Int,
            val validTime: Int,
            val fileOffset: Long
        )
        val encodedChunks = mutableListOf<EncodedChunk>()
        val jobContext = currentCoroutineContext()

        val encoderOutFile = File.createTempFile("gigaam_enc", ".bin", pcmFile.parentFile)
        try {
            val encChannel = FileOutputStream(encoderOutFile).channel
            try {
                sessions.withSession(encoder) { session ->
                    val audioBytes = ByteArray(22 * 16000 * 4) // max chunk size bytes
                    val audioFloats = FloatArray(22 * 16000)
                    RandomAccessFile(pcmFile, "r").use { raf ->
                        chunks.forEachIndexed { index, chunk ->
                            jobContext.ensureActive()
                            raf.seek(chunk.offsetSamples * 4L)
                            raf.readFully(audioBytes, 0, chunk.durationSamples * 4)
                            ByteBuffer.wrap(audioBytes, 0, chunk.durationSamples * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(audioFloats, 0, chunk.durationSamples)

                            val quantized = FloatArray(chunk.durationSamples) { i ->
                                (audioFloats[i] * 32768f).toInt().coerceIn(-32768, 32767) / 32768f
                            }
                            if (quantized.size >= GigaamFeatureExtractor.FRAME_SIZE) {
                                onStage("Chunk ${index + 1}/${chunks.size}: feature extraction")
                                val inputFeatures = features.extract(quantized)
                                onStage("Chunk ${index + 1}/${chunks.size}: GigaAM encoder")
                                val encoded = encode(session, inputFeatures)

                                val fileOffset = encChannel.position()
                                val buf = ByteBuffer.allocateDirect(encoded.values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                                buf.asFloatBuffer().put(encoded.values)
                                while(buf.hasRemaining()) encChannel.write(buf)

                                encodedChunks.add(EncodedChunk(chunk.offsetSamples, chunk.durationSamples, encoded.channels, encoded.time, encoded.validTime, fileOffset))
                            }
                        }
                    }
                }
            } finally {
                encChannel.close()
            }

            val words = mutableListOf<AsrWord>()
            val texts = mutableListOf<String>()

            if (encodedChunks.isNotEmpty()) {
                RandomAccessFile(encoderOutFile, "r").channel.use { decChannel ->
                    sessions.withSessions(listOf(decoder, joint)) { opened ->
                        encodedChunks.forEachIndexed { index, item ->
                            jobContext.ensureActive()
                            onStage("Chunk ${index + 1}/${encodedChunks.size}: RNNT decoder")

                            val buf = ByteBuffer.allocateDirect(item.channels * item.time * 4).order(ByteOrder.LITTLE_ENDIAN)
                            decChannel.read(buf, item.fileOffset)
                            buf.flip()
                            val values = FloatArray(item.channels * item.time)
                            buf.asFloatBuffer().get(values)
                            val encoded = Encoded(values, item.channels, item.time, item.validTime)

                            val ids = decodeRnnt(opened[0], opened[1], encoded, vocab.blankId) {
                                jobContext.ensureActive()
                            }
                            val partial = RnntText.decode(
                                ids, vocab.tokens, item.offsetSamples / 16_000.0, item.durationSamples / 16_000.0,
                            )
                            texts.add(partial.text.trim())
                            words.addAll(partial.words)
                        }
                    }
                }
            }
            AsrOutput(texts.filter { it.isNotEmpty() }.joinToString(" "), words)
        } finally {
            encoderOutFile.delete()
        }
    }

    private data class Encoded(val values: FloatArray, val channels: Int, val time: Int, val validTime: Int)

    private fun encode(session: OrtSession, feat: GigaamFeatureExtractor.Features): Encoded {
        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(feat.data), longArrayOf(1, 64, feat.frames.toLong()),
        )
        val lenTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(feat.frames.toLong())), longArrayOf(1))
        tensor.use { x ->
            lenTensor.use { len ->
                session.run(mapOf("audio_signal" to x, "length" to len)).use { result ->
                    val values = result.floatTensor("encoded")
                    val encodedTensor = result.named("encoded")
                    val shape = encodedTensor.info.shape
                    require(shape.size == 3 && shape[0] == 1L && shape[1] > 0L && shape[2] > 0L) {
                        "Unexpected GigaAM encoder output: ${shape.contentToString()}"
                    }
                    val time = shape[2].toInt()
                    val valid = result.named("encoded_len").intBuffer.get(0)
                    require(valid in 1..time) { "Invalid GigaAM encoder length: $valid / $time" }
                    require(values.size.toLong() == shape[1] * shape[2]) { "Encoder output size mismatch" }
                    return Encoded(values, shape[1].toInt(), time, valid)
                }
            }
        }
    }

    private fun decodeRnnt(
        decSession: OrtSession, jointSession: OrtSession,
        encoded: Encoded, blankId: Int, cancelCheck: () -> Unit
    ): List<RnntText.Emission> {
        val emitted = ArrayList<RnntText.Emission>()
        var h = FloatArray(320)
        var c = FloatArray(320)
        var decCache: DecState? = null
        var frame = 0
        var perFrame = 0
        val encFrame = FloatArray(encoded.channels)
        while (frame < encoded.validTime) {
            cancelCheck()
            for (channel in 0 until encoded.channels) {
                encFrame[channel] = encoded.values[channel * encoded.time + frame]
            }
            val d = decCache ?: runDecoder(
                decSession,
                if (emitted.isEmpty()) blankId else emitted.last().id,
                h, c,
            ).also { decCache = it }
            val logits = runJoint(jointSession, encFrame, d.dec)
            var bestId = 0
            var best = Float.NEGATIVE_INFINITY
            for (i in logits.indices) {
                if (logits[i] > best) { best = logits[i]; bestId = i }
            }
            require(best.isFinite()) { "GigaAM joint returned invalid logits" }
            if (bestId != blankId) {
                h = d.h
                c = d.c
                decCache = null
                emitted.add(RnntText.Emission(bestId, frame * 0.04))
                perFrame++
                check(emitted.size <= 50_000) { "RNNT safety token limit reached" }
            }
            if (bestId == blankId || perFrame >= 3) {
                frame++
                perFrame = 0
            }
        }
        return emitted
    }

    private data class DecState(val dec: FloatArray, val h: FloatArray, val c: FloatArray)

    private fun runDecoder(session: OrtSession, previousToken: Int, h: FloatArray, c: FloatArray): DecState {
        require(h.size == 320 && c.size == 320)
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(previousToken.toLong())), longArrayOf(1, 1)).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(1, 1, 320)).use { ht ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(1, 1, 320)).use { ct ->
                    session.run(mapOf("x" to x, "h.1" to ht, "c.1" to ct)).use { result ->
                        val d = result.floatTensor("dec")
                        val nh = result.floatTensor("h")
                        val nc = result.floatTensor("c")
                        require(d.size == 320 && nh.size == 320 && nc.size == 320) {
                            "Unexpected RNNT decoder state dimensions"
                        }
                        return DecState(d, nh, nc)
                    }
                }
            }
        }
    }

    private fun runJoint(session: OrtSession, encoderFrame: FloatArray, decoderState: FloatArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(encoderFrame), longArrayOf(1, encoderFrame.size.toLong(), 1)).use { enc ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(decoderState), longArrayOf(1, 320, 1)).use { dec ->
                session.run(mapOf("enc" to enc, "dec" to dec)).use { result ->
                    return result.floatTensor("joint")
                }
            }
        }
    }

    private fun OrtSession.Result.named(name: String): OnnxTensor =
        get(name).orElseThrow { IllegalStateException("GigaAM ONNX output '$name' missing") } as OnnxTensor

    private fun OrtSession.Result.floatTensor(name: String): FloatArray {
        val buf = named(name).floatBuffer
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }
}
