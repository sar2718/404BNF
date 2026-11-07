package com.team404bnf.app.transcription

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object TranscriptUserEdits {

    fun apply(
        original: ParsedTranscript,
        editsJson: String
    ): ParsedTranscript {

        val root = JsonParser.parseString(editsJson)
            .asJsonObject

        val texts = root.getAsJsonObject("texts")
        val speakers = root.getAsJsonObject("speakers")
        val names = root.getAsJsonObject("names")

        val hiddenSegmentIds = root
            .getAsJsonArray("hiddenSegments")
            ?.mapNotNull { element ->
                if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    element.asString
                } else {
                    null
                }
            }
            ?.toSet()
            ?: emptySet()

        val labels = original.speakerLabels.toMutableMap()

        original.segments.forEach { segment ->
            val id = segment.speakerId

            if (id != null && id !in labels) {
                labels[id] = segment.speakerLabel
                    ?: "Участник ${labels.size + 1}"
            }
        }

        names?.entrySet()?.forEach { (id, value) ->
            if (
                id in labels &&
                value.isJsonPrimitive &&
                value.asString.isNotBlank()
            ) {
                labels[id] = value.asString
            }
        }

        val updatedSegments = original.segments
            .filterNot { it.id in hiddenSegmentIds }
            .map { segment ->

                val editedText = texts?.get(segment.id)
                    ?.takeUnless { it.isJsonNull }
                    ?.asString

                val speakerOverride = speakers?.get(segment.id)

                val speakerChanged = speakerOverride != null

                val editedSpeaker = if (speakerChanged) {
                    if (speakerOverride!!.isJsonNull) {
                        null
                    } else {
                        speakerOverride.asString
                    }
                } else {
                    segment.speakerId
                }

                segment.copy(
                    text = editedText ?: segment.text,
                    speakerId = editedSpeaker,
                    speakerLabel = if (speakerChanged) {
                        null
                    } else {
                        segment.speakerLabel
                    }
                )
            }

        return original.copy(
            segments = updatedSegments,
            speakerLabels = labels
        )
    }

    fun section(
        root: JsonObject,
        name: String
    ): JsonObject {

        val existing = root.get(name)

        if (existing != null && existing.isJsonObject) {
            return existing.asJsonObject
        }

        return JsonObject().also {
            root.add(name, it)
        }
    }
}