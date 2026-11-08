package com.team404bnf.app.ui.playback

import androidx.compose.foundation.layout.*

import androidx.compose.material3.*

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

import com.team404bnf.app.transcription.TranscriptSegment

internal sealed interface TranscriptEditRequest {

    data class SpeakerChange(
        val segment: TranscriptSegment
    ) : TranscriptEditRequest

    data class SpeakerRename(
        val speakerId: String
    ) : TranscriptEditRequest
}

@Composable
internal fun TranscriptEditDialog(
    request: TranscriptEditRequest,
    speakerLabels: Map<String, String>,
    onDismiss: () -> Unit,
    onSpeakerChange: (String, String?) -> Unit,
    onSpeakerRename: (String, String) -> Unit,
    enabled: Boolean = true
) {
    when (request) {

        is TranscriptEditRequest.SpeakerChange -> {

            SpeakerPickerDialog(
                speakerLabels = speakerLabels,
                selectedSpeakerId = request.segment.speakerId,
                onChoose = { speakerId ->
                    onSpeakerChange(
                        request.segment.id,
                        speakerId
                    )
                },
                onDismiss = onDismiss,
                enabled = enabled
            )
        }

        is TranscriptEditRequest.SpeakerRename -> {

            var value by remember(request.speakerId) {
                mutableStateOf(
                    speakerLabels[request.speakerId].orEmpty()
                )
            }

            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Имя участника") },
                text = {
                    Column {
                        Text(
                            "Имя изменится во всех репликах этого говорящего.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = value,
                            enabled = enabled,
                            onValueChange = { value = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Отображаемое имя") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = enabled && value.isNotBlank() &&
                            value.trim().length <= 60,
                        onClick = {
                            onSpeakerRename(
                                request.speakerId,
                                value
                            )
                            onDismiss()
                        }
                    ) {
                        Text("Сохранить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}
