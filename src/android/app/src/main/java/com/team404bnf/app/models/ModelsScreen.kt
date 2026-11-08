package com.team404bnf.app.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.team404bnf.app.transcription.TranscriptionTaskManager
import com.team404bnf.app.ui.components.*
import com.team404bnf.app.ui.theme.*

@Composable
fun ModelsScreen(models: RecognitionModels, onBack: () -> Unit) {
    val state by models.state.collectAsState()
    val pendingId by models.pendingRecordingId.collectAsState()
    val tasks by TranscriptionTaskManager.tasksFlow.collectAsState()
    val active = tasks.values.any { it.isProcessing }
    val waiting = tasks.values.filter { it.status == com.team404bnf.app.transcription.TranscriptionTaskStatus.WAITING_FOR_MODELS }
    LaunchedEffect(models) { models.refresh() }
    var confirmDelete by remember { mutableStateOf(false) }
    val pack = models.modelPackage
    BackHandler { onBack() }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить модели распознавания?") },
        text = { Text("Будет удалён комплект офлайн-распознавания (${modelSize(state.storageBytes)}). Аудиозаписи, расшифровки и ваши исправления сохранятся. Комплект можно скачать повторно.") },
        confirmButton = { TextButton(enabled = !state.busy && !active, onClick = {
            confirmDelete = false; models.delete()
        }) { Text("Удалить", color = ArchiveColors.Red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
    )
    Scaffold(containerColor = ArchiveColors.Background, bottomBar = {
        Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = ArchiveDimens.ContentMaxWidth).fillMaxWidth()
                .padding(ArchiveDimens.PagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.ready) ArchiveButton(
                    when {
                        state.phase == ModelPhase.DOWNLOADING -> "Загрузка моделей…"
                        state.phase == ModelPhase.CHECKING -> "Проверка моделей…"
                        state.phase == ModelPhase.REMOVING -> "Удаление моделей…"
                        state.error != null -> "Повторить загрузку"
                        else -> "Скачать модель"
                    }, models::download, Modifier.fillMaxWidth(), accent = true,
                    enabled = !state.busy && !active)
                if (waiting.isNotEmpty()) TextButton(onClick = { models.cancelPendingTranscriptions(waiting) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (waiting.size > 1) "Отменить все ожидающие расшифровки" else "Отменить ожидание расшифровки")
                }
                ArchiveButton("К записям", onBack, Modifier.fillMaxWidth())
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = ArchiveDimens.ContentMaxWidth).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(horizontal = ArchiveDimens.PagePadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArchiveIconButton(ArchiveSymbol.Back, "К записям", onBack)
                    Text("Модели распознавания", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(24.dp))
                ArchiveEyebrow("404BNF / ЛОКАЛЬНЫЙ АНАЛИЗ")
                Spacer(Modifier.height(8.dp))
                Text(if (state.ready) "Офлайн-распознавание готово" else "Подготовка офлайн-распознавания",
                    style = MaterialTheme.typography.headlineMedium)
                if (pendingId != null) Text(
                    if (state.ready) "Модели готовы. Запускаем ожидающие расшифровки…" else
                        "${TranscriptionTaskManager.MODELS_REQUIRED_MESSAGE}. Аудиозаписи сохранены. После загрузки обработка продолжится автоматически.",
                    modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                    color = ArchiveColors.Muted)
                Spacer(Modifier.height(24.dp))
                Text(pack.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Text(pack.description, style = MaterialTheme.typography.bodyMedium, color = ArchiveColors.Muted)
                Spacer(Modifier.height(14.dp))
                Text("Размер загрузки: около ${modelSize(pack.approximateDownloadBytes)}", color = ArchiveColors.Gold)
                if (state.storageBytes > 0) Text("На устройстве: ${modelSize(state.storageBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = ArchiveColors.Muted)
                Spacer(Modifier.height(24.dp))
                Text(when (state.phase) {
                    ModelPhase.READY -> "Установлено · Проверка целостности пройдена"
                    ModelPhase.MISSING -> "Комплект не готов к работе"
                    else -> state.stage ?: "Подготовка…"
                }, style = MaterialTheme.typography.bodyMedium)
                if (state.busy) {
                    Spacer(Modifier.height(12.dp))
                    val total = state.totalBytes
                    if (state.phase == ModelPhase.DOWNLOADING && total != null && total > 0) {
                        LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth())
                        Text("Текущий файл: ${modelSize(state.downloadedBytes)} из ${modelSize(total)}",
                            style = MaterialTheme.typography.bodySmall)
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.error?.let { Text(it, modifier = Modifier.padding(top = 12.dp), color = ArchiveColors.Red) }
                if (active) Text("Модели используются фоновой обработкой. Удаление и переустановка доступны после её завершения.",
                    modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = ArchiveColors.Muted)
                if (state.storageBytes > 0) TextButton(enabled = !state.busy && !active,
                    onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Удалить модели", color = ArchiveColors.Red)
                }
                Spacer(Modifier.height(20.dp))
                LocalStorageNote("Ваши записи остаются на устройстве")
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

private fun modelSize(bytes: Long): String = "%.0f МиБ".format(java.util.Locale.getDefault(), bytes / 1_048_576.0)
