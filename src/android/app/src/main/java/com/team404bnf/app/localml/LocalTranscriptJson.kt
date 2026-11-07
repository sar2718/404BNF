package com.team404bnf.app.localml

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.team404bnf.ml.asr.AsrOutput
import com.team404bnf.ml.asr.AsrWord
import com.team404bnf.ml.diar.DiarTurn
import com.team404bnf.ml.diar.DiarizationOutput
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Produces schema_version=1 JSON accepted by Edik's TranscriptJsonParser/Room repository. */
internal object LocalTranscriptJson {
    internal data class AssignedWord(
        val text: String, val start: Double, val end: Double,
        val speakerId: String?, val ambiguous: Boolean,
    )

    internal fun assign(words: List<AsrWord>, turns: List<DiarTurn>, duration: Double): List<AssignedWord> {
        return words.mapNotNull { word ->
            val text = word.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val start = word.startSeconds.coerceIn(0.0, duration)
            val end = max(word.endSeconds.coerceIn(0.0, duration), min(duration, start + 0.001))
            if (end <= start) return@mapNotNull null
            val overlaps = linkedMapOf<String, Double>()
            for (turn in turns) {
                if (turn.speakerId == "UNKNOWN") continue
                val overlap = max(0.0, min(end, turn.end) - max(start, turn.start))
                if (overlap > 0.0) {
                    overlaps[turn.speakerId] = (overlaps[turn.speakerId] ?: 0.0) + overlap
                }
            }
            val ranked = overlaps.entries.sortedWith(
                compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key },
            )
            val ambiguous = ranked.size > 1 && ranked[1].value >= ranked[0].value * 0.90
            val speaker = when {
                ambiguous -> null
                ranked.isNotEmpty() -> ranked.first().key
                else -> {
                    val midpoint = (start + end) / 2.0
                    val nearby = turns.filter { it.speakerId != "UNKNOWN" }.map {
                        val dist = max(0.0, max(it.start - midpoint, midpoint - it.end))
                        dist to it.speakerId
                    }.filter { it.first <= 0.35 }.sortedWith(compareBy({ it.first }, { it.second }))
                    if (nearby.size > 1 && nearby[0].second != nearby[1].second &&
                        nearby[1].first - nearby[0].first < 0.04) null
                    else nearby.firstOrNull()?.second
                }
            }
            AssignedWord(text, start, end, speaker, ambiguous)
        }.sortedWith(compareBy({ it.start }, { it.end }))
    }

    fun build(
        asr: AsrOutput,
        diar: DiarizationOutput,
        duration: Double,
        sha256: String,
        inferenceSeconds: Double,
    ): String {
        require(duration > 0 && sha256.length == 64)
        val words = assign(asr.words, diar.turns, duration)
        val root = JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("id", "404bnf_android_local")
            addProperty("result_id", UUID.randomUUID().toString())
            addProperty("audio_sha256", sha256)
            addProperty("duration", duration)
            addProperty("asr_model", "gigaam-v3-e2e-rnnt-fp32")
            addProperty("diarization", "silero-v6.2.1+wespeaker-campp")
            addProperty("pipeline_version", 1)
            addProperty("timing_precision", "estimated")
            addProperty("asr_chunking", "quiet_point")
            addProperty("text", asr.text)
            addProperty("speakers_detected", diar.speakersDetected)
            addProperty("words_unassigned", words.count { it.speakerId == null })
            addProperty("words_ambiguous", words.count { it.ambiguous })
            addProperty("inference_seconds", inferenceSeconds)
            addProperty("rtf", inferenceSeconds / duration)
        }
        val labels = JsonObject()
        diar.turns.map { it.speakerId }.filter { it != "UNKNOWN" }.distinct().sorted()
            .forEachIndexed { index, id -> labels.addProperty(id, "Говорящий ${index + 1}") }
        root.add("speaker_labels", labels)

        val rawWords = JsonArray()
        words.forEachIndexed { index, word ->
            rawWords.add(JsonObject().apply {
                addProperty("id", "word_%06d".format(java.util.Locale.ROOT, index + 1))
                addProperty("start", word.start)
                addProperty("end", word.end)
                putNullable("speaker_id", word.speakerId)
                addProperty("text", word.text)
                addProperty("timing_source", "onnx_rnnt_token_estimate")
                addProperty("speaker_ambiguous", word.ambiguous)
            })
        }
        root.add("words", rawWords)

        // The UI renders segments as chat bubbles. Group adjacent words by speaker.
        val groups = mutableListOf<MutableList<AssignedWord>>()
        for (word in words) {
            val last = groups.lastOrNull()
            if (last == null || last.last().speakerId != word.speakerId ||
                last.last().ambiguous != word.ambiguous || word.start - last.last().end > 0.8) {
                groups.add(mutableListOf(word))
            } else {
                last.add(word)
            }
        }
        val segments = JsonArray()
        groups.forEachIndexed { index, group ->
            segments.add(JsonObject().apply {
                addProperty("id", "segment_%06d".format(java.util.Locale.ROOT, index + 1))
                addProperty("start", group.first().start)
                addProperty("end", group.maxOf { it.end })
                putNullable("speaker_id", group.first().speakerId)
                addProperty("text", group.joinToString(" ") { it.text })
                addProperty("speaker_ambiguous", group.first().ambiguous)
                addProperty("overlap", false) // Single-label diarization: cannot assert overlapping speech.
            })
        }
        root.add("segments", segments)
        return root.toString()
    }

    private fun JsonObject.putNullable(key: String, value: String?) {
        if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value)
    }
}
