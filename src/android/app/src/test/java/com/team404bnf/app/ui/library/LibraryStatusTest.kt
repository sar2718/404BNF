package com.team404bnf.app.ui.library

import com.team404bnf.app.transcription.TranscriptionTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryStatusTest {
    @Test fun legacyTranscriptWithoutTaskIsReady() {
        assertEquals("Расшифровка готова",recordingStatusLabel(true,null))
    }
    @Test fun failedOrInterruptedTaskDoesNotHideStoredTranscript() {
        listOf(TranscriptionTaskStatus.FAILED,TranscriptionTaskStatus.INTERRUPTED,TranscriptionTaskStatus.CANCELLED).forEach {
            assertEquals("Расшифровка готова",recordingStatusLabel(true,it))
        }
    }
    @Test fun completedTaskWithoutSnapshotDoesNotInventTranscript() {
        assertEquals("Только аудио",recordingStatusLabel(false,TranscriptionTaskStatus.COMPLETED))
    }
    @Test fun queueAndRunningStagesAreDistinct() {
        assertEquals("В очереди",recordingStatusLabel(false,TranscriptionTaskStatus.QUEUED))
        listOf(TranscriptionTaskStatus.PREPARING,TranscriptionTaskStatus.RUNNING_ASR,
            TranscriptionTaskStatus.RUNNING_DIARIZATION,TranscriptionTaskStatus.SAVING).forEach {
            assertEquals("Обработка",recordingStatusLabel(false,it))
        }
    }
    @Test fun failureAndInterruptionWithoutTranscriptRemainVisible() {
        assertEquals("Ошибка обработки",recordingStatusLabel(false,TranscriptionTaskStatus.FAILED))
        assertEquals("Прервано",recordingStatusLabel(false,TranscriptionTaskStatus.INTERRUPTED))
        assertEquals("Прервано",recordingStatusLabel(false,TranscriptionTaskStatus.CANCELLED))
        assertEquals("Только аудио",recordingStatusLabel(false,null))
    }
}
