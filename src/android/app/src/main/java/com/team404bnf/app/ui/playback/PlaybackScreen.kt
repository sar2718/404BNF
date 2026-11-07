package com.team404bnf.app.ui.playback

import android.content.pm.ApplicationInfo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import com.team404bnf.app.playback.PlaybackViewModel
import com.team404bnf.app.recording.RecordingSession
import com.team404bnf.app.recording.RecordingStatus
import kotlinx.coroutines.launch

@Composable
fun PlaybackScreen(
    viewModel: PlaybackViewModel,
    onBack: () -> Unit
) {
    val screen by viewModel.screenState.collectAsState()
    val playback by viewModel.playbackState.collectAsState()
    val recording by RecordingSession.state.collectAsState()

    val jsonImport by viewModel.jsonImportState.collectAsState()
    val localMl by viewModel.localMlState.collectAsState()
    val transcriptDisplay by viewModel.transcriptDisplayState.collectAsState()

    val editing by viewModel.editing.collectAsState()
    val editError by viewModel.editError.collectAsState()

    val renaming by viewModel.renaming.collectAsState()
    val renameError by viewModel.renameError.collectAsState()

    val recordingDetails by
        viewModel.recordingDetailsState.collectAsState()

    val recovery by viewModel.recoveryState.collectAsState()

    var restoreDialogOpen by remember {
        mutableStateOf(false)
    }

    var resetDialogOpen by remember {
        mutableStateOf(false)
    }

    val context = LocalContext.current

    val txtExport by
        viewModel.txtExportState.collectAsState()

    val wavExport by viewModel.wavExportState.collectAsState()

    val mp3Export by viewModel.mp3ExportState.collectAsState()

    val jsonExport by viewModel.jsonExportState.collectAsState()

    var jsonVersionDialogOpen by remember {
        mutableStateOf(false)
    }

    var selectedJsonExportVersion by remember {
        mutableStateOf(com.team404bnf.app.transcription.ExportVersion.CORRECTED)
    }

    val txtExportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(
                "text/plain"
            )
        ) { uri ->

            if (uri != null) {
                viewModel.exportTranscriptTxt(
                    uri = uri,
                    resolver = context.contentResolver
                )
            }
        }

    val wavExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "audio/wav"
        )
    ) { uri ->

        if (uri != null) {
            viewModel.exportWav(
                uri = uri,
                resolver = context.contentResolver
            )
        }
    }

    val mp3ExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "audio/mpeg"
        )
    ) { uri ->

        if (uri != null) {
            viewModel.exportMp3(
                uri = uri,
                resolver = context.contentResolver
            )
        }
    }

    val jsonExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "application/json"
        )
    ) { uri ->
        if (uri != null) {
            viewModel.exportTranscriptJson(
                uri = uri,
                version = selectedJsonExportVersion,
                resolver = context.contentResolver
            )
        }
    }

    fun launchJsonExportPicker(
        version: com.team404bnf.app.transcription.ExportVersion
    ) {
        selectedJsonExportVersion = version
        val fileName = suggestJsonFileName(
            title = screen.title,
            version = version
        )
        jsonExportLauncher.launch(fileName)
    }

    val showDebugImport = remember(context) {
        (context.applicationInfo.flags and
            ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    val jsonPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->

        if (uri != null) {
            viewModel.importJson(uri)
        }
    }

    val recordingActive = recording.status in setOf(
        RecordingStatus.STARTING,
        RecordingStatus.RECORDING,
        RecordingStatus.PAUSED,
        RecordingStatus.FINISHING
    )

    val lifecycleOwner = LocalLifecycleOwner.current

    // Останавливаем воспроизведение при уходе
    // с экрана или сворачивании приложения.
    DisposableEffect(viewModel, lifecycleOwner) {

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.pause()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pause()
        }
    }

    var dragging by remember {
        mutableStateOf(false)
    }

    var dragPosition by remember {
        mutableFloatStateOf(0f)
    }

    val durationMs = maxOf(
        screen.durationMs,
        playback.durationMs
    )

    val sliderMaximum = durationMs
        .coerceAtLeast(1L)
        .toFloat()

    val currentPosition = if (dragging) {
        dragPosition
    } else {
        playback.positionMs.toFloat()
    }.coerceIn(0f, sliderMaximum)

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(txtExport) {
        if (txtExport.success) {
            snackbarHostState.showSnackbar("Файл успешно сохранён")
            viewModel.clearTxtExportStatus()
        }
    }

    LaunchedEffect(mp3Export) {
        if (mp3Export.success) {
            snackbarHostState.showSnackbar("Аудиофайл успешно сохранён")
            viewModel.clearMp3ExportStatus()
        }
    }

    txtExport.error?.let { errorMsg ->
        AlertDialog(
            onDismissRequest = viewModel::clearTxtExportStatus,
            title = { Text("Ошибка экспорта") },
            text = { Text(errorMsg) },
            confirmButton = {
                TextButton(onClick = viewModel::clearTxtExportStatus) {
                    Text("Понятно")
                }
            }
        )
    }

    wavExport.error?.let { error ->

        AlertDialog(
            onDismissRequest =
                viewModel::clearWavExportError,

            title = {
                Text("Ошибка экспорта аудио")
            },

            text = {
                Text(error)
            },

            confirmButton = {
                TextButton(
                    onClick =
                        viewModel::clearWavExportError
                ) {
                    Text("Понятно")
                }
            }
        )
    }

    mp3Export.error?.let { error ->

        AlertDialog(
            onDismissRequest =
                viewModel::clearMp3ExportError,

            title = {
                Text("Ошибка экспорта аудио")
            },

            text = {
                Text(error)
            },

            confirmButton = {
                TextButton(
                    onClick =
                        viewModel::clearMp3ExportError
                ) {
                    Text("Понятно")
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = 20.dp
                )
        ) {

            RecordingDetailHeader(
                title = screen.title,
                renameEnabled = !renaming &&
                    !screen.loading &&
                    screen.error == null,
                hasTranscript = jsonImport.hasTranscript,
                hasDeletedSegments =
                    recovery.deletedSegments.isNotEmpty(),
                hasUserEdits = recovery.hasUserEdits,
                canExportTranscript =
                    jsonImport.hasTranscript &&
                    !editing &&
                    !txtExport.inProgress &&
                    transcriptDisplay.error == null &&
                    !transcriptDisplay.loading &&
                    transcriptDisplay.segments.isNotEmpty(),
                canExportJson =
                    jsonImport.hasTranscript &&
                    !editing &&
                    !jsonExport.inProgress &&
                    transcriptDisplay.error == null &&
                    !transcriptDisplay.loading,
                onBack = onBack,
                onRename = viewModel::renameChat,
                onShowInfo = viewModel::showRecordingDetails,
                onRestoreDeleted = {
                    restoreDialogOpen = true
                },
                onResetEdits = {
                    resetDialogOpen = true
                },
                onExportTranscript = {
                    txtExportLauncher.launch(
                        suggestedTranscriptFileName(screen.title)
                    )
                },
                onExportWav = {

                    val safeTitle = screen.title
                        .replace(
                            Regex("[^\\p{L}\\p{N} _.-]"),
                            "_"
                        )
                        .trim()
                        .take(64)
                        .ifBlank { "Аудиозапись" }

                    val fileName = if (
                        safeTitle.endsWith(".wav", ignoreCase = true)
                    ) {
                        safeTitle
                    } else {
                        "$safeTitle.wav"
                    }

                    wavExportLauncher.launch(fileName)
                },
                onExportMp3 = {

                    val safeTitle = screen.title
                        .replace(
                            Regex("[^\\p{L}\\p{N} _.-]"),
                            "_"
                        )
                        .trim()
                        .take(64)
                        .ifBlank { "Аудиозапись" }

                    val fileName = if (
                        safeTitle.endsWith(".mp3", ignoreCase = true)
                    ) {
                        safeTitle
                    } else {
                        "$safeTitle.mp3"
                    }

                    mp3ExportLauncher.launch(fileName)
                },
                onExportJson = {
                    viewModel.prepareJsonExport(
                        onShowVersionSelection = {
                            jsonVersionDialogOpen = true
                        },
                        onLaunchPicker = { version ->
                            launchJsonExportPicker(version)
                        }
                    )
                },
                wavExportInProgress = wavExport.inProgress,
                mp3ExportInProgress = mp3Export.inProgress
            )

            Spacer(Modifier.height(8.dp))

            when {
                screen.loading -> {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                screen.error != null -> {

                    Text(
                        text = screen.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> {

                    if (transcriptDisplay.segments.isEmpty()) {

                        val message = when {
                            transcriptDisplay.loading ->
                                "Загрузка расшифровки..."

                            transcriptDisplay.error != null ->
                                transcriptDisplay.error.orEmpty()

                            jsonImport.hasTranscript ->
                                "В расшифровке нет реплик"

                            else ->
                                "Расшифровка пока недоступна"
                        }

                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (transcriptDisplay.error != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    if (!jsonImport.hasTranscript) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = viewModel::transcribeLocally,
                            enabled = !localMl.running && !localMl.downloading &&
                                !jsonImport.inProgress && !editing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Расшифровать на устройстве")
                        }
                        TextButton(
                            onClick = viewModel::downloadLocalModels,
                            enabled = !localMl.running && !localMl.downloading,
                        ) {
                            Text("Скачать модели для офлайн-обработки (~925 МБ)")
                        }
                        if (localMl.running || localMl.downloading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(localMl.stage ?: "Обработка…", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = viewModel::cancelLocalMl) {
                                Text("Отменить")
                            }
                        } else {
                            localMl.stage?.let { stage ->
                                Text(stage, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        localMl.error?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (showDebugImport && !jsonImport.hasTranscript) {

                        Spacer(Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = {
                                jsonPicker.launch(arrayOf("*/*"))
                            },
                            enabled = !jsonImport.inProgress
                        ) {
                            Text(
                                if (jsonImport.inProgress) {
                                    "Загрузка JSON..."
                                } else {
                                    "Загрузить JSON (DEBUG)"
                                }
                            )
                        }

                        jsonImport.message?.let { message ->

                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        jsonImport.error?.let { error ->

                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    if (transcriptDisplay.segments.isNotEmpty()) {

                        Text(
                            text = "Нажмите — слушать · Удерживайте — изменить",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(6.dp))

                        TranscriptMessages(
                            segments = transcriptDisplay.segments,
                            speakerLabels = transcriptDisplay.speakerLabels,
                            originalPositions = transcriptDisplay.originalPositions,
                            splitBeforeSegmentIds =
                                transcriptDisplay.splitBeforeSegmentIds,
                            playbackPositionMs = playback.positionMs,
                            isPlaybackRunning = playback.isPlayRequested,
                            onSegmentClick = viewModel::playFromTranscriptSegment,
                            onEditText = viewModel::editSegmentText,
                            onChangeSpeaker = viewModel::changeSegmentSpeaker,
                            onRenameSpeaker = viewModel::renameTranscriptSpeaker,
                            onDeleteSegment = viewModel::deleteTranscriptSegment,
                            onChangeGroupSpeaker =
                                viewModel::changeTranscriptGroupSpeaker,
                            onDeleteGroup =
                                viewModel::deleteTranscriptGroup,
                            onSplitGroup =
                                viewModel::splitTranscriptGroup,
                            onRestoreAutoMerge =
                                viewModel::restoreAutomaticMerge,
                            enabled = !recordingActive &&
                                playback.error == null,
                            editingEnabled = !editing,
                            modifier = Modifier.weight(1f)
                        )

                    } else {

                        Spacer(Modifier.weight(1f))
                    }

                    if (recordingActive) {
                        Text(
                            text = "Воспроизведение недоступно " +
                                "во время записи звука",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(12.dp))
                    }

                    playback.error?.let { message ->

                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error
                        )

                        Spacer(Modifier.height(12.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Text(
                            text = formatPlaybackTime(
                                currentPosition.toLong()
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )

                        Spacer(Modifier.width(8.dp))

                        Slider(
                            value = currentPosition,
                            onValueChange = {
                                dragging = true
                                dragPosition = it
                            },
                            onValueChangeFinished = {
                                viewModel.seekTo(
                                    dragPosition.toLong()
                                )
                                dragging = false
                            },
                            valueRange = 0f..sliderMaximum,
                            enabled = !recordingActive &&
                                playback.error == null &&
                                durationMs > 0L,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(Modifier.width(8.dp))

                        Text(
                            text = formatPlaybackTime(durationMs),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    val speedLabel = when (playback.speed) {
                        1f -> "1×"
                        1.25f -> "1.25×"
                        1.5f -> "1.5×"
                        1.75f -> "1.75×"
                        2f -> "2×"
                        else -> "1×"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Button(
                            onClick = {
                                viewModel.togglePlayback()
                            },
                            enabled = !recordingActive &&
                                playback.error == null,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {

                            Text(
                                if (playback.isPlayRequested) {
                                    "⏸ Пауза"
                                } else {
                                    "▶ Воспроизвести"
                                }
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.cyclePlaybackSpeed()
                            },
                            enabled = !recordingActive &&
                                playback.error == null,
                            modifier = Modifier.height(56.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text(speedLabel)
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    editError?.let { error ->

        AlertDialog(
            onDismissRequest = viewModel::clearEditError,
            title = { Text("Ошибка сохранения") },
            text = { Text(error) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::clearEditError
                ) {
                    Text("Понятно")
                }
            }
        )
    }

    renameError?.let { error ->

        AlertDialog(
            onDismissRequest = viewModel::clearRenameError,
            title = {
                Text("Ошибка переименования")
            },
            text = {
                Text(error)
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::clearRenameError
                ) {
                    Text("Понятно")
                }
            }
        )
    }

    if (recordingDetails.visible) {
        RecordingInfoDialog(
            state = recordingDetails,
            onDismiss = viewModel::hideRecordingDetails
        )
    }

    if (restoreDialogOpen) {

        TranscriptRecoveryDialog(
            segments = recovery.deletedSegments,
            editing = editing,
            onRestore = viewModel::restoreDeletedSegment,
            onDismiss = {
                restoreDialogOpen = false
            }
        )
    }

    if (resetDialogOpen) {

        AlertDialog(
            onDismissRequest = {
                resetDialogOpen = false
            },

            title = {
                Text("Сбросить изменения?")
            },

            text = {
                Text(
                    "Все исправления текста, изменения участников " +
                        "и удаления сообщений будут отменены. " +
                        "Расшифровка вернётся к первоначальному виду. " +
                        "Аудиозапись и название чата сохранятся."
                )
            },

            confirmButton = {

                TextButton(
                    enabled = !editing,
                    onClick = {
                        viewModel.resetAllTranscriptEdits()
                        resetDialogOpen = false
                    }
                ) {
                    Text(
                        "Сбросить",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        resetDialogOpen = false
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    LaunchedEffect(jsonExport) {
        if (jsonExport.success) {
            snackbarHostState.showSnackbar("JSON сохранён")
            viewModel.clearJsonExportStatus()
        }
    }

    jsonExport.error?.let { errorMsg ->
        AlertDialog(
            onDismissRequest = viewModel::clearJsonExportStatus,
            title = { Text("Ошибка экспорта") },
            text = { Text(errorMsg) },
            confirmButton = {
                TextButton(onClick = viewModel::clearJsonExportStatus) {
                    Text("Понятно")
                }
            }
        )
    }

    if (jsonVersionDialogOpen) {
        AlertDialog(
            onDismissRequest = {
                jsonVersionDialogOpen = false
            },
            title = {
                Text("Выберите версию расшифровки")
            },
            text = {
                Text(
                    "Расшифровка содержит пользовательские исправления. " +
                        "Какую версию вы хотите экспортировать?"
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            jsonVersionDialogOpen = false
                            launchJsonExportPicker(
                                com.team404bnf.app.transcription.ExportVersion.CORRECTED
                            )
                        }
                    ) {
                        Text("С исправлениями")
                    }

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            jsonVersionDialogOpen = false
                            launchJsonExportPicker(
                                com.team404bnf.app.transcription.ExportVersion.ORIGINAL
                            )
                        }
                    ) {
                        Text("Оригинальная расшифровка")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        jsonVersionDialogOpen = false
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}

internal fun suggestJsonFileName(
    title: String,
    version: com.team404bnf.app.transcription.ExportVersion
): String {
    val sanitized = title
        .replace(
            Regex("""[\\/:*?"<>|\p{Cntrl}]"""),
            "_"
        )
        .trim()
        .trim('.')
        .take(64)
        .ifBlank { "Расшифровка" }

    val suffix = when (version) {
        com.team404bnf.app.transcription.ExportVersion.ORIGINAL -> "_original"
        com.team404bnf.app.transcription.ExportVersion.CORRECTED -> "_corrected"
    }

    return "${sanitized}${suffix}.json"
}

private fun suggestedTranscriptFileName(
    title: String
): String {

    val sanitized = title
        .replace(
            Regex("""[\\/:*?"<>|\p{Cntrl}]"""),
            "_"
        )
        .trim()
        .trim('.')
        .take(64)
        .ifBlank { "Расшифровка" }

    return "$sanitized.txt"
}

private fun formatPlaybackTime(
    milliseconds: Long
): String {

    val totalSeconds = (milliseconds / 1000)
        .coerceAtLeast(0L)

    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val remainingSeconds = totalSeconds % 60

    return if (hours > 0) {
        "%02d:%02d:%02d".format(
            hours,
            minutes,
            remainingSeconds
        )
    } else {
        "%02d:%02d".format(
            minutes,
            remainingSeconds
        )
    }
}