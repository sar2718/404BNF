package com.team404bnf.app.ui.playback

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.team404bnf.app.transcription.TranscriptSegment
import com.team404bnf.app.ui.components.*
import com.team404bnf.app.ui.theme.*

@Composable
internal fun SegmentEditScreen(segment: TranscriptSegment,speakerLabels: Map<String,String>,saving: Boolean,error: String?,
    onDismiss: () -> Unit,onListen: () -> Unit,onPause: () -> Unit = {},isListening: Boolean = false,
    canListen: Boolean,onSave: (String,String?) -> Unit) {
    var draft by rememberSaveable(segment.id) {mutableStateOf(segment.text)}
    var speakerId by rememberSaveable(segment.id) {mutableStateOf(segment.speakerId)}
    var speakerMenu by remember {mutableStateOf(false)}
    var discardDialog by remember {mutableStateOf(false)}
    val dirty=draft!=segment.text || speakerId!=segment.speakerId
    val busy=saving
    DisposableEffect(segment.id) { onDispose { onPause() } }
    fun back() {if(!busy) {if(dirty) discardDialog=true else onDismiss()}}
    BackHandler {back()}
    if(discardDialog) AlertDialog(onDismissRequest={discardDialog=false},title={Text("Отменить изменения?")},
        text={Text("Несохранённые изменения этой реплики будут потеряны.")},
        confirmButton={TextButton(onClick=onDismiss){Text("Выйти без сохранения")}},
        dismissButton={TextButton(onClick={discardDialog=false}){Text("Остаться")}})
    Scaffold(containerColor=ArchiveColors.Background,bottomBar={
        Box(Modifier.fillMaxWidth().imePadding().navigationBarsPadding(),contentAlignment=Alignment.Center) {
            Row(Modifier.widthIn(max=ArchiveDimens.ContentMaxWidth).fillMaxWidth().padding(ArchiveDimens.PagePadding),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ArchiveButton("Отмена",{back()},Modifier.weight(1f),enabled=!busy)
                Button(onClick={onSave(draft,speakerId)},enabled=!busy && draft.isNotBlank() && draft.trim().length<=5000,
                    contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp),
                    shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f).heightIn(min=56.dp)) {Text(if(busy) "Сохранение…" else "Сохранить",textAlign=TextAlign.Center)}
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
            Column(Modifier.widthIn(max=ArchiveDimens.ContentMaxWidth).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=ArchiveDimens.PagePadding)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    ArchiveIconButton(ArchiveSymbol.Back,"Отменить редактирование",{back()},!busy)
                    Column(Modifier.weight(1f)) {
                        Text("Редактирование",style=MaterialTheme.typography.titleLarge)
                        Text("Реплика · ${segment.startMs?.let(::archiveTime) ?: "—"}–${segment.endMs?.let(::archiveTime) ?: "—"}",style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
                    }
                }
                Spacer(Modifier.height(20.dp));HorizontalDivider();Spacer(Modifier.height(24.dp))
                Text("ГОВОРЯЩИЙ",style=MaterialTheme.typography.labelSmall,color=ArchiveColors.Muted)
                Box(Modifier.padding(top=10.dp)) {
                    OutlinedButton(onClick={speakerMenu=true},enabled=!busy,shape=RoundedCornerShape(10.dp)) {
                        Text(speakerId?.let{speakerLabels[it] ?: segment.speakerLabel ?: it} ?: "Не определён",
                            modifier=Modifier.weight(1f,fill=false),color=ArchiveColors.Text)
                        Spacer(Modifier.width(20.dp));ArchiveIcon(ArchiveSymbol.Down,Modifier.size(14.dp),ArchiveColors.Gold)
                    }
                    DropdownMenu(expanded=speakerMenu,onDismissRequest={speakerMenu=false}) {
                        DropdownMenuItem(text={Text("Не определён")},onClick={speakerId=null;speakerMenu=false})
                        speakerLabels.forEach { (id,label) -> DropdownMenuItem(text={Text(label,color=ArchiveColors.speaker(speakerLabels.keys.indexOf(id)))},onClick={speakerId=id;speakerMenu=false}) }
                    }
                }
                Spacer(Modifier.height(24.dp));Text("ТЕКСТ РЕПЛИКИ",style=MaterialTheme.typography.labelSmall,color=ArchiveColors.Muted)
                OutlinedTextField(value=draft,onValueChange={if(it.length<=5000) draft=it},enabled=!busy,
                    modifier=Modifier.fillMaxWidth().padding(top=12.dp),minLines=6,maxLines=12,shape=RoundedCornerShape(12.dp),
                    colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=ArchiveColors.Gold,unfocusedBorderColor=ArchiveColors.Gold),
                    textStyle=MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp));LocalStorageNote("Оригинальная расшифровка сохраняется")
                error?.let{Text(it,color=ArchiveColors.Red,modifier=Modifier.padding(top=12.dp))}
                Spacer(Modifier.height(36.dp));Text("ПРОВЕРИТЬ НА СЛУХ",style=MaterialTheme.typography.labelSmall,color=ArchiveColors.Muted)
                ArchiveButton("Прослушать реплику",onListen,Modifier.fillMaxWidth().padding(top=12.dp),enabled=canListen && !busy,symbol=ArchiveSymbol.Play)
                if(isListening) TextButton(onClick=onPause,modifier=Modifier.align(Alignment.CenterHorizontally)) {Text("Пауза")}
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
