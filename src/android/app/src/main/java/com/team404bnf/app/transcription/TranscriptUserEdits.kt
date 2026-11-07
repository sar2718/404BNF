package com.team404bnf.app.transcription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.team404bnf.app.ui.playback.groupTranscriptSegments

object TranscriptUserEdits {

    fun clean(
        root: JsonObject,
        original: ParsedTranscript
    ): JsonObject {
        val originalSegmentsById = original.segments.associateBy { it.id }

        // 1. Clean texts
        root.getAsJsonObject("texts")?.let { texts ->
            val keysToRemove = mutableListOf<String>()
            texts.entrySet().forEach { (segmentId, value) ->
                val origSegment = originalSegmentsById[segmentId]
                if (
                    origSegment == null ||
                    value.isJsonNull ||
                    !value.isJsonPrimitive ||
                    value.asString.trim() == origSegment.text.trim()
                ) {
                    keysToRemove.add(segmentId)
                }
            }
            keysToRemove.forEach { texts.remove(it) }
            if (texts.size() == 0) {
                root.remove("texts")
            }
        }

        // 2. Clean speakers
        root.getAsJsonObject("speakers")?.let { speakers ->
            val keysToRemove = mutableListOf<String>()
            speakers.entrySet().forEach { (segmentId, value) ->
                val origSegment = originalSegmentsById[segmentId]
                if (origSegment == null) {
                    keysToRemove.add(segmentId)
                } else {
                    val requestedSpeaker = if (value.isJsonNull) null else value.asString
                    if (requestedSpeaker == origSegment.speakerId) {
                        keysToRemove.add(segmentId)
                    }
                }
            }
            keysToRemove.forEach { speakers.remove(it) }
            if (speakers.size() == 0) {
                root.remove("speakers")
            }
        }

        // 3. Clean names
        root.getAsJsonObject("names")?.let { names ->
            val defaultLabels = original.speakerLabels.toMutableMap()
            original.segments.forEach { segment ->
                val id = segment.speakerId
                if (id != null && id !in defaultLabels) {
                    defaultLabels[id] = segment.speakerLabel
                        ?: "Участник ${defaultLabels.size + 1}"
                }
            }

            val keysToRemove = mutableListOf<String>()
            names.entrySet().forEach { (speakerId, value) ->
                val originalName = defaultLabels[speakerId]
                if (
                    speakerId !in defaultLabels ||
                    value.isJsonNull ||
                    !value.isJsonPrimitive ||
                    value.asString.isBlank() ||
                    value.asString.trim() == originalName?.trim()
                ) {
                    keysToRemove.add(speakerId)
                }
            }
            keysToRemove.forEach { names.remove(it) }
            if (names.size() == 0) {
                root.remove("names")
            }
        }

        // 4. Clean hiddenSegments
        root.getAsJsonArray("hiddenSegments")?.let { hidden ->
            val validIds = mutableSetOf<String>()
            val cleanedArray = JsonArray()
            hidden.forEach { elem ->
                if (elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
                    val id = elem.asString
                    if (originalSegmentsById.containsKey(id) && validIds.add(id)) {
                        cleanedArray.add(id)
                    }
                }
            }
            if (cleanedArray.size() == 0) {
                root.remove("hiddenSegments")
            } else {
                root.add("hiddenSegments", cleanedArray)
            }
        }

        // 5. Clean splitBeforeSegmentIds
        root.getAsJsonArray("splitBeforeSegmentIds")?.let { splits ->
            val validIds = mutableSetOf<String>()
            val cleanedArray = JsonArray()
            splits.forEach { elem ->
                if (elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
                    val id = elem.asString
                    if (originalSegmentsById.containsKey(id) && validIds.add(id)) {
                        cleanedArray.add(id)
                    }
                }
            }
            if (cleanedArray.size() == 0) {
                root.remove("splitBeforeSegmentIds")
            } else {
                root.add("splitBeforeSegmentIds", cleanedArray)
            }
        }

        // 6. Prune stale splitBeforeSegmentIds if no data edits exist AND grouping matches original
        val hasDataEditsPresent = root.has("texts") || root.has("speakers") || root.has("names") || root.has("hiddenSegments")
        if (root.has("splitBeforeSegmentIds") && !hasDataEditsPresent) {
            if (!hasEffectiveGroupingChanges(root, original)) {
                root.remove("splitBeforeSegmentIds")
            }
        }

        return root
    }

    fun hasDataEdits(
        root: JsonObject,
        original: ParsedTranscript
    ): Boolean {
        val cleaned = clean(root.deepCopy(), original)
        return cleaned.has("texts") || cleaned.has("speakers") || cleaned.has("names") || cleaned.has("hiddenSegments")
    }

    fun hasEffectiveGroupingChanges(
        root: JsonObject,
        original: ParsedTranscript
    ): Boolean {
        val originalPositions = original.segments
            .mapIndexed { index, segment -> segment.id to index }
            .toMap()

        val originalGroups = groupTranscriptSegments(
            segments = original.segments,
            originalPositions = originalPositions,
            maxGapMs = 1500L,
            splitBeforeSegmentIds = emptySet()
        )

        val texts = root.getAsJsonObject("texts")
        val speakers = root.getAsJsonObject("speakers")
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

        val currentSegments = original.segments
            .filterNot { it.id in hiddenSegmentIds }
            .map { segment ->
                val editedText = texts?.get(segment.id)
                    ?.takeUnless { it.isJsonNull }
                    ?.asString

                val speakerOverride = speakers?.get(segment.id)
                val speakerChanged = speakerOverride != null
                val editedSpeaker = if (speakerChanged) {
                    if (speakerOverride!!.isJsonNull) null else speakerOverride.asString
                } else {
                    segment.speakerId
                }

                segment.copy(
                    text = editedText ?: segment.text,
                    speakerId = editedSpeaker
                )
            }

        val currentSplitBeforeSegmentIds = root
            .getAsJsonArray("splitBeforeSegmentIds")
            ?.mapNotNull { element ->
                if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    element.asString
                } else {
                    null
                }
            }
            ?.toSet()
            ?: emptySet()

        val currentGroups = groupTranscriptSegments(
            segments = currentSegments,
            originalPositions = originalPositions,
            maxGapMs = 1500L,
            splitBeforeSegmentIds = currentSplitBeforeSegmentIds
        )

        if (originalGroups.size != currentGroups.size) {
            return true
        }

        for (i in originalGroups.indices) {
            val origIds = originalGroups[i].segments.map { it.id }
            val currIds = currentGroups[i].segments.map { it.id }
            if (origIds != currIds) {
                return true
            }
        }

        return false
    }

    fun hasUserEdits(
        root: JsonObject,
        original: ParsedTranscript
    ): Boolean {
        return hasDataEdits(root, original) || hasEffectiveGroupingChanges(root, original)
    }

    fun apply(
        original: ParsedTranscript,
        editsJson: String
    ): ParsedTranscript {

        val root = JsonParser.parseString(editsJson)
            .asJsonObject

        clean(root, original)

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