package com.team404bnf.app.ui.playback

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

import com.team404bnf.app.ui.components.*
import com.team404bnf.app.ui.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ExportCategory(
    val title: String,
    val description: String,
) {
    TEXT("Текст", "Экспорт расшифровки"),
    AUDIO("Аудио", "Экспорт звукозаписи")
}

private enum class ExportFormat(
    val category: ExportCategory,
    val title: String,
    val description: String
) {
    TXT(ExportCategory.TEXT, "TXT", "Текстовый файл"),
    JSON(ExportCategory.TEXT, "JSON", "Структурированная расшифровка"),
    WAV(ExportCategory.AUDIO, "WAV", "Аудиозапись"),
    MP3(ExportCategory.AUDIO, "MP3", "Сжатый аудиофайл")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailHeader(
    title: String,
    renameEnabled: Boolean,
    hasTranscript: Boolean,
    hasDeletedSegments: Boolean,
    hasUserEdits: Boolean,
    canExportTranscript: Boolean,
    canExportJson: Boolean,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onShowInfo: () -> Unit,
    onRestoreDeleted: () -> Unit,
    onResetEdits: () -> Unit,
    onExportTranscript: () -> Unit,
    onExportJson: () -> Unit,
    onExportWav: () -> Unit,
    onExportMp3: () -> Unit,
    wavExportInProgress: Boolean,
    mp3ExportInProgress: Boolean = false,
    audioAvailable: Boolean = true,
    createdAtEpochMs: Long? = null,
    durationMs: Long = 0,
    speakerIds: List<String> = emptyList(),
    editsEnabled: Boolean = true
) {
    var menuExpanded by remember {
        mutableStateOf(false)
    }

    var renameDialogOpen by remember {
        mutableStateOf(false)
    }

    var draftTitle by remember {
        mutableStateOf("")
    }

    var exportSheetOpen by remember {
        mutableStateOf(false)
    }

    var selectedExportCategory by remember {
        mutableStateOf<ExportCategory?>(null)
    }

    var exportSelectionInProgress by remember {
        mutableStateOf(false)
    }

    val exportSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val exportScope = rememberCoroutineScope()

    val availableExportFormats = remember(
        canExportTranscript,
        canExportJson,
        wavExportInProgress,
        mp3ExportInProgress, audioAvailable
    ) {
        buildList {
            if (canExportTranscript) add(ExportFormat.TXT)
            if (canExportJson) add(ExportFormat.JSON)
            if (audioAvailable && !wavExportInProgress) add(ExportFormat.WAV)
            if (audioAvailable && !mp3ExportInProgress) add(ExportFormat.MP3)
        }
    }

    fun openExportSheet() {
        selectedExportCategory = null
        exportSheetOpen = true
    }

    fun selectExportFormat(format: ExportFormat) {
        if (exportSelectionInProgress) return

        exportSelectionInProgress = true
        exportScope.launch {
            try {
                exportSheetState.hide()
                exportSheetOpen = false
                selectedExportCategory = null

                when (format) {
                    ExportFormat.TXT -> onExportTranscript()
                    ExportFormat.JSON -> onExportJson()
                    ExportFormat.WAV -> onExportWav()
                    ExportFormat.MP3 -> onExportMp3()
                }
            } finally {
                exportSelectionInProgress = false
            }
        }
    }

    Column {
        Row(Modifier.fillMaxWidth().padding(top=4.dp), verticalAlignment=Alignment.CenterVertically) {
            ArchiveIconButton(ArchiveSymbol.Back, "Назад", onBack)
            Column(Modifier.weight(1f)) {
                Text(title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                val date = createdAtEpochMs?.let { SimpleDateFormat("d MMMM",Locale.getDefault()).format(Date(it)) }
                Text(listOfNotNull(date, archiveTime(durationMs)).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
            }
            Box {
                ArchiveIconButton(ArchiveSymbol.More,"Действия с записью",{menuExpanded=true})
                DropdownMenu(expanded=menuExpanded,onDismissRequest={menuExpanded=false}) {
                    DropdownMenuItem(text={Text("Переименовать запись")},enabled=renameEnabled,onClick={menuExpanded=false;draftTitle=title;renameDialogOpen=true})
                    if(hasTranscript) {
                        if(hasDeletedSegments) DropdownMenuItem(text={Text("Восстановить удалённые")},enabled=editsEnabled,onClick={menuExpanded=false;onRestoreDeleted()})
                        if(hasUserEdits) DropdownMenuItem(text={Text("Сбросить исправления",color=ArchiveColors.Red)},enabled=editsEnabled,onClick={menuExpanded=false;onResetEdits()})
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text={Text("Информация о записи")},onClick={menuExpanded=false;onShowInfo()})
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top=20.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(speakerIds.isNotEmpty()) Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                    speakerIds.take(7).forEachIndexed {index,_ ->
                        Box(Modifier.size(9.dp).background(ArchiveColors.speaker(index),CircleShape))
                    }
                }
                if(hasTranscript) Text(
                    if(speakerIds.isEmpty()) "Говорящие не определены" else "${speakerIds.size} ${speakerCountLabel(speakerIds.size)}",
                    style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
            }
            OutlinedButton(onClick={openExportSheet()},enabled=availableExportFormats.isNotEmpty(),
                shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)) {
                ArchiveIcon(ArchiveSymbol.Upload,Modifier.size(16.dp),ArchiveColors.Gold)
                Spacer(Modifier.width(6.dp));Text("Экспорт",style=MaterialTheme.typography.labelMedium)
            }
        }
        HorizontalDivider()
    }

    if (exportSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = {
                exportSheetOpen = false
                selectedExportCategory = null
            },
            sheetState = exportSheetState
        ) {
            val category = selectedExportCategory
            val formats = availableExportFormats.filter {
                it.category == category
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                if (category == null) {
                    Text(
                        text = "Экспортировать",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(
                            start = 24.dp,
                            end = 24.dp,
                            bottom = 8.dp
                        )
                    )

                    ExportCategory.entries.forEach { exportCategory ->
                        val categoryAvailable = availableExportFormats.any {
                            it.category == exportCategory
                        }

                        ListItem(
                            colors = ListItemDefaults.colors(containerColor=ArchiveColors.Surface),
                            leadingContent = { ArchiveIcon(if(exportCategory==ExportCategory.TEXT) ArchiveSymbol.Document else ArchiveSymbol.Wave,color=ArchiveColors.Gold) },
                            trailingContent = { ArchiveIcon(ArchiveSymbol.Next,color=ArchiveColors.Muted) },
                            headlineContent = {
                                Text(exportCategory.title)
                            },
                            supportingContent = {
                                Text(
                                    if (categoryAvailable) {
                                        exportCategory.description
                                    } else {
                                        "Недоступно для этой записи"
                                    }
                                )
                            },
                            modifier = if (categoryAvailable) {
                                Modifier.clickable {
                                    selectedExportCategory = exportCategory
                                }
                            } else {
                                Modifier
                            }
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                end = 24.dp,
                                bottom = 8.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                selectedExportCategory = null
                            }
                        ) {
                            Text("‹")
                        }
                        Text(
                            text = category.title,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    if (formats.isEmpty()) {
                        Text(
                            text = "Нет доступных форматов",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = 24.dp,
                                end = 24.dp,
                                bottom = 16.dp
                            )
                        )
                    } else {
                        formats.forEach { format ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor=ArchiveColors.Surface),
                                leadingContent = { ArchiveIcon(if(category==ExportCategory.TEXT) ArchiveSymbol.Document else ArchiveSymbol.Wave,color=ArchiveColors.Gold) },
                                trailingContent = { ArchiveIcon(ArchiveSymbol.Next,color=ArchiveColors.Muted) },
                                headlineContent = {
                                    Text(format.title)
                                },
                                supportingContent = {
                                    Text(format.description)
                                },
                                modifier = Modifier.clickable {
                                    selectExportFormat(format)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (renameDialogOpen) {

        AlertDialog(
            onDismissRequest = {
                renameDialogOpen = false
            },

            title = {
                Text("Переименовать запись")
            },

            text = {
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = {
                        if (it.length <= 80) {
                            draftTitle = it
                        }
                    },
                    label = {
                        Text("Название")
                    },
                    singleLine = true,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
            },

            confirmButton = {
                TextButton(
                    enabled = renameEnabled &&
                        draftTitle.isNotBlank() &&
                        draftTitle.length <= 80,
                    onClick = {
                        onRename(draftTitle)
                        renameDialogOpen = false
                    }
                ) {
                    Text("Сохранить")
                }
            },

            dismissButton = {
                TextButton(
                    onClick = {
                        renameDialogOpen = false
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}
private fun speakerCountLabel(count: Int): String = when {
    count % 100 in 11..14 -> "говорящих"
    count % 10 == 1 -> "говорящий"
    count % 10 in 2..4 -> "говорящих"
    else -> "говорящих"
}
