package com.team404bnf.app.transcription

import android.content.Context
import android.content.Intent
import android.util.Log
import com.team404bnf.app.data.db.AppDatabase
import com.team404bnf.app.data.db.TranscriptionTaskDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

object TranscriptionTaskManager {
    private const val TAG = "TranscriptionTaskManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _tasksMap = MutableStateFlow<Map<String, TranscriptionTask>>(emptyMap())
    val tasksFlow: StateFlow<Map<String, TranscriptionTask>> = _tasksMap.asStateFlow()
    val mlMutex = Mutex()
    private val transitionMutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val taskOperationLocks = ConcurrentHashMap<String, Mutex>()
    private val localUpdateTimes = ConcurrentHashMap<String, Long>()
    private var dao: TranscriptionTaskDao? = null
    private val dbInitDeferred = CompletableDeferred<Unit>()

    fun initDatabase(context: Context) {
        if (dao != null) return
        val taskDao = AppDatabase.getInstance(context.applicationContext).transcriptionTaskDao()
        dao = taskDao
        scope.launch(Dispatchers.IO) {
            try {
                val stored = taskDao.getAllTasks().map(TranscriptionTask::fromEntity)
                _tasksMap.update { current -> current + stored.associateBy { it.recordingId } }
                recoverInterruptedTasksInternal()
                if (stored.any { it.status == TranscriptionTaskStatus.QUEUED }) {
                    try {
                        startService(context, null)
                    } catch (e: Exception) {
                        stored.filter { it.status == TranscriptionTaskStatus.QUEUED }.forEach { task ->
                            updateTask(
                                recordingId = task.recordingId,
                                status = TranscriptionTaskStatus.FAILED,
                                errorMessage = "Не удалось запустить фоновый сервис: ${e.message}",
                                attemptId = task.attemptId
                            )
                        }
                    }
                }
                dbInitDeferred.complete(Unit)
            } catch (e: Exception) {
                dbInitDeferred.completeExceptionally(e)
                Log.e(TAG, "Room initialization failed", e)
            }
        }
        scope.launch(Dispatchers.IO) {
            taskDao.observeAllTasks().collect { list ->
                val fromDb = list.map(TranscriptionTask::fromEntity).associateBy { it.recordingId }
                _tasksMap.update { current ->
                    current.toMutableMap().apply {
                        fromDb.forEach { (id, task) ->
                            val inMemory = current[id]
                            val localUpdateTime = localUpdateTimes[id]
                            if ((localUpdateTime == null || task.updatedAtEpochMs > localUpdateTime) &&
                                (inMemory == null || task.updatedAtEpochMs >= inMemory.updatedAtEpochMs)
                            ) {
                                put(id, task)
                            }
                        }
                    }
                }
            }
        }
    }

    fun setTaskDaoForTest(taskDao: TranscriptionTaskDao) {
        dao = taskDao
        if (!dbInitDeferred.isCompleted) dbInitDeferred.complete(Unit)
    }

    suspend fun awaitDatabaseInitialized() = dbInitDeferred.await()

    fun observeTask(recordingId: String): Flow<TranscriptionTask?> =
        tasksFlow.map { it[recordingId] }

    fun getTask(recordingId: String): TranscriptionTask? = _tasksMap.value[recordingId]

    fun enqueue(context: Context, recordingId: String) {
        initDatabase(context)
        scope.launch {
            try {
                enqueueAndStart(context, recordingId)
            } catch (e: Exception) {
                Log.e(TAG, "Could not enqueue $recordingId", e)
                runCatching {
                    updateTask(
                        recordingId = recordingId,
                        status = TranscriptionTaskStatus.FAILED,
                        errorMessage = "Ошибка запуска фонового сервиса: ${e.message}"
                    )
                }.onFailure { failure ->
                    Log.e(TAG, "Could not persist enqueue failure for $recordingId", failure)
                }
            }
        }
    }

    fun retry(context: Context, recordingId: String) = enqueue(context, recordingId)

    internal suspend fun enqueueForTest(recordingId: String): TranscriptionTask? =
        enqueueTask(recordingId)

    private suspend fun enqueueAndStart(context: Context, recordingId: String) {
        awaitDatabaseInitialized()
        val task = enqueueTask(recordingId)
        if (task != null) startService(context, task.recordingId)
    }

    private suspend fun enqueueTask(recordingId: String): TranscriptionTask? {
        return transitionMutex.withLock {
            val existing = _tasksMap.value[recordingId]
            if (existing?.isActive == true) return@withLock null
            val now = System.currentTimeMillis()
            val queued = TranscriptionTask(
                recordingId = recordingId,
                status = TranscriptionTaskStatus.QUEUED,
                stageMessage = "В очереди…",
                attemptId = UUID.randomUUID().toString(),
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
            dao?.upsertTask(queued.toEntity())
            localUpdateTimes[recordingId] = queued.updatedAtEpochMs
            _tasksMap.update { it + (recordingId to queued) }
            queued
        }
    }

    suspend fun cancel(recordingId: String) {
        operationLock(recordingId).withLock {
            terminate(recordingId, TranscriptionTaskStatus.CANCELLED, "Обработка отменена", null)
        }
    }

    suspend fun interrupt(recordingId: String, reason: String) {
        terminate(
            recordingId,
            TranscriptionTaskStatus.INTERRUPTED,
            "Превышен лимит времени фоновой службы",
            reason
        )
    }

    private suspend fun terminate(
        recordingId: String,
        status: TranscriptionTaskStatus,
        stageMessage: String,
        errorMessage: String?
    ) {
        transitionMutex.withLock {
            val current = _tasksMap.value[recordingId] ?: return
            if (current.isFinished) return
            val terminal = current.copy(
                status = status,
                stageMessage = stageMessage,
                errorMessage = errorMessage,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            dao?.upsertTask(terminal.toEntity())
            localUpdateTimes[recordingId] = terminal.updatedAtEpochMs
            _tasksMap.update { it + (recordingId to terminal) }
            activeJobs[recordingId]?.cancel()
            activeJobs.remove(recordingId)
        }
    }

    fun registerJob(recordingId: String, job: Job) {
        activeJobs[recordingId] = job
    }

    fun unregisterJob(recordingId: String) {
        activeJobs.remove(recordingId)
    }

    suspend fun updateTask(
        recordingId: String,
        status: TranscriptionTaskStatus,
        stageMessage: String = "",
        errorMessage: String? = null,
        attemptId: String? = null
    ): Boolean = transitionMutex.withLock {
        val current = _tasksMap.value[recordingId]
        if (current != null) {
            if (attemptId != null && attemptId != current.attemptId) return@withLock false
            if (current.isFinished) return@withLock false
            if (!isAllowed(current.status, status)) return@withLock false
        }

        val now = System.currentTimeMillis()
        val updated = (current ?: TranscriptionTask(recordingId, status)).copy(
            status = status,
            stageMessage = stageMessage,
            errorMessage = errorMessage,
            updatedAtEpochMs = now
        )
        dao?.upsertTask(updated.toEntity())
        localUpdateTimes[recordingId] = updated.updatedAtEpochMs
        _tasksMap.update { it + (recordingId to updated) }
        true
    }

    suspend fun deleteIfInactive(recordingId: String, delete: suspend () -> Unit) {
        operationLock(recordingId).withLock {
            transitionMutex.withLock {
                val current = _tasksMap.value[recordingId]
                val persisted = dao?.getTask(recordingId)
                check(current?.isActive != true && TranscriptionTask.fromEntityOrNull(persisted)?.isActive != true) {
                    "Нельзя удалить запись во время фоновой обработки"
                }
                delete()
                dao?.deleteTask(recordingId)
                localUpdateTimes.remove(recordingId)
                _tasksMap.update { it - recordingId }
            }
        }
    }

    suspend fun saveResult(
        recordingId: String,
        attemptId: String,
        save: suspend () -> Unit
    ): Boolean {
        return operationLock(recordingId).withLock {
            val eligible = transitionMutex.withLock {
                val current = _tasksMap.value[recordingId]
                current != null && current.attemptId == attemptId &&
                    current.status == TranscriptionTaskStatus.SAVING
            }
            if (!eligible) return@withLock false

            save()

            transitionMutex.withLock {
                val current = _tasksMap.value[recordingId]
                if (current == null || current.attemptId != attemptId ||
                    current.status != TranscriptionTaskStatus.SAVING
                ) {
                    return@withLock false
                }
                val completed = current.copy(
                    status = TranscriptionTaskStatus.COMPLETED,
                    stageMessage = "Расшифровка готова",
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                dao?.upsertTask(completed.toEntity())
                localUpdateTimes[recordingId] = completed.updatedAtEpochMs
                _tasksMap.update { it + (recordingId to completed) }
                true
            }
        }
    }

    suspend fun failQueuedTasks(errorMessage: String): Int =
        transitionMutex.withLock {
            val queued = _tasksMap.value.values.filter { it.status == TranscriptionTaskStatus.QUEUED }
            queued.forEach { task ->
                val failed = task.copy(
                    status = TranscriptionTaskStatus.FAILED,
                    errorMessage = errorMessage,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                dao?.upsertTask(failed.toEntity())
                localUpdateTimes[task.recordingId] = failed.updatedAtEpochMs
                _tasksMap.update { it + (task.recordingId to failed) }
            }
            queued.size
        }

    private fun operationLock(recordingId: String): Mutex =
        taskOperationLocks.computeIfAbsent(recordingId) { Mutex() }

    private fun isAllowed(from: TranscriptionTaskStatus, to: TranscriptionTaskStatus): Boolean {
        if (from == to) return true
        return when (from) {
            TranscriptionTaskStatus.QUEUED -> to == TranscriptionTaskStatus.PREPARING
                || to == TranscriptionTaskStatus.FAILED
            TranscriptionTaskStatus.PREPARING -> to in setOf(
                TranscriptionTaskStatus.RUNNING_ASR, TranscriptionTaskStatus.RUNNING_DIARIZATION,
                TranscriptionTaskStatus.SAVING, TranscriptionTaskStatus.FAILED
            )
            TranscriptionTaskStatus.RUNNING_ASR -> to in setOf(
                TranscriptionTaskStatus.RUNNING_DIARIZATION, TranscriptionTaskStatus.SAVING,
                TranscriptionTaskStatus.FAILED
            )
            TranscriptionTaskStatus.RUNNING_DIARIZATION -> to in setOf(
                TranscriptionTaskStatus.SAVING, TranscriptionTaskStatus.FAILED
            )
            TranscriptionTaskStatus.SAVING -> to in setOf(
                TranscriptionTaskStatus.COMPLETED, TranscriptionTaskStatus.FAILED
            )
            else -> false
        }
    }

    suspend fun recoverInterruptedTasks() {
        recoverInterruptedTasksInternal()
    }

    private suspend fun recoverInterruptedTasksInternal() {
        val orphaned = _tasksMap.value.values.filter {
            it.status in setOf(
                TranscriptionTaskStatus.PREPARING, TranscriptionTaskStatus.RUNNING_ASR,
                TranscriptionTaskStatus.RUNNING_DIARIZATION, TranscriptionTaskStatus.SAVING
            ) && !activeJobs.containsKey(it.recordingId)
        }
        orphaned.forEach {
            terminate(it.recordingId, TranscriptionTaskStatus.INTERRUPTED, "Обработка прервана", "Процесс был завершён системой")
        }
    }

    fun hasActiveOrQueuedTasks() = _tasksMap.value.values.any { it.isActive }

    fun getNextQueuedTask() = _tasksMap.value.values
        .filter { it.status == TranscriptionTaskStatus.QUEUED }
        .minByOrNull { it.createdAtEpochMs }

    private suspend fun startService(context: Context, recordingId: String?) {
        val intent = Intent(context.applicationContext, TranscriptionService::class.java).apply {
            action = TranscriptionService.ACTION_ENQUEUE
            recordingId?.let { putExtra(TranscriptionService.EXTRA_RECORDING_ID, it) }
        }
        withContext(Dispatchers.Main) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }
    }
}
