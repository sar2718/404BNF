package com.team404bnf.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.team404bnf.app.data.repository.RecordingRepository
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

class LibraryViewModel(
    private val repository: RecordingRepository
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
                    if (e is IllegalArgumentException ||
                        e is IllegalStateException
                    ) {
                        e.message ?: "Ошибка импорта"
                    } else {
                        "Не удалось импортировать аудиофайл"
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

    val savedRecordings = combine(
        repository.observeRecordings(),
        TranscriptionTaskManager.tasksFlow
    ) { recordings, tasks ->

        val formatter = SimpleDateFormat(
            "HH:mm",
            Locale.getDefault()
        )

        recordings.map { recording ->
            val task = tasks[recording.id]
            val totalSeconds = recording.durationMs / 1000

            val sourceLabel = if (recording.sourceType == "IMPORT") {
                "Импортировано"
            } else {
                "Аудиозапись"
            }

            val statusText = when {
                task?.isActive == true -> "Обработка (${task.stageMessage})"
                task?.status == TranscriptionTaskStatus.INTERRUPTED -> "Прервано"
                task?.status == TranscriptionTaskStatus.FAILED -> "Ошибка"
                else -> "без расшифровки"
            }

            RecordingPreview(
                id = recording.id,
                title = recording.title,
                subtitle = if (recording.audioRelativePath != null) {
                    "$sourceLabel · %02d:%02d · $statusText".format(
                        totalSeconds / 60,
                        totalSeconds % 60
                    )
                } else {
                    "Тест Room · аудио отсутствует"
                },
                date = formatter.format(
                    Date(recording.createdAtEpochMs)
                )
            )
        }
    }

    class Factory(
        private val repository: RecordingRepository
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {

            if (modelClass.isAssignableFrom(
                    LibraryViewModel::class.java
                )
            ) {
                return LibraryViewModel(repository) as T
            }

            throw IllegalArgumentException(
                "Unknown ViewModel class"
            )
        }
    }
}
