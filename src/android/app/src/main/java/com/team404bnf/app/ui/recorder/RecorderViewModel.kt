package com.team404bnf.app.ui.recorder

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

import com.team404bnf.app.recording.RecordingService
import com.team404bnf.app.recording.RecordingSession
import com.team404bnf.app.recording.RecordingStatus
import com.team404bnf.app.recording.RecorderUiState

class RecorderViewModel(
    private val context: Context
) : ViewModel() {

    val state = RecordingSession.state

    fun consumeSavedRecording(recordingId: String) {
        RecordingSession.update {
            if(it.savedRecordingId == recordingId) it.copy(savedRecordingId = null) else it
        }
    }

    fun prepareNewRecording() {
        if(state.value.status == RecordingStatus.SAVED) RecordingSession.set(RecorderUiState())
    }

    fun start() {

        if (state.value.status !in setOf(
                RecordingStatus.IDLE,
                RecordingStatus.SAVED,
                RecordingStatus.ERROR
            )
        ) return

        RecordingSession.set(
            RecorderUiState(
                status = RecordingStatus.STARTING
            )
        )

        try {

            val intent = Intent(
                context,
                RecordingService::class.java
            ).apply {
                action = RecordingService.ACTION_START
            }

            context.startForegroundService(intent)

        } catch (e: Exception) {

            RecordingSession.set(
                RecorderUiState(
                    status = RecordingStatus.ERROR,
                    message = "Не удалось запустить сервис записи"
                )
            )
        }
    }

    fun pause() {
        if (state.value.status == RecordingStatus.RECORDING) {
            sendCommand(RecordingService.ACTION_PAUSE)
        }
    }

    fun resume() {
        if (state.value.status == RecordingStatus.PAUSED) {
            sendCommand(RecordingService.ACTION_RESUME)
        }
    }

    fun stop() {
        if (state.value.status in setOf(
                RecordingStatus.RECORDING,
                RecordingStatus.PAUSED
            )
        ) {
            sendCommand(RecordingService.ACTION_STOP)
        }
    }

    private fun sendCommand(action: String) {

        // A repeated failure must emit again so the UI can release its pending action.
        RecordingSession.update { it.copy(message = null) }

        val intent = Intent(
            context,
            RecordingService::class.java
        ).apply {
            this.action = action
        }

        try {
            context.startService(intent)

        } catch (e: Exception) {
            RecordingSession.update {
                it.copy(
                    message = "Не удалось выполнить команду"
                )
            }
        }
    }

    fun permissionDenied() {
        RecordingSession.set(
            RecorderUiState(
                status = RecordingStatus.ERROR,
                message = "Нет разрешения на микрофон"
            )
        )
    }

    class Factory(
        private val context: Context
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {

            if (modelClass.isAssignableFrom(
                    RecorderViewModel::class.java
                )
            ) {
                return RecorderViewModel(
                    context.applicationContext
                ) as T
            }

            throw IllegalArgumentException(
                "Unknown ViewModel"
            )
        }
    }
}
