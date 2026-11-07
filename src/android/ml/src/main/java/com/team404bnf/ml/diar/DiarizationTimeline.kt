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

    /** Scalable Leader-Follower + Centroid AHC; handles unlimited embeddings with bounded memory and strict distance thresholds. */
    fun cluster(embeddings: List<FloatArray>, distance: Double = 0.55): List<Int> {
        require(distance > 0 && distance < 2 && distance.isFinite())
        val n = embeddings.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(0)

        val dimensions = embeddings[0].size
        require(dimensions > 0 && embeddings.all { it.size == dimensions && it.all(Float::isFinite) })

        // Stage 1: Leader-Follower Online Centroid Clustering
        val microThreshold = minOf(distance, 0.40)

        class ClusterNode(val centroid: DoubleArray, var count: Int)
        val clusters = ArrayList<ClusterNode>()
        val assignment = IntArray(n)

        for (i in 0 until n) {
            val emb = embeddings[i]
            var norm = 0.0
            for (v in emb) norm += v.toDouble() * v.toDouble()
            norm = kotlin.math.sqrt(norm)
            require(norm > 1e-8) { "Zero embedding" }

            var bestIdx = -1
            var bestDist = Double.POSITIVE_INFINITY

            for (c in clusters.indices) {
                val node = clusters[c]
                var dot = 0.0
                var nodeNormSq = 0.0
                for (k in 0 until dimensions) {
                    val cv = node.centroid[k]
                    dot += (emb[k] / norm) * cv
                    nodeNormSq += cv * cv
                }
                val nodeNorm = kotlin.math.sqrt(nodeNormSq)
                if (nodeNorm > 1e-8) {
                    val dist = 1.0 - (dot / nodeNorm).coerceIn(-1.0, 1.0)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIdx = c
                    }
                }
            }

            if (bestIdx >= 0 && bestDist <= microThreshold) {
                val node = clusters[bestIdx]
                for (d in 0 until dimensions) {
                    node.centroid[d] += emb[d] / norm
                }
                node.count++
                assignment[i] = bestIdx
            } else {
                val newCentroid = DoubleArray(dimensions) { k -> (emb[k] / norm).toDouble() }
                clusters.add(ClusterNode(newCentroid, 1))
                assignment[i] = clusters.lastIndex
            }
        }

        // Stage 2: Iteratively merge clusters whose centroid distance <= distance (0.55).
        // STOPS strictly when minD > distance. NEVER forcibly merges distinct speakers!
        val clusterParent = IntArray(clusters.size) { it }

        while (true) {
            var bestI = -1
            var bestJ = -1
            var minD = Double.POSITIVE_INFINITY
            val k = clusters.size

            for (i in 0 until k) {
                if (clusterParent[i] != i) continue
                val cI = clusters[i]
                var normI = 0.0
                for (v in cI.centroid) normI += v * v
                normI = kotlin.math.sqrt(normI)

                for (j in i + 1 until k) {
                    if (clusterParent[j] != j) continue
                    val cJ = clusters[j]
                    var normJ = 0.0
                    for (v in cJ.centroid) normJ += v * v
                    normJ = kotlin.math.sqrt(normJ)

                    var dot = 0.0
                    for (dim in 0 until dimensions) dot += cI.centroid[dim] * cJ.centroid[dim]
                    val dist = 1.0 - (dot / (normI * normJ)).coerceIn(-1.0, 1.0)
                    if (dist < minD) {
                        minD = dist
                        bestI = i
                        bestJ = j
                    }
                }
            }

            if (bestI < 0 || minD > distance) break

            val cI = clusters[bestI]
            val cJ = clusters[bestJ]
            for (dim in 0 until dimensions) cI.centroid[dim] += cJ.centroid[dim]
            cI.count += cJ.count
            clusterParent[bestJ] = bestI
            for (idx in 0 until k) {
                if (clusterParent[idx] == bestJ) clusterParent[idx] = bestI
            }
        }

        val remap = LinkedHashMap<Int, Int>()
        val clusterToFinal = clusterParent.map { root -> remap.getOrPut(root) { remap.size } }
        return List(n) { clusterToFinal[assignment[it]] }
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
