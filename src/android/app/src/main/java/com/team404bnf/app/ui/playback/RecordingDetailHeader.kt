package com.team404bnf.app.ui.playback

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.team404bnf.app.R

@Composable
fun RecordingDetailHeader(
    title: String,
    renameEnabled: Boolean,
    hasTranscript: Boolean,
    hasDeletedSegments: Boolean,
    hasUserEdits: Boolean,
    canExportTranscript: Boolean,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onShowInfo: () -> Unit,
    onRestoreDeleted: () -> Unit,
    onResetEdits: () -> Unit,
    onExportTranscript: () -> Unit,
    onExportWav: () -> Unit,
    wavExportInProgress: Boolean,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = "Назад",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Box {

            IconButton(
                onClick = {
                    menuExpanded = true
                },
                modifier = Modifier.semantics {
                    contentDescription = "Меню чата"
                }
            ) {
                Text(
                    text = "⋮",
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = {
                    menuExpanded = false
                }
            ) {

                DropdownMenuItem(
                    text = {
                        Text("Переименовать чат")
                    },
                    enabled = renameEnabled,
                    onClick = {
                        menuExpanded = false
                        draftTitle = title
                        renameDialogOpen = true
                    }
                )

                if (hasTranscript && hasDeletedSegments) {

                    DropdownMenuItem(
                        text = {
                            Text("Восстановить удалённые")
                        },

                        onClick = {
                            menuExpanded = false
                            onRestoreDeleted()
                        }
                    )
                }

                if (hasTranscript && hasUserEdits) {

                    DropdownMenuItem(
                        text = {
                            Text("Сбросить исправления")
                        },

                        onClick = {
                            menuExpanded = false
                            onResetEdits()
                        }
                    )
                }

                if (canExportTranscript) {

                    DropdownMenuItem(
                        text = {
                            Text("Экспорт расшифровки")
                        },
                        onClick = {
                            menuExpanded = false
                            onExportTranscript()
                        }
                    )
                }

                DropdownMenuItem(
                    text = {
                        Text("Экспорт аудио (.wav)")
                    },
                    enabled = !wavExportInProgress,
                    onClick = {
                        menuExpanded = false
                        onExportWav()
                    }
                )

                HorizontalDivider()

                DropdownMenuItem(
                    text = {
                        Text("Информация о записи")
                    },

                    onClick = {
                        menuExpanded = false
                        onShowInfo()
                    }
                )
            }
        }
    }

    if (renameDialogOpen) {

        AlertDialog(
            onDismissRequest = {
                renameDialogOpen = false
            },

            title = {
                Text("Переименовать чат")
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