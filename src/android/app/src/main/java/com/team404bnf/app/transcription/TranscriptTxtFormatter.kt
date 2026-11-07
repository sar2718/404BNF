package com.team404bnf.app.transcription

import java.io.Writer
import java.util.Locale

object TranscriptTxtFormatter {

    fun writeTo(
        writer: Writer,
        title: String,
        segments: List<TranscriptSegment>,
        speakerLabels: Map<String, String>
    ) {
        writer.write(title.trim())
        writer.write("\n\n")

        segments.forEach { segment ->

            val speakerName =
                segment.speakerId?.let { id ->
                    speakerLabels[id]
                        ?: segment.speakerLabel
                        ?: id
                } ?: "Говорящий не указан"

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

        return if (hours > 0) {
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
    }
}