package com.team404bnf.app.transcription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.roundToLong
import java.util.UUID

/** Не зависит от Android, Room, Compose или конкретной ASR/diarization модели. */
object TranscriptJsonParser {
    private const val MAX_JSON_CHARS = 20_000_000
    private val SHA256 = Regex("[0-9a-fA-F]{64}")
    private val METRICS = listOf(
        "inference_seconds", "rtf", "cached_diarization_seconds",
        "estimated_total_seconds", "estimated_total_rtf"
    )

    fun parse(json: String): ParsedTranscript {
        if (json.isBlank() || json.length > MAX_JSON_CHARS) {
            bad("JSON пуст или превышает 20 млн символов")
        }
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw TranscriptFormatException("Некорректный JSON", e)
        }
        if (!root.isJsonObject) bad("Корневой элемент должен быть объектом")
        val obj = root.asJsonObject

        val schemaVersion = obj.int("schema_version", "$", optional = true)
        if (schemaVersion != null && schemaVersion != 1) {
            bad("Неподдерживаемая schema_version: $schemaVersion")
        }
        val sourceId = obj.str("id", "$", required = true)!!
        val resultId = obj.str("result_id", "$")
        if (resultId != null && runCatching {
                UUID.fromString(resultId).toString().equals(resultId, ignoreCase = true)
            }.getOrDefault(false).not()
        ) bad("$.result_id: ожидается UUID")
        val audioSha256 = obj.str("audio_sha256", "$")
        if (audioSha256 != null && !SHA256.matches(audioSha256)) {
            bad("$.audio_sha256: ожидается 64 шестнадцатеричных символа")
        }

        val durationMs = obj.millis("duration", "$")
        if (durationMs <= 0) bad("$.duration: должна быть положительной")
        val segments = obj.array("segments", "$", required = true).mapIndexed { index, item ->
            val path = "$.segments[$index]"
            if (!item.isJsonObject) bad("$path: ожидается объект")
            val s = item.asJsonObject
            val startRaw = s.millisOrNull("start", path)
            val endRaw = s.millisOrNull("end", path)

            val (start, end) = if (startRaw != null && endRaw != null) {
                validateRange(startRaw, endRaw, path)
                startRaw to endRaw
            } else {
                null to null
            }

            TranscriptSegment(
                id = s.str("id", path) ?: "segment_%06d".format(index + 1),
                startMs = start,
                endMs = end,
                speakerId = s.str("speaker_id", path),
                speakerLabel = s.str("speaker_label", path),
                text = s.str("text", path, required = true)!!,
                overlap = s.flag("overlap", path),
                speakerAmbiguous = s.flag("speaker_ambiguous", path)
            )
        }
        uniqueIds(segments.map { it.id }, "segments")

        val words = obj.array("words", "$").mapIndexed { index, item ->
            val path = "$.words[$index]"
            if (!item.isJsonObject) bad("$path: ожидается объект")
            val w = item.asJsonObject
            val start = w.millis("start", path)
            val end = w.millis("end", path)
            validateRange(start, end, path)
            TranscriptWord(
                id = w.str("id", path) ?: "word_%06d".format(index + 1),
                startMs = start,
                endMs = end,
                speakerId = w.str("speaker_id", path),
                speakerLabel = w.str("speaker_label", path),
                text = w.str("text", path, required = true)!!,
                timingSource = w.str("timing_source", path),
                overlap = w.flag("overlap", path),
                speakerAmbiguous = w.flag("speaker_ambiguous", path)
            )
        }
        uniqueIds(words.map { it.id }, "words")

        val chunks = obj.array("chunks", "$").mapIndexed { index, item ->
            val path = "$.chunks[$index]"
            if (!item.isJsonObject) bad("$path: ожидается объект")
            val c = item.asJsonObject
            val start = c.millis("start", path)
            val end = c.millis("end", path)
            validateRange(start, end, path)
            TranscriptChunk(start, end, c.int("word_count", path, optional = true))
        }
        val labels = linkedMapOf<String, String>()
        val labelObj = obj.get("speaker_labels")
        if (labelObj != null && !labelObj.isJsonNull) {
            if (!labelObj.isJsonObject) bad("$.speaker_labels: ожидается объект")
            for ((key, value) in labelObj.asJsonObject.entrySet()) {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString ||
                    value.asString.isBlank()) bad("$.speaker_labels.$key: некорректное имя")
                labels[key] = value.asString
            }
        }
        val diagnostics = linkedMapOf<String, Double>()
        for (metric in METRICS) {
            obj.number(metric, "$", optional = true)?.let { diagnostics[metric] = it }
        }

        return ParsedTranscript(
            schemaVersion = schemaVersion,
            sourceId = sourceId,
            resultId = resultId,
            audioSha256 = audioSha256,
            sourceAudioPath = obj.str("audio", "$"),
            durationMs = durationMs,
            asrModel = obj.str("asr_model", "$"),
            diarizationModel = obj.str("diarization", "$"),
            pipelineVersion = obj.int("pipeline_version", "$", optional = true),
            timingPrecision = obj.str("timing_precision", "$"),
            asrChunking = obj.str("asr_chunking", "$"),
            fullText = obj.str("text", "$", allowEmpty = true) ?: segments.joinToString(" ") { it.text },
            segments = segments,
            words = words,
            chunks = chunks,
            speakerLabels = labels,
            speakersDetected = obj.int("speakers_detected", "$", optional = true),
            wordsUnassigned = obj.int("words_unassigned", "$", optional = true),
            wordsAmbiguous = obj.int("words_ambiguous", "$", optional = true),
            diagnostics = diagnostics,
            rawJson = json
        )
    }

    private fun JsonObject.str(
        key: String, path: String, required: Boolean = false, allowEmpty: Boolean = false
    ): String? {
        val value = get(key)
        if (value == null || value.isJsonNull) {
            if (required) bad("$path.$key: обязательное поле отсутствует")
            return null
        }
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || (!allowEmpty && value.asString.isBlank())) {
            bad("$path.$key: ожидается непустая строка")
        }
        return value.asString
    }

    private fun JsonObject.flag(key: String, path: String): Boolean? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
            bad("$path.$key: ожидается boolean")
        }
        return value.asBoolean
    }

    private fun JsonObject.number(key: String, path: String, optional: Boolean = false): Double? {
        val value = get(key)
        if (value == null || value.isJsonNull) {
            if (!optional) bad("$path.$key: обязательное число отсутствует")
            return null
        }
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            bad("$path.$key: ожидается число")
        }
        val num = try { value.asDouble } catch (e: Exception) {
            bad("$path.$key: некорректное число")
        }
        if (!num.isFinite()) bad("$path.$key: число должно быть конечным")
        return num
    }

    private fun JsonObject.int(key: String, path: String, optional: Boolean = false): Int? {
        val num = number(key, path, optional) ?: return null
        if (num < 0 || num > Int.MAX_VALUE || num % 1.0 != 0.0) {
            bad("$path.$key: ожидается неотрицательное целое число")
        }
        return num.toInt()
    }

    private fun JsonObject.millis(key: String, path: String): Long {
        val seconds = number(key, path)!!
        if (seconds < 0.0 || seconds > Long.MAX_VALUE.toDouble() / 1000.0) {
            bad("$path.$key: время вне допустимого диапазона")
        }
        return (seconds * 1000.0).roundToLong()
    }

    private fun JsonObject.millisOrNull(key: String, path: String): Long? {
        val value = get(key)
        if (value == null || value.isJsonNull) {
            return null
        }
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            bad("$path.$key: ожидается число")
        }
        val seconds = try {
            value.asDouble
        } catch (e: Exception) {
            bad("$path.$key: некорректное число")
        }
        if (!seconds.isFinite() || seconds < 0.0 || seconds > Long.MAX_VALUE.toDouble() / 1000.0) {
            bad("$path.$key: время вне допустимого диапазона")
        }
        return (seconds * 1000.0).roundToLong()
    }

    private fun JsonObject.array(key: String, path: String, required: Boolean = false): JsonArray {
        val value = get(key)
        if (value == null || value.isJsonNull) {
            if (required) bad("$path.$key: обязательный массив отсутствует")
            return JsonArray()
        }
        if (!value.isJsonArray) bad("$path.$key: ожидается массив")
        return value.asJsonArray
    }

    private fun validateRange(start: Long, end: Long, path: String) {
        if (end <= start) bad("$path: end должен быть больше start")
    }

    private fun uniqueIds(ids: List<String>, name: String) {
        if (ids.size != ids.toSet().size) bad("$.$name: повторяющиеся идентификаторы")
    }

    private fun bad(message: String): Nothing = throw TranscriptFormatException(message)
}