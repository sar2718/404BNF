package com.team404bnf.app.ui.playback

import androidx.compose.foundation.layout.*

import androidx.compose.material3.*

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

import com.team404bnf.app.transcription.TranscriptSegment

internal sealed interface TranscriptEditRequest {

    data class TextEdit(
        val segment: TranscriptSegment
    ) : TranscriptEditRequest

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
    onTextSave: (String, String) -> Unit,
    onSpeakerChange: (String, String?) -> Unit,
    onSpeakerRename: (String, String) -> Unit
) {
    when (request) {

        is TranscriptEditRequest.TextEdit -> {

            var value by remember(request.segment.id) {
                mutableStateOf(request.segment.text)
            }

            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Изменить текст") },
                text = {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = value.isNotBlank() &&
                            value.length <= 5000,
                        onClick = {
                            onTextSave(
                                request.segment.id,
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
                onDismiss = onDismiss
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
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Отображаемое имя") }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = value.isNotBlank() &&
                            value.length <= 60,
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