package com.team404bnf.app.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.team404bnf.app.MainActivity
import com.team404bnf.app.data.db.AppDatabase
import com.team404bnf.app.data.repository.RecordingRepository
import com.team404bnf.app.data.repository.TranscriptRepository
import com.team404bnf.app.localml.LocalTranscriptionPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import android.util.Log

class TranscriptionService : Service() {

    companion object {
        const val ACTION_ENQUEUE = "com.team404bnf.app.transcription.ENQUEUE"
        const val ACTION_CANCEL = "com.team404bnf.app.transcription.CANCEL"
        const val EXTRA_RECORDING_ID = "recording_id"

        private const val CHANNEL_ID = "transcription_channel"
        private const val NOTIFICATION_ID = 2002
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timeoutScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var timedOut = false

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private val recordingRepository by lazy {
        val database = AppDatabase.getInstance(applicationContext)
        RecordingRepository(database.recordingDao(), applicationContext)
    }

    private val transcriptRepository by lazy {
        val database = AppDatabase.getInstance(applicationContext)
        TranscriptRepository(database, recordingRepository)
    }

    private var workerJob: Job? = null
    private var lastStartId: Int = 0
    @Volatile private var activeTaskId: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        TranscriptionTaskManager.initDatabase(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_RECORDING_ID)
                if (id != null) {
                    scope.launch { TranscriptionTaskManager.cancel(id) }
                }
            }
            ACTION_ENQUEUE -> {
                if (timedOut) return START_NOT_STICKY
                if (!ensureForeground()) {
                    return START_NOT_STICKY
                }
                triggerWorkerLoop()
            }
            else -> {
                if (timedOut) return START_NOT_STICKY
                if (!TranscriptionTaskManager.hasActiveOrQueuedTasks()) {
                    stopSelf()
                } else {
                    if (!ensureForeground()) {
                        return START_NOT_STICKY
                    }
                    triggerWorkerLoop()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground(): Boolean {
        try {
            val notification = buildNotification(
                title = "404BNF — Фоновая обработка",
                text = "Идёт подготовка локальной транскрибации…"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return true
        } catch (e: Exception) {
            val message = "Не удалось запустить фоновую службу: ${e.message}"
            timeoutScope.launch {
                try {
                    TranscriptionTaskManager.awaitDatabaseInitialized()
                    TranscriptionTaskManager.failQueuedTasks(message)
                } catch (persistError: Exception) {
                    Log.e("TranscriptionService", "Could not persist FGS failure", persistError)
                } finally {
                    stopSelf()
                    timeoutScope.cancel()
                }
            }
            return false
        }
    }

    @Synchronized
    private fun triggerWorkerLoop() {
        if (timedOut || workerJob?.isActive == true) return
        workerJob = scope.launch {
            try {
                TranscriptionTaskManager.awaitDatabaseInitialized()
                while (TranscriptionTaskManager.hasActiveOrQueuedTasks()) {
                    val task = TranscriptionTaskManager.getNextQueuedTask() ?: break
                    processSingleTask(task.recordingId)
                }
            } catch (e: CancellationException) {
                if (!timedOut) throw e
            } catch (e: Exception) {
                // If DB initialization or queue reading failed
                val currentId = activeTaskId
                if (currentId != null) {
                    TranscriptionTaskManager.updateTask(
                        recordingId = currentId,
                        status = TranscriptionTaskStatus.FAILED,
                        errorMessage = "Ошибка считывания очереди: ${e.message}"
                    )
                }
            } finally {
                if (!timedOut) {
                    synchronized(this@TranscriptionService) {
                        activeTaskId = null
                        workerJob = null
                        // A resumed/new task may arrive while the previous loop is finishing.
                        if (TranscriptionTaskManager.getNextQueuedTask() != null) triggerWorkerLoop()
                        else if (stopSelfResult(lastStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
            }
        }
    }

    private suspend fun processSingleTask(recordingId: String) {
        TranscriptionTaskManager.mlMutex.withLock {
            val currentTask = TranscriptionTaskManager.getTask(recordingId)
            if (currentTask == null || currentTask.status != TranscriptionTaskStatus.QUEUED) return

            activeTaskId = recordingId
            val currentJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // A persisted result (including user edits) always wins over a stale queued task.
                    if (AppDatabase.getInstance(applicationContext).transcriptSnapshotDao().get(recordingId) != null) {
                        TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.COMPLETED,
                            stageMessage = "Расшифровка готова", attemptId = currentTask.attemptId)
                        return@launch
                    }
                    val recording = recordingRepository.getPlayableRecording(recordingId)
                        ?: error("Аудиозапись недоступна")

                    // Revalidate at execution time, under the same lock that protects model removal.
                    if (!com.team404bnf.app.models.RecognitionModels.get(applicationContext).verifyForProcessing()) {
                        TranscriptionTaskManager.updateTask(recordingId, TranscriptionTaskStatus.WAITING_FOR_MODELS,
                            stageMessage = TranscriptionTaskManager.MODELS_REQUIRED_MESSAGE, attemptId = currentTask.attemptId)
                        return@launch
                    }
                    val preparing = TranscriptionTaskManager.updateTask(
                        recordingId = recordingId,
                        status = TranscriptionTaskStatus.PREPARING,
                        stageMessage = "Подготавливаем запись…",
                        attemptId = currentTask.attemptId
                    )
                    if (!preparing) return@launch
                    val latest = TranscriptionTaskManager.getTask(recordingId)
                    if (latest?.attemptId != currentTask.attemptId || latest.status != TranscriptionTaskStatus.PREPARING) {
                        return@launch
                    }
                    updateNotification("Запись «${recording.title}»", "Подготовка аудио…", recordingId)

                    val pipeline = LocalTranscriptionPipeline(applicationContext)
                    val json = pipeline.transcribe(recording.file) { stageMessage ->
                        val status = when {
                            stageMessage.contains("GigaAM") -> TranscriptionTaskStatus.RUNNING_ASR
                            stageMessage.contains("Silero") || stageMessage.contains("WeSpeaker") -> TranscriptionTaskStatus.RUNNING_DIARIZATION
                            stageMessage.contains("JSON") -> TranscriptionTaskStatus.SAVING
                            else -> TranscriptionTaskStatus.PREPARING
                        }
                        runBlocking {
                            TranscriptionTaskManager.updateTask(
                                recordingId = recordingId,
                                status = status,
                                stageMessage = stageMessage,
                                attemptId = currentTask.attemptId
                            )
                        }
                        updateNotification("Запись «${recording.title}»", stageMessage, recordingId)
                    }

                    // Check if cancelled before saving
                    val checkTask = TranscriptionTaskManager.getTask(recordingId)
                    if (checkTask?.isFinished == true || checkTask?.attemptId != currentTask.attemptId) {
                        return@launch
                    }

                    TranscriptionTaskManager.updateTask(
                        recordingId = recordingId,
                        status = TranscriptionTaskStatus.SAVING,
                        stageMessage = "Сохраняем расшифровку…",
                        attemptId = currentTask.attemptId
                    )
                    updateNotification("Запись «${recording.title}»", "Сохранение результатов…", recordingId)

                    val saved = TranscriptionTaskManager.saveResult(
                        recordingId = recordingId,
                        attemptId = currentTask.attemptId
                    ) {
                        transcriptRepository.save(recordingId, json)
                    }
                    if (!saved) return@launch
                    updateNotification("Запись «${recording.title}»", "Расшифровка готова", recordingId)

                } catch (e: CancellationException) {
                    TranscriptionTaskManager.updateTask(
                        recordingId = recordingId,
                        status = TranscriptionTaskStatus.CANCELLED,
                        stageMessage = "Обработка отменена",
                        attemptId = currentTask.attemptId
                    )
                    throw e
                } catch (e: Exception) {
                    TranscriptionTaskManager.updateTask(
                        recordingId = recordingId,
                        status = TranscriptionTaskStatus.FAILED,
                        errorMessage = e.message ?: "Ошибка локальной обработки",
                        attemptId = currentTask.attemptId
                    )
                }
            }

            TranscriptionTaskManager.registerJob(recordingId, currentJob)
            try {
                currentJob.start()
                currentJob.join()
            } finally {
                TranscriptionTaskManager.unregisterJob(recordingId)
                if (!timedOut) {
                    activeTaskId = null
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        val currentId = activeTaskId
        workerJob?.cancel()
        if (currentId != null) {
            timeoutScope.launch {
                try {
                    TranscriptionTaskManager.interrupt(
                        currentId,
                        "Система Android завершила фоновую службу по таймауту"
                    )
                } catch (e: Exception) {
                    Log.e("TranscriptionService", "Could not persist timeout state", e)
                } finally {
                    activeTaskId = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    timeoutScope.cancel()
                }
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Расшифровка аудио",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Уведомления о локальной обработке аудиозаписей"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String, recordingId: String? = null): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (recordingId != null) {
            val cancelIntent = Intent(this, TranscriptionService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_RECORDING_ID, recordingId)
            }
            val pendingCancel = PendingIntent.getService(
                this,
                recordingId.hashCode(),
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отменить",
                pendingCancel
            )
        }

        return builder.build()
    }

    private fun updateNotification(title: String, text: String, recordingId: String? = null) {
        val notification = buildNotification(title, text, recordingId)
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (!timedOut) timeoutScope.cancel()
        super.onDestroy()
    }
}
