package com.team404bnf.app.transcription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.team404bnf.app.ui.playback.groupTranscriptSegments

object TranscriptUserEdits {

    fun splitBeforeIds(root: JsonObject): Set<String> = root.getAsJsonArray("splitBeforeSegmentIds")
        ?.mapNotNull { if(it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null }
        ?.toSet() ?: emptySet()

    private fun hiddenIds(root: JsonObject): Set<String> = root.getAsJsonArray("hiddenSegments")
        ?.map { it.asString }?.toSet() ?: emptySet()

    fun changeSpeakers(root: JsonObject, original: ParsedTranscript, ids: List<String>, speakerId: String?) {
        val byId = original.segments.associateBy { it.id }
        val known = original.speakerLabels.keys + original.segments.mapNotNull { it.speakerId }
        require(speakerId == null || speakerId in known) { "Участник не найден" }
        require(ids.isNotEmpty() && ids.all { it in byId && it !in hiddenIds(root) }) { "Реплика недоступна для редактирования" }
        val speakers = section(root, "speakers")
        ids.forEach { id ->
            when {
                speakerId == byId.getValue(id).speakerId -> speakers.remove(id)
                speakerId == null -> speakers.add(id, com.google.gson.JsonNull.INSTANCE)
                else -> speakers.addProperty(id, speakerId)
            }
        }
    }

    fun changeGroupSpeaker(root: JsonObject, original: ParsedTranscript, ids: List<String>, speakerId: String?) {
        validateGroup(root, original, ids)
        changeSpeakers(root, original, ids, speakerId)
    }

    fun hideSegments(root: JsonObject, original: ParsedTranscript, ids: List<String>) {
        val known = original.segments.map { it.id }.toSet()
        require(ids.isNotEmpty() && ids.all { it in known }) { "Реплика не найдена" }
        val hidden = hiddenIds(root) + ids
        root.add("hiddenSegments", JsonArray().apply { hidden.forEach { add(it) } })
    }

    fun hideGroup(root: JsonObject, original: ParsedTranscript, ids: List<String>) {
        validateGroup(root, original, ids)
        hideSegments(root, original, ids)
    }

    fun restoreSegment(root: JsonObject, original: ParsedTranscript, id: String) {
        require(original.segments.any { it.id == id }) { "Реплика не найдена" }
        val remaining = hiddenIds(root) - id
        if(remaining.isEmpty()) root.remove("hiddenSegments")
        else root.add("hiddenSegments", JsonArray().apply { remaining.forEach { add(it) } })
    }

    fun splitGroup(root: JsonObject, original: ParsedTranscript, ids: List<String>) {
        validateGroup(root, original, ids)
        val boundaries = splitBeforeIds(root) + ids.drop(1)
        root.add("splitBeforeSegmentIds", JsonArray().apply { boundaries.forEach { add(it) } })
    }

    fun restoreMerge(root: JsonObject, original: ParsedTranscript, id: String) {
        val current = apply(original, root.toString())
        val positions = original.segments.mapIndexed { index, segment -> segment.id to index }.toMap()
        require(id in com.team404bnf.app.ui.playback.restorableMergeStarts(current.segments, positions, splitBeforeIds(root))) {
            "Эту реплику нельзя объединить с предыдущей"
        }
        val boundaries = splitBeforeIds(root) - id
        if(boundaries.isEmpty()) root.remove("splitBeforeSegmentIds")
        else root.add("splitBeforeSegmentIds", JsonArray().apply { boundaries.forEach { add(it) } })
    }

    fun reset(root: JsonObject) { root.keySet().toList().forEach { root.remove(it) } }

    /** Validate the selected visible group, not merely a consecutive range of original IDs. */
    fun validateGroup(root: JsonObject, original: ParsedTranscript, segmentIds: List<String>) {
        require(segmentIds.size >= 2 && segmentIds.distinct().size == segmentIds.size) { "Некорректная группа реплик" }
        val positions = original.segments.mapIndexed { index, segment -> segment.id to index }.toMap()
        val current = apply(original, root.toString())
        require(groupTranscriptSegments(current.segments, positions, splitBeforeSegmentIds = splitBeforeIds(root))
            .any { group -> group.segments.map { it.id } == segmentIds }) {
            "Группа изменилась. Откройте её меню заново."
        }
    }

    /** Recovery previews the saved corrections while leaving the hidden overlay untouched. */
    fun deletedSegments(root: JsonObject, original: ParsedTranscript): List<TranscriptSegment> {
        val restored = clean(root.deepCopy(), original)
        val hidden = restored.getAsJsonArray("hiddenSegments")?.map { it.asString }?.toSet() ?: emptySet()
        if(hidden.isEmpty()) return emptyList()
        restored.remove("hiddenSegments")
        return apply(original, restored.toString()).segments.filter { it.id in hidden }
    }

    /** Apply one editor submission by segment ID, without changing the original or its timing. */
    fun updateSegment(
        root: JsonObject,
        original: ParsedTranscript,
        segmentId: String,
        text: String,
        speakerId: String?
    ) {
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Текст реплики не может быть пустым" }
        require(normalized.length <= 5000) { "Текст слишком длинный" }
        val segment = original.segments.firstOrNull { it.id == segmentId }
            ?: error("Реплика не найдена")
        require(segmentId !in hiddenIds(root)) { "Реплика удалена. Сначала восстановите её." }
        val knownSpeakers = original.speakerLabels.keys + original.segments.mapNotNull { it.speakerId }
        require(speakerId == null || speakerId in knownSpeakers) { "Участник не найден" }

        val texts = section(root, "texts")
        if (normalized == segment.text) texts.remove(segmentId)
        else texts.addProperty(segmentId, normalized)
        val speakers = section(root, "speakers")
        when {
            speakerId == segment.speakerId -> speakers.remove(segmentId)
            speakerId == null -> speakers.add(segmentId, com.google.gson.JsonNull.INSTANCE)
            else -> speakers.addProperty(segmentId, speakerId)
        }
    }

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
