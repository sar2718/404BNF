package com.team404bnf.app.transcription

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TranscriptJsonParserTest {
    private fun sample(): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("01_dialog_clean.json")
    ) { "Нет тестового файла 01_dialog_clean.json" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun legacyRealFile() {
        val result = TranscriptJsonParser.parse(sample())
        assertNull(result.schemaVersion)
        assertEquals("01_dialog_clean", result.sourceId)
        assertEquals(61_453L, result.durationMs)
        assertEquals(18, result.segments.size)
        assertEquals(135, result.words.size)
        assertEquals(2, result.speakerLabels.size)
        assertEquals(160L, result.segments[0].startMs)
        assertEquals(4_200L, result.segments[0].endMs)
        assertEquals("segment_000001", result.segments[0].id)
        assertEquals(true, result.segments[3].overlap)
        assertEquals("onnx_rnnt_token_estimate", result.words[0].timingSource)
        assertEquals(sample(), result.rawJson)
    }

    @Test fun newVersionWithFourAdditionalFields() {
        val obj = JsonParser.parseString(sample()).asJsonObject
        obj.addProperty("schema_version", 1)
        obj.addProperty("result_id", "550e8400-e29b-41d4-a716-446655440000")
        obj.addProperty("audio_sha256", "a".repeat(64))
        obj.getAsJsonArray("segments")[0].asJsonObject.addProperty("id", "replica-1")
        val result = TranscriptJsonParser.parse(obj.toString())
        assertEquals(1, result.schemaVersion)
        assertEquals("replica-1", result.segments[0].id)
        assertEquals("a".repeat(64), result.audioSha256)
        assertEquals(18, result.segments.size)
    }

    @Test fun unknownFieldsDoNotBreakParsing() {
        val obj = JsonParser.parseString(sample()).asJsonObject
        obj.addProperty("new_future_field", "unknown")
        val result = TranscriptJsonParser.parse(obj.toString())
        assertTrue(result.rawJson.contains("new_future_field"))
    }

    @Test fun negativeTimestampIsRejected() {
        val obj = JsonParser.parseString(sample()).asJsonObject
        obj.getAsJsonArray("segments")[0].asJsonObject.addProperty("start", -0.3)
        expectBad { TranscriptJsonParser.parse(obj.toString()) }
    }

    @Test fun repeatedSegmentIdIsRejected() {
        val obj = JsonParser.parseString(sample()).asJsonObject
        obj.getAsJsonArray("segments")[0].asJsonObject.addProperty("id", "duplicate")
        obj.getAsJsonArray("segments")[1].asJsonObject.addProperty("id", "duplicate")
        expectBad { TranscriptJsonParser.parse(obj.toString()) }
    }

    @Test fun unknownSpeakerAndOverlapAreAccepted() {
        val obj = JsonParser.parseString(sample()).asJsonObject
        val first = obj.getAsJsonArray("segments")[0].asJsonObject
        first.add("speaker_id", JsonNull.INSTANCE)
        first.addProperty("speaker_ambiguous", true)
        first.addProperty("overlap", true)
        val result = TranscriptJsonParser.parse(obj.toString())
        assertNull(result.segments[0].speakerId)
        assertEquals(true, result.segments[0].speakerAmbiguous)
        assertEquals(true, result.segments[0].overlap)
    }

    @Test fun malformedJsonAndUnknownVersionAreRejected() {
        expectBad { TranscriptJsonParser.parse("{bad json") }
        val obj = JsonParser.parseString(sample()).asJsonObject
        obj.addProperty("schema_version", 99)
        expectBad { TranscriptJsonParser.parse(obj.toString()) }
    }

    @Test fun missingStartAndEndParsedAsNull() {
        val obj = JsonParser.parseString(sample()).asJsonObject
        val first = obj.getAsJsonArray("segments")[0].asJsonObject
        first.remove("start")
        first.remove("end")
        val result = TranscriptJsonParser.parse(obj.toString())
        assertNull(result.segments[0].startMs)
        assertNull(result.segments[0].endMs)
    }

    @Test fun nullStartAndEndParsedAsNull() {
        val obj = JsonParser.parseString(sample()).asJsonObject
        val first = obj.getAsJsonArray("segments")[0].asJsonObject
        first.add("start", JsonNull.INSTANCE)
        first.add("end", JsonNull.INSTANCE)
        val result = TranscriptJsonParser.parse(obj.toString())
        assertNull(result.segments[0].startMs)
        assertNull(result.segments[0].endMs)
    }

    @Test fun singleMissingTimestampProducesBothNull() {
        val obj = JsonParser.parseString(sample()).asJsonObject
        val first = obj.getAsJsonArray("segments")[0].asJsonObject
        first.remove("start")
        val result = TranscriptJsonParser.parse(obj.toString())
        assertNull(result.segments[0].startMs)
        assertNull(result.segments[0].endMs)
    }

    private fun expectBad(action: () -> Unit) {
        try {
            action()
            fail("Ожидалась ошибка формата JSON")
        } catch (_: TranscriptFormatException) {
            // expected
        }
    }
}