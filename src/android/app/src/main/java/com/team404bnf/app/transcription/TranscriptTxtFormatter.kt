package com.team404bnf.app.transcription

import java.io.Writer
import java.util.Locale

object TranscriptTxtFormatter {

    fun selectVersion(original: ParsedTranscript, userEditsJson: String, version: ExportVersion): ParsedTranscript {
        require(original.segments.isNotEmpty()) { "Исходная расшифровка пуста" }
        return when(version) {
            // Derive the same fallback speaker labels for both versions; these are not user edits.
            ExportVersion.ORIGINAL -> TranscriptUserEdits.apply(original, "{}")
            ExportVersion.CORRECTED -> TranscriptUserEdits.apply(original, userEditsJson)
        }
    }

    /** TXT supports unknown speakers/times, unlike strict JSON; compare what TXT actually includes. */
    fun checkExportOptions(original: ParsedTranscript, userEditsJson: String): ExportOptions {
        require(original.segments.isNotEmpty()) { "Исходная расшифровка пуста" }
        val corrected = try { selectVersion(original, userEditsJson, ExportVersion.CORRECTED) }
        catch (_: Exception) { return ExportOptions(originalValid = true, correctedValid = false, hasDifferences = false) }
        val source = selectVersion(original, "{}", ExportVersion.ORIGINAL)
        val differs = source.segments.size != corrected.segments.size ||
            source.segments.zip(corrected.segments).any { (before, after) ->
                before.text.trim() != after.text.trim() || before.startMs != after.startMs || before.endMs != after.endMs ||
                    speakerName(before, source.speakerLabels) != speakerName(after, corrected.speakerLabels)
            }
        return ExportOptions(originalValid = true, correctedValid = true, hasDifferences = differs)
    }

    private fun speakerName(segment: TranscriptSegment, labels: Map<String, String>): String =
        segment.speakerId?.let { labels[it] ?: segment.speakerLabel ?: it } ?: "Говорящий не указан"

    fun writeTo(
        writer: Writer,
        title: String,
        segments: List<TranscriptSegment>,
        speakerLabels: Map<String, String>
    ) {
        writer.write(title.trim())
        writer.write("\n\n")

        segments.forEach { segment ->

            val speakerName = speakerName(segment, speakerLabels)

            writer.write(
                formatEntry(
                    speakerName = speakerName,
                    text = segment.text,
                    startMs = segment.startMs,
                    endMs = segment.endMs
                )
            )
        }
    }

    fun formatEntry(
        speakerName: String,
        text: String,
        startMs: Long?,
        endMs: Long?
    ): String {
        return buildString {

            if (
                startMs != null &&
                endMs != null &&
                startMs >= 0L &&
                endMs >= startMs
            ) {
                append("[")
                append(formatTime(startMs))
                append("–")
                append(formatTime(endMs))
                append("] ")
            }

            append(speakerName)
            append("\n")

            append(text.trim())
            append("\n\n")
        }
    }

    private fun formatTime(
        milliseconds: Long
    ): String {

        val seconds = milliseconds / 1000

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remaining = seconds % 60

        val time = if (hours > 0) {
            String.format(
                Locale.ROOT,
                "%02d:%02d:%02d",
                hours,
                minutes,
                remaining
            )
        } else {
            String.format(
                Locale.ROOT,
                "%02d:%02d",
                minutes,
                remaining
            )
        }
        return if (milliseconds % 1000 == 0L) time else time + String.format(Locale.ROOT, ".%03d", milliseconds % 1000)
    }
}
