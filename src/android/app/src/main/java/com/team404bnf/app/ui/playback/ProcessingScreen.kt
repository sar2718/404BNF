package com.team404bnf.app.ui.playback

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.team404bnf.app.transcription.TranscriptionTask
import com.team404bnf.app.transcription.TranscriptionTaskStatus
import com.team404bnf.app.ui.components.*
import com.team404bnf.app.ui.theme.*

internal fun processingStage(status: TranscriptionTaskStatus?): Int = when(status) {
    TranscriptionTaskStatus.RUNNING_ASR -> 0
    TranscriptionTaskStatus.RUNNING_DIARIZATION -> 1
    TranscriptionTaskStatus.SAVING -> 2
    TranscriptionTaskStatus.COMPLETED -> 3
    else -> -1
}

@Composable
internal fun ProcessingScreen(title: String, durationMs: Long, task: TranscriptionTask?, audioSaved: Boolean,
    hasTranscript: Boolean, retryEnabled: Boolean, onBack: () -> Unit, onRetry: () -> Unit,
    onLibrary: () -> Unit = onBack, onCancel: () -> Unit = {}, modelsRequired: Boolean = false,
    onManageModels: () -> Unit = {}, startError: String? = null) {
    BackHandler {onLibrary()}
    val stage=processingStage(task?.status)
    val failed=task?.status in setOf(TranscriptionTaskStatus.FAILED,TranscriptionTaskStatus.CANCELLED,TranscriptionTaskStatus.INTERRUPTED) ||
        (task==null && startError!=null && !modelsRequired)
    Scaffold(containerColor=ArchiveColors.Background,bottomBar={
        Box(Modifier.fillMaxWidth().navigationBarsPadding(),contentAlignment=Alignment.Center) {
            Row(Modifier.widthIn(max=ArchiveDimens.ContentMaxWidth).fillMaxWidth()
                .padding(horizontal=ArchiveDimens.PagePadding,vertical=12.dp),
                horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                if(failed && !hasTranscript) ArchiveButton("Повторить",onRetry,
                    Modifier.weight(1f),enabled=retryEnabled)
                if(modelsRequired) ArchiveButton("Модели распознавания",onManageModels,Modifier.weight(1f),accent=true)
                ArchiveButton(if(hasTranscript) "Открыть расшифровку" else "Вернуться к записи",
                    onBack,Modifier.weight(1f))
            }
        }
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
            val compact = maxHeight < 720.dp
            Column(Modifier.widthIn(max=ArchiveDimens.ContentMaxWidth).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=ArchiveDimens.PagePadding)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    ArchiveIconButton(ArchiveSymbol.Back,"К записям",onLibrary)
                    Text("Обработка",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
                    if(task?.isActive==true) TextButton(onClick=onCancel) {Text("Отменить")}
                }
                Spacer(Modifier.height(if(compact) 12.dp else 24.dp));ArchiveEyebrow("404BNF / АУДИОАРХИВ")
                Spacer(Modifier.height(6.dp));Text(when {
                    hasTranscript -> "Расшифровка готова"
                    task?.status==TranscriptionTaskStatus.FAILED || (task==null && startError!=null) -> "Ошибка обработки"
                    task?.status==TranscriptionTaskStatus.INTERRUPTED -> "Обработка прервана"
                    task?.status==TranscriptionTaskStatus.CANCELLED -> "Обработка отменена"
                    else -> "Обработка записи"
                },style=MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().background(ArchiveColors.Surface,RoundedCornerShape(12.dp)).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    ArchiveIcon(ArchiveSymbol.Wave,Modifier.size(28.dp),ArchiveColors.Gold);Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {Text(title,style=MaterialTheme.typography.titleMedium);Text(archiveTime(durationMs),style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)}
                }
                Spacer(Modifier.height(16.dp))
                if(!compact) {
                    Text("АНАЛИЗ АУДИО",style=MaterialTheme.typography.labelSmall,color=ArchiveColors.Muted)
                    ArchiveWaveform(if(failed) ArchiveColors.Muted else ArchiveColors.Gold,Modifier.height(48.dp),emphasis=task?.isActive==true)
                }
                if(task?.isActive==true) LinearProgressIndicator(Modifier.fillMaxWidth(),color=ArchiveColors.Gold,trackColor=ArchiveColors.Divider)
                Spacer(Modifier.height(8.dp))
                Text(when(task?.status) {
                    TranscriptionTaskStatus.QUEUED -> "В очереди"
                    TranscriptionTaskStatus.PREPARING -> "Подготовка аудио и моделей…"
                    TranscriptionTaskStatus.COMPLETED -> if(hasTranscript) "Расшифровка сохранена" else "Открываем сохранённую расшифровку…"
                    TranscriptionTaskStatus.FAILED -> "Ошибка обработки"
                    TranscriptionTaskStatus.CANCELLED -> "Обработка отменена"
                    TranscriptionTaskStatus.INTERRUPTED -> "Обработка прервана"
                    null -> when {
                        modelsRequired -> "Для расшифровки необходимо скачать офлайн-модели."
                        startError!=null -> "Не удалось запустить обработку"
                        else -> "Подготовка запуска обработки…"
                    }
                    else -> task.stageMessage.ifBlank {"Идёт обработка…"}
                },color=if(failed) ArchiveColors.Red else ArchiveColors.Text)
                (task?.errorMessage ?: startError?.takeIf {task==null})?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Red,modifier=Modifier.padding(top=10.dp))}
                Spacer(Modifier.height(16.dp));Text("ЭТАПЫ ОБРАБОТКИ",style=MaterialTheme.typography.labelSmall,color=ArchiveColors.Muted)
                Spacer(Modifier.height(4.dp))
                listOf("Распознавание речи" to "Преобразуем аудио в текст", "Определение говорящих" to "Разделяем реплики по голосам", "Сохранение расшифровки" to "Объединяем текст и время").forEachIndexed { index,(name,hint) ->
                    Row(Modifier.fillMaxWidth().padding(vertical=if(compact) 8.dp else 12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("%02d".format(index+1),Modifier.width(32.dp),style=MaterialTheme.typography.labelSmall,color=if(stage>=index) ArchiveColors.Gold else ArchiveColors.Muted)
                        Column(Modifier.weight(1f)) {
                            Text(name,style=MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(2.dp));Text(hint,style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(24.dp),contentAlignment=Alignment.Center) {
                            if(stage>index) ArchiveIcon(ArchiveSymbol.Check,Modifier.size(18.dp),ArchiveColors.Gold)
                            else if(stage==index && task?.isActive==true) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                            else Box(Modifier.size(10.dp).background(ArchiveColors.Divider,CircleShape))
                        }
                    }
                    if(index<2) HorizontalDivider()
                }
                Spacer(Modifier.height(12.dp))
                if(audioSaved) LocalStorageNote("Исходная аудиозапись сохранена")
                if(task?.isActive==true) Text("Можно вернуться к записям. Обработка продолжится в фоне.",style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted,modifier=Modifier.padding(top=8.dp))
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
