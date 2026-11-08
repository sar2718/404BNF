package com.team404bnf.app.models

import android.content.Context
import com.team404bnf.app.data.db.AppDatabase
import com.team404bnf.app.data.repository.RecordingRepository
import com.team404bnf.app.transcription.TranscriptionTaskManager
import com.team404bnf.ml.ModelCatalog
import com.team404bnf.ml.ModelEvent
import com.team404bnf.ml.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val _pendingRecordingId = MutableStateFlow(preferences.getString("pending_recording", null))
    val pendingRecordingId = _pendingRecordingId.asStateFlow()

    init { refresh() }

    fun rememberRecording(recordingId: String) {
        require(UUID.fromString(recordingId).toString() == recordingId)
        preferences.edit().putString("pending_recording", recordingId).apply()
        _pendingRecordingId.value = recordingId
    }

    private fun clearPending(recordingId: String) {
        if (_pendingRecordingId.value != recordingId) return
        preferences.edit().remove("pending_recording").apply()
        _pendingRecordingId.value = null
    }

    fun refresh() {
        if (_state.value.phase in setOf(ModelPhase.DOWNLOADING, ModelPhase.REMOVING)) return
        scope.launch { operations.withLock { verifyReady() } }
    }

    private suspend fun verifyReady(): Boolean {
        _state.update { it.copy(phase = ModelPhase.CHECKING, stage = "Проверяем целостность моделей…", error = null) }
        return try {
            val problems = manager.verifyInstalled(modelPackage.models)
            val bytes = manager.storageBytes(modelPackage.models)
            // Do not keep routing to setup for an audio recording the user has since deleted.
            _pendingRecordingId.value?.let { id ->
                if (AppDatabase.getInstance(appContext).recordingDao().getRecordingById(id) == null) clearPending(id)
            }
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
                    verifyReady()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val bytes = runCatching { manager.storageBytes(modelPackage.models) }.getOrDefault(_state.value.storageBytes)
                    verifyReady()
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
                    verifyReady()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // A partial deletion must never leave a stale READY state.
                    verifyReady()
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
        rememberRecording(recordingId)
        if (_state.value.phase in setOf(ModelPhase.DOWNLOADING, ModelPhase.REMOVING)) return false
        return operations.withLock {
            try {
                val database = AppDatabase.getInstance(appContext)
                val recording = RecordingRepository(database.recordingDao(), appContext).getPlayableRecording(recordingId)
                if (recording == null) {
                    clearPending(recordingId)
                    error("Сохранённая аудиозапись недоступна")
                }
                if (database.transcriptSnapshotDao().get(recordingId) != null) {
                    clearPending(recordingId)
                    return@withLock true
                }
                TranscriptionTaskManager.initDatabase(appContext)
                TranscriptionTaskManager.awaitDatabaseInitialized()
                if (TranscriptionTaskManager.getTask(recordingId)?.isActive == true) {
                    clearPending(recordingId)
                    return@withLock true
                }
                if (!verifyReady()) return@withLock false
                TranscriptionTaskManager.enqueueSavedRecording(appContext, recordingId)
                clearPending(recordingId)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Не удалось запустить расшифровку") }
                false
            }
        }
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
