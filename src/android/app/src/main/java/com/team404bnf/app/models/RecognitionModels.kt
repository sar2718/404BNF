package com.team404bnf.app.models

import android.content.Context
import com.team404bnf.app.data.db.AppDatabase
import com.team404bnf.app.data.repository.RecordingRepository
import com.team404bnf.app.transcription.TranscriptionTaskManager
import com.team404bnf.app.transcription.TranscriptionTask
import com.team404bnf.app.transcription.TranscriptionTaskStatus
import com.team404bnf.ml.ModelCatalog
import com.team404bnf.ml.ModelEvent
import com.team404bnf.ml.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID

enum class ModelPhase { CHECKING, MISSING, DOWNLOADING, REMOVING, READY }

data class RecognitionModelsState(
    val phase: ModelPhase = ModelPhase.CHECKING,
    val storageBytes: Long = 0L,
    val stage: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val error: String? = null
) {
    val ready get() = phase == ModelPhase.READY
    val busy get() = phase in setOf(ModelPhase.CHECKING, ModelPhase.DOWNLOADING, ModelPhase.REMOVING)
}

/** App-scoped model setup. Files, downloads and validation remain owned by ModelManager. */
class RecognitionModels private constructor(context: Context) {
    companion object {
        @Volatile private var instance: RecognitionModels? = null
        fun get(context: Context): RecognitionModels = instance ?: synchronized(this) {
            instance ?: RecognitionModels(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val manager = ModelManager(appContext)
    val modelPackage = ModelCatalog.packages.first { it.id == ModelCatalog.DEFAULT_PACKAGE_ID }
    private val preferences = appContext.getSharedPreferences("recognition_setup", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(RecognitionModelsState())
    val state = _state.asStateFlow()
    val pendingRecordingId = TranscriptionTaskManager.tasksFlow.map { tasks ->
        tasks.values.filter { it.status == TranscriptionTaskStatus.WAITING_FOR_MODELS }
            .minByOrNull { it.createdAtEpochMs }?.recordingId
    }.stateIn(scope, SharingStarted.Eagerly, null)
    private var refreshJob: Job? = null

    init {
        refresh()
        // Migrate the earlier single pending preference into durable transcription tasks.
        preferences.getString("pending_recording", null)?.let { id ->
            scope.launch {
                val valid = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
                val requested = if (valid) requestTranscription(id) else false
                // Keep the legacy request if Room could not persist its replacement task.
                if (!valid || requested || TranscriptionTaskManager.getTask(id) != null) {
                    preferences.edit().remove("pending_recording").apply()
                }
            }
        }
    }

    @Synchronized
    fun refresh() {
        if (_state.value.phase in setOf(ModelPhase.DOWNLOADING, ModelPhase.REMOVING) || refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            try {
                TranscriptionTaskManager.initDatabase(appContext)
                TranscriptionTaskManager.awaitDatabaseInitialized()
                operations.withLock { if (verifyReady()) resumeWaiting() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(phase = ModelPhase.MISSING, error = error.message ?: "Не удалось проверить модели") }
            }
        }
    }

    private suspend fun verifyReady(): Boolean {
        _state.update { it.copy(phase = ModelPhase.CHECKING, stage = "Проверяем целостность моделей…", error = null) }
        return try {
            val problems = manager.verifyInstalled(modelPackage.models)
            val bytes = manager.storageBytes(modelPackage.models)
            _state.value = RecognitionModelsState(
                phase = if (problems.isEmpty()) ModelPhase.READY else ModelPhase.MISSING,
                storageBytes = bytes,
                error = if (problems.isNotEmpty() && bytes > 0L)
                    "Комплект моделей неполный или повреждён. Нажмите «Скачать» для восстановления." else null
            )
            problems.isEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(phase = ModelPhase.MISSING, error = "Не удалось проверить модели: ${error.message}") }
            false
        }
    }

    fun download() {
        if (_state.value.busy || _state.value.ready) return
        _state.update { it.copy(phase = ModelPhase.DOWNLOADING, stage = "Подготовка загрузки…", error = null) }
        scope.launch {
            operations.withLock {
                try {
                    TranscriptionTaskManager.initDatabase(appContext)
                    TranscriptionTaskManager.awaitDatabaseInitialized()
                    // Only removal of invalid existing files needs the destructive-operation guard.
                    TranscriptionTaskManager.withModelsIdle { manager.removeInvalidManaged(modelPackage.models) }
                    manager.ensureInstalled(modelPackage.models) { event ->
                        val asset = when (event) {
                            is ModelEvent.Downloading -> event.asset
                            is ModelEvent.Installed -> event.asset
                            is ModelEvent.Verified -> event.asset
                        }
                        _state.update { it.copy(
                            stage = when (event) {
                                is ModelEvent.Downloading -> "Загрузка ${asset.model.name.substringBefore('_')}…"
                                is ModelEvent.Installed -> "Проверка скачанных файлов…"
                                is ModelEvent.Verified -> "Проверка установленных файлов…"
                            },
                            downloadedBytes = (event as? ModelEvent.Downloading)?.downloaded ?: 0L,
                            totalBytes = (event as? ModelEvent.Downloading)?.total
                        ) }
                    }
                    // Readiness is published only after the existing full integrity check succeeds.
                    if (verifyReady()) resumeWaiting()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val bytes = runCatching { manager.storageBytes(modelPackage.models) }.getOrDefault(_state.value.storageBytes)
                    if (verifyReady()) resumeWaiting()
                    _state.update { it.copy(storageBytes = bytes, error = downloadError(error)) }
                }
            }
        }
    }

    fun delete() {
        if (_state.value.busy) return
        val oldState = _state.value
        _state.update { it.copy(phase = ModelPhase.REMOVING, error = null) }
        scope.launch {
            operations.withLock {
                try {
                    TranscriptionTaskManager.initDatabase(appContext)
                    TranscriptionTaskManager.awaitDatabaseInitialized()
                    TranscriptionTaskManager.withModelsIdle { manager.removeManaged(modelPackage.models) }
                    if (verifyReady()) resumeWaiting()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // A partial deletion must never leave a stale READY state.
                    if (verifyReady()) resumeWaiting()
                    val bytes = runCatching { manager.storageBytes(modelPackage.models) }.getOrDefault(oldState.storageBytes)
                    _state.update { it.copy(error = error.message ?: "Не удалось удалить модели", storageBytes = bytes) }
                }
            }
        }
    }

    fun launchTranscription(recordingId: String) {
        scope.launch { requestTranscription(recordingId) }
    }

    /** Called once after audio is closed and its Room row exists, independently of Compose. */
    suspend fun requestTranscription(recordingId: String): Boolean {
        return try {
            require(UUID.fromString(recordingId).toString() == recordingId)
            TranscriptionTaskManager.initDatabase(appContext)
            TranscriptionTaskManager.awaitDatabaseInitialized()
            val database = AppDatabase.getInstance(appContext)
            if (database.transcriptSnapshotDao().get(recordingId) != null) return true
            check(RecordingRepository(database.recordingDao(), appContext).getPlayableRecording(recordingId) != null) {
                "Сохранённая аудиозапись недоступна"
            }
            val waiting = TranscriptionTaskManager.waitForModels(recordingId)
                ?: return TranscriptionTaskManager.getTask(recordingId)?.let {
                    it.isActive || it.status == TranscriptionTaskStatus.COMPLETED
                } == true
            // Waiting is already durable if installation is in progress or the app is closed now.
            if (_state.value.phase in setOf(ModelPhase.DOWNLOADING, ModelPhase.REMOVING)) return false
            operations.withLock {
                val latest = TranscriptionTaskManager.getTask(recordingId)
                if (latest?.isActive == true || latest?.status == TranscriptionTaskStatus.COMPLETED) return@withLock true
                if (latest?.status != TranscriptionTaskStatus.WAITING_FOR_MODELS || latest.attemptId != waiting.attemptId)
                    return@withLock false
                if (!verifyReady()) return@withLock false
                resumeWaitingTask(waiting)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(error = error.message ?: "Не удалось запустить расшифровку") }
            false
        }
    }

    /** The transcription worker holds mlMutex from this check until native sessions have closed. */
    suspend fun verifyForProcessing(): Boolean {
        if (_state.value.phase in setOf(ModelPhase.DOWNLOADING, ModelPhase.REMOVING)) return false
        return operations.withLock { verifyReady() }
    }

    private suspend fun resumeWaiting() {
        val waiting = TranscriptionTaskManager.tasksFlow.value.values
            .filter { it.status == TranscriptionTaskStatus.WAITING_FOR_MODELS }.sortedBy { it.createdAtEpochMs }
        waiting.forEach { resumeWaitingTask(it) }
    }

    private suspend fun resumeWaitingTask(task: TranscriptionTask): Boolean {
        val database = AppDatabase.getInstance(appContext)
        return try {
            if (database.transcriptSnapshotDao().get(task.recordingId) != null) {
                TranscriptionTaskManager.updateTask(task.recordingId, TranscriptionTaskStatus.COMPLETED,
                    stageMessage = "Расшифровка готова", attemptId = task.attemptId)
            } else {
                check(RecordingRepository(database.recordingDao(), appContext).getPlayableRecording(task.recordingId) != null) {
                    "Сохранённая аудиозапись недоступна"
                }
                TranscriptionTaskManager.enqueueSavedRecording(appContext, task.recordingId, task.attemptId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            TranscriptionTaskManager.updateTask(task.recordingId, TranscriptionTaskStatus.FAILED,
                errorMessage = error.message ?: "Не удалось запустить расшифровку", attemptId = task.attemptId)
            false
        }
    }

    fun cancelPendingTranscriptions(waiting: List<TranscriptionTask>) {
        scope.launch { waiting.forEach { TranscriptionTaskManager.cancel(it.recordingId, it.attemptId) } }
    }

    private fun downloadError(error: Exception): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.toList()
        return when {
            causes.any { it is IOException } -> "Не удалось скачать модели. Проверьте подключение к интернету и повторите попытку."
            error.message?.contains("Not enough free storage") == true -> "Недостаточно места. Освободите не менее 1 ГБ и повторите загрузку."
            error.message?.contains("SHA-256") == true -> "Скачанный файл не прошёл проверку целостности. Повторите загрузку."
            else -> "Не удалось скачать модели: ${error.message ?: "повторите попытку"}"
        }
    }
}
