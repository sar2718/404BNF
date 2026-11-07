package com.team404bnf.app.ui.playback

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.*

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.team404bnf.app.transcription.TranscriptSegment

@Composable
fun TranscriptRecoveryDialog(
    segments: List<TranscriptSegment>,
    editing: Boolean,
    onRestore: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,

        title = {
            Text("Удалённые сообщения")
        },

        text = {

            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),

                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {

                if (segments.isEmpty()) {

                    Text("Удалённых сообщений нет.")

                } else {

                    segments.forEach { segment ->

                        Row(
                            modifier = Modifier.fillMaxWidth(),

                            verticalAlignment =
                                Alignment.CenterVertically,

                            horizontalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {

                                Text(
                                    text = segment.text,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography
                                        .bodyMedium
                                )

                                val startMs = segment.startMs

                                if (startMs != null) {

                                    Spacer(Modifier.height(4.dp))

                                    Text(
                                        text = formatRecoveryTime(
                                            startMs
                                        ),
                                        style = MaterialTheme.typography
                                            .labelSmall,
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                    )
                                }
                            }

                            TextButton(
                                enabled = !editing,
                                onClick = {
                                    onRestore(segment.id)
                                }
                            ) {
                                Text("Вернуть")
                            }
                        }

                        HorizontalDivider()
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

private fun formatRecoveryTime(
    milliseconds: Long
): String {

    val seconds = milliseconds.coerceAtLeast(0) / 1000

    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remaining = seconds % 60

    return if (hours > 0) {
        "%02d:%02d:%02d".format(
            hours, minutes, remaining
        )
    } else {
        "%02d:%02d".format(
            minutes, remaining
        )
    }
}