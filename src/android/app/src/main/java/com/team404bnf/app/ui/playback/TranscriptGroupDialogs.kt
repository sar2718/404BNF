package com.team404bnf.app.ui.playback

import androidx.compose.material3.*

import androidx.compose.runtime.Composable

@Composable
internal fun GroupSpeakerDialog(
    group: TranscriptMessageGroup,
    speakerLabels: Map<String, String>,
    onConfirm: (List<String>, String?) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true
) {
    SpeakerPickerDialog(
        speakerLabels = speakerLabels,
        selectedSpeakerId = group.speakerId,
        onChoose = { speakerId ->
            onConfirm(
                group.segments.map { it.id },
                speakerId
            )
        },
        onDismiss = onDismiss,
        enabled = enabled
    )
}

@Composable
internal fun GroupDeleteDialog(
    group: TranscriptMessageGroup,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Удалить сообщение?")
        },
        text = {
            Text(
                "Фрагментов будет скрыто: ${group.segments.size}. " +
                    "Их можно восстановить через меню чата. " +
                    "Исходная расшифровка сохранится."
            )
        },
        confirmButton = {
            TextButton(
                enabled = enabled,
                onClick = {
                    onConfirm(
                        group.segments.map { it.id }
                    )
                    onDismiss()
                }
            ) {
                Text(
                    "Удалить",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}