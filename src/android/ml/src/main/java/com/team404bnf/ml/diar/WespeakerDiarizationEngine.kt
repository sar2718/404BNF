package com.team404bnf.ml.diar

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.team404bnf.ml.ModelCatalog
import com.team404bnf.ml.ModelId
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
import kotlin.math.max
import kotlin.math.sqrt

/** M3 offline Silero VAD + WeSpeaker CAM++ + UPGMA speaker clustering. */
class WespeakerDiarizationEngine(
    private val sessions: OnnxSessionManager,
    private val fbank: KaldiFbank80 = KaldiFbank80(),
    private val threshold: Double = 0.5,
    private val clusterDistance: Double = 0.55,
) {
    init {
        require(threshold > 0 && threshold < 1 && clusterDistance > 0 && clusterDistance < 2)
    }
    private val env = OrtEnvironment.getEnvironment()
    private val campp = ModelCatalog.onnxFilesFor(ModelId.WESPEAKER_CAMPP).single()

    suspend fun diarize(
        pcmFile: File, totalSamples: Long,
        onStage: (String) -> Unit = {},
    ): DiarizationOutput = withContext(Dispatchers.Default) {
        val start = System.nanoTime()
        onStage("M3: Silero VAD ONNX")
        val probs = SileroVadEngine(sessions).probabilities(pcmFile, totalSamples)
        val jobContext = currentCoroutineContext()
        jobContext.ensureActive()
        val intervals = DiarizationTimeline.speechIntervals(
            probs, totalSamples.toDouble() / 16_000, threshold = threshold,
        )
        val windows = DiarizationTimeline.windows(intervals)
        onStage("M3: VAD intervals=${intervals.size}; CAM++ windows=${windows.size}")

        val fbankFile = File.createTempFile("fbank_feats", ".bin", pcmFile.parentFile)
        try {
            val fbankOut = FileOutputStream(fbankFile).channel
            var fbankFramesTotal = 0

            val chunkSamples = 16000 * 10
            val overlap = 240
            val audioBuf = ByteArray((chunkSamples + overlap) * 4)
            val floatBuf = FloatArray(chunkSamples + overlap)

            RandomAccessFile(pcmFile, "r").use { raf ->
                var offset = 0L
                while (offset < totalSamples) {
                    jobContext.ensureActive()
                    val readLen = minOf((chunkSamples + overlap).toLong(), totalSamples - offset).toInt()
                    raf.seek(offset * 4L)
                    raf.readFully(audioBuf, 0, readLen * 4)
                    ByteBuffer.wrap(audioBuf, 0, readLen * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floatBuf, 0, readLen)

                    val feats = fbank.extract(floatBuf.copyOfRange(0, readLen), applyCmn = false)

                    val bb = ByteBuffer.allocateDirect(feats.frames * 80 * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asFloatBuffer().put(feats.values)
                    while(bb.hasRemaining()) fbankOut.write(bb)

                    fbankFramesTotal += feats.frames
                    offset += chunkSamples
                }
            }
            fbankOut.close()

            val speechFrames = BooleanArray(fbankFramesTotal)
            for (interval in intervals) {
                val startFrame = (interval.start * 100).toInt().coerceIn(0, fbankFramesTotal)
                val endFrame = (interval.end * 100).toInt().coerceIn(startFrame, fbankFramesTotal)
                for (i in startFrame until endFrame) speechFrames[i] = true
            }

            val globalSum = DoubleArray(80)
            var globalSpeechFramesCount = 0

            RandomAccessFile(fbankFile, "r").use { raf ->
                val bb = ByteBuffer.allocateDirect(80 * 4).order(ByteOrder.LITTLE_ENDIAN)
                val bandVals = FloatArray(80)
                for (i in 0 until fbankFramesTotal) {
                    if ((i and 8191) == 0) jobContext.ensureActive()
                    if (speechFrames[i]) {
                        bb.clear()
                        raf.channel.read(bb, i * 80L * 4L)
                        bb.flip()
                        bb.asFloatBuffer().get(bandVals)
                        for (b in 0 until 80) globalSum[b] += bandVals[b]
                        globalSpeechFramesCount++
                    }
                }
            }

            val globalMean = FloatArray(80) {
                if (globalSpeechFramesCount > 0) (globalSum[it] / globalSpeechFramesCount).toFloat() else 0f
            }

            val embeds = if (windows.isEmpty()) emptyList() else sessions.withSession(campp) { session ->
                require(session.inputNames.contains("feats")) { "CAM++ input 'feats' missing" }

                windows.mapIndexed { index, window ->
                    jobContext.ensureActive()
                    if (index == 0 || (index + 1) % 5 == 0 || index == windows.lastIndex)
                        onStage("M3: WeSpeaker CAM++ ${index + 1}/${windows.size}")

                    val startFrame = (window.start * 100).toInt().coerceIn(0, fbankFramesTotal)
                    val endFrame = (window.end * 100).toInt().coerceIn(startFrame, fbankFramesTotal)
                    val frames = endFrame - startFrame

                    val slice = FloatArray(frames * 80)
                    RandomAccessFile(fbankFile, "r").use { raf ->
                        val bb = ByteBuffer.allocateDirect(frames * 80 * 4).order(ByteOrder.LITTLE_ENDIAN)
                        raf.channel.read(bb, startFrame * 80L * 4L)
                        bb.flip()
                        bb.asFloatBuffer().get(slice)
                    }

                    for (i in 0 until frames) {
                        for (b in 0 until 80) slice[i * 80 + b] -= globalMean[b]
                    }

                    OnnxTensor.createTensor(
                        env, FloatBuffer.wrap(slice), longArrayOf(1, frames.toLong(), 80),
                    ).use { input ->
                        session.run(mapOf("feats" to input)).use { out ->
                            val buffer = (out[0] as OnnxTensor).floatBuffer
                            val emb = FloatArray(buffer.remaining()).also { buffer.get(it) }
                            require(emb.isNotEmpty() && emb.all(Float::isFinite)) { "Invalid WeSpeaker embedding" }
                            val norm = sqrt(emb.sumOf { (it * it).toDouble() }).toFloat()
                            require(norm > 1e-8f) { "Zero WeSpeaker embedding" }
                            FloatArray(emb.size) { i -> emb[i] / norm }
                        }
                    }
                }
            }
            jobContext.ensureActive()
            onStage("M3: clustering cosine distance=$clusterDistance")
            val labels = DiarizationTimeline.cluster(embeds, clusterDistance)
            val timeline = DiarizationTimeline.assignTimeline(intervals, windows, labels)
            val count = timeline.map { it.speakerId }.filter { it != "UNKNOWN" }.distinct().size
            DiarizationOutput(
                turns = timeline,
                speakersDetected = count,
                speechIntervals = intervals.size,
                embeddingWindows = windows.size,
                elapsedSeconds = (System.nanoTime() - start) / 1e9,
            )
        } finally {
            fbankFile.delete()
        }
    }
}
