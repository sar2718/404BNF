package com.team404bnf.app.transcription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.team404bnf.app.ui.playback.groupTranscriptSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptUserEditsTest {

    private fun sample(): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("01_dialog_clean.json")
    ) { "Нет тестового файла 01_dialog_clean.json" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun originalTranscript(): ParsedTranscript =
        TranscriptJsonParser.parse(sample())

    // ========================================================================
    // User edit persistence and recovery.
    // ========================================================================

    @Test
    fun test01_changeSpeakerAndRestoreOriginal() {
        val original = originalTranscript()
        val seg1 = original.segments[0]
        val originalSpeaker = seg1.speakerId!! // "SPEAKER_00"
        val newSpeaker = "SPEAKER_01"

        val edits = JsonObject()
        val speakers = JsonObject()

        // 1. Change speaker
        speakers.addProperty(seg1.id, newSpeaker)
        edits.add("speakers", speakers)

        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        // 2. Restore original speaker
        speakers.addProperty(seg1.id, originalSpeaker)

        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))

        val cleaned = TranscriptUserEdits.clean(edits, original)
        assertEquals(0, cleaned.size())
    }

    @Test
    fun test02_changeTextAndRestoreOriginal() {
        val original = originalTranscript()
        val seg1 = original.segments[0]
        val originalText = seg1.text
        val newText = "Совершенно новый текст реплики"

        val edits = JsonObject()
        val texts = JsonObject()

        // 1. Change text
        texts.addProperty(seg1.id, newText)
        edits.add("texts", texts)

        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        // 2. Restore original text
        texts.addProperty(seg1.id, originalText)

        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))

        val cleaned = TranscriptUserEdits.clean(edits, original)
        assertEquals(0, cleaned.size())
    }

    @Test
    fun test03_renameSpeakerAndReturnOriginalName() {
        val original = originalTranscript()
        val speakerId = "SPEAKER_00"
        val originalName = original.speakerLabels[speakerId]!! // "Участник 1"
        val newName = "Алексей"

        val edits = JsonObject()
        val names = JsonObject()

        // 1. Rename speaker
        names.addProperty(speakerId, newName)
        edits.add("names", names)

        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        // 2. Return original name
        names.addProperty(speakerId, originalName)

        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))

        val cleaned = TranscriptUserEdits.clean(edits, original)
        assertEquals(0, cleaned.size())
    }

    @Test
    fun test04_hideAndRestoreSegment() {
        val original = originalTranscript()
        val seg1 = original.segments[0]

        val edits = JsonObject()
        val hidden = JsonArray()

        // 1. Hide segment
        hidden.add(seg1.id)
        edits.add("hiddenSegments", hidden)

        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        val appliedHidden = TranscriptUserEdits.apply(original, edits.toString())
        assertEquals(original.segments.size - 1, appliedHidden.segments.size)

        // 2. Restore segment
        hidden.remove(0)

        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))

        val appliedRestored = TranscriptUserEdits.apply(original, edits.toString())
        assertEquals(original.segments.size, appliedRestored.segments.size)

        val cleaned = TranscriptUserEdits.clean(edits, original)
        assertEquals(0, cleaned.size())
    }

    @Test
    fun test05_multipleEditsWhenOnlyOneRestored() {
        val original = originalTranscript()
        val seg1 = original.segments[0]
        val seg2 = original.segments[1]

        val edits = JsonObject()
        val texts = JsonObject()
        val speakers = JsonObject()

        // Edit text of seg1 and speaker of seg2 (change seg2 speaker to SPEAKER_00)
        texts.addProperty(seg1.id, "Изменённый текст")
        speakers.addProperty(seg2.id, "SPEAKER_00")

        edits.add("texts", texts)
        edits.add("speakers", speakers)

        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        // Restore speaker of seg2 back to original seg2.speakerId ("SPEAKER_01")
        speakers.addProperty(seg2.id, seg2.speakerId!!)

        // hasUserEdits MUST still be true because text of seg1 is still edited!
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        val cleaned = TranscriptUserEdits.clean(edits, original)
        assertEquals(1, cleaned.size())
        assertTrue(cleaned.has("texts"))
        assertFalse(cleaned.has("speakers"))
    }

    @Test
    fun test06_noFictitiousEditsAfterReloadingSavedState() {
        val original = originalTranscript()
        val seg1 = original.segments[0]

        // Simulate userEditsJson stored in Room that contains redundant entries
        val redundantEditsJson = """
            {
                "speakers": {
                    "${seg1.id}": "${seg1.speakerId}"
                },
                "texts": {
                    "${seg1.id}": "${seg1.text}"
                },
                "names": {
                    "SPEAKER_00": "Участник 1"
                },
                "hiddenSegments": []
            }
        """.trimIndent()

        val parsedEdits = JsonParser.parseString(redundantEditsJson).asJsonObject

        assertFalse(TranscriptUserEdits.hasUserEdits(parsedEdits, original))

        val applied = TranscriptUserEdits.apply(original, redundantEditsJson)
        assertEquals(original.segments, applied.segments)
        assertEquals(original.speakerLabels, applied.speakerLabels)
    }

    @Test
    fun test07_preservationOfUserGroupBoundaries() {
        val testJson = """
            {
                "id": "test_dialog",
                "duration": 5.0,
                "segments": [
                    { "id": "seg1", "start": 0.0, "end": 1.0, "speaker_id": "SPEAKER_00", "text": "A" },
                    { "id": "seg2", "start": 1.2, "end": 2.0, "speaker_id": "SPEAKER_00", "text": "B" }
                ],
                "speaker_labels": { "SPEAKER_00": "A" }
            }
        """.trimIndent()

        val original = TranscriptJsonParser.parse(testJson)

        val edits = JsonObject()
        val splits = JsonArray()

        splits.add("seg2")
        edits.add("splitBeforeSegmentIds", splits)

        // UI edit is present so hasUserEdits is true
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        // But data-only edit is false (content/text/speakers/hidden are unchanged)
        assertFalse(TranscriptUserEdits.hasDataEdits(edits, original))

        val cleaned = TranscriptUserEdits.clean(edits, original)
        assertEquals(1, cleaned.size())
        assertTrue(cleaned.has("splitBeforeSegmentIds"))
        assertEquals(1, cleaned.getAsJsonArray("splitBeforeSegmentIds").size())
    }

    @Test
    fun test08_mainUserScenario_changeSpeakerMergeSplitAndRestore() {
        val originalJson = """
            {
                "id": "test_dialog",
                "duration": 5.0,
                "segments": [
                    { "id": "seg1", "start": 0.0, "end": 1.0, "speaker_id": "SPEAKER_00", "text": "First" },
                    { "id": "seg2", "start": 1.2, "end": 2.0, "speaker_id": "SPEAKER_01", "text": "Second" },
                    { "id": "seg3", "start": 2.2, "end": 3.0, "speaker_id": "SPEAKER_00", "text": "Third" }
                ],
                "speaker_labels": {
                    "SPEAKER_00": "Участник 1",
                    "SPEAKER_01": "Участник 2"
                }
            }
        """.trimIndent()

        val original = TranscriptJsonParser.parse(originalJson)
        val positions = mapOf("seg1" to 0, "seg2" to 1, "seg3" to 2)

        val origGroups = groupTranscriptSegments(original.segments, positions)
        assertEquals(3, origGroups.size)
        assertFalse(TranscriptUserEdits.hasUserEdits(JsonObject(), original))

        val edits = JsonObject()

        // 1. Change seg2 speaker to SPEAKER_00
        val speakers = JsonObject()
        speakers.addProperty("seg2", "SPEAKER_00")
        edits.add("speakers", speakers)

        val transcript1 = TranscriptUserEdits.apply(original, edits.toString())
        val groups1 = groupTranscriptSegments(transcript1.segments, positions)
        assertEquals(1, groups1.size)
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        // 2. Split group [seg1, seg2, seg3]
        val splits = JsonArray()
        splits.add("seg2")
        splits.add("seg3")
        edits.add("splitBeforeSegmentIds", splits)

        // 3. Return seg2 speaker back to SPEAKER_01
        speakers.addProperty("seg2", "SPEAKER_01")

        // Clean & Apply
        val transcript3 = TranscriptUserEdits.apply(original, edits.toString())
        val currentSplitIds = edits.getAsJsonArray("splitBeforeSegmentIds")
            ?.map { it.asString }?.toSet() ?: emptySet()
        val groups3 = groupTranscriptSegments(
            transcript3.segments, positions, splitBeforeSegmentIds = currentSplitIds
        )

        assertEquals(3, groups3.size)
        assertFalse(TranscriptUserEdits.hasEffectiveGroupingChanges(edits, original))
        assertFalse(TranscriptUserEdits.hasDataEdits(edits, original))
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))
    }

    @Test
    fun test09_groupSpeakerChangeSplitAndRestore() {
        val testJson = """
            {
                "id": "test_dialog",
                "duration": 5.0,
                "segments": [
                    { "id": "seg1", "start": 0.0, "end": 1.0, "speaker_id": "SPEAKER_00", "text": "A" },
                    { "id": "seg2", "start": 1.2, "end": 2.0, "speaker_id": "SPEAKER_01", "text": "B" },
                    { "id": "seg3", "start": 2.2, "end": 3.0, "speaker_id": "SPEAKER_00", "text": "C" }
                ],
                "speaker_labels": { "SPEAKER_00": "A", "SPEAKER_01": "B" }
            }
        """.trimIndent()

        val original = TranscriptJsonParser.parse(testJson)

        val edits = JsonObject()
        val speakers = JsonObject()

        speakers.addProperty("seg1", "SPEAKER_00")
        speakers.addProperty("seg2", "SPEAKER_00")
        speakers.addProperty("seg3", "SPEAKER_00")
        edits.add("speakers", speakers)

        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        val splits = JsonArray()
        splits.add("seg2")
        splits.add("seg3")
        edits.add("splitBeforeSegmentIds", splits)

        // Restore speakers back to original
        speakers.addProperty("seg1", "SPEAKER_00")
        speakers.addProperty("seg2", "SPEAKER_01")
        speakers.addProperty("seg3", "SPEAKER_00")

        assertFalse(TranscriptUserEdits.hasDataEdits(edits, original))
        assertFalse(TranscriptUserEdits.hasEffectiveGroupingChanges(edits, original))
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))
    }

    // ========================================================================
    // Diagnostics and edge cases.
    // ========================================================================

    @Test
    fun test10_multipleSpeakerChangesRestoredInDifferentOrder() {
        val testJson = """
            {
                "id": "test_dialog",
                "duration": 10.0,
                "segments": [
                    { "id": "seg1", "start": 0.0, "end": 1.0, "speaker_id": "SPEAKER_00", "text": "A" },
                    { "id": "seg2", "start": 1.2, "end": 2.0, "speaker_id": "SPEAKER_01", "text": "B" },
                    { "id": "seg3", "start": 2.2, "end": 3.0, "speaker_id": "SPEAKER_00", "text": "C" },
                    { "id": "seg4", "start": 3.2, "end": 4.0, "speaker_id": "SPEAKER_01", "text": "D" }
                ],
                "speaker_labels": { "SPEAKER_00": "A", "SPEAKER_01": "B" }
            }
        """.trimIndent()

        val original = TranscriptJsonParser.parse(testJson)
        val edits = JsonObject()
        val speakers = JsonObject()

        // 1. Change seg2 to SPEAKER_00 and seg4 to SPEAKER_00
        speakers.addProperty("seg2", "SPEAKER_00")
        speakers.addProperty("seg4", "SPEAKER_00")
        edits.add("speakers", speakers)

        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        // 2. Restore seg4 first
        speakers.addProperty("seg4", "SPEAKER_01")
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        // 3. Restore seg2
        speakers.addProperty("seg2", "SPEAKER_01")
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))
    }

    @Test
    fun test11_manualSplitAndRestoreAutoMerge() {
        val testJson = """
            {
                "id": "test_dialog",
                "duration": 5.0,
                "segments": [
                    { "id": "seg1", "start": 0.0, "end": 1.0, "speaker_id": "SPEAKER_00", "text": "A" },
                    { "id": "seg2", "start": 1.2, "end": 2.0, "speaker_id": "SPEAKER_00", "text": "B" }
                ],
                "speaker_labels": { "SPEAKER_00": "A" }
            }
        """.trimIndent()

        val original = TranscriptJsonParser.parse(testJson)
        val edits = JsonObject()
        val splits = JsonArray()

        splits.add("seg2")
        edits.add("splitBeforeSegmentIds", splits)
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        splits.remove(0)
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))
    }

    @Test
    fun test12_hideSegmentChangeAdjacentRestoreSegmentRevertSpeakers() {
        val testJson = """
            {
                "id": "test_dialog",
                "duration": 5.0,
                "segments": [
                    { "id": "seg1", "start": 0.0, "end": 1.0, "speaker_id": "SPEAKER_00", "text": "A" },
                    { "id": "seg2", "start": 1.2, "end": 2.0, "speaker_id": "SPEAKER_01", "text": "B" },
                    { "id": "seg3", "start": 2.2, "end": 3.0, "speaker_id": "SPEAKER_00", "text": "C" }
                ],
                "speaker_labels": { "SPEAKER_00": "A", "SPEAKER_01": "B" }
            }
        """.trimIndent()

        val original = TranscriptJsonParser.parse(testJson)
        val edits = JsonObject()

        val hidden = JsonArray()
        hidden.add("seg2")
        edits.add("hiddenSegments", hidden)

        val speakers = JsonObject()
        speakers.addProperty("seg3", "SPEAKER_01")
        edits.add("speakers", speakers)

        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        hidden.remove(0)
        speakers.addProperty("seg3", "SPEAKER_00")

        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))
    }

    @Test
    fun test13_leftoverSplitBoundariesAfterSpeakerChanges() {
        val testJson = """
            {
                "id": "test_dialog",
                "duration": 5.0,
                "segments": [
                    { "id": "seg1", "start": 0.0, "end": 1.0, "speaker_id": "SPEAKER_00", "text": "A" },
                    { "id": "seg2", "start": 1.2, "end": 2.0, "speaker_id": "SPEAKER_01", "text": "B" },
                    { "id": "seg3", "start": 2.2, "end": 3.0, "speaker_id": "SPEAKER_00", "text": "C" }
                ],
                "speaker_labels": { "SPEAKER_00": "A", "SPEAKER_01": "B" }
            }
        """.trimIndent()

        val original = TranscriptJsonParser.parse(testJson)
        val positions = mapOf("seg1" to 0, "seg2" to 1, "seg3" to 2)
        val edits = JsonObject()
        val speakers = TranscriptUserEdits.section(edits, "speakers")

        // 1. Change seg2 to SPEAKER_00
        speakers.addProperty("seg2", "SPEAKER_00")

        // 2. Split group
        val splits = JsonArray()
        splits.add("seg2")
        splits.add("seg3")
        edits.add("splitBeforeSegmentIds", splits)

        // 3. Revert seg2 back to SPEAKER_01 -> transcript matches original, stale splits are cleaned
        speakers.addProperty("seg2", "SPEAKER_01")
        TranscriptUserEdits.clean(edits, original)
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))

        // 4. Change seg3 to SPEAKER_01
        val speakers4 = TranscriptUserEdits.section(edits, "speakers")
        speakers4.addProperty("seg3", "SPEAKER_01")

        val currentTranscript = TranscriptUserEdits.apply(original, edits.toString())
        val currentSplitIds = edits.getAsJsonArray("splitBeforeSegmentIds")
            ?.map { it.asString }?.toSet() ?: emptySet()
        val currentGroups = groupTranscriptSegments(
            currentTranscript.segments, positions, splitBeforeSegmentIds = currentSplitIds
        )

        // seg2 (SPEAKER_01) and seg3 (SPEAKER_01) auto-merge cleanly into 1 group!
        val seg2Group = currentGroups.firstOrNull { g -> g.segments.any { it.id == "seg2" } }
        val seg3Group = currentGroups.firstOrNull { g -> g.segments.any { it.id == "seg3" } }

        assertEquals("seg2 and seg3 should auto-merge when both are SPEAKER_01",
            seg2Group, seg3Group)
    }

    @Test
    fun test14_renameSpeakerAndRevertToOriginalName() {
        val original = originalTranscript()
        val speakerId = "SPEAKER_00"
        val origName = original.speakerLabels[speakerId]!!

        val edits = JsonObject()
        val names = JsonObject()

        names.addProperty(speakerId, "Новое Имя")
        edits.add("names", names)
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        names.addProperty(speakerId, origName)
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))
    }

    @Test
    fun test15_renameNonExistentSpeakerID() {
        val original = originalTranscript()
        val editsJson = """
            {
                "names": {
                    "NON_EXISTENT_SPEAKER_123": "Призрак"
                }
            }
        """.trimIndent()

        val parsed = JsonParser.parseString(editsJson).asJsonObject

        // Non-existent speaker rename is cleaned and does not trigger fictitious edits
        assertFalse("Rename of non-existent speaker should not trigger hasUserEdits",
            TranscriptUserEdits.hasUserEdits(parsed, original))
    }

    @Test
    fun test16_textEditRevertWithWhitespaceAndLinebreaks() {
        val original = originalTranscript()
        val seg1 = original.segments[0]

        val edits = JsonObject()
        val texts = JsonObject()

        texts.addProperty(seg1.id, "Другой текст")
        edits.add("texts", texts)
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        // Revert text with surrounding spaces/linebreaks
        texts.addProperty(seg1.id, "  " + seg1.text + "\n")
        assertFalse("Text matching original after trim should not trigger hasUserEdits",
            TranscriptUserEdits.hasUserEdits(edits, original))
    }

    @Test
    fun test17_roomSaveAndReloadSimulation() {
        val original = originalTranscript()
        val seg1 = original.segments[0]

        val edits = JsonObject()
        val texts = JsonObject()
        texts.addProperty(seg1.id, seg1.text)
        edits.add("texts", texts)

        val cleaned = TranscriptUserEdits.clean(edits, original)
        assertEquals(0, cleaned.size())
        assertFalse(TranscriptUserEdits.hasUserEdits(cleaned, original))
    }

    @Test
    fun test18_manualBoundariesMissingTimestampsAndLargeGaps() {
        val testJson = """
            {
                "id": "test_dialog",
                "duration": 20.0,
                "segments": [
                    { "id": "seg1", "start": 0.0, "end": 1.0, "speaker_id": "SPEAKER_00", "text": "A" },
                    { "id": "seg2", "start": 10.0, "end": 12.0, "speaker_id": "SPEAKER_00", "text": "B" },
                    { "id": "seg3", "speaker_id": "SPEAKER_00", "text": "C" }
                ],
                "speaker_labels": { "SPEAKER_00": "A" }
            }
        """.trimIndent()

        val original = TranscriptJsonParser.parse(testJson)
        val edits = JsonObject()
        val splits = JsonArray()

        splits.add("seg2")
        splits.add("seg3")
        edits.add("splitBeforeSegmentIds", splits)

        assertFalse(TranscriptUserEdits.hasEffectiveGroupingChanges(edits, original))
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))
    }

    @Test
    fun test19_multipleRealChangesRevertingPartOfThem() {
        val original = originalTranscript()
        val seg1 = original.segments[0]
        val seg2 = original.segments[1]

        val edits = JsonObject()
        val texts = JsonObject()
        val speakers = JsonObject()
        val names = JsonObject()

        texts.addProperty(seg1.id, "Новый текст 1")
        speakers.addProperty(seg2.id, "SPEAKER_00")
        names.addProperty("SPEAKER_00", "Иван")

        edits.add("texts", texts)
        edits.add("speakers", speakers)
        edits.add("names", names)

        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        texts.addProperty(seg1.id, seg1.text)
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        speakers.addProperty(seg2.id, seg2.speakerId!!)
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))

        names.addProperty("SPEAKER_00", original.speakerLabels["SPEAKER_00"]!!)
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))
    }

    @Test
    fun test20_repositoryDataFlow_staleSplitsClearedBeforeNewEdit() {
        val testJson = """
            {
                "id": "test_dialog",
                "duration": 5.0,
                "segments": [
                    { "id": "seg1", "start": 0.0, "end": 1.0, "speaker_id": "SPEAKER_00", "text": "A" },
                    { "id": "seg2", "start": 1.2, "end": 2.0, "speaker_id": "SPEAKER_01", "text": "B" },
                    { "id": "seg3", "start": 2.2, "end": 3.0, "speaker_id": "SPEAKER_00", "text": "C" }
                ],
                "speaker_labels": { "SPEAKER_00": "A", "SPEAKER_01": "B" }
            }
        """.trimIndent()

        val original = TranscriptJsonParser.parse(testJson)
        val positions = mapOf("seg1" to 0, "seg2" to 1, "seg3" to 2)

        // 1. Simulate state in Room after user changed seg2 to SPEAKER_00, split group, and reverted seg2 to SPEAKER_01
        val staleRoomEditsJson = """
            {
                "speakers": { "seg2": "SPEAKER_01" },
                "splitBeforeSegmentIds": ["seg2", "seg3"]
            }
        """.trimIndent()

        val edits = JsonParser.parseString(staleRoomEditsJson).asJsonObject

        // Pre-clean existing state from Room before applying new edit (simulating updateEdits behavior)
        TranscriptUserEdits.clean(edits, original)
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))

        // Now apply new edit: change seg3 speaker to SPEAKER_01
        val speakers = TranscriptUserEdits.section(edits, "speakers")
        speakers.addProperty("seg3", "SPEAKER_01")
        TranscriptUserEdits.clean(edits, original)

        val currentTranscript = TranscriptUserEdits.apply(original, edits.toString())
        val currentSplitIds = edits.getAsJsonArray("splitBeforeSegmentIds")
            ?.map { it.asString }?.toSet() ?: emptySet()
        val currentGroups = groupTranscriptSegments(
            currentTranscript.segments, positions, splitBeforeSegmentIds = currentSplitIds
        )

        // Verify seg2 and seg3 auto-merge into 1 group without stale split boundaries!
        val seg2Group = currentGroups.firstOrNull { g -> g.segments.any { it.id == "seg2" } }
        val seg3Group = currentGroups.firstOrNull { g -> g.segments.any { it.id == "seg3" } }

        assertEquals("seg2 and seg3 should auto-merge when both are SPEAKER_01",
            seg2Group, seg3Group)
    }

    private fun groupedTranscript(): ParsedTranscript = TranscriptJsonParser.parse("""
        {
            "id": "group_editor", "duration": 30.0,
            "segments": [
                {"id":"a", "start":10.0, "end":15.0, "speaker_id":"S1", "text":"Первый"},
                {"id":"b", "start":15.2, "end":21.7, "speaker_id":"S1", "text":"Второй"},
                {"id":"c", "start":21.7, "end":28.0, "speaker_id":"S1", "text":"Третий"}
            ],
            "speaker_labels": {"S1":"Первый говорящий", "S2":"Второй говорящий"}
        }
    """.trimIndent())

    @Test
    fun editingInnerFragmentDoesNotChangeItsNeighboursOrOriginal() {
        val original = groupedTranscript()
        val originalSegments = original.segments.toList()
        val edits = JsonObject()
        TranscriptUserEdits.updateSegment(edits, original, "b", "  Исправленный второй  ", "S1")
        TranscriptUserEdits.clean(edits, original)
        val reloaded = TranscriptUserEdits.apply(original, edits.toString())
        assertEquals(originalSegments, original.segments)
        assertEquals(original.segments[0], reloaded.segments[0])
        assertEquals(original.segments[2], reloaded.segments[2])
        assertEquals(original.segments[1].copy(text = "Исправленный второй"), reloaded.segments[1])
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))
    }

    @Test
    fun splittingEditedGroupAfterReloadPreservesAllFragmentData() {
        val original = groupedTranscript()
        val edits = JsonObject()
        TranscriptUserEdits.updateSegment(edits, original, "b", "Исправление внутри группы", "S1")
        val positions = original.segments.mapIndexed { i, s -> s.id to i }.toMap()
        val edited = TranscriptUserEdits.apply(original, edits.toString())
        assertEquals(1, groupTranscriptSegments(edited.segments, positions).size)
        edits.add("splitBeforeSegmentIds", JsonArray().apply { add("b"); add("c") })
        TranscriptUserEdits.clean(edits, original)
        val persisted = JsonParser.parseString(edits.toString()).asJsonObject
        val reloaded = TranscriptUserEdits.apply(original, persisted.toString())
        val splits = persisted.getAsJsonArray("splitBeforeSegmentIds").map { it.asString }.toSet()
        val separated = groupTranscriptSegments(reloaded.segments, positions, splitBeforeSegmentIds = splits)
        assertEquals(3, separated.size)
        assertEquals(edited.segments, separated.flatMap { it.segments })
        assertEquals(original.segments.map { Triple(it.id, it.startMs, it.endMs) },
            reloaded.segments.map { Triple(it.id, it.startMs, it.endMs) })
        assertTrue(TranscriptUserEdits.hasUserEdits(persisted, original))
    }

    @Test
    fun changingSpeakerAndRegroupingPreservesCorrectedText() {
        val original = groupedTranscript()
        val edits = JsonObject()
        val positions = original.segments.mapIndexed { i, s -> s.id to i }.toMap()
        TranscriptUserEdits.updateSegment(edits, original, "b", "Исправленный текст", "S2")
        val changed = TranscriptUserEdits.apply(original, edits.toString())
        assertEquals(3, groupTranscriptSegments(changed.segments, positions).size)
        TranscriptUserEdits.updateSegment(edits, original, "b", changed.segments[1].text, "S1")
        TranscriptUserEdits.clean(edits, original)
        val restoredSpeaker = TranscriptUserEdits.apply(original, edits.toString())
        assertEquals(1, groupTranscriptSegments(restoredSpeaker.segments, positions).size)
        assertEquals("Исправленный текст", restoredSpeaker.segments[1].text)
        assertEquals(original.segments[1].startMs, restoredSpeaker.segments[1].startMs)
        assertEquals(original.segments[1].endMs, restoredSpeaker.segments[1].endMs)
    }

    @Test
    fun deletingAndRestoringEditedFragmentsKeepsTheirEdits() {
        val original = groupedTranscript()
        val edits = JsonObject()
        TranscriptUserEdits.updateSegment(edits, original, "b", "Сохранённая правка", "S2")
        val beforeDelete = TranscriptUserEdits.apply(original, edits.toString())
        edits.add("hiddenSegments", JsonArray().apply { add("a"); add("b"); add("c") })
        assertTrue(TranscriptUserEdits.apply(original, edits.toString()).segments.isEmpty())
        edits.add("hiddenSegments", JsonArray().apply { add("a"); add("c") })
        assertEquals(listOf(beforeDelete.segments[1]),
            TranscriptUserEdits.apply(original, edits.toString()).segments)
        edits.remove("hiddenSegments")
        assertEquals(beforeDelete, TranscriptUserEdits.apply(original, edits.toString()))
    }

    @Test
    fun resetRemovesAllSupportedEditsAndReturnsOriginalOrderAndTiming() {
        val original = groupedTranscript()
        val edits = JsonObject()
        TranscriptUserEdits.updateSegment(edits, original, "b", "Правка", "S2")
        TranscriptUserEdits.section(edits, "names").addProperty("S1", "Новое имя")
        edits.add("hiddenSegments", JsonArray().apply { add("a") })
        edits.add("splitBeforeSegmentIds", JsonArray().apply { add("c") })
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))
        // resetAllUserEdits clears these overlay keys, leaving the saved raw transcript intact.
        edits.keySet().toList().forEach(edits::remove)
        val persisted = JsonParser.parseString(edits.toString()).asJsonObject
        assertFalse(TranscriptUserEdits.hasUserEdits(persisted, original))
        assertEquals(original, TranscriptUserEdits.apply(original, persisted.toString()))
    }

    @Test
    fun invalidCombinedSubmissionCannotApplyOnlyTheText() {
        val original = groupedTranscript()
        val edits = JsonObject()
        TranscriptUserEdits.updateSegment(edits, original, "a", "Ранее сохранено", "S1")
        val before = edits.deepCopy()
        try {
            TranscriptUserEdits.updateSegment(edits, original, "b", "Новый текст", "missing-speaker")
            org.junit.Assert.fail("Unknown speaker must reject the complete submission")
        } catch (_: IllegalArgumentException) {
            assertEquals(before, edits)
        }
    }

    @Test
    fun savingUnchangedTextAndSpeakerDoesNotExposeResetAction() {
        val original = groupedTranscript()
        val edits = JsonObject()
        val segment = original.segments[1]
        TranscriptUserEdits.updateSegment(edits, original, segment.id, segment.text, segment.speakerId)
        TranscriptUserEdits.clean(edits, original)
        assertEquals("{}", edits.toString())
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))
    }

}
