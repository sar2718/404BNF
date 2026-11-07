package com.team404bnf.app.ui.transcript

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Участник разговора
data class DemoSpeaker(
    val id: String,
    val name: String
)

// Реплика разговора
data class DemoUtterance(
    val id: String,
    val speakerId: String?,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

// Демонстрационная расшифровка
data class DemoTranscript(
    val title: String,
    val speakers: List<DemoSpeaker>,
    val utterances: List<DemoUtterance>
)

// Тестовые данные для разных записей
private fun getDemoTranscript(recordingId: String): DemoTranscript {

    return when (recordingId) {

        "1" -> DemoTranscript(
            title = "Запись 03.11, 14:35",

            speakers = listOf(
                DemoSpeaker("spk1", "Участник 1"),
                DemoSpeaker("spk2", "Участник 2")
            ),

            utterances = listOf(
                DemoUtterance(
                    "u1", "spk1", 3000, 7000,
                    "Привет! Давай обсудим наш проект."
                ),
                DemoUtterance(
                    "u2", "spk2", 8000, 12000,
                    "Конечно. С чего начнём?"
                ),
                DemoUtterance(
                    "u3", "spk1", 13000, 19000,
                    "Для начала нужно закончить Android-приложение."
                ),
                DemoUtterance(
                    "u4", "spk2", 20000, 26000,
                    "Хорошо, тогда займёмся интерфейсом и записью звука."
                )
            )
        )

        "2" -> DemoTranscript(
            title = "Запись 02.11, 18:20",

            speakers = listOf(
                DemoSpeaker("spk1", "Участник 1"),
                DemoSpeaker("spk2", "Участник 2"),
                DemoSpeaker("spk3", "Участник 3")
            ),

            utterances = listOf(
                DemoUtterance(
                    "u1", "spk1", 1000, 4000,
                    "Предлагаю распределить задачи."
                ),
                DemoUtterance(
                    "u2", "spk2", 5000, 9000,
                    "Я могу заняться нейросетевой частью."
                ),
                DemoUtterance(
                    "u3", "spk3", 10000, 15000,
                    "А я помогу с интеграцией и тестированием."
                ),
                DemoUtterance(
                    "u4", "spk1", 16000, 20000,
                    "Отлично, тогда начинаем."
                )
            )
        )

        else -> DemoTranscript(
            title = "Запись 25.10, 11:10",

            speakers = listOf(
                DemoSpeaker("spk1", "Участник 1")
            ),

            utterances = listOf(
                DemoUtterance(
                    "u1", "spk1", 1000, 5000,
                    "Это тестовая запись одного участника."
                ),
                DemoUtterance(
                    "u2", "spk1", 6000, 11000,
                    "Все сообщения должны находиться слева."
                )
            )
        )
    }
}


// Основной экран
@Composable
fun TranscriptScreen(
    recordingId: String,
    onBack: () -> Unit
) {
    val transcript = remember(recordingId) {
        getDemoTranscript(recordingId)
    }

    var selectedUtteranceId by rememberSaveable(recordingId) {
        mutableStateOf<String?>(null)
    }

    val selectedUtterance = transcript.utterances.firstOrNull {
        it.id == selectedUtteranceId
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,

        bottomBar = {

            // Временная панель плеера
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {

                    Text(
                        text = if (selectedUtterance != null) {
                            "Выбрана реплика: ${formatTime(selectedUtterance.startMs)}"
                        } else {
                            "Выберите реплику"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        // Плеер пока отключён
                        Text(
                            text = "▶",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Slider(
                            value = 0f,
                            onValueChange = {},
                            enabled = false,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "Аудиофайл пока не подключён",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            // Верхняя панель
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                TextButton(onClick = onBack) {
                    Text("← Назад")
                }

                Text(
                    text = transcript.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider()

            Text(
                text = "Демонстрационная расшифровка",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                )
            )

            // Список сообщений
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),

                contentPadding = PaddingValues(16.dp),

                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                items(
                    items = transcript.utterances,
                    key = { it.id }
                ) { utterance ->

                    TranscriptMessage(
                        utterance = utterance,
                        speakers = transcript.speakers,
                        selected = utterance.id == selectedUtteranceId,

                        onClick = {
                            selectedUtteranceId = utterance.id
                        }
                    )
                }
            }
        }
    }
}


// Одно сообщение
@Composable
private fun TranscriptMessage(
    utterance: DemoUtterance,
    speakers: List<DemoSpeaker>,
    selected: Boolean,
    onClick: () -> Unit
) {

    val speakerIndex = speakers.indexOfFirst {
        it.id == utterance.speakerId
    }

    val speaker = speakers.getOrNull(speakerIndex)

    // Стабильные цвета участников
    val speakerColors = listOf(
        Color(0xFF8AB4F8),
        Color(0xFFE9A1CF),
        Color(0xFF6AD5BC),
        Color(0xFFF7C87C),
        Color(0xFFB5A0F2)
    )

    val speakerColor = if (speakerIndex >= 0) {
        speakerColors[speakerIndex % speakerColors.size]
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // При двух участниках второй справа
    val alignRight = speakers.size == 2 &&
            speakerIndex == 1

    Row(
        modifier = Modifier.fillMaxWidth(),

        horizontalArrangement = if (alignRight) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {

        Surface(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clickable(onClick = onClick),

            shape = RoundedCornerShape(16.dp),

            color = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
            } else {
                speakerColor.copy(alpha = 0.14f)
            }

        ) {

            Column(
                modifier = Modifier.padding(12.dp)
            ) {

                Text(
                    text = speaker?.name ?: "Не определён",
                    style = MaterialTheme.typography.labelMedium,
                    color = speakerColor
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = utterance.text,
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${formatTime(utterance.startMs)}–${formatTime(utterance.endMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


// Миллисекунды → минуты и секунды
private fun formatTime(milliseconds: Long): String {

    val totalSeconds = milliseconds / 1000

    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    return "%02d:%02d".format(minutes, seconds)
}