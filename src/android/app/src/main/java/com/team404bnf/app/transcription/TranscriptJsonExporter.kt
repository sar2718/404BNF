package com.team404bnf.app.transcription

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

enum class ExportVersion {
    ORIGINAL,
    CORRECTED
}

data class ExportOptions(
    val originalValid: Boolean,
    val correctedValid: Boolean,
    val hasDifferences: Boolean
) {
    val canExport: Boolean
        get() = originalValid || correctedValid

    val showVersionSelection: Boolean
        get() = originalValid && correctedValid && hasDifferences
}

object TranscriptJsonExporter {

    private val prettyGson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .disableHtmlEscaping()
        .create()

    /**
     * Validates that all segments in the transcript have non-null timestamps, speaker IDs, and non-blank text.
     */
    fun validateSegmentData(transcript: ParsedTranscript) {
        transcript.segments.forEach { segment ->
            if (segment.speakerId.isNullOrBlank() || segment.startMs == null || segment.endMs == null) {
                throw IllegalArgumentException(
                    "Расшифровка содержит неполные данные (отсутствуют временные метки или участники) и не может быть экспортирована"
                )
            }
            if (segment.text.isBlank()) {
                throw IllegalArgumentException(
                    "Расшифровка содержит реплики с пустым текстом"
                )
            }
        }
    }

    /**
     * Validates that the original transcript and target export version contain complete, valid segment data.
     */
    fun validateExportable(
        original: ParsedTranscript,
        userEditsJson: String = "{}",
        version: ExportVersion = ExportVersion.CORRECTED
    ) {
        if (original.segments.isEmpty()) {
            throw IllegalArgumentException("Расшифровка пуста и не может быть экспортирована")
        }

        val targetTranscript = when (version) {
            ExportVersion.ORIGINAL -> original
            ExportVersion.CORRECTED -> TranscriptUserEdits.apply(original, userEditsJson)
        }

        // If target is CORRECTED and all segments are hidden, targetTranscript.segments is empty, which exports to {"transcript": []}
        if (version == ExportVersion.ORIGINAL || targetTranscript.segments.isNotEmpty()) {
            validateSegmentData(targetTranscript)
        }
    }

    /**
     * Checks validity of both Original and Corrected versions and determines export options.
     */
    fun checkExportOptions(
        original: ParsedTranscript,
        userEditsJson: String = "{}"
    ): ExportOptions {
        var originalValid = true
        var originalError: Throwable? = null
        try {
            validateExportable(original, userEditsJson, ExportVersion.ORIGINAL)
        } catch (e: Exception) {
            originalValid = false
            originalError = e
        }

        var correctedValid = true
        var correctedError: Throwable? = null
        try {
            validateExportable(original, userEditsJson, ExportVersion.CORRECTED)
        } catch (e: Exception) {
            correctedValid = false
            correctedError = e
        }

        if (!originalValid && !correctedValid) {
            throw originalError ?: correctedError ?: IllegalArgumentException("Расшифровка не может быть экспортирована")
        }

        val hasDiff = if (originalValid && correctedValid) {
            hasExportableDifferences(original, userEditsJson)
        } else {
            false
        }

        return ExportOptions(
            originalValid = originalValid,
            correctedValid = correctedValid,
            hasDifferences = hasDiff
        )
    }

    /**
     * Builds the export JsonObject for the given [transcript].
     */
    fun buildJsonObject(transcript: ParsedTranscript): JsonObject {
        val root = JsonObject()
        val array = JsonArray()

        transcript.segments.forEach { segment ->
            val obj = JsonObject()

            if (segment.speakerId != null) {
                obj.addProperty("speaker_id", segment.speakerId)
            } else {
                obj.add("speaker_id", JsonNull.INSTANCE)
            }

            if (segment.startMs != null) {
                obj.addProperty("start_ms", segment.startMs)
            } else {
                obj.add("start_ms", JsonNull.INSTANCE)
            }

            if (segment.endMs != null) {
                obj.addProperty("end_ms", segment.endMs)
            } else {
                obj.add("end_ms", JsonNull.INSTANCE)
            }

            obj.addProperty("text", segment.text)

            array.add(obj)
        }

        root.add("transcript", array)
        return root
    }

    /**
     * Serializes the transcript into a formatted JSON string for export.
     */
    fun export(
        original: ParsedTranscript,
        userEditsJson: String = "{}",
        version: ExportVersion = ExportVersion.CORRECTED
    ): String {
        validateExportable(original, userEditsJson, version)

        val targetTranscript = when (version) {
            ExportVersion.ORIGINAL -> original
            ExportVersion.CORRECTED -> TranscriptUserEdits.apply(original, userEditsJson)
        }

        val jsonObject = buildJsonObject(targetTranscript)
        return prettyGson.toJson(jsonObject)
    }

    /**
     * Checks if there are actual exportable differences between Original and Corrected JSON versions.
     */
    fun hasExportableDifferences(
        original: ParsedTranscript,
        userEditsJson: String
    ): Boolean {
        val originalJson = buildJsonObject(original)
        val correctedTranscript = TranscriptUserEdits.apply(original, userEditsJson)
        val correctedJson = buildJsonObject(correctedTranscript)

        return originalJson != correctedJson
    }
}
