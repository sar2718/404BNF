package com.team404bnf.app.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RecordingStatus {
    IDLE,
    STARTING,
    RECORDING,
    PAUSED,
    FINISHING,
    SAVED,
    ERROR
}

data class RecorderUiState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val durationMs: Long = 0,
    val savedRecordingId: String? = null,
    val message: String? = null
)

object RecordingSession {

    private val _state = MutableStateFlow(RecorderUiState())

    val state = _state.asStateFlow()

    fun set(value: RecorderUiState) {
        _state.value = value
    }

    fun update(
        transform: (RecorderUiState) -> RecorderUiState
    ) {
        _state.update(transform)
    }
}
