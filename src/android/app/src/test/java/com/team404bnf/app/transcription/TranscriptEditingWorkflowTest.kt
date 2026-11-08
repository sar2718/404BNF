package com.team404bnf.app.transcription

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.team404bnf.app.ui.playback.groupTranscriptSegments
import com.team404bnf.app.ui.playback.restorableMergeStarts
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class TranscriptEditingWorkflowTest {
    private val original = TranscriptJsonParser.parse("""
        {"id":"workflow", "duration":6.0,
         "segments":[
          {"id":"a","start":0.0,"end":1.0,"speaker_id":"S1","text":"Первый"},
          {"id":"b","start":1.2,"end":2.7,"speaker_id":"S1","text":"Второй"},
          {"id":"c","start":2.7,"end":3.5,"speaker_id":"S1","text":"Третий"},
          {"id":"d","start":4.0,"end":5.0,"speaker_id":"S2","text":"Сосед"}],
         "speaker_labels":{"S1":"Анна","S2":"Борис"}}
    """.trimIndent())
    private val positions = original.segments.mapIndexed { index, segment -> segment.id to index }.toMap()
    private var edits = JsonObject()
    // The repository uses the same pre/post normalization within one Room transaction.
    private fun edit(action: () -> Unit) {
        TranscriptUserEdits.clean(edits, original)
        action()
        TranscriptUserEdits.clean(edits, original)
    }
    private fun reopen() { edits = JsonParser.parseString(edits.toString()).asJsonObject }
    private fun current() = TranscriptUserEdits.apply(original, edits.toString())
    private fun groups() = groupTranscriptSegments(current().segments, positions,
        splitBeforeSegmentIds = TranscriptUserEdits.splitBeforeIds(edits))
    private fun groupIds() = groups().map { it.segments.map { segment -> segment.id } }
    private fun mergeable() = restorableMergeStarts(current().segments, positions, TranscriptUserEdits.splitBeforeIds(edits))
    private fun assertTimingAndOrder() {
        assertEquals(original.segments.map { Triple(it.id, it.startMs, it.endMs) },
            current().segments.map { Triple(it.id, it.startMs, it.endMs) })
    }
    private fun rejectsWithoutMutation(action: () -> Unit) {
        val before = edits.deepCopy()
        try { action(); fail("Operation is not applicable to the current selection") }
        catch (_: IllegalArgumentException) { assertEquals(before, edits) }
    }

    @Test fun `merge edit inner fragment split and reopen preserve independent fragments`() {
        assertEquals(listOf(listOf("a","b","c"),listOf("d")), groupIds())
        edit { TranscriptUserEdits.updateSegment(edits, original, "b", "Исправленный второй", "S1") }
        edit { TranscriptUserEdits.splitGroup(edits, original, listOf("a","b","c")) }
        reopen()
        assertEquals(listOf(listOf("a"),listOf("b"),listOf("c"),listOf("d")), groupIds())
        assertEquals(original.segments[1].copy(text = "Исправленный второй"), current().segments[1])
        assertEquals(original.segments[0], current().segments[0])
        assertEquals(original.segments[2], current().segments[2])
        assertEquals(original.segments[3], current().segments[3])
        assertTimingAndOrder()
    }

    @Test fun `speaker changes regroup visible fragments without rewriting neighbours`() {
        edit { TranscriptUserEdits.updateSegment(edits, original, "b", "Правка второго", "S2") }
        assertEquals(listOf(listOf("a"),listOf("b"),listOf("c"),listOf("d")), groupIds())
        edit { TranscriptUserEdits.changeSpeakers(edits, original, listOf("c"), "S2") }
        reopen()
        assertEquals(listOf(listOf("a"),listOf("b","c","d")), groupIds())
        assertEquals("Правка второго", current().segments[1].text)
        assertEquals(original.segments[3], current().segments[3])
        edit { TranscriptUserEdits.changeGroupSpeaker(edits, original, listOf("b","c","d"), "S1") }
        assertEquals(listOf(listOf("a","b","c","d")), groupIds())
        assertTimingAndOrder()
        assertEquals("Правка второго", current().segments[1].text)
    }

    @Test fun `delete restore and reset preserve edits then recover the immutable original`() {
        edit { TranscriptUserEdits.updateSegment(edits, original, "b", "Сохранённая правка", "S2") }
        val editedFragment = current().segments[1]
        edit { TranscriptUserEdits.hideSegments(edits, original, listOf("b")) }
        reopen()
        assertEquals(listOf("a","c","d"), current().segments.map { it.id })
        assertEquals(listOf(editedFragment), TranscriptUserEdits.deletedSegments(edits, original))
        edit { TranscriptUserEdits.restoreSegment(edits, original, "b") }
        assertEquals(editedFragment, current().segments[1])
        assertTimingAndOrder()
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))
        edit { TranscriptUserEdits.reset(edits) }
        reopen()
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))
        assertEquals(original, current())
    }

    @Test fun `partially rejoined group can merge back with its previous group`() {
        edit { TranscriptUserEdits.splitGroup(edits, original, listOf("a","b","c")) }
        assertTrue(TranscriptUserEdits.hasUserEdits(edits, original))
        assertFalse(TranscriptTxtFormatter.checkExportOptions(original, edits.toString()).showVersionSelection)
        edit { TranscriptUserEdits.restoreMerge(edits, original, "c") }
        assertEquals(listOf(listOf("a"),listOf("b","c"),listOf("d")), groupIds())
        assertEquals(setOf("b"), mergeable())
        edit { TranscriptUserEdits.restoreMerge(edits, original, "b") }
        reopen()
        assertEquals(listOf(listOf("a","b","c"),listOf("d")), groupIds())
        assertFalse(TranscriptUserEdits.hasUserEdits(edits, original))
    }

    @Test fun `stale group and inapplicable merge requests cannot modify unrelated fragments`() {
        edit { TranscriptUserEdits.splitGroup(edits, original, listOf("a","b","c")) }
        edit { TranscriptUserEdits.changeSpeakers(edits, original, listOf("b"), "S2") }
        assertTrue(mergeable().isEmpty())
        rejectsWithoutMutation { TranscriptUserEdits.restoreMerge(edits, original, "b") }
        rejectsWithoutMutation { TranscriptUserEdits.restoreMerge(edits, original, "c") }
        rejectsWithoutMutation { TranscriptUserEdits.changeGroupSpeaker(edits, original, listOf("a","b","c"), "S2") }
        rejectsWithoutMutation { TranscriptUserEdits.hideGroup(edits, original, listOf("a","b","c")) }
        rejectsWithoutMutation { TranscriptUserEdits.splitGroup(edits, original, listOf("a","b","c")) }
        assertTimingAndOrder()
    }

    @Test fun `hidden fragment offers recovery and cannot be changed by a stale editor`() {
        edit { TranscriptUserEdits.hideGroup(edits, original, listOf("a","b","c")) }
        reopen()
        rejectsWithoutMutation { TranscriptUserEdits.updateSegment(edits, original, "b", "Не сохранять", "S2") }
        rejectsWithoutMutation { TranscriptUserEdits.changeSpeakers(edits, original, listOf("b"), "S2") }
        assertEquals(listOf("a","b","c"), TranscriptUserEdits.deletedSegments(edits, original).map { it.id })
        edit { TranscriptUserEdits.restoreSegment(edits, original, "b") }
        assertEquals(listOf("b","d"), current().segments.map { it.id })
        assertEquals(original.segments[1], current().segments.first())
    }

    @Test fun `both export versions after multiple edits read a snapshot and do not modify overlays`() {
        edit { TranscriptUserEdits.updateSegment(edits, original, "b", "Новый текст", "S2") }
        edit { TranscriptUserEdits.hideSegments(edits, original, listOf("c")) }
        TranscriptUserEdits.section(edits, "names").addProperty("S2", "Переименованный говорящий")
        reopen()
        val saved = edits.toString()
        for(version in ExportVersion.entries) {
            val selected = TranscriptTxtFormatter.selectVersion(original, saved, version)
            val writer = StringWriter()
            TranscriptTxtFormatter.writeTo(writer, "Запись", selected.segments, selected.speakerLabels)
            val txt = writer.toString()
            val json = JsonParser.parseString(TranscriptJsonExporter.export(original, saved, version))
                .asJsonObject.getAsJsonArray("transcript")
            assertEquals(saved, edits.toString())
            if(version == ExportVersion.ORIGINAL) {
                assertTrue(txt.contains("Второй")); assertTrue(txt.contains("Третий")); assertFalse(txt.contains("Новый текст"))
                assertFalse(txt.contains("Переименованный")); assertEquals(4, json.size())
            } else {
                assertTrue(txt.contains("Новый текст")); assertTrue(txt.contains("Переименованный")); assertFalse(txt.contains("Третий"))
                assertTrue(txt.contains("[00:01.200–00:02.700]")); assertEquals(3, json.size())
                assertEquals("S2", json[1].asJsonObject.get("speaker_id").asString)
                assertEquals(1200L, json[1].asJsonObject.get("start_ms").asLong)
                assertEquals(2700L, json[1].asJsonObject.get("end_ms").asLong)
            }
        }
        assertEquals("Второй", original.segments[1].text)
        assertEquals("S1", original.segments[1].speakerId)
    }
}
