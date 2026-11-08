package com.team404bnf.app.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

import com.team404bnf.app.MainActivity
import com.team404bnf.app.models.RecognitionModels
import com.team404bnf.app.data.db.AppDatabase
import com.team404bnf.app.data.repository.RecordingRepository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

import java.util.UUID

class RecordingService : Service() {

    companion object {
        const val ACTION_START =
            "com.team404bnf.app.recording.START"

        const val ACTION_PAUSE =
            "com.team404bnf.app.recording.PAUSE"

        const val ACTION_RESUME =
            "com.team404bnf.app.recording.RESUME"

        const val ACTION_STOP =
            "com.team404bnf.app.recording.STOP"

        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate
    )

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private val repository by lazy {
        val database = AppDatabase.getInstance(applicationContext)

        RecordingRepository(
            database.recordingDao(),
            applicationContext
        )
    }

    private var capture: AudioCapture? = null
    private var recordingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> startRecording()

            ACTION_PAUSE -> pauseRecording()

            ACTION_RESUME -> resumeRecording()

            ACTION_STOP -> stopRecording()

            else -> {
                if (recordingJob == null) {
                    stopSelf()
                }
            }
        }

        // Не перезапускаем микрофон автоматически после
        // уничтожения процесса Android.
        return START_NOT_STICKY
    }

    private fun startRecording() {

        // Нельзя запускать две параллельные записи.
        if (recordingJob != null) return

        if (RecordingSession.state.value.status !in setOf(
                RecordingStatus.IDLE,
                RecordingStatus.STARTING,
                RecordingStatus.SAVED,
                RecordingStatus.ERROR
            )
        ) return

        // Проверяем разрешение.
        if (checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            RecordingSession.set(
                RecorderUiState(
                    status = RecordingStatus.ERROR,
                    message = "Нет разрешения на микрофон"
                )
            )

            stopSelf()
            return
        }

        // Сначала переводим Service в foreground.
        try {
            val notification = buildNotification(
                RecordingStatus.RECORDING
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

        } catch (e: Exception) {

            RecordingSession.set(
                RecorderUiState(
                    status = RecordingStatus.ERROR,
                    message = "Не удалось запустить фоновую запись"
                )
            )

            stopSelf()
            return
        }

        val recordingId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        val audioCapture = AudioCapture(applicationContext)

        capture = audioCapture

        RecordingSession.set(
            RecorderUiState(
                status = RecordingStatus.RECORDING
            )
        )

        recordingJob = scope.launch {

            try {

                val result = audioCapture.capture(
                    recordingId = recordingId,
                    onDurationChanged = { duration ->

                        RecordingSession.update {
                            it.copy(durationMs = duration)
                        }
                    }
                )

                // После успешного создания WAV сохраняем
                // сведения о записи в Room.
                repository.saveAudioRecording(
                    id = recordingId,
                    createdAtEpochMs = createdAt,
                    durationMs = result.durationMs,
                    relativePath = result.relativePath
                )

                // The file is closed and its row is committed before any model or queue work.
                // Await enqueue while this FGS is alive; Compose never schedules the ML task.
                val queued = RecognitionModels.get(applicationContext).requestTranscription(recordingId)

                RecordingSession.update {
                    it.copy(
                        status = RecordingStatus.SAVED,
                        savedRecordingId = recordingId,
                        message = if(!queued && com.team404bnf.app.transcription.TranscriptionTaskManager.getTask(recordingId)?.status ==
                            com.team404bnf.app.transcription.TranscriptionTaskStatus.WAITING_FOR_MODELS)
                            "Аудио сохранено. ${com.team404bnf.app.transcription.TranscriptionTaskManager.MODELS_REQUIRED_MESSAGE}."
                        else "Аудиозапись сохранена"
                    )
                }

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {

                RecordingSession.update {
                    it.copy(
                        status = RecordingStatus.ERROR,
                        message = e.message ?: "Ошибка записи"
                    )
                }

            } finally {

                capture = null
                recordingJob = null

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun pauseRecording() {

        if (RecordingSession.state.value.status !=
            RecordingStatus.RECORDING
        ) return

        capture?.pause()

        RecordingSession.update {
            it.copy(status = RecordingStatus.PAUSED)
        }

        updateNotification()
    }

    private fun resumeRecording() {

        if (RecordingSession.state.value.status !=
            RecordingStatus.PAUSED
        ) return

        capture?.resume()

        RecordingSession.update {
            it.copy(status = RecordingStatus.RECORDING)
        }

        updateNotification()
    }

    private fun stopRecording() {

        val status = RecordingSession.state.value.status

        if (status !in setOf(
                RecordingStatus.RECORDING,
                RecordingStatus.PAUSED
            )
        ) return

        RecordingSession.update {
            it.copy(status = RecordingStatus.FINISHING)
        }

        capture?.stop()

        updateNotification()
    }

    private fun createNotificationChannel() {

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Аудиозапись",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Управление текущей аудиозаписью"
        }

        notificationManager.createNotificationChannel(channel)
    }

    private fun actionIntent(
        action: String,
        requestCode: Int
    ): PendingIntent {

        val intent = Intent(
            this,
            RecordingService::class.java
        ).apply {
            this.action = action
        }

        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(
        status: RecordingStatus
    ): Notification {

        val openAppIntent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val text = when (status) {
            RecordingStatus.PAUSED -> "Запись приостановлена"
            RecordingStatus.FINISHING -> "Сохранение аудио..."
            else -> "Идёт запись звука"
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("404BNF")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        when (status) {

            RecordingStatus.RECORDING -> {

                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Пауза",
                    actionIntent(ACTION_PAUSE, 1)
                )

                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Стоп",
                    actionIntent(ACTION_STOP, 2)
                )
            }

            RecordingStatus.PAUSED -> {

                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Продолжить",
                    actionIntent(ACTION_RESUME, 3)
                )

                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Стоп",
                    actionIntent(ACTION_STOP, 2)
                )
            }

            else -> Unit
        }

        return builder.build()
    }

    private fun updateNotification() {

        val notification = buildNotification(
            RecordingSession.state.value.status
        )

        runCatching {
            notificationManager.notify(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    override fun onDestroy() {

        capture?.stop()
        scope.cancel()

        super.onDestroy()
    }
}
