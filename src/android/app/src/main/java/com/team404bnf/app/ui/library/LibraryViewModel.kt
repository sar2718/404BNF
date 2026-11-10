package com.team404bnf.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.team404bnf.app.data.repository.RecordingRepository
import com.team404bnf.app.data.repository.AudioImportException
import com.team404bnf.app.data.repository.TranscriptRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.team404bnf.app.transcription.TranscriptionTaskManager
import com.team404bnf.app.transcription.TranscriptionTaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: RecordingRepository,
    private val transcriptRepository: TranscriptRepository
) : ViewModel() {

    private val _deleting = MutableStateFlow(false)
    val deleting = _deleting.asStateFlow()

    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError = _deleteError.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError = _importError.asStateFlow()

    fun importAudio(uri: Uri) {

        if (_importing.value || _deleting.value) return

        _importing.value = true
        _importError.value = null

        viewModelScope.launch {
            try {
                repository.importAudio(uri)

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {
                _importError.value =
                    if (e is AudioImportException) {
                        e.message ?: "Не удалось прочитать аудиофайл."
                    } else {
                        "Не удалось прочитать аудиофайл."
                    }

            } finally {
                _importing.value = false
            }
        }
    }

    fun deleteRecordings(ids: Set<String>) {
        if (ids.isEmpty() || _deleting.value) return

        _deleting.value = true
        _deleteError.value = null

        viewModelScope.launch {
            try {
                repository.deleteRecordings(ids)

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {
                _deleteError.value =
                    e.message ?: "Ошибка удаления записей"

            } finally {
                _deleting.value = false
            }
        }
    }

    // Observe persisted snapshots as well as tasks: a transcript can exist without a task entry.
    private val recordingsWithTranscripts = repository.observeRecordings().flatMapLatest { recordings ->
        if (recordings.isEmpty()) flowOf(emptyList())
        else combine(recordings.map { recording ->
            transcriptRepository.observeExists(recording.id).map { exists -> recording to exists }
        }) { it.toList() }
    }

    val savedRecordings = combine(
        recordingsWithTranscripts,
        TranscriptionTaskManager.tasksFlow
    ) { recordings, tasks ->
        val formatter = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
        recordings.map { (recording, hasTranscript) ->
            val task = tasks[recording.id]
            RecordingPreview(
                id = recording.id,
                title = recording.title,
                date = formatter.format(Date(recording.createdAtEpochMs)),
                createdAtEpochMs = recording.createdAtEpochMs,
                durationMs = recording.durationMs,
                status = recordingStatusLabel(hasTranscript, task?.status),
                hasTranscript = hasTranscript,
                processing = task?.isActive == true,
                hasAudio = recording.audioRelativePath != null
            )
        }
    }

    class Factory(
        private val repository: RecordingRepository,
        private val transcriptRepository: TranscriptRepository
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {

            if (modelClass.isAssignableFrom(
                    LibraryViewModel::class.java
                )
            ) {
                return LibraryViewModel(repository, transcriptRepository) as T
            }

            throw IllegalArgumentException(
                "Unknown ViewModel class"
            )
        }
    }
}

internal fun recordingStatusLabel(hasTranscript: Boolean, status: TranscriptionTaskStatus?): String = when {
    hasTranscript -> "Расшифровка готова"
    status == TranscriptionTaskStatus.WAITING_FOR_MODELS -> "Ожидает установки модели"
    status == TranscriptionTaskStatus.QUEUED -> "В очереди"
    status in setOf(TranscriptionTaskStatus.PREPARING, TranscriptionTaskStatus.RUNNING_ASR,
        TranscriptionTaskStatus.RUNNING_DIARIZATION, TranscriptionTaskStatus.SAVING) -> "Обработка"
    status == TranscriptionTaskStatus.FAILED -> "Ошибка обработки"
    status == TranscriptionTaskStatus.INTERRUPTED -> "Прервано"
    status == TranscriptionTaskStatus.CANCELLED -> "Прервано"
    else -> "Только аудио"
}
