package com.team404bnf.ml.diar

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Seconds, not milliseconds. UNKNOWN is deliberately preserved for short VAD turns. */
data class DiarTurn(val start: Double, val end: Double, val speakerId: String)
data class SpeechInterval(val start: Double, val end: Double)
data class EmbedWindow(val start: Double, val end: Double, val part: Int) {
    val center: Double get() = (start + end) / 2.0
}
data class DiarizationOutput(
    val turns: List<DiarTurn>,
    val speakersDetected: Int,
    val speechIntervals: Int,
    val embeddingWindows: Int,
    val elapsedSeconds: Double,
    val modelId: String = "silero-wespeaker-campp",
)

/** Port of src/ml-core/processor_core/engines/diarization_impl.py (A4). */
object DiarizationTimeline {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_SAMPLES = 512
    private fun ms3(value: Double): Double = round(value * 1000.0) / 1000.0

    fun speechIntervals(
        probs: List<Float>, duration: Double, threshold: Double = 0.5,
        minSilence: Double = 0.25, minSpeech: Double = 0.25, pad: Double = 0.10,
    ): List<SpeechInterval> {
        require(threshold > 0.0 && threshold < 1.0 && duration.isFinite())
        if (duration <= 0) return emptyList()
        val step = FRAME_SAMPLES.toDouble() / SAMPLE_RATE
        val low = max(0.05, threshold - 0.15)
        val raw = ArrayList<SpeechInterval>()
        var active = false
        var onset = 0.0
        var silenceStart: Double? = null
        for ((i, p) in probs.withIndex()) {
            require(p.isFinite() && p in 0f..1f) { "Silero returned invalid probability" }
            val t = min(i * step, duration)
            if (!active) {
                if (p.toDouble() >= threshold) { onset = t; active = true; silenceStart = null }
            } else if (p.toDouble() < low) {
                if (silenceStart == null) silenceStart = t
                if ((i + 1) * step - silenceStart >= minSilence) {
                    val end = max(onset, silenceStart)
                    if (end - onset >= minSpeech) raw += SpeechInterval(onset, end)
                    active = false; silenceStart = null
                }
            } else silenceStart = null
        }
        if (active) {
            val end = min(duration, silenceStart ?: duration)
            if (end - onset >= minSpeech) raw += SpeechInterval(onset, end)
        }
        val merged = ArrayList<SpeechInterval>()
        for (interval in raw) {
            val start = max(0.0, interval.start - pad)
            val end = min(duration, interval.end + pad)
            val prev = merged.lastOrNull()
            if (prev != null && start <= prev.end) merged[merged.lastIndex] =
                SpeechInterval(prev.start, max(prev.end, end))
            else merged += SpeechInterval(start, end)
        }
        return merged
    }

    fun windows(
        speech: List<SpeechInterval>, size: Double = 2.0,
        hop: Double = 0.8, minEmbed: Double = 0.55,
    ): List<EmbedWindow> {
        require(size >= 0.5 && hop > 0.0 && hop <= size && minEmbed > 0.0)
        val result = ArrayList<EmbedWindow>()
        for ((part, s) in speech.withIndex()) {
            if (s.end - s.start < minEmbed) continue
            if (s.end - s.start <= size) {
                result += EmbedWindow(s.start, s.end, part)
            } else {
                var pos = s.start
                while (pos + size < s.end) {
                    result += EmbedWindow(pos, pos + size, part)
                    pos += hop
                }
                val lastStart = s.end - size
                if (result.lastOrNull()?.let { it.part != part || lastStart - it.start > 0.10 } != false) {
                    result += EmbedWindow(lastStart, s.end, part)
                }
            }
        }
        return result
    }

    /** Average linkage of cosine distances on unit-normalized embeddings; stable first-appearance IDs. */
    fun cluster(embeddings: List<FloatArray>, distance: Double = 0.55): List<Int> {
        require(distance > 0 && distance < 2 && distance.isFinite())
        val n = embeddings.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(0)
        require(n <= 500) { "Too many embeddings for mobile agglomerative clustering" }
        val dimensions = embeddings[0].size
        require(dimensions > 0 && embeddings.all { it.size == dimensions && it.all(Float::isFinite) })
        val norms = DoubleArray(n) { i ->
            kotlin.math.sqrt(embeddings[i].sumOf { (it * it).toDouble() }).also {
                require(it > 1e-8) { "Zero embedding" }
            }
        }
        // Distances between currently live clusters. Lance-Williams UPGMA updates.
        val dis = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            var dot = 0.0
            for (k in 0 until dimensions) dot += embeddings[i][k] * embeddings[j][k]
            val v = 1.0 - (dot / (norms[i] * norms[j])).coerceIn(-1.0, 1.0)
            dis[i][j] = v; dis[j][i] = v
        }
        val parent = IntArray(n) { it }
        val sizes = IntArray(n) { 1 }
        val active = BooleanArray(n) { true }
        while (true) {
            var left = -1
            var right = -1
            var closest = Double.POSITIVE_INFINITY
            for (i in 0 until n) if (active[i]) for (j in i + 1 until n) if (active[j]) {
                if (dis[i][j] < closest) {
                    closest = dis[i][j]; left = i; right = j
                }
            }
            if (left < 0 || closest > distance) break
            val total = sizes[left] + sizes[right]
            for (k in 0 until n) if (active[k] && k != left && k != right) {
                val updated = (dis[left][k] * sizes[left] + dis[right][k] * sizes[right]) / total
                dis[left][k] = updated; dis[k][left] = updated
            }
            sizes[left] = total
            active[right] = false
            // Preserve membership via root table; cluster labels are normalized below.
            for (i in 0 until n) if (parent[i] == right) parent[i] = left
        }
        val remap = LinkedHashMap<Int, Int>()
        return parent.map { root -> remap.getOrPut(root) { remap.size } }
    }

    /** Quantize to 160 ms within every VAD turn, then merge adjacent equal-speaker spans. */
    fun assignTimeline(
        speech: List<SpeechInterval>, windows: List<EmbedWindow>, labels: List<Int>,
        quantum: Double = 0.16,
    ): List<DiarTurn> {
        require(windows.size == labels.size && quantum > 0)
        val result = ArrayList<DiarTurn>()
        for ((part, s) in speech.withIndex()) {
            val matching = windows.indices.filter { windows[it].part == part }
            if (matching.isEmpty()) {
                result += DiarTurn(ms3(s.start), ms3(s.end), "UNKNOWN")
                continue
            }
            var t = s.start
            while (t < s.end - 1e-8) {
                val stop = min(t + quantum, s.end)
                val mid = (t + stop) / 2
                val best = matching.minWithOrNull(compareBy<Int>({ abs(windows[it].center - mid) }, { windows[it].center }))!!
                val speaker = "SPEAKER_%02d".format(java.util.Locale.ROOT, labels[best])
                val prev = result.lastOrNull()
                if (prev != null && prev.speakerId == speaker && abs(prev.end - t) < 0.001) {
                    result[result.lastIndex] = prev.copy(end = ms3(stop))
                } else result += DiarTurn(ms3(t), ms3(stop), speaker)
                t = stop
            }
        }
        return result
    }
}
