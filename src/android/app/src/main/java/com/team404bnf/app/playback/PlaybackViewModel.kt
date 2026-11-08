package com.team404bnf.app.playback

import android.content.Context
import com.team404bnf.app.models.RecognitionModels
import android.net.Uri

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

import com.team404bnf.app.data.repository.RecordingDetails
import com.team404bnf.app.data.repository.RecordingRepository
import com.team404bnf.app.data.repository.TranscriptRecoveryState
import com.team404bnf.app.data.repository.TranscriptRepository
import com.team404bnf.app.recording.RecordingSession
import com.team404bnf.app.recording.RecordingStatus
import com.team404bnf.app.transcription.TranscriptSegment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.team404bnf.app.transcription.TranscriptionTaskManager
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
    val error: String? = null,
    val createdAtEpochMs: Long? = null,
    val audioSaved: Boolean = false
)

data class TranscriptDisplayState(
    val loading: Boolean = false,
    val hasTranscript: Boolean = false,
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

data class JsonExportState(
    val inProgress: Boolean = false,
    val success: Boolean = false,
    val error: String? = null
)

data class WavExportState(
    val inProgress: Boolean = false,
    val error: String? = null
)

data class Mp3ExportState(
    val inProgress: Boolean = false,
    val success: Boolean = false,
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

    private val _transcriptDisplayState = MutableStateFlow(
        TranscriptDisplayState(loading = true)
    )

    val transcriptDisplayState =
        _transcriptDisplayState.asStateFlow()

    private val _editing = MutableStateFlow(false)
    val editing = _editing.asStateFlow()

    private val _editError = MutableStateFlow<String?>(null)
    val editError = _editError.asStateFlow()

    // A save result survives Activity recreation; the editor closes after the repository transaction.
    private val _savedSegmentId = MutableStateFlow<String?>(null)
    val savedSegmentId = _savedSegmentId.asStateFlow()

    private val _renaming = MutableStateFlow(false)
    val renaming = _renaming.asStateFlow()

    private val _renameError = MutableStateFlow<String?>(null)
    val renameError = _renameError.asStateFlow()

    private val _txtExportState = MutableStateFlow(TxtExportState())
    val txtExportState = _txtExportState.asStateFlow()

    private val _jsonExportState = MutableStateFlow(JsonExportState())
    val jsonExportState = _jsonExportState.asStateFlow()

    private val _wavExportState = MutableStateFlow(
        WavExportState()
    )

    val wavExportState = _wavExportState.asStateFlow()

    private val _mp3ExportState = MutableStateFlow(
        Mp3ExportState()
    )

    val mp3ExportState = _mp3ExportState.asStateFlow()

    private val _recordingDetailsState = MutableStateFlow(
        RecordingDetailsUiState()
    )

    val recordingDetailsState =
        _recordingDetailsState.asStateFlow()

    private val _recoveryState = MutableStateFlow(
        TranscriptRecoveryState()
    )

    val recoveryState = _recoveryState.asStateFlow()

    private val models = RecognitionModels.get(appContext)
    val modelState = models.state

    private val controller = PlaybackController(context)

    val playbackState = controller.state

    private val _screenState = MutableStateFlow(
        PlaybackScreenState()
    )

    val screenState = _screenState.asStateFlow()
    val transcriptionTask = TranscriptionTaskManager.observeTask(recordingId).stateIn(
        viewModelScope, SharingStarted.Eagerly, TranscriptionTaskManager.getTask(recordingId)
    )

    init {
        viewModelScope.launch {

            try {

                transcriptRepository.observeEditableWithOrder(
                    recordingId
                ).collect { content ->

                    _transcriptDisplayState.value =
                        if (content == null) {

                            TranscriptDisplayState()

                        } else {

                            TranscriptDisplayState(
                                hasTranscript = true,
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
                        loading = false,
                        audioSaved = true,
                        createdAtEpochMs = repository.getRecordingDetails(recordingId)?.createdAtEpochMs
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

    fun prepareTxtExport(
        onShowVersionSelection: () -> Unit,
        onLaunchPicker: (com.team404bnf.app.transcription.ExportVersion) -> Unit
    ) {
        if (_txtExportState.value.inProgress || _editing.value) return
        _txtExportState.value = TxtExportState(inProgress = true)
        viewModelScope.launch {
            try {
                val options = transcriptRepository.checkTxtExportOptions(recordingId)
                _txtExportState.value = TxtExportState()
                if (options.showVersionSelection) onShowVersionSelection()
                else onLaunchPicker(if(options.correctedValid) com.team404bnf.app.transcription.ExportVersion.CORRECTED
                    else com.team404bnf.app.transcription.ExportVersion.ORIGINAL)
            } catch (error: CancellationException) {
                _txtExportState.value = TxtExportState()
                throw error
            } catch (error: Exception) {
                _txtExportState.value = TxtExportState(error = error.message ?: "Не удалось проверить расшифровку для экспорта")
            }
        }
    }

    fun exportTranscriptTxt(
        uri: Uri,
        resolver: ContentResolver,
        version: com.team404bnf.app.transcription.ExportVersion = com.team404bnf.app.transcription.ExportVersion.CORRECTED
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
            !_transcriptDisplayState.value.hasTranscript
        ) {
            _txtExportState.value = TxtExportState(
                error = "Расшифровка недоступна для экспорта"
            )
            return
        }

        // Фиксируем данные на момент начала экспорта.
        val title = screen.title

        _txtExportState.value = TxtExportState(
            inProgress = true
        )

        viewModelScope.launch {

            try {
                val selected = transcriptRepository.getForTxtExport(recordingId, version)
                    ?: throw IOException("Расшифровка недоступна для экспорта")
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
                            segments = selected.segments,
                            speakerLabels = selected.speakerLabels
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
                    error = e.message ?: "Не удалось сохранить расшифровку"
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

    fun exportMp3(
        uri: Uri,
        resolver: ContentResolver
    ) {
        if (_mp3ExportState.value.inProgress) return

        _mp3ExportState.value = Mp3ExportState(
            inProgress = true
        )

        viewModelScope.launch {
            try {
                val playable = withContext(Dispatchers.IO) {
                    repository.getPlayableRecording(recordingId)
                } ?: throw IllegalStateException(
                    "Аудиозапись недоступна"
                )

                Mp3FileExporter.export(
                    source = playable.file,
                    destination = uri,
                    resolver = resolver
                )

                _mp3ExportState.value = Mp3ExportState(
                    success = true
                )

            } catch (e: CancellationException) {
                runCatching {
                    android.provider.DocumentsContract.deleteDocument(resolver, uri)
                }
                throw e

            } catch (e: Exception) {
                runCatching {
                    android.provider.DocumentsContract.deleteDocument(resolver, uri)
                }
                _mp3ExportState.value = Mp3ExportState(
                    error = "Не удалось экспортировать аудиозапись"
                )

            } finally {
                _mp3ExportState.update {
                    it.copy(inProgress = false)
                }
            }
        }
    }

    fun clearMp3ExportStatus() {
        _mp3ExportState.value = Mp3ExportState()
    }

    fun clearMp3ExportError() {
        _mp3ExportState.update {
            it.copy(error = null)
        }
    }

    fun prepareJsonExport(
        onShowVersionSelection: () -> Unit,
        onLaunchPicker: (com.team404bnf.app.transcription.ExportVersion) -> Unit
    ) {
        if (_jsonExportState.value.inProgress || _editing.value) return

        _jsonExportState.value = JsonExportState(inProgress = true)

        viewModelScope.launch {
            try {
                val options = withContext(Dispatchers.IO) {
                    transcriptRepository.checkExportOptions(recordingId)
                }

                _jsonExportState.value = JsonExportState(inProgress = false)

                if (options.showVersionSelection) {
                    onShowVersionSelection()
                } else if (options.correctedValid) {
                    onLaunchPicker(com.team404bnf.app.transcription.ExportVersion.CORRECTED)
                } else {
                    onLaunchPicker(com.team404bnf.app.transcription.ExportVersion.ORIGINAL)
                }

            } catch (e: CancellationException) {
                _jsonExportState.value = JsonExportState(inProgress = false)
                throw e

            } catch (e: Exception) {
                _jsonExportState.value = JsonExportState(
                    inProgress = false,
                    error = e.message ?: "Не удалось проверить расшифровку для экспорта"
                )
            }
        }
    }

    fun exportTranscriptJson(
        uri: Uri,
        version: com.team404bnf.app.transcription.ExportVersion,
        resolver: ContentResolver
    ) {
        if (_jsonExportState.value.inProgress) return

        _jsonExportState.value = JsonExportState(inProgress = true)

        viewModelScope.launch {
            try {
                val jsonContent = withContext(Dispatchers.IO) {
                    transcriptRepository.exportTranscriptJson(
                        recordingId,
                        version
                    ) ?: throw IOException("Не удалось сформировать JSON расшифровки")
                }

                withContext(Dispatchers.IO) {
                    val output = resolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Не удалось открыть файл для сохранения")

                    OutputStreamWriter(
                        output,
                        Charsets.UTF_8
                    ).buffered().use { writer ->
                        writer.write(jsonContent)
                    }
                }

                _jsonExportState.value = JsonExportState(success = true)

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {

                _jsonExportState.value = JsonExportState(
                    error = e.message ?: "Не удалось сохранить JSON"
                )
            }
        }
    }

    fun clearJsonExportStatus() {
        _jsonExportState.value = JsonExportState()
    }

    private fun submitEdit(
        action: suspend () -> Unit
    ): Job? {
        if (_editing.value) return null

        _editing.value = true
        _editError.value = null

        return viewModelScope.launch {
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

    /** One repository transaction keeps text, speaker and grouping consistent. */
    fun saveSegmentEdits(segmentId: String, text: String, speakerId: String?) {
        if (_editing.value) return
        _savedSegmentId.value = null
        submitEdit {
            transcriptRepository.updateSegmentEdits(recordingId, segmentId, text, speakerId)
            _savedSegmentId.value = segmentId
        }
    }

    fun consumeSegmentSaveResult() {
        _savedSegmentId.value = null
    }

    fun retryTranscription() {
        transcribeLocally()
    }

    fun clearEditError() {
        _editError.value = null
    }

    fun transcribeLocally() {
        if (_transcriptDisplayState.value.loading || _transcriptDisplayState.value.error != null ||
            _transcriptDisplayState.value.hasTranscript || _screenState.value.loading ||
            _screenState.value.error != null || !_screenState.value.audioSaved || isMicrophoneBusy() ||
            TranscriptionTaskManager.getTask(recordingId)?.isActive == true) return
        models.launchTranscription(recordingId)
    }

    fun cancelLocalMl() {
        viewModelScope.launch {
            com.team404bnf.app.transcription.TranscriptionTaskManager.cancel(recordingId)
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

    fun previewTranscriptSegment(segmentId: String) {
        if (_screenState.value.loading || _screenState.value.error != null ||
            playbackState.value.error != null || isMicrophoneBusy()) {
            controller.pause()
            return
        }
        val segment = _transcriptDisplayState.value.segments.firstOrNull { it.id == segmentId }
        controller.playRange(segment?.startMs, segment?.endMs)
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
