package com.team404bnf.app.localml

import com.team404bnf.app.transcription.TranscriptJsonParser
import com.team404bnf.ml.asr.AsrOutput
import com.team404bnf.ml.asr.AsrWord
import com.team404bnf.ml.diar.DiarTurn
import com.team404bnf.ml.diar.DiarizationOutput
import org.junit.Assert.*
import org.junit.Test

class LocalTranscriptJsonTest {
    private val diar = DiarizationOutput(
        listOf(DiarTurn(0.0, 1.9, "SPEAKER_00"), DiarTurn(2.1, 4.0, "SPEAKER_01")),
        speakersDetected = 2,
        speechIntervals = 2,
        embeddingWindows = 2,
        elapsedSeconds = 0.25,
    )

    @Test fun jsonV1ParsesAndGroupsBySpeaker() {
        val asr = AsrOutput("Привет. Как дела?", listOf(
            AsrWord("Привет.", 0.2, 0.8),
            AsrWord("Как", 2.2, 2.5),
            AsrWord("дела?", 2.5, 3.0),
        ))
        val parsed = TranscriptJsonParser.parse(LocalTranscriptJson.build(asr, diar, 4.0, "a".repeat(64), 1.5))
        assertEquals(1, parsed.schemaVersion)
        assertEquals(2, parsed.segments.size)
        assertEquals(3, parsed.words.size)
        assertEquals("SPEAKER_00", parsed.segments[0].speakerId)
        assertEquals("SPEAKER_01", parsed.segments[1].speakerId)
        assertEquals("Как дела?", parsed.segments[1].text)
        assertEquals(4000L, parsed.durationMs)
        assertEquals(2, parsed.speakersDetected)
        assertNotNull(parsed.resultId)
    }

    @Test fun noDiarizationDoesNotInventSpeaker() {
        val absent = diar.copy(turns = emptyList(), speakersDetected = 0)
        val json = LocalTranscriptJson.build(
            AsrOutput("Один", listOf(AsrWord("Один", 0.1, 0.5))),
            absent, 1.0, "f".repeat(64), 0.4,
        )
        val parsed = TranscriptJsonParser.parse(json)
        assertNull(parsed.words[0].speakerId)
        assertNull(parsed.segments[0].speakerId)
        assertEquals(1, parsed.wordsUnassigned)
    }

    @Test fun ambiguousOverlappingTurnRemainsUnassigned() {
        val words = listOf(AsrWord("Тест", 0.5, 1.0))
        val turns = listOf(DiarTurn(0.0, 0.75, "SPEAKER_00"),
            DiarTurn(0.75, 1.2, "SPEAKER_01"))
        val assigned = LocalTranscriptJson.assign(words, turns, 2.0)
        assertTrue(assigned.single().ambiguous)
        assertNull(assigned.single().speakerId)
    }
}
