package com.team404bnf.app.ui.playback

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import com.team404bnf.app.playback.PlaybackViewModel
import com.team404bnf.app.ui.components.*
import com.team404bnf.app.ui.theme.*
import com.team404bnf.app.recording.RecordingSession
import com.team404bnf.app.recording.RecordingStatus

private enum class TranscriptExportFormat { TXT, JSON }

@Composable
fun PlaybackScreen(
    viewModel: PlaybackViewModel,
    onBack: () -> Unit,
    onManageModels: () -> Unit,
    openProcessing: Boolean = false
) {
    val screen by viewModel.screenState.collectAsState()
    val task by viewModel.transcriptionTask.collectAsState()
    var processingOpen by rememberSaveable { mutableStateOf(openProcessing) }
    var editSegmentId by rememberSaveable { mutableStateOf<String?>(null) }
    val playback by viewModel.playbackState.collectAsState()
    val recording by RecordingSession.state.collectAsState()

    val models by viewModel.modelState.collectAsState()
    val transcriptDisplay by viewModel.transcriptDisplayState.collectAsState()

    LaunchedEffect(task?.status, transcriptDisplay.hasTranscript) {
        if (task?.status == com.team404bnf.app.transcription.TranscriptionTaskStatus.COMPLETED &&
            transcriptDisplay.hasTranscript) processingOpen = false
    }

    val editing by viewModel.editing.collectAsState()
    val editError by viewModel.editError.collectAsState()
    val savedSegmentId by viewModel.savedSegmentId.collectAsState()
    LaunchedEffect(savedSegmentId) {
        if(savedSegmentId != null) {
            if(savedSegmentId == editSegmentId) editSegmentId = null
            viewModel.consumeSegmentSaveResult()
        }
    }

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
    LaunchedEffect(recovery.hasUserEdits) {
        if(!recovery.hasUserEdits) resetDialogOpen = false
    }

    val context = LocalContext.current

    val txtExport by
        viewModel.txtExportState.collectAsState()

    val wavExport by viewModel.wavExportState.collectAsState()

    val mp3Export by viewModel.mp3ExportState.collectAsState()

    val jsonExport by viewModel.jsonExportState.collectAsState()

    var versionDialogFormat by rememberSaveable {
        mutableStateOf<TranscriptExportFormat?>(null)
    }
    var selectedTxtExportVersion by rememberSaveable {
        mutableStateOf(com.team404bnf.app.transcription.ExportVersion.CORRECTED)
    }

    var selectedJsonExportVersion by rememberSaveable {
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
                    resolver = context.contentResolver,
                    version = selectedTxtExportVersion
                )
            }
        }

    fun launchTxtExportPicker(version: com.team404bnf.app.transcription.ExportVersion) {
        selectedTxtExportVersion = version
        txtExportLauncher.launch(suggestTxtFileName(screen.title, version))
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

    val durationMs = maxOf(screen.durationMs, playback.durationMs)
    val speakerIds = remember(transcriptDisplay.segments) {
        transcriptDisplay.segments.mapNotNull { it.speakerId }.distinct()
    }
    val availableSpeakerLabels = remember(transcriptDisplay.speakerLabels, transcriptDisplay.segments) {
        linkedMapOf<String,String>().apply {
            putAll(transcriptDisplay.speakerLabels)
            transcriptDisplay.segments.forEach { segment ->
                segment.speakerId?.let { id -> if(id !in this) put(id,segment.speakerLabel ?: "Говорящий ${size+1}") }
            }
        }
    }

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

    if(processingOpen) {
        ProcessingScreen(screen.title,durationMs,task,screen.audioSaved,transcriptDisplay.hasTranscript,
            retryEnabled=!recordingActive && !models.busy && !transcriptDisplay.loading && transcriptDisplay.error==null,
            onBack={processingOpen=false},
            onRetry={if(models.ready) viewModel.retryTranscription() else onManageModels()},
            onLibrary=onBack,onCancel=viewModel::cancelLocalMl,
            modelsRequired=(task==null || task?.status==com.team404bnf.app.transcription.TranscriptionTaskStatus.WAITING_FOR_MODELS) && !models.ready,
            onManageModels=onManageModels,startError=models.error)
        return
    }
    val editingSegment = transcriptDisplay.segments.firstOrNull { it.id == editSegmentId }
    if(editingSegment != null) {
        SegmentEditScreen(segment=editingSegment,speakerLabels=availableSpeakerLabels,
            saving=editing,error=editError,onDismiss={editSegmentId=null;viewModel.clearEditError()},
            onListen={viewModel.previewTranscriptSegment(editingSegment.id)},
            onPause=viewModel::pause,isListening=playback.isPlayRequested,
            canListen=!recordingActive && playback.error==null && screen.audioSaved &&
                com.team404bnf.app.playback.SegmentPlaybackRange.create(
                    editingSegment.startMs,editingSegment.endMs,playback.durationMs)!=null,
            onSave={text,speaker -> viewModel.saveSegmentEdits(editingSegment.id,text,speaker) })
        return
    }

    Scaffold(
        containerColor=ArchiveColors.Background,
        snackbarHost={SnackbarHost(snackbarHostState)},
        bottomBar={if(!screen.loading && screen.audioSaved) ArchivePlayer(playback,durationMs,
            enabled=!recordingActive && screen.error==null && playback.error==null,
            onSeek=viewModel::seekTo,onToggle=viewModel::togglePlayback,onSpeed=viewModel::cyclePlaybackSpeed)}
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
            Column(Modifier.widthIn(max=ArchiveDimens.ContentMaxWidth).fillMaxSize().padding(horizontal=ArchiveDimens.PagePadding)) {
            RecordingDetailHeader(
                title = screen.title,
                renameEnabled = !renaming &&
                    !screen.loading &&
                    screen.error == null,
                hasTranscript = transcriptDisplay.hasTranscript,
                hasDeletedSegments =
                    recovery.deletedSegments.isNotEmpty(),
                hasUserEdits = recovery.hasUserEdits,
                editsEnabled = !editing,
                canExportTranscript =
                    transcriptDisplay.hasTranscript &&
                    !editing &&
                    !txtExport.inProgress &&
                    transcriptDisplay.error == null &&
                    !transcriptDisplay.loading &&
                    (transcriptDisplay.segments.isNotEmpty() || recovery.deletedSegments.isNotEmpty()),
                canExportJson =
                    transcriptDisplay.hasTranscript &&
                    !editing &&
                    !jsonExport.inProgress &&
                    transcriptDisplay.error == null &&
                    !transcriptDisplay.loading &&
                    (transcriptDisplay.segments.isNotEmpty() || recovery.deletedSegments.isNotEmpty()),
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
                    viewModel.prepareTxtExport(
                        onShowVersionSelection = { versionDialogFormat = TranscriptExportFormat.TXT },
                        onLaunchPicker = ::launchTxtExportPicker
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
                            versionDialogFormat = TranscriptExportFormat.JSON
                        },
                        onLaunchPicker = { version ->
                            launchJsonExportPicker(version)
                        }
                    )
                },
                wavExportInProgress = wavExport.inProgress,
                mp3ExportInProgress = mp3Export.inProgress,
                audioAvailable = screen.audioSaved && screen.error == null,
                createdAtEpochMs = screen.createdAtEpochMs,
                durationMs = durationMs,
                speakerIds = speakerIds
            )


                when {
                    screen.loading -> Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {CircularProgressIndicator()}
                    screen.error != null -> Text(screen.error.orEmpty(),color=ArchiveColors.Red,modifier=Modifier.padding(vertical=20.dp))
                    else -> {
                        if(task!=null && !transcriptDisplay.hasTranscript) {
                            TextButton(onClick={processingOpen=true},modifier=Modifier.fillMaxWidth()) {
                                Text(if(task?.isActive==true) "${task?.stageMessage ?: "Обработка"} →" else "Состояние обработки →")
                            }
                        }
                        if(transcriptDisplay.segments.isNotEmpty()) {
                            Text("Нажмите — слушать · Удерживайте — изменить",style=MaterialTheme.typography.labelSmall,color=ArchiveColors.Muted,modifier=Modifier.padding(vertical=10.dp))
                            TranscriptMessages(
                                segments=transcriptDisplay.segments,speakerLabels=availableSpeakerLabels,
                                originalPositions=transcriptDisplay.originalPositions,
                                splitBeforeSegmentIds=transcriptDisplay.splitBeforeSegmentIds,
                                playbackPositionMs=playback.positionMs,isPlaybackRunning=playback.isPlayRequested,
                                onSegmentClick=viewModel::playFromTranscriptSegment,
                                onRequestTextEdit={ viewModel.pause();editSegmentId=it.id;viewModel.clearEditError() },
                                onChangeSpeaker={id,speaker -> viewModel.changeSegmentSpeaker(id,speaker)},
                                onRenameSpeaker={id,name -> viewModel.renameTranscriptSpeaker(id,name)},
                                onDeleteSegment={viewModel.deleteTranscriptSegment(it)},
                                onChangeGroupSpeaker={ids,speaker -> viewModel.changeTranscriptGroupSpeaker(ids,speaker)},
                                onDeleteGroup={viewModel.deleteTranscriptGroup(it)},
                                onSplitGroup={viewModel.splitTranscriptGroup(it)},
                                onRestoreAutoMerge={viewModel.restoreAutomaticMerge(it)},
                                enabled=!recordingActive && playback.error==null && screen.audioSaved,
                                editingEnabled=!editing,modifier=Modifier.weight(1f))
                        } else {
                            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                                Text(when {
                                    transcriptDisplay.loading -> "Загрузка расшифровки…"
                                    transcriptDisplay.error != null -> transcriptDisplay.error.orEmpty()
                                    transcriptDisplay.hasTranscript -> "В расшифровке нет реплик"
                                    else -> "Расшифровка ещё не создана"
                                },style=MaterialTheme.typography.titleMedium)
                                if(!transcriptDisplay.hasTranscript && !transcriptDisplay.loading && transcriptDisplay.error==null) {
                                    when {
                                        task?.isActive==true -> Text("Можно вернуться к записям. Обработка продолжится в фоне.",
                                            style=MaterialTheme.typography.bodyMedium,color=ArchiveColors.Muted)
                                        models.busy -> Text(models.stage ?: "Подготовка офлайн-моделей…",
                                            style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
                                        !models.ready -> {
                                            Text("${com.team404bnf.app.transcription.TranscriptionTaskManager.MODELS_REQUIRED_MESSAGE}.",
                                                style=MaterialTheme.typography.bodyMedium,color=ArchiveColors.Muted)
                                            ArchiveButton("Скачать модель",onManageModels,
                                                Modifier.fillMaxWidth(),accent=true,enabled=!recordingActive && !editing)
                                        }
                                        task?.status == com.team404bnf.app.transcription.TranscriptionTaskStatus.COMPLETED ->
                                            Text("Открываем сохранённую расшифровку…",style=MaterialTheme.typography.bodySmall)
                                        task != null -> Text("Обработка не завершена. Откройте состояние обработки, чтобы повторить попытку.",
                                            style=MaterialTheme.typography.bodyMedium,color=ArchiveColors.Muted)
                                        else -> {
                                            Text("Распознайте речь и определите говорящих, чтобы читать разговор по репликам.",
                                                style=MaterialTheme.typography.bodyMedium,color=ArchiveColors.Muted)
                                            ArchiveButton("Расшифровать на устройстве",{
                                                viewModel.transcribeLocally(); processingOpen=true
                                            },Modifier.fillMaxWidth(),accent=true,enabled=!editing && !recordingActive)
                                        }
                                    }
                                    LocalStorageNote("Без отправки аудио в интернет")
                                }
                            }
                        }
                        if(recordingActive) Text("Воспроизведение недоступно во время записи звука",style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
                        playback.error?.let {Text(it,color=ArchiveColors.Red,style=MaterialTheme.typography.bodySmall)}
                    }
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

    val exportFormat = versionDialogFormat
    if (exportFormat != null) {
        fun launchSelectedVersion(version: com.team404bnf.app.transcription.ExportVersion) {
            versionDialogFormat = null
            when(exportFormat) {
                TranscriptExportFormat.TXT -> launchTxtExportPicker(version)
                TranscriptExportFormat.JSON -> launchJsonExportPicker(version)
            }
        }
        AlertDialog(
            onDismissRequest = {
                versionDialogFormat = null
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
                            launchSelectedVersion(
                                com.team404bnf.app.transcription.ExportVersion.CORRECTED
                            )
                        }
                    ) {
                        Text("Отредактированная версия")
                    }

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            launchSelectedVersion(
                                com.team404bnf.app.transcription.ExportVersion.ORIGINAL
                            )
                        }
                    ) {
                        Text("Оригинальная версия")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        versionDialogFormat = null
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}

internal fun suggestJsonFileName(title: String, version: com.team404bnf.app.transcription.ExportVersion): String =
    suggestTranscriptFileName(title, version, "json")

internal fun suggestTxtFileName(title: String, version: com.team404bnf.app.transcription.ExportVersion): String =
    suggestTranscriptFileName(title, version, "txt")

private fun suggestTranscriptFileName(
    title: String, version: com.team404bnf.app.transcription.ExportVersion, extension: String
): String {
    val sanitized = title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
        .trim().trim('.').take(64).ifBlank { "Расшифровка" }
    val suffix = when(version) {
        com.team404bnf.app.transcription.ExportVersion.ORIGINAL -> "_original"
        com.team404bnf.app.transcription.ExportVersion.CORRECTED -> "_corrected"
    }
    return "$sanitized$suffix.$extension"
}
