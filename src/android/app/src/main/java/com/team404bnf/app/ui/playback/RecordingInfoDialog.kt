package com.team404bnf.app.ui.playback

import android.text.format.Formatter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import com.team404bnf.app.playback.RecordingDetailsUiState

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingInfoDialog(
    state: RecordingDetailsUiState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,

        title = {
            Text("Информация о записи")
        },

        text = {
            when {
                state.loading -> {
                    CircularProgressIndicator()
                }

                state.error != null -> {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                state.details != null -> {
                    val details = state.details

                    val createdAt = SimpleDateFormat(
                        "dd.MM.yyyy, HH:mm",
                        Locale.getDefault()
                    ).format(
                        Date(details.createdAtEpochMs)
                    )

                    val isImport =
                        details.sourceType == "IMPORT"

                    val transcriptBytes =
                        state.transcriptBytes ?: 0L

                    val totalBytes =
                        details.recordingFilesBytes +
                            transcriptBytes

                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp)
                    ) {

                        InfoLine(
                            if (isImport) "Добавлено" else "Создано",
                            createdAt
                        )

                        InfoLine(
                            "Длительность",
                            formatInfoDuration(details.durationMs)
                        )

                        InfoLine(
                            "Источник",
                            if (isImport) {
                                "Импорт"
                            } else {
                                "Диктофон"
                            }
                        )

                        InfoLine(
                            "Формат аудио",
                            details.audioExtension
                                ?.uppercase(Locale.ROOT)
                                ?: "Недоступен"
                        )

                        InfoLine(
                            "Размер аудио",
                            details.audioBytes?.let {
                                Formatter.formatShortFileSize(
                                    context,
                                    it
                                )
                            } ?: "Файл недоступен"
                        )

                        InfoLine(
                            "Расшифровка",
                            if (state.transcriptBytes == null) {
                                "Нет"
                            } else {
                                Formatter.formatShortFileSize(
                                    context,
                                    transcriptBytes
                                )
                            }
                        )

                        HorizontalDivider()

                        InfoLine(
                            "Всего (примерно)",
                            Formatter.formatShortFileSize(
                                context,
                                totalBytes
                            )
                        )
                    }
                }
            }
        },

        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

@Composable
private fun InfoLine(
    label: String,
    value: String
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatInfoDuration(
    durationMs: Long
): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000

    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%02d:%02d:%02d".format(
            hours,
            minutes,
            seconds
        )
    } else {
        "%02d:%02d".format(
            minutes,
            seconds
        )
    }
}