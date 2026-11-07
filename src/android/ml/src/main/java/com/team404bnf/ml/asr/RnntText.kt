package com.team404bnf.ml.asr

import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Debug ASR output. Does not claim full transcript-v1 until speaker assignment is added. */
data class AsrWord(val text: String, val startSeconds: Double, val endSeconds: Double)
data class AsrOutput(val text: String, val words: List<AsrWord>)

internal object RnntText {
    data class Emission(val id: Int, val timeSeconds: Double)
    class Vocab(file: File) {
        val tokens: Map<Int, String>
        val blankId: Int
        init {
            val parsed = mutableMapOf<Int, String>()
            file.forEachLine(Charsets.UTF_8) { line ->
                val lastSpace = line.lastIndexOf(' ')
                require(lastSpace > 0) { "Invalid vocab entry" }
                val id = line.substring(lastSpace + 1).trim().toInt()
                val tok = line.substring(0, lastSpace).replace('▁', ' ')
                require(parsed.put(id, tok) == null) { "Duplicate vocab ID $id" }
            }
            tokens = parsed
            blankId = parsed.entries.single { it.value == "<blk>" }.key
        }
    }

    fun decode(emissions: List<Emission>, vocab: Map<Int, String>, offset: Double, duration: Double): AsrOutput {
        if (emissions.isEmpty()) return AsrOutput("", emptyList())
        val raw = StringBuilder()
        val charTimes = ArrayList<Double>()
        for (item in emissions) {
            val token = requireNotNull(vocab[item.id]) { "Unmapped token ${item.id}" }
            require(token != "<blk>")
            val time = item.timeSeconds.coerceIn(0.0, duration)
            raw.append(token)
            repeat(token.length) { charTimes.add(time) }
        }
        // Python's unicode-aware r"\A\s|\s\B|(\s)\b" loses Unicode
        // boundaries with default Java regex; implement the intended rule explicitly.
        val joined = StringBuilder()
        val times = ArrayList<Double>()
        for (i in 0 until raw.length) {
            val ch = raw[i]
            if (!ch.isWhitespace()) {
                joined.append(ch)
                times.add(charTimes[i])
            } else if (i > 0 && i + 1 < raw.length && isWord(raw[i + 1])) {
                joined.append(' ')
                times.add(charTimes[i])
            }
        }
        val resultText = joined.toString()
        val matches = mutableListOf<IntRange>()
        var pos = 0
        while (pos < resultText.length) {
            while (pos < resultText.length && resultText[pos].isWhitespace()) pos++
            if (pos >= resultText.length) break
            val from = pos
            while (pos < resultText.length && !resultText[pos].isWhitespace()) pos++
            matches.add(from until pos)
        }
        val resultWords = matches.mapIndexed { index, span ->
            val start = times[span.first].coerceIn(0.0, duration)
            val lastTime = max(start, times[span.last])
            val next = if (index + 1 < matches.size) times[matches[index + 1].first] else duration
            val end = max(start, minOf(duration, lastTime + 0.12, max(start, next)))
            AsrWord(resultText.substring(span.first, span.last + 1), roundMs(offset + start), roundMs(offset + end))
        }
        return AsrOutput(resultText, resultWords)
    }
    private fun isWord(ch: Char) = ch.isLetterOrDigit() || ch == '_'
    private fun roundMs(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0
}
