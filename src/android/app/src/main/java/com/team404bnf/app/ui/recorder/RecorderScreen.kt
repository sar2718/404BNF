package com.team404bnf.app.ui.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import com.team404bnf.app.recording.RecordingStatus

@Composable
fun RecorderScreen(
    viewModel: RecorderViewModel,
    onBack: () -> Unit
) {

    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val permissionSettings = remember(context) {
        context.getSharedPreferences(
            "notification_permission_prompt",
            Context.MODE_PRIVATE
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {

            if (
                context.checkSelfPermission(
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.start()
            } else {
                viewModel.permissionDenied()
            }
        }

    fun requestStart() {

        val permissions = mutableListOf<String>()

        if (
            context.checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        // Разрешение на уведомления запрашиваем один раз.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            val alreadyAsked = permissionSettings.getBoolean(
                "requested_once",
                false
            )

            val notificationGranted =
                context.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

            if (!alreadyAsked && !notificationGranted) {

                permissions.add(
                    Manifest.permission.POST_NOTIFICATIONS
                )

                permissionSettings.edit()
                    .putBoolean("requested_once", true)
                    .apply()
            }
        }

        if (permissions.isEmpty()) {
            viewModel.start()
        } else {
            permissionLauncher.launch(
                permissions.toTypedArray()
            )
        }
    }

    val isRecording =
        state.status == RecordingStatus.RECORDING

    val isPaused =
        state.status == RecordingStatus.PAUSED

    val isActive = state.status in setOf(
        RecordingStatus.STARTING,
        RecordingStatus.RECORDING,
        RecordingStatus.PAUSED,
        RecordingStatus.FINISHING
    )

    val seconds = state.durationMs / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),

            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onBack) {
                    Text("← Назад")
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Новая запись",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "%02d:%02d".format(
                    minutes,
                    remainingSeconds
                ),
                style = MaterialTheme.typography.displayLarge
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = when (state.status) {

                    RecordingStatus.IDLE ->
                        "Микрофон не активен"

                    RecordingStatus.STARTING ->
                        "Запуск микрофона..."

                    RecordingStatus.RECORDING ->
                        "● Идёт запись"

                    RecordingStatus.PAUSED ->
                        "⏸ Запись приостановлена"

                    RecordingStatus.FINISHING ->
                        "Сохранение аудио..."

                    RecordingStatus.SAVED ->
                        "✓ Запись сохранена"

                    RecordingStatus.ERROR ->
                        "Ошибка записи"
                }
            )

            state.message?.let { message ->

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { requestStart() },

                enabled = !isActive,

                modifier = if (
                    state.status == RecordingStatus.SAVED
                ) {
                    Modifier
                        .fillMaxWidth(0.82f)
                        .height(48.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                }
            ) {

                Text(
                    if (state.status == RecordingStatus.SAVED) {
                        "Начать новую запись"
                    } else {
                        "Начать запись"
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                OutlinedButton(
                    onClick = {
                        if (isRecording) {
                            viewModel.pause()
                        } else {
                            viewModel.resume()
                        }
                    },

                    enabled = isRecording || isPaused,

                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                ) {
                    Text(
                        if (isPaused) "Продолжить" else "Пауза"
                    )
                }

                OutlinedButton(
                    onClick = {
                        viewModel.stop()
                    },

                    enabled = isRecording || isPaused,

                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                ) {
                    Text("Завершить")
                }
            }
        }
    }
}