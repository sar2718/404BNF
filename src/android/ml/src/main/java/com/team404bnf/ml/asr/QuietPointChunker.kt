package com.team404bnf.ml.asr

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exact split strategy of src/ml-core/processor_core/engines/gigaam.py adapted for file streaming. */
internal object QuietPointChunker {
    data class Chunk(val offsetSamples: Long, val durationSamples: Int)

    fun split(pcmFile: File, totalSamples: Long): List<Chunk> {
        val maxLen = 22 * 16_000
        val quietFrom = 16 * 16_000
        val target = 20 * 16_000
        val window = 640
        val out = mutableListOf<Chunk>()
        var cursor = 0L

        RandomAccessFile(pcmFile, "r").use { raf ->
            val byteBuffer = ByteArray(maxLen * 4)
            val floatBuffer = FloatArray(maxLen)

            while (cursor < totalSamples) {
                val remaining = totalSamples - cursor
                if (remaining <= maxLen) {
                    out += Chunk(cursor, remaining.toInt())
                    break
                }
                val readSamples = maxLen
                raf.seek(cursor * 4L)
                raf.readFully(byteBuffer, 0, readSamples * 4)
                ByteBuffer.wrap(byteBuffer, 0, readSamples * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floatBuffer, 0, readSamples)

                val lo = quietFrom
                val hi = maxLen
                var bestRms = Double.POSITIVE_INFINITY
                var bestDistance = Int.MAX_VALUE
                var best = hi
                var i = lo
                while (i < hi - window + 1) {
                    var rms = 0.0
                    for (p in i until i + window) {
                        val v = floatBuffer[p].toDouble()
                        rms += v * v
                    }
                    rms /= window
                    val distance = kotlin.math.abs(i - target)
                    if (rms < bestRms || (rms == bestRms && distance < bestDistance)) {
                        bestRms = rms
                        bestDistance = distance
                        best = i
                    }
                    i += window
                }
                require(best > 0) { "Invalid quiet split point" }
                out += Chunk(cursor, best)
                cursor += best
            }
        }
        return out
    }
}
