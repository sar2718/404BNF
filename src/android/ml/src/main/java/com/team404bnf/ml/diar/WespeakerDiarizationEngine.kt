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
        mono16k: FloatArray,
        onStage: (String) -> Unit = {},
    ): DiarizationOutput = withContext(Dispatchers.Default) {
        require(mono16k.isNotEmpty() && mono16k.size <= 16_000 * 300)
        require(mono16k.all { it.isFinite() && it in -1.0001f..1.0001f })
        val start = System.nanoTime()
        onStage("M3: Silero VAD ONNX")
        val probs = SileroVadEngine(sessions).probabilities(mono16k)
        currentCoroutineContext().ensureActive()
        val intervals = DiarizationTimeline.speechIntervals(
            probs, mono16k.size.toDouble() / 16_000, threshold = threshold,
        )
        val windows = DiarizationTimeline.windows(intervals)
        onStage("M3: VAD intervals=${intervals.size}; CAM++ windows=${windows.size}")
        require(windows.size <= 500) { "Too many embedding windows for mobile debug" }
        val jobContext = currentCoroutineContext()
        val embeds = if (windows.isEmpty()) emptyList() else sessions.withSession(campp) { session ->
            require(session.inputNames.contains("feats")) { "CAM++ input 'feats' missing" }
            onStage("M3: FBank Feature Extraction")
            val globalFeats = fbank.extract(mono16k, applyCmn = false)

            // Compute global mean over all speech intervals (CMN)
            val speechFrames = BooleanArray(globalFeats.frames)
            for (interval in intervals) {
                val startFrame = (interval.start * 100).toInt().coerceIn(0, globalFeats.frames)
                val endFrame = (interval.end * 100).toInt().coerceIn(startFrame, globalFeats.frames)
                for (i in startFrame until endFrame) speechFrames[i] = true
            }
            val totalSpeechFrames = speechFrames.count { it }
            if (totalSpeechFrames > 0) {
                for (band in 0 until 80) {
                    var sum = 0.0
                    for (frame in 0 until globalFeats.frames) {
                        if (speechFrames[frame]) {
                            sum += globalFeats.values[frame * 80 + band]
                        }
                    }
                    val mean = (sum / totalSpeechFrames).toFloat()
                    for (frame in 0 until globalFeats.frames) {
                        globalFeats.values[frame * 80 + band] -= mean
                    }
                }
            }

            windows.mapIndexed { index, window ->
                jobContext.ensureActive()
                if (index == 0 || (index + 1) % 5 == 0 || index == windows.lastIndex)
                    onStage("M3: WeSpeaker CAM++ ${index + 1}/${windows.size}")

                val startFrame = (window.start * 100).toInt().coerceIn(0, globalFeats.frames)
                val endFrame = (window.end * 100).toInt().coerceIn(startFrame, globalFeats.frames)
                val frames = endFrame - startFrame

                require(frames > 0) { "Empty embedding window" }

                val slice = FloatArray(frames * 80)
                System.arraycopy(globalFeats.values, startFrame * 80, slice, 0, slice.size)

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
        currentCoroutineContext().ensureActive()
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
    }
}
