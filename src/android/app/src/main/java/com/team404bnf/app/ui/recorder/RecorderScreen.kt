package com.team404bnf.app.ui.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import com.team404bnf.app.recording.RecordingStatus
import com.team404bnf.app.ui.components.*
import com.team404bnf.app.ui.theme.*

@Composable
fun RecorderScreen(
    viewModel: RecorderViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit
) {

    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = LocalActivity.current

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

    val microphoneDenied = state.status == RecordingStatus.ERROR &&
        state.message == "Нет разрешения на микрофон"

    fun allowMicrophone() {
        if (microphoneDenied &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
        ) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
        } else {
            requestStart()
        }
    }

    val isActive = state.status in setOf(
        RecordingStatus.STARTING,
        RecordingStatus.RECORDING,
        RecordingStatus.PAUSED,
        RecordingStatus.FINISHING
    )

    var exitDialogOpen by remember { mutableStateOf(false) }
    var finishingRequested by rememberSaveable { mutableStateOf(false) }
    var returnAfterSave by rememberSaveable { mutableStateOf(false) }
    val latestBack by rememberUpdatedState(onBack)
    val latestSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(state.status, state.message, state.savedRecordingId) {
        val savedId = state.savedRecordingId
        if (state.status == RecordingStatus.SAVED && savedId != null) {
            returnAfterSave = false
            viewModel.consumeSavedRecording(savedId)
            latestSaved(savedId)
        } else if (state.status == RecordingStatus.SAVED && returnAfterSave) {
            returnAfterSave = false
            latestBack()
        }
        if (state.status in setOf(RecordingStatus.SAVED, RecordingStatus.ERROR) || state.message == "Не удалось выполнить команду") {
            finishingRequested = false
            if(state.status != RecordingStatus.SAVED) returnAfterSave = false
        }
    }
    fun finish() {
        if (!finishingRequested && (isRecording || isPaused)) {
            finishingRequested = true
            viewModel.stop()
        }
    }
    fun back() { if(isActive || finishingRequested) exitDialogOpen = true else onBack() }
    BackHandler(isActive || finishingRequested) { back() }

    if(exitDialogOpen) AlertDialog(
        onDismissRequest = { exitDialogOpen = false },
        title = { Text("Сохранить запись перед выходом?") },
        text = { Text("Запись появится в библиотеке после сохранения аудиофайла.") },
        confirmButton = { TextButton(enabled = (isRecording || isPaused) && !finishingRequested, onClick = {
            returnAfterSave = true; exitDialogOpen = false; finish()
        }) { Text("Сохранить и выйти") } },
        dismissButton = { TextButton(onClick = { exitDialogOpen = false }) { Text("Остаться") } }
    )

    Scaffold(containerColor = ArchiveColors.Background, bottomBar = {
        Box(Modifier.fillMaxWidth().navigationBarsPadding(),contentAlignment=Alignment.Center) {
            Column(Modifier.widthIn(max=ArchiveDimens.ContentMaxWidth).fillMaxWidth().padding(ArchiveDimens.PagePadding),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(when {
                    state.status == RecordingStatus.SAVED -> "Запись доступна в библиотеке"
                    isPaused -> "Можно продолжить или завершить запись"
                    isActive -> "Запись сохранится после завершения"
                    else -> "Микрофон включается по вашему нажатию"
                },modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center,
                    style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
                Spacer(Modifier.height(24.dp))
                if(isActive || finishingRequested) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        ArchiveButton(if(isPaused) "Продолжить" else "Пауза", {
                            if(isPaused) viewModel.resume() else viewModel.pause()
                        },Modifier.weight(1f), enabled=(isRecording || isPaused) && !finishingRequested,
                            symbol=if(isPaused) ArchiveSymbol.Play else ArchiveSymbol.Pause)
                        ArchiveButton(if(finishingRequested || state.status == RecordingStatus.FINISHING) "Сохранение…" else "Завершить",{finish()},
                            Modifier.weight(1f),accent=true,enabled=(isRecording || isPaused) && !finishingRequested,symbol=ArchiveSymbol.Stop)
                    }
                } else {
                    ArchiveButton(when {
                        state.status==RecordingStatus.SAVED -> "К записям"
                        microphoneDenied -> "Разрешить микрофон"
                        else -> "Начать запись"
                    },{
                        if(state.status==RecordingStatus.SAVED) onBack() else allowMicrophone()
                    },Modifier.fillMaxWidth(),accent=true,symbol=if(state.status==RecordingStatus.SAVED) ArchiveSymbol.Check else ArchiveSymbol.Wave)
                    if(state.status==RecordingStatus.SAVED) TextButton(onClick={requestStart()}) {Text("Начать новую запись")}
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
            Column(Modifier.widthIn(max=ArchiveDimens.ContentMaxWidth).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(horizontal=ArchiveDimens.PagePadding)) {
                Box(Modifier.fillMaxWidth().padding(top=4.dp),contentAlignment=Alignment.Center) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=48.dp),
                        horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(if(isRecording) ArchiveColors.Red else ArchiveColors.Gold,CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(when(state.status) {
                            RecordingStatus.IDLE -> "Диктофон готов"
                            RecordingStatus.STARTING -> "Запуск микрофона…"
                            RecordingStatus.RECORDING -> "Идёт запись"
                            RecordingStatus.PAUSED -> "Запись на паузе"
                            RecordingStatus.FINISHING -> "Сохранение аудио…"
                            RecordingStatus.SAVED -> "Запись сохранена"
                            RecordingStatus.ERROR -> "Ошибка записи"
                        },style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted,
                            modifier=Modifier.weight(1f,fill=false),textAlign=TextAlign.Center)
                        Spacer(Modifier.width(15.dp))
                    }
                    Box(Modifier.align(Alignment.CenterStart)) {
                        ArchiveIconButton(ArchiveSymbol.Down,"Назад",{back()})
                    }
                }
                Spacer(Modifier.height(60.dp))
                ArchiveEyebrow("404BNF / ДИКТОФОН")
                Spacer(Modifier.height(8.dp))
                Text(if(microphoneDenied) "Нет доступа к микрофону" else "Новая запись",style=MaterialTheme.typography.headlineMedium)
                if(microphoneDenied) Text("Для записи разговора разрешите приложению использовать микрофон.",
                    modifier=Modifier.padding(top=16.dp),style=MaterialTheme.typography.bodyMedium,color=ArchiveColors.Muted)
                Spacer(Modifier.height(60.dp))
                ArchiveWaveform(if(isRecording) ArchiveColors.Red else ArchiveColors.Muted,emphasis=isRecording)
                Text("Индикатор записи",style=MaterialTheme.typography.labelSmall,color=ArchiveColors.Muted,modifier=Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(56.dp))
                Text(archiveTime(state.durationMs).let {if(state.durationMs < 3_600_000) "00:$it" else it},
                    style=MaterialTheme.typography.displayLarge,
                    modifier=Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(20.dp))
                LocalStorageNote(when {
                    state.status==RecordingStatus.SAVED -> "Аудиозапись сохранена"
                    isActive -> "Сохраняется на устройстве"
                    else -> "Аудио хранится на устройстве"
                },Modifier.align(Alignment.CenterHorizontally))
                state.message?.takeIf {state.status!=RecordingStatus.SAVED}?.let { Text(it,color=if(state.status==RecordingStatus.ERROR) ArchiveColors.Red else ArchiveColors.Muted,
                    style=MaterialTheme.typography.bodyMedium,textAlign=TextAlign.Center,
                    modifier=Modifier.fillMaxWidth().padding(top=20.dp)) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
