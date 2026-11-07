package com.team404bnf.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class RecordingPreview(
    val id: String,
    val title: String,
    val subtitle: String,
    val date: String
)

private val demoRecordings = listOf(
    RecordingPreview(
        "1", "Запись 03.11, 14:35",
        "2 участника · 05:32", "14:35"
    ),
    RecordingPreview(
        "2", "Запись 02.11, 18:20",
        "3 участника · 12:48", "Пн"
    ),
    RecordingPreview(
        "3", "Запись 25.10, 11:10",
        "Обработка завершена · 08:15", "25/10"
    )
)

@Composable
fun LibraryScreen(
    savedRecordings: List<RecordingPreview>,
    deleting: Boolean,
    deleteError: String?,
    onDeleteRecordings: (Set<String>) -> Unit,
    onNewRecording: () -> Unit,
    onRecordingOpen: (String) -> Unit,
    onSavedRecordingOpen: (String) -> Unit,
    importing: Boolean,
    importError: String?,
    onImportAudio: (Uri) -> Unit
) {
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->

        if (uri != null && !importing) {
            onImportAudio(uri)
        }
    }

    var selectedIds by remember {
        mutableStateOf<Set<String>>(emptySet())
    }

    var pendingDeleteIds by remember {
        mutableStateOf<Set<String>>(emptySet())
    }

    val selectionMode = selectedIds.isNotEmpty()

    // Убираем из выделения уже удалённые записи
    LaunchedEffect(savedRecordings) {
        val existingIds = savedRecordings.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(existingIds)
    }

    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) {
            selectedIds - id
        } else {
            selectedIds + id
        }
    }

    // Общее подтверждение для обоих способов удаления
    if (pendingDeleteIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                if (!deleting) {
                    pendingDeleteIds = emptySet()
                }
            },
            title = { Text("Удалить записи?") },
            text = {
                Text(
                    "Будет удалено записей: ${pendingDeleteIds.size}. " +
                            "Аудиофайлы и расшифровки также будут удалены. " +
                            "Это действие нельзя отменить."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        onDeleteRecordings(pendingDeleteIds)
                        pendingDeleteIds = emptySet()
                        selectedIds = emptySet()
                    }
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        pendingDeleteIds = emptySet()
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,

        floatingActionButton = {
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onNewRecording
                ) {
                    Text("＋ Новая запись")
                }
            }
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            // Панель выбора
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionMode) {
                    TextButton(
                        onClick = {
                            selectedIds = emptySet()
                        }
                    ) {
                        Text("Отмена")
                    }

                    Spacer(Modifier.weight(1f))

                    Text("${selectedIds.size} выбрано")

                    Spacer(Modifier.width(12.dp))

                    TextButton(
                        enabled = !deleting,
                        onClick = {
                            pendingDeleteIds = selectedIds
                        }
                    ) {
                        Text("Удалить")
                    }

                } else {
                    Text(
                        text = "404BNF",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f)
                    )

                    TextButton(
                        onClick = {
                            audioPicker.launch(arrayOf("audio/*"))
                        },
                        enabled = !importing && !deleting
                    ) {
                        Text("Импорт")
                    }
                }
            }

            HorizontalDivider()

            if (importing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Импортирование аудиофайла...",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (importError != null) {
                Text(
                    text = importError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                if (deleteError != null) {
                    item {
                        Text(
                            text = deleteError,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                items(
                    items = savedRecordings,
                    key = { "saved_${it.id}" }
                ) { recording ->

                    SwipeRecordingRow(
                        recording = recording,
                        selectionMode = selectionMode,
                        selected = recording.id in selectedIds,
                        enabled = !deleting,
                        onSelect = {
                            toggleSelection(recording.id)
                        },
                        onDelete = {
                            pendingDeleteIds = setOf(recording.id)
                        },
                        onOpen = {
                            onSavedRecordingOpen(recording.id)
                        }
                    )
                }

                item {
                    Text(
                        text = "Демонстрационные записи",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                items(
                    items = demoRecordings,
                    key = { "demo_${it.id}" }
                ) { recording ->

                    RecordingRow(
                        recording = recording,
                        enabled = !selectionMode,
                        onClick = {
                            onRecordingOpen(recording.id)
                        }
                    )
                }
            }
        }
    }
}

// Свайп влево открывает кнопку удаления
@Composable
private fun SwipeRecordingRow(
    recording: RecordingPreview,
    selectionMode: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit
) {
    var revealed by remember(recording.id) {
        mutableStateOf(false)
    }

    var dragDistance by remember {
        mutableFloatStateOf(0f)
    }

    val threshold = with(LocalDensity.current) {
        48.dp.toPx()
    }

    val offset by animateDpAsState(
        targetValue = if (revealed && !selectionMode) {
            (-90).dp
        } else {
            0.dp
        },
        label = "recording_swipe"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {

        if (!selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(90.dp)
                    .height(80.dp)
                    .background(Color(0xFFB3261E))
                    .clickable(enabled = enabled) {
                        onDelete()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Удалить",
                    color = Color.White
                )
            }
        }

        Box(
            modifier = Modifier
                .offset(x = offset)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(recording.id, selectionMode, enabled) {
                    if (!selectionMode && enabled) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragDistance = 0f
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                dragDistance += amount
                            },
                            onDragEnd = {
                                if (dragDistance < -threshold) {
                                    revealed = true
                                } else if (dragDistance > threshold) {
                                    revealed = false
                                }
                            }
                        )
                    }
                }
        ) {

            RecordingRow(
                recording = recording,
                enabled = enabled,
                selected = selected,
                selectionMode = selectionMode,

                onClick = {
                    if (selectionMode) {
                        onSelect()
                    } else if (revealed) {
                        revealed = false
                    } else {
                        onOpen()
                    }
                },

                onLongClick = onSelect
            )
        }
    }
}

// Общий элемент библиотеки
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(
    recording: RecordingPreview,
    enabled: Boolean = true,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.background
                }
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(16.dp),

        verticalAlignment = Alignment.CenterVertically
    ) {

        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() }
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("♫")
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = recording.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = recording.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(5.dp))

            Text(
                text = recording.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant
    )
}