package com.team404bnf.ml.asr

/** Exact split strategy of src/ml-core/processor_core/engines/gigaam.py. */
internal object QuietPointChunker {
    data class Chunk(val offsetSamples: Int, val data: FloatArray)
    fun split(input: FloatArray): List<Chunk> {
        val maxLen = 22 * 16_000
        val quietFrom = 16 * 16_000
        val target = 20 * 16_000
        val window = 640
        val out = mutableListOf<Chunk>()
        var cursor = 0
        while (cursor < input.size) {
            val remaining = input.size - cursor
            if (remaining <= maxLen) {
                out += Chunk(cursor, input.copyOfRange(cursor, input.size))
                break
            }
            val lo = cursor + quietFrom
            val hi = minOf(cursor + maxLen, input.size)
            var bestRms = Double.POSITIVE_INFINITY
            var bestDistance = Int.MAX_VALUE
            var best = hi
            var i = lo
            while (i < maxOf(lo + 1, hi - window + 1)) {
                var rms = 0.0
                for (p in i until minOf(i + window, input.size)) {
                    val v = input[p].toDouble()
                    rms += v * v
                }
                rms /= minOf(window, input.size - i)
                val distance = kotlin.math.abs(i - (cursor + target))
                if (rms < bestRms || (rms == bestRms && distance < bestDistance)) {
                    bestRms = rms
                    bestDistance = distance
                    best = i
                }
                i += window
            }
            require(best > cursor) { "Invalid quiet split point" }
            out += Chunk(cursor, input.copyOfRange(cursor, best))
            cursor = best
        }
        return out
    }
}
