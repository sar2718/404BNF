package com.team404bnf.app.transcription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranscriptJsonExporterTest {

    private fun sample(): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("01_dialog_clean.json")
    ) { "Нет тестового файла 01_dialog_clean.json" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun originalTranscript(): ParsedTranscript =
        TranscriptJsonParser.parse(sample())

    @Test
    fun testOriginalVersionExportStructure() {
        val original = originalTranscript()
        val jsonString = TranscriptJsonExporter.export(original, version = ExportVersion.ORIGINAL)

        val root = JsonParser.parseString(jsonString).asJsonObject
        assertEquals(1, root.keySet().size)
        assertTrue(root.has("transcript"))

        val array = root.getAsJsonArray("transcript")
        assertEquals(original.segments.size, array.size())

        val firstObj = array[0].asJsonObject
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), firstObj.keySet())
        assertEquals(original.segments[0].speakerId, firstObj.get("speaker_id").asString)
        assertEquals(original.segments[0].startMs, firstObj.get("start_ms").asLong)
        assertEquals(original.segments[0].endMs, firstObj.get("end_ms").asLong)
        assertEquals(original.segments[0].text, firstObj.get("text").asString)
    }

    @Test
    fun testOriginalExportIsIndependentOfUserEdits() {
        val original = originalTranscript()
        val seg1 = original.segments[0]

        val edits = JsonObject()
        val texts = JsonObject()
        texts.addProperty(seg1.id, "Изменённый текст для теста")
        edits.add("texts", texts)

        val originalExportWithoutEdits = TranscriptJsonExporter.export(original, version = ExportVersion.ORIGINAL)
        val originalExportWithEdits = TranscriptJsonExporter.export(original, edits.toString(), version = ExportVersion.ORIGINAL)

        assertEquals(originalExportWithoutEdits, originalExportWithEdits)
    }

    @Test
    fun testCorrectedVersionEditedText() {
        val original = originalTranscript()
        val seg1 = original.segments[0]
        val editedText = "Новый отредактированный текст реплики"

        val edits = JsonObject()
        val texts = JsonObject()
        texts.addProperty(seg1.id, editedText)
        edits.add("texts", texts)

        assertTrue(TranscriptJsonExporter.hasExportableDifferences(original, edits.toString()))

        val correctedJson = TranscriptJsonExporter.export(original, edits.toString(), version = ExportVersion.CORRECTED)
        val root = JsonParser.parseString(correctedJson).asJsonObject
        val firstObj = root.getAsJsonArray("transcript")[0].asJsonObject

        assertEquals(editedText, firstObj.get("text").asString)
    }

    @Test
    fun testCorrectedVersionEditedSpeaker() {
        val original = originalTranscript()
        val seg1 = original.segments[0]
        val newSpeaker = "SPEAKER_99"

        val edits = JsonObject()
        val speakers = JsonObject()
        speakers.addProperty(seg1.id, newSpeaker)
        edits.add("speakers", speakers)

        assertTrue(TranscriptJsonExporter.hasExportableDifferences(original, edits.toString()))

        val correctedJson = TranscriptJsonExporter.export(original, edits.toString(), version = ExportVersion.CORRECTED)
        val root = JsonParser.parseString(correctedJson).asJsonObject
        val firstObj = root.getAsJsonArray("transcript")[0].asJsonObject

        assertEquals(newSpeaker, firstObj.get("speaker_id").asString)
    }

    @Test
    fun testHiddenAndRestoredSegments() {
        val original = originalTranscript()
        val seg1 = original.segments[0]

        val edits = JsonObject()
        val hidden = JsonArray()
        hidden.add(seg1.id)
        edits.add("hiddenSegments", hidden)

        // Hidden segment should create difference
        assertTrue(TranscriptJsonExporter.hasExportableDifferences(original, edits.toString()))

        val correctedJson = TranscriptJsonExporter.export(original, edits.toString(), version = ExportVersion.CORRECTED)
        val root = JsonParser.parseString(correctedJson).asJsonObject
        val array = root.getAsJsonArray("transcript")

        assertEquals(original.segments.size - 1, array.size())

        // Restoring segment removes difference
        hidden.remove(0)
        assertFalse(TranscriptJsonExporter.hasExportableDifferences(original, edits.toString()))
    }

    @Test
    fun testSpeakerRenameDoesNotAffectExportedJson() {
        val original = originalTranscript()
        val speakerId = "SPEAKER_00"

        val edits = JsonObject()
        val names = JsonObject()
        names.addProperty(speakerId, "Виктор Иванович")
        edits.add("names", names)

        // Renaming speaker only changes display name, NOT speaker_id in JSON export
        assertFalse(TranscriptJsonExporter.hasExportableDifferences(original, edits.toString()))

        val originalExport = TranscriptJsonExporter.export(original, version = ExportVersion.ORIGINAL)
        val correctedExport = TranscriptJsonExporter.export(original, edits.toString(), version = ExportVersion.CORRECTED)

        assertEquals(originalExport, correctedExport)
    }

    @Test
    fun testManualGroupSplitDoesNotAffectExportedJson() {
        val original = originalTranscript()
        val seg2 = original.segments[1]

        val edits = JsonObject()
        val splits = JsonArray()
        splits.add(seg2.id)
        edits.add("splitBeforeSegmentIds", splits)

        // Group split is UI-only and does NOT change segment list in exported JSON
        assertFalse(TranscriptJsonExporter.hasExportableDifferences(original, edits.toString()))

        val originalExport = TranscriptJsonExporter.export(original, version = ExportVersion.ORIGINAL)
        val correctedExport = TranscriptJsonExporter.export(original, edits.toString(), version = ExportVersion.CORRECTED)

        assertEquals(originalExport, correctedExport)
    }

    @Test
    fun testRevertingAllEditsResultInNoDifferences() {
        val original = originalTranscript()
        val seg1 = original.segments[0]

        val edits = JsonObject()
        val texts = JsonObject()
        texts.addProperty(seg1.id, "Временный текст")
        edits.add("texts", texts)

        assertTrue(TranscriptJsonExporter.hasExportableDifferences(original, edits.toString()))

        // Revert text back to original
        texts.addProperty(seg1.id, seg1.text)

        assertFalse(TranscriptJsonExporter.hasExportableDifferences(original, edits.toString()))
    }

    @Test
    fun testMissingTimestampsAndSpeakerIdRejectsExport() {
        val testJson = """
            {
                "id": "test_missing",
                "duration": 5.0,
                "segments": [
                    { "id": "s1", "text": "Без времени и без спикера" }
                ]
            }
        """.trimIndent()

        val parsed = TranscriptJsonParser.parse(testJson)

        try {
            TranscriptJsonExporter.export(parsed, version = ExportVersion.ORIGINAL)
            fail("Expected IllegalArgumentException due to missing timestamps and speaker ID")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("неполные данные"))
        }
    }

    @Test
    fun testEmptyOriginalTranscriptRejectsExport() {
        val testJson = """
            {
                "id": "test_empty",
                "duration": 5.0,
                "segments": []
            }
        """.trimIndent()

        val emptyParsed = TranscriptJsonParser.parse(testJson)

        try {
            TranscriptJsonExporter.export(emptyParsed, version = ExportVersion.ORIGINAL)
            fail("Expected IllegalArgumentException due to empty transcript")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("пуста"))
        }
    }

    @Test
    fun testAllSegmentsHiddenExportsEmptyArray() {
        val original = originalTranscript()
        val edits = JsonObject()
        val hidden = JsonArray()
        original.segments.forEach { hidden.add(it.id) }
        edits.add("hiddenSegments", hidden)

        // Hiding all segments is a valid user edit; Corrected version exports {"transcript": []}
        assertTrue(TranscriptJsonExporter.hasExportableDifferences(original, edits.toString()))

        val allHiddenExport = TranscriptJsonExporter.export(original, edits.toString(), version = ExportVersion.CORRECTED)
        val allHiddenRoot = JsonParser.parseString(allHiddenExport).asJsonObject

        assertTrue(allHiddenRoot.has("transcript"))
        assertEquals(0, allHiddenRoot.getAsJsonArray("transcript").size())
    }

    @Test
    fun testTextEscapingSpecialCharactersAndNewlines() {
        val testJson = """
            {
                "id": "test_escaping",
                "duration": 5.0,
                "segments": [
                    { "id": "s1", "start": 0.0, "end": 1.0, "speaker_id": "SPEAKER_00", "text": "Строка с \"кавычками\", \nпереносом, \tтабуляцией и \\ слэшем." }
                ]
            }
        """.trimIndent()

        val parsed = TranscriptJsonParser.parse(testJson)
        val exportedJson = TranscriptJsonExporter.export(parsed, version = ExportVersion.ORIGINAL)

        val root = JsonParser.parseString(exportedJson).asJsonObject
        val text = root.getAsJsonArray("transcript")[0].asJsonObject.get("text").asString

        assertEquals("Строка с \"кавычками\", \nпереносом, \tтабуляцией и \\ слэшем.", text)
    }

    @Test
    fun testStrictJsonSchemaConformance() {
        val original = originalTranscript()
        val first = original.segments.first()
        val edits = JsonObject().apply {
            add("texts", JsonObject().apply { addProperty(first.id, "Исправленный текст") })
        }
        for (version in ExportVersion.entries) {
            val jsonString = TranscriptJsonExporter.export(original, edits.toString(), version)
            val root = JsonParser.parseString(jsonString).asJsonObject
            assertEquals(setOf("transcript"), root.keySet())
            assertTrue(root.get("transcript").isJsonArray)
            val array = root.getAsJsonArray("transcript")
            array.forEachIndexed { index, element ->
                assertTrue(element.isJsonObject)
                val obj = element.asJsonObject
                assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), obj.keySet())
                assertTrue(obj.get("speaker_id").asJsonPrimitive.isString)
                assertTrue(obj.get("text").asJsonPrimitive.isString)
                for (field in listOf("start_ms", "end_ms")) {
                    val value = obj.get(field).asJsonPrimitive
                    assertTrue(value.isNumber)
                    assertTrue("$field must be an integer token in milliseconds", value.toString().matches(Regex("[0-9]+")))
                }
                assertEquals(original.segments[index].startMs, obj.get("start_ms").asLong)
                assertEquals(original.segments[index].endMs, obj.get("end_ms").asLong)
            }
            assertEquals(if (version == ExportVersion.CORRECTED) "Исправленный текст" else first.text,
                array.first().asJsonObject.get("text").asString)
        }
    }

    @Test
    fun testExactExampleFromRequiredPhotoFormat() {
        val original = TranscriptJsonParser.parse("""
            {
                "id": "photo-example", "duration": 5.0,
                "segments": [
                    {"id": "fragment-1", "start": 1.25, "end": 4.38,
                     "speaker_id": "speaker_1", "text": "Добрый день!"}
                ]
            }
        """.trimIndent())
        val expected = JsonParser.parseString("""
            {"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}
        """.trimIndent())
        for (version in ExportVersion.entries) {
            val actual = JsonParser.parseString(TranscriptJsonExporter.export(original, version = version))
            assertEquals(expected, actual)
            val segment = actual.asJsonObject.getAsJsonArray("transcript")[0].asJsonObject
            assertEquals("1250", segment.get("start_ms").toString())
            assertEquals("4380", segment.get("end_ms").toString())
        }
    }

    @Test
    fun testEachMissingRequiredFieldRejectsExportInsteadOfWritingNull() {
        val original = originalTranscript()
        val first = original.segments.first()
        val incompleteSegments = listOf(first.copy(speakerId = null), first.copy(startMs = null), first.copy(endMs = null))
        for (segment in incompleteSegments) {
            val incomplete = original.copy(segments = listOf(segment) + original.segments.drop(1))
            for (version in ExportVersion.entries) {
                try {
                    TranscriptJsonExporter.export(incomplete, version = version)
                    fail("Incomplete $version export must not produce null fields")
                } catch (error: IllegalArgumentException) {
                    assertTrue(error.message.orEmpty().contains("неполные данные"))
                }
            }
        }
    }

    @Test
    fun testSuggestJsonFileName() {
        val originalName = com.team404bnf.app.ui.playback.suggestJsonFileName(
            title = "Запись 07.11/14:35",
            version = ExportVersion.ORIGINAL
        )
        assertEquals("Запись 07.11_14_35_original.json", originalName)

        val correctedName = com.team404bnf.app.ui.playback.suggestJsonFileName(
            title = "Запись 07.11/14:35",
            version = ExportVersion.CORRECTED
        )
        assertEquals("Запись 07.11_14_35_corrected.json", correctedName)
    }

    @Test
    fun testIncompleteOriginalSegmentHiddenInCorrected() {
        val testJson = """
            {
                "id": "test_incomplete",
                "duration": 5.0,
                "segments": [
                    { "id": "s1", "text": "Неполный сегмент без времени" }
                ]
            }
        """.trimIndent()

        val original = TranscriptJsonParser.parse(testJson)

        val edits = JsonObject()
        val hidden = JsonArray()
        hidden.add("s1")
        edits.add("hiddenSegments", hidden)

        val options = TranscriptJsonExporter.checkExportOptions(original, edits.toString())

        assertFalse(options.originalValid)
        assertTrue(options.correctedValid)
        assertFalse(options.showVersionSelection)

        val correctedExport = TranscriptJsonExporter.export(original, edits.toString(), version = ExportVersion.CORRECTED)
        val root = JsonParser.parseString(correctedExport).asJsonObject
        assertEquals(0, root.getAsJsonArray("transcript").size())

        try {
            TranscriptJsonExporter.export(original, edits.toString(), version = ExportVersion.ORIGINAL)
            fail("Expected IllegalArgumentException when exporting incomplete ORIGINAL version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("неполные данные"))
        }
    }
}
