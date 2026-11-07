package com.team404bnf.app.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.*

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
internal fun SpeakerPickerDialog(
    speakerLabels: Map<String, String>,
    selectedSpeakerId: String?,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    val maxListHeight = minOf(
        340.dp,
        screenHeight * 0.45f
    )

    Dialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = colors.surface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 18.dp,
                    bottom = 8.dp
                )
            ) {

                // Заголовок
                Text(
                    text = "Сменить участника",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    modifier = Modifier.padding(
                        horizontal = 8.dp
                    )
                )

                Spacer(Modifier.height(14.dp))

                // Список участников.
                // Высота зависит от содержимого.
                // При переполнении появляется прокрутка.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight)
                        .verticalScroll(rememberScrollState()),

                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {

                    if (speakerLabels.isEmpty()) {

                        Text(
                            text = "Нет доступных участников",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = 12.dp,
                                vertical = 16.dp
                            )
                        )

                    } else {

                        speakerLabels.forEach { (id, name) ->

                            val isSelected = id == selectedSpeakerId

                            val backgroundColor = if (isSelected) {
                                colors.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                Color.Transparent
                            }

                            val textColor = if (isSelected) {
                                colors.onPrimaryContainer
                            } else {
                                colors.onSurface
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(backgroundColor)
                                    .selectable(
                                        selected = isSelected,
                                        role = Role.RadioButton,
                                        onClick = {
                                            onChoose(id)
                                            onDismiss()
                                        }
                                    )
                                    .defaultMinSize(minHeight = 48.dp)
                                    .padding(
                                        horizontal = 14.dp,
                                        vertical = 10.dp
                                    ),

                                verticalAlignment = Alignment.CenterVertically
                            ) {

                                Text(
                                    text = name.ifBlank { id },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    color = textColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (isSelected) {

                                    Spacer(Modifier.width(12.dp))

                                    Text(
                                        text = "✓",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.primary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Нижняя панель
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {

                    TextButton(
                        onClick = onDismiss
                    ) {
                        Text("Отмена")
                    }
                }
            }
        }
    }
}