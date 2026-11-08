package com.team404bnf.app.transcription

import com.google.gson.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter

class TranscriptTxtFormatterTest {

    @Test
    fun testFormatEntryWithFullTimestamps() {
        val entry = TranscriptTxtFormatter.formatEntry(
            speakerName = "Участник 1",
            text = "Илья, привет. Давай начнём.",
            startMs = 0L,
            endMs = 4500L
        )

        val expected = "[00:00–00:04.500] Участник 1\nИлья, привет. Давай начнём.\n\n"
        assertEquals(expected, entry)
    }

    @Test
    fun testFormatEntryWithoutTimestamps() {
        val entry = TranscriptTxtFormatter.formatEntry(
            speakerName = "Участник 2",
            text = "Без времени",
            startMs = null,
            endMs = null
        )

        val expected = "Участник 2\nБез времени\n\n"
        assertEquals(expected, entry)
    }

    @Test
    fun testFormatEntryOverOneHour() {
        val entry = TranscriptTxtFormatter.formatEntry(
            speakerName = "Спикер",
            text = "Долгий разговор",
            startMs = 3605000L, // 1:00:05
            endMs = 7261000L    // 2:01:01
        )

        val expected = "[01:00:05–02:01:01] Спикер\nДолгий разговор\n\n"
        assertEquals(expected, entry)
    }

    @Test
    fun testWriteToFullTranscript() {
        val writer = StringWriter()
        val title = "Запись 05.11, 00:17"

        val segments = listOf(
            TranscriptSegment(
                id = "1",
                startMs = 0L,
                endMs = 4000L,
                speakerId = "SPK1",
                speakerLabel = null,
                text = "Илья, привет. Давай начнём.",
                overlap = null,
                speakerAmbiguous = null
            ),
            TranscriptSegment(
                id = "2",
                startMs = 4000L,
                endMs = 9000L,
                speakerId = "SPK2",
                speakerLabel = null,
                text = "Привет, Алексей. В основном работал над задачей.",
                overlap = null,
                speakerAmbiguous = null
            ),
            TranscriptSegment(
                id = "3",
                startMs = 9000L,
                endMs = 12000L,
                speakerId = "SPK1",
                speakerLabel = null,
                text = "Окей, по логированию какой статус сейчас?",
                overlap = null,
                speakerAmbiguous = null
            )
        )

        val speakerLabels = mapOf(
            "SPK1" to "Участник 1",
            "SPK2" to "Участник 2"
        )

        TranscriptTxtFormatter.writeTo(writer, title, segments, speakerLabels)

        val expected = """
            Запись 05.11, 00:17

            [00:00–00:04] Участник 1
            Илья, привет. Давай начнём.

            [00:04–00:09] Участник 2
            Привет, Алексей. В основном работал над задачей.

            [00:09–00:12] Участник 1
            Окей, по логированию какой статус сейчас?


        """.trimIndent()

        // Note: The formatter adds "\n\n" after each entry, which corresponds to two newlines at the end
        // However trimIndent() removes trailing whitespaces from the whole block if not careful.
        // Let's construct expected string manually to be exact

        val expectedExact = "Запись 05.11, 00:17\n\n" +
            "[00:00–00:04] Участник 1\nИлья, привет. Давай начнём.\n\n" +
            "[00:04–00:09] Участник 2\nПривет, Алексей. В основном работал над задачей.\n\n" +
            "[00:09–00:12] Участник 1\nОкей, по логированию какой статус сейчас?\n\n"

        assertEquals(expectedExact, writer.toString())
    }

    @Test
    fun testWriteToWithMissingSpeakerLabel() {
        val writer = StringWriter()
        val title = "Test"

        val segments = listOf(
            TranscriptSegment(
                id = "1",
                startMs = 0L,
                endMs = 1000L,
                speakerId = "SPK3",
                speakerLabel = "Speaker 3", // Present in segment but not in labels map
                text = "Hello",
                overlap = null,
                speakerAmbiguous = null
            ),
            TranscriptSegment(
                id = "2",
                startMs = 1000L,
                endMs = 2000L,
                speakerId = null, // No speaker ID
                speakerLabel = null,
                text = "Unknown",
                overlap = null,
                speakerAmbiguous = null
            )
        )

        TranscriptTxtFormatter.writeTo(writer, title, segments, emptyMap())

        val expectedExact = "Test\n\n" +
            "[00:00–00:01] Speaker 3\nHello\n\n" +
            "[00:01–00:02] Говорящий не указан\nUnknown\n\n"

        assertEquals(expectedExact, writer.toString())
    }
    private fun originalForVersions(): ParsedTranscript = TranscriptJsonParser.parse("""
        {"id":"txt-versions","duration":5.0,
         "segments":[{"id":"one","start":1.2,"end":2.7,"speaker_id":"S1","text":"Оригинал"}],
         "speaker_labels":{"S1":"Участник"}}
    """.trimIndent())

    @Test fun versionChoiceUsesTxtDifferencesIncludingRenamedSpeakers() {
        val original = originalForVersions()
        assertFalse(TranscriptTxtFormatter.checkExportOptions(original, "{}").showVersionSelection)
        val edits = JsonObject().apply {
            add("names", JsonObject().apply { addProperty("S1", "Новое имя") })
        }
        assertTrue(TranscriptTxtFormatter.checkExportOptions(original, edits.toString()).showVersionSelection)
        // Names are meaningful in TXT; the strict JSON schema contains only IDs.
        assertFalse(TranscriptJsonExporter.checkExportOptions(original, edits.toString()).showVersionSelection)
    }

    @Test fun allHiddenFragmentsKeepOriginalExportAvailableAndCorrectedVersionEmpty() {
        val original = originalForVersions()
        val edits = JsonObject()
        TranscriptUserEdits.hideSegments(edits, original, listOf("one"))
        val options = TranscriptTxtFormatter.checkExportOptions(original, edits.toString())
        assertTrue(options.originalValid); assertTrue(options.correctedValid); assertTrue(options.showVersionSelection)
        assertEquals(original, TranscriptTxtFormatter.selectVersion(original, edits.toString(), ExportVersion.ORIGINAL))
        assertTrue(TranscriptTxtFormatter.selectVersion(original, edits.toString(), ExportVersion.CORRECTED).segments.isEmpty())
    }

    @Test fun txtRetainsSupportForUnknownSpeakersAndMissingTimestamps() {
        val original = originalForVersions().let { it.copy(segments = listOf(it.segments.first().copy(
            speakerId = null, startMs = null, endMs = null))) }
        val options = TranscriptTxtFormatter.checkExportOptions(original, "{}")
        assertTrue(options.originalValid); assertTrue(options.correctedValid); assertFalse(options.showVersionSelection)
        val selected = TranscriptTxtFormatter.selectVersion(original, "{}", ExportVersion.ORIGINAL)
        val writer = StringWriter()
        TranscriptTxtFormatter.writeTo(writer, "Запись", selected.segments, selected.speakerLabels)
        assertTrue(writer.toString().contains("Говорящий не указан"))
    }

    @Test fun txtFileNamesPreserveTheSelectedVersion() {
        assertEquals("Запись_тест_original.txt", com.team404bnf.app.ui.playback.suggestTxtFileName("Запись/тест", ExportVersion.ORIGINAL))
        assertEquals("Запись_тест_corrected.txt", com.team404bnf.app.ui.playback.suggestTxtFileName("Запись/тест", ExportVersion.CORRECTED))
    }

    @Test fun derivedSpeakerLabelsAreNotMistakenForUserEdits() {
        val original = originalForVersions().copy(speakerLabels = emptyMap())
        assertFalse(TranscriptTxtFormatter.checkExportOptions(original, "{}").showVersionSelection)
        val raw = TranscriptTxtFormatter.selectVersion(original, "{}", ExportVersion.ORIGINAL)
        val corrected = TranscriptTxtFormatter.selectVersion(original, "{}", ExportVersion.CORRECTED)
        assertEquals(raw, corrected)
        assertEquals(original.segments, raw.segments)
    }

}
