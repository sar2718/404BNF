package com.team404bnf.app.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

import com.team404bnf.app.data.repository.RecordingDetails
import com.team404bnf.app.data.repository.RecordingRepository
import com.team404bnf.app.data.repository.TranscriptRecoveryState
import com.team404bnf.app.data.repository.TranscriptRepository
import com.team404bnf.app.data.repository.TranscriptSaveResult
import com.team404bnf.app.recording.RecordingSession
import com.team404bnf.app.recording.RecordingStatus
import com.team404bnf.app.transcription.JsonDocumentReader
import com.team404bnf.app.transcription.TranscriptSegment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.content.ContentResolver
import java.io.IOException
import java.io.OutputStreamWriter
import com.team404bnf.app.transcription.TranscriptTxtFormatter

data class PlaybackScreenState(
    val title: String = "Аудиозапись",
    val durationMs: Long = 0,
    val loading: Boolean = true,
    val error: String? = null
)

data class JsonImportState(
    val inProgress: Boolean = false,
    val hasTranscript: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

data class TranscriptDisplayState(
    val loading: Boolean = false,
    val segments: List<TranscriptSegment> = emptyList(),
    val speakerLabels: Map<String, String> = emptyMap(),
    val originalPositions: Map<String, Int> = emptyMap(),
    val splitBeforeSegmentIds: Set<String> = emptySet(),
    val error: String? = null
)

data class TxtExportState(
    val inProgress: Boolean = false,
    val success: Boolean = false,
    val error: String? = null
)

data class WavExportState(
    val inProgress: Boolean = false,
    val error: String? = null
)

data class RecordingDetailsUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val details: RecordingDetails? = null,
    val transcriptBytes: Long? = null,
    val error: String? = null
)

class PlaybackViewModel(
    context: Context,
    private val repository: RecordingRepository,
    private val recordingId: String,
    private val transcriptRepository: TranscriptRepository
) : ViewModel() {

    private val appContext = context.applicationContext

    private val isDebugBuild =
        (appContext.applicationInfo.flags and
            ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val _jsonImportState = MutableStateFlow(
        JsonImportState()
    )

    val jsonImportState = _jsonImportState.asStateFlow()

    private val _transcriptDisplayState = MutableStateFlow(
        TranscriptDisplayState()
    )

    val transcriptDisplayState =
        _transcriptDisplayState.asStateFlow()

    private val _editing = MutableStateFlow(false)
    val editing = _editing.asStateFlow()

    private val _editError = MutableStateFlow<String?>(null)
    val editError = _editError.asStateFlow()

    private val _renaming = MutableStateFlow(false)
    val renaming = _renaming.asStateFlow()

    private val _renameError = MutableStateFlow<String?>(null)
    val renameError = _renameError.asStateFlow()

    private val _txtExportState = MutableStateFlow(TxtExportState())
    val txtExportState = _txtExportState.asStateFlow()

    private val _wavExportState = MutableStateFlow(
        WavExportState()
    )

    val wavExportState = _wavExportState.asStateFlow()

    private val _recordingDetailsState = MutableStateFlow(
        RecordingDetailsUiState()
    )

    val recordingDetailsState =
        _recordingDetailsState.asStateFlow()

    private val _recoveryState = MutableStateFlow(
        TranscriptRecoveryState()
    )

    val recoveryState = _recoveryState.asStateFlow()

    private val controller = PlaybackController(context)

    val playbackState = controller.state

    private val _screenState = MutableStateFlow(
        PlaybackScreenState()
    )

    val screenState = _screenState.asStateFlow()

    init {
        viewModelScope.launch {

            try {

                transcriptRepository.observeEditableWithOrder(
                    recordingId
                ).collect { content ->

                    _jsonImportState.update {
                        it.copy(
                            hasTranscript = content != null
                        )
                    }

                    _transcriptDisplayState.value =
                        if (content == null) {

                            TranscriptDisplayState()

                        } else {

                            TranscriptDisplayState(
                                segments = content.transcript.segments,
                                speakerLabels =
                                    content.transcript.speakerLabels,
                                originalPositions =
                                    content.originalPositions,
                                splitBeforeSegmentIds =
                                    content.splitBeforeSegmentIds
                            )
                        }
                }

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {

                _transcriptDisplayState.value =
                    TranscriptDisplayState(
                        error = "Не удалось загрузить расшифровку"
                    )
            }
        }

        viewModelScope.launch {
            try {

                transcriptRepository.observeRecoveryState(
                    recordingId
                ).collect { state ->

                    _recoveryState.value = state
                }

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {

                _editError.value =
                    "Не удалось загрузить сведения об удалённых репликах"
            }
        }

        // Загружаем выбранную запись из Room.
        viewModelScope.launch {
            try {
                val recording = repository.getPlayableRecording(
                    recordingId
                )

                if (recording == null) {
                    _screenState.value = PlaybackScreenState(
                        loading = false,
                        error = "Аудиофайл отсутствует или недоступен"
                    )
                } else {
                    controller.load(recording.file)

                    _screenState.value = PlaybackScreenState(
                        title = recording.title,
                        durationMs = recording.durationMs,
                        loading = false
                    )
                }

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {
                _screenState.value = PlaybackScreenState(
                    loading = false,
                    error = "Не удалось открыть аудиозапись"
                )
            }
        }

        // Если начинается запись с микрофона,
        // прекращаем воспроизведение.
        viewModelScope.launch {
            RecordingSession.state.collect { session ->

                if (session.status in activeRecordingStatuses) {
                    controller.pause()
                }
            }
        }
    }

    fun changeTranscriptGroupSpeaker(
        segmentIds: List<String>,
        speakerId: String?
    ) = submitEdit {
        transcriptRepository.changeGroupSpeaker(
            recordingId,
            segmentIds,
            speakerId
        )
    }

    fun deleteTranscriptGroup(
        segmentIds: List<String>
    ) = submitEdit {
        transcriptRepository.deleteGroup(
            recordingId,
            segmentIds
        )
    }

    fun splitTranscriptGroup(
        segmentIds: List<String>
    ) = submitEdit {
        transcriptRepository.splitGroup(
            recordingId,
            segmentIds
        )
    }

    fun restoreAutomaticMerge(
        segmentId: String
    ) = submitEdit {
        transcriptRepository.allowAutoMergeBefore(
            recordingId,
            segmentId
        )
    }

    fun restoreDeletedSegment(
        segmentId: String
    ) = submitEdit {

        transcriptRepository.restoreDeletedSegment(
            recordingId,
            segmentId
        )
    }

    fun resetAllTranscriptEdits() = submitEdit {

        transcriptRepository.resetAllUserEdits(
            recordingId
        )
    }

    fun showRecordingDetails() {

        if (_recordingDetailsState.value.visible) return

        _recordingDetailsState.value = RecordingDetailsUiState(
            visible = true,
            loading = true
        )

        viewModelScope.launch {
            try {
                val details = repository.getRecordingDetails(
                    recordingId
                ) ?: error("Запись не найдена")

                val transcriptBytes =
                    transcriptRepository.getStoredTranscriptBytes(
                        recordingId
                    )

                if (_recordingDetailsState.value.visible) {
                    _recordingDetailsState.value =
                        RecordingDetailsUiState(
                            visible = true,
                            details = details,
                            transcriptBytes = transcriptBytes
                        )
                }

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {

                if (_recordingDetailsState.value.visible) {
                    _recordingDetailsState.value =
                        RecordingDetailsUiState(
                            visible = true,
                            error = "Не удалось получить информацию о записи"
                        )
                }
            }
        }
    }

    fun hideRecordingDetails() {
        _recordingDetailsState.value = RecordingDetailsUiState()
    }

    fun renameChat(newTitle: String) {

        if (
            _renaming.value ||
            _screenState.value.loading ||
            _screenState.value.error != null
        ) return

        val normalized = newTitle.trim()

        if (normalized.isEmpty() || normalized.length > 80) {
            _renameError.value =
                "Название должно содержать от 1 до 80 символов"
            return
        }

        if (normalized == _screenState.value.title) return

        _renaming.value = true
        _renameError.value = null

        viewModelScope.launch {
            try {

                repository.renameRecording(
                    recordingId,
                    normalized
                )

                _screenState.update {
                    it.copy(title = normalized)
                }

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {
                _renameError.value =
                    "Не удалось переименовать запись"

            } finally {
                _renaming.value = false
            }
        }
    }

    fun clearRenameError() {
        _renameError.value = null
    }

    fun exportTranscriptTxt(
        uri: Uri,
        resolver: ContentResolver
    ) {
        if (_txtExportState.value.inProgress) return

        val screen = _screenState.value
        val transcript = _transcriptDisplayState.value

        if (
            screen.loading ||
            screen.error != null ||
            transcript.loading ||
            transcript.error != null ||
            _editing.value ||
            !_jsonImportState.value.hasTranscript ||
            transcript.segments.isEmpty()
        ) {
            _txtExportState.value = TxtExportState(
                error = "Расшифровка недоступна для экспорта"
            )
            return
        }

        // Фиксируем данные на момент начала экспорта.
        val title = screen.title
        val segments = transcript.segments.toList()
        val speakerLabels = transcript.speakerLabels.toMap()

        _txtExportState.value = TxtExportState(
            inProgress = true
        )

        viewModelScope.launch {

            try {

                withContext(Dispatchers.IO) {

                    val output = resolver.openOutputStream(
                        uri,
                        "wt"
                    ) ?: throw IOException(
                        "Не удалось открыть файл для записи"
                    )

                    OutputStreamWriter(
                        output,
                        Charsets.UTF_8
                    ).buffered().use { writer ->

                        TranscriptTxtFormatter.writeTo(
                            writer = writer,
                            title = title,
                            segments = segments,
                            speakerLabels = speakerLabels
                        )
                    }
                }

                _txtExportState.value = TxtExportState(
                    success = true
                )

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {

                _txtExportState.value = TxtExportState(
                    error = "Не удалось сохранить расшифровку"
                )
            }
        }
    }

    fun clearTxtExportStatus() {
        _txtExportState.value = TxtExportState()
    }

    fun exportWav(
        uri: Uri,
        resolver: ContentResolver
    ) {
        if (_wavExportState.value.inProgress) return

        _wavExportState.value = WavExportState(
            inProgress = true
        )

        viewModelScope.launch {

            try {

                val playable = withContext(Dispatchers.IO) {
                    repository.getPlayableRecording(recordingId)
                } ?: throw IllegalStateException(
                    "Аудиозапись недоступна"
                )

                WavFileExporter.export(
                    source = playable.file,
                    destination = uri,
                    resolver = resolver
                )

                _wavExportState.value = WavExportState()

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {

                _wavExportState.value = WavExportState(
                    error = "Не удалось экспортировать аудиозапись"
                )

            } finally {

                _wavExportState.update {
                    it.copy(inProgress = false)
                }
            }
        }
    }

    fun clearWavExportError() {
        _wavExportState.update {
            it.copy(error = null)
        }
    }

    private fun submitEdit(
        action: suspend () -> Unit
    ) {
        if (_editing.value) return

        _editing.value = true
        _editError.value = null

        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editError.value =
                    e.message ?: "Не удалось сохранить изменение"
            } finally {
                _editing.value = false
            }
        }
    }

    fun editSegmentText(
        segmentId: String,
        text: String
    ) = submitEdit {
        transcriptRepository.updateSegmentText(
            recordingId, segmentId, text
        )
    }

    fun changeSegmentSpeaker(
        segmentId: String,
        speakerId: String?
    ) = submitEdit {
        transcriptRepository.updateSegmentSpeaker(
            recordingId, segmentId, speakerId
        )
    }

    fun renameTranscriptSpeaker(
        speakerId: String,
        name: String
    ) = submitEdit {
        transcriptRepository.renameSpeaker(
            recordingId, speakerId, name
        )
    }

    fun deleteTranscriptSegment(
        segmentId: String
    ) = submitEdit {
        transcriptRepository.deleteSegment(
            recordingId,
            segmentId
        )
    }

    fun clearEditError() {
        _editError.value = null
    }

    fun importJson(uri: Uri) {

        if (
            !isDebugBuild ||
            _jsonImportState.value.inProgress ||
            _screenState.value.loading ||
            _screenState.value.error != null
        ) {
            return
        }

        _jsonImportState.update {
            it.copy(
                inProgress = true,
                message = null,
                error = null
            )
        }

        viewModelScope.launch {

            try {

                val json = withContext(Dispatchers.IO) {
                    JsonDocumentReader.read(
                        appContext,
                        uri
                    )
                }

                val result = transcriptRepository.save(
                    recordingId = recordingId,
                    rawJson = json
                )

                val message = when (result) {
                    TranscriptSaveResult.SAVED ->
                        "JSON успешно сохранён"

                    TranscriptSaveResult.ALREADY_PRESENT ->
                        "Этот JSON уже был загружен"
                }

                _jsonImportState.update {
                    it.copy(message = message)
                }

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {

                val message = when (e) {

                    is IllegalArgumentException,
                    is IllegalStateException ->
                        e.message ?: "Ошибка обработки JSON"

                    else ->
                        "Не удалось прочитать или сохранить JSON"
                }

                _jsonImportState.update {
                    it.copy(error = message)
                }

            } finally {

                _jsonImportState.update {
                    it.copy(inProgress = false)
                }
            }
        }
    }

    fun togglePlayback() {
        if (
            _screenState.value.loading ||
            _screenState.value.error != null ||
            playbackState.value.error != null
        ) return

        if (isMicrophoneBusy()) {
            controller.pause()
            return
        }

        if (playbackState.value.isPlayRequested) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun playFromTranscriptSegment(startMs: Long) {

        if (
            _screenState.value.loading ||
            _screenState.value.error != null ||
            playbackState.value.error != null ||
            isMicrophoneBusy()
        ) {
            return
        }

        val segmentExists =
            _transcriptDisplayState.value.segments.any {
                it.startMs == startMs
            }

        if (!segmentExists) return

        controller.playFrom(startMs)
    }

    fun cyclePlaybackSpeed() {
        if (
            _screenState.value.loading ||
            _screenState.value.error != null ||
            playbackState.value.error != null ||
            isMicrophoneBusy()
        ) {
            return
        }

        val nextSpeed = when (playbackState.value.speed) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 1.75f
            1.75f -> 2f
            else -> 1f
        }

        controller.setPlaybackSpeed(nextSpeed)
    }

    fun seekTo(positionMs: Long) {
        if (
            _screenState.value.loading ||
            _screenState.value.error != null ||
            playbackState.value.error != null ||
            isMicrophoneBusy()
        ) return

        controller.seekTo(positionMs)
    }

    fun pause() {
        controller.pause()
    }

    private fun isMicrophoneBusy(): Boolean {
        return RecordingSession.state.value.status in
            activeRecordingStatuses
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }

    companion object {
        private val activeRecordingStatuses = setOf(
            RecordingStatus.STARTING,
            RecordingStatus.RECORDING,
            RecordingStatus.PAUSED,
            RecordingStatus.FINISHING
        )
    }

    class Factory(
        private val context: Context,
        private val repository: RecordingRepository,
        private val recordingId: String,
        private val transcriptRepository: TranscriptRepository
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {

            if (modelClass.isAssignableFrom(
                    PlaybackViewModel::class.java
                )
            ) {
                return PlaybackViewModel(
                    context.applicationContext,
                    repository,
                    recordingId,
                    transcriptRepository
                ) as T
            }

            throw IllegalArgumentException(
                "Unknown ViewModel class"
            )
        }
    }
}