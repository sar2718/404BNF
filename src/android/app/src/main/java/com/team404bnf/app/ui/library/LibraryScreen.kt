package com.team404bnf.app.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.team404bnf.app.ui.components.*
import com.team404bnf.app.ui.theme.*
import com.team404bnf.app.data.repository.ImportFileLimit
import com.team404bnf.app.data.repository.ImportSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

data class RecordingPreview(
    val id: String,
    val title: String,
    val date: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val status: String,
    val hasTranscript: Boolean,
    val processing: Boolean,
    val hasAudio: Boolean
)

@Composable
fun LibraryScreen(
    savedRecordings: List<RecordingPreview>,
    deleting: Boolean,
    deleteError: String?,
    onDeleteRecordings: (Set<String>) -> Unit,
    onNewRecording: () -> Unit,
    onSavedRecordingOpen: (String) -> Unit,
    importing: Boolean,
    importError: String?,
    onImportAudio: (Uri) -> Unit,
    onManageModels: () -> Unit = {}
) {
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) onImportAudio(uri)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var sortOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val visibleRecordings = remember(savedRecordings, query, newestFirst) {
        val filtered = savedRecordings.filter { it.title.contains(query.trim(), ignoreCase = true) }
        if(newestFirst) filtered.sortedByDescending { it.createdAtEpochMs } else filtered.sortedBy { it.createdAtEpochMs }
    }
    val canDeleteSelection = savedRecordings.none { it.id in selectedIds && it.processing }
    LaunchedEffect(savedRecordings) { selectedIds = selectedIds.intersect(savedRecordings.map { it.id }.toSet()) }
    BackHandler(selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    fun toggle(id: String) { selectedIds = if(id in selectedIds) selectedIds - id else selectedIds + id }

    if(pendingDeleteIds.isNotEmpty()) AlertDialog(
        onDismissRequest = { if(!deleting) pendingDeleteIds = emptySet() },
        title = { Text(if(pendingDeleteIds.size==1) "Удалить запись?" else "Удалить записи?") },
        text = { Text("Будет удалено записей: ${pendingDeleteIds.size}. Аудиофайлы и расшифровки также будут удалены. Это действие нельзя отменить.") },
        confirmButton = { TextButton(enabled = !deleting && savedRecordings.none { it.id in pendingDeleteIds && it.processing }, onClick = {
            onDeleteRecordings(pendingDeleteIds); pendingDeleteIds = emptySet(); selectedIds = emptySet()
        }) { Text("Удалить",color=ArchiveColors.Red) } },
        dismissButton = { TextButton(onClick = { pendingDeleteIds = emptySet() }) { Text("Отмена") } }
    )
    if(settingsOpen) ArchiveSettingsDialog(onDismiss={ settingsOpen = false }, onManageModels={
        settingsOpen = false; onManageModels()
    })

    Scaffold(containerColor = ArchiveColors.Background, bottomBar = {
        Box(Modifier.fillMaxWidth().background(ArchiveColors.Background).navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Row(Modifier.widthIn(max=ArchiveDimens.ContentMaxWidth).fillMaxWidth().padding(ArchiveDimens.PagePadding),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ArchiveButton(if(importing) "Импорт…" else "Импорт", { audioPicker.launch(arrayOf("audio/*")) },
                    Modifier.weight(.85f), enabled = !importing && !deleting)
                ArchiveButton("Записать",onNewRecording,Modifier.weight(1.15f),accent=true)
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
            Column(Modifier.widthIn(max=ArchiveDimens.ContentMaxWidth).fillMaxSize().imePadding().padding(horizontal=ArchiveDimens.PagePadding)) {
                Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=18.dp), verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        ArchiveEyebrow("404BNF / АУДИОАРХИВ")
                        Spacer(Modifier.height(4.dp))
                        Text("Записи",style=MaterialTheme.typography.headlineLarge)
                    }
                    ArchiveIconButton(ArchiveSymbol.Settings,"Настройки",{ settingsOpen = true })
                }
                OutlinedTextField(value=query,onValueChange={ query=it },singleLine=true,
                    placeholder={ Text("Поиск записей",style=MaterialTheme.typography.bodyMedium) },
                    trailingIcon={ ArchiveIcon(ArchiveSymbol.Search,color=ArchiveColors.Muted) },
                    shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth(),
                    textStyle=MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(selectedIds.isNotEmpty()) {
                        TextButton(onClick={selectedIds=emptySet()}) { Text("Отмена") }
                        Text("${selectedIds.size} выбрано",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                        TextButton(enabled=!deleting && canDeleteSelection,onClick={ pendingDeleteIds=selectedIds }) { Text("Удалить",color=ArchiveColors.Red) }
                    } else {
                        Text("ВСЕ ЗАПИСИ",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=ArchiveColors.Muted)
                        Box {
                            TextButton(onClick={sortOpen=true}) {
                                Text(if(newestFirst) "Сначала новые" else "Сначала старые",style=MaterialTheme.typography.labelMedium,color=ArchiveColors.Text)
                                Spacer(Modifier.width(6.dp)); ArchiveIcon(ArchiveSymbol.Down,Modifier.size(12.dp),ArchiveColors.Muted)
                            }
                            DropdownMenu(expanded=sortOpen,onDismissRequest={sortOpen=false}) {
                                listOf(true to "Сначала новые",false to "Сначала старые").forEach { (order,label) ->
                                    DropdownMenuItem(text={Text(label,color=if(newestFirst==order) ArchiveColors.Gold else ArchiveColors.Text)},onClick={newestFirst=order;sortOpen=false})
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                if(importing || deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
                listOfNotNull(importError,deleteError).forEach { Text(it,color=ArchiveColors.Red,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=8.dp)) }
                if(visibleRecordings.isEmpty()) {
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(vertical=64.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        ArchiveIcon(ArchiveSymbol.Wave,Modifier.size(40.dp),ArchiveColors.Gold)
                        Spacer(Modifier.height(20.dp))
                        Text(if(query.isBlank()) "Пока нет записей" else "Ничего не найдено",style=MaterialTheme.typography.titleLarge,textAlign=TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Text(if(query.isBlank()) "Запишите разговор или импортируйте аудиофайл." else "Попробуйте другое название или очистите строку поиска.",
                            style=MaterialTheme.typography.bodyMedium,color=ArchiveColors.Muted,textAlign=TextAlign.Center)
                        if(query.isNotBlank()) ArchiveButton("Очистить поиск",{query=""},Modifier.padding(top=24.dp))
                    }
                } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=12.dp)) {
                    items(visibleRecordings,key={it.id}) { recording ->
                        ArchiveRecordingRow(recording,recording.id in selectedIds, !deleting,
                            onOpen={ if(selectedIds.isNotEmpty()) toggle(recording.id) else onSavedRecordingOpen(recording.id) },
                            onSelect={toggle(recording.id)},onDelete={pendingDeleteIds=setOf(recording.id)})
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveRecordingRow(recording: RecordingPreview, selected: Boolean, enabled: Boolean, onOpen: () -> Unit, onSelect: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember(recording.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(if(selected) ArchiveColors.Selected else ArchiveColors.Background)
        .combinedClickable(enabled=enabled,onClick=onOpen,onLongClick=onSelect).padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(ArchiveColors.Control,RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) {
            ArchiveIcon(if(selected) ArchiveSymbol.Check else ArchiveSymbol.Wave,color=ArchiveColors.Gold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(recording.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text("${recording.date} · ${archiveTime(recording.durationMs)}",style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
            Spacer(Modifier.height(4.dp))
            Text(if(recording.hasAudio) recording.status else "Аудиофайл отсутствует",style=MaterialTheme.typography.labelSmall,
                color=if(recording.hasTranscript || recording.processing) ArchiveColors.Gold else if(recording.status=="Ошибка обработки") ArchiveColors.Red else ArchiveColors.Muted)
        }
        Box {
            ArchiveIconButton(ArchiveSymbol.More,"Действия с записью",{menuOpen=true},enabled)
            DropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false}) {
                DropdownMenuItem(text={Text("Выбрать")},onClick={menuOpen=false;onSelect()})
                DropdownMenuItem(text={Text(if(recording.processing) "Удаление недоступно при обработке" else "Удалить",color=ArchiveColors.Red)},
                    enabled=!recording.processing,onClick={menuOpen=false;onDelete()})
            }
        }
    }
}

@Composable
private fun ArchiveSettingsDialog(onDismiss: () -> Unit, onManageModels: () -> Unit) {
    val context=LocalContext.current
    val models=com.team404bnf.app.models.RecognitionModels.get(context)
    val modelState by models.state.collectAsState()
    val settings = remember(context) { ImportSettings(context) }
    val scope = rememberCoroutineScope()
    var limitMenu by remember { mutableStateOf(false) }
    var manualLimitOpen by rememberSaveable { mutableStateOf(false) }
    var manualLimitText by rememberSaveable { mutableStateOf("") }
    val manualLimit = remember(manualLimitText) { ImportFileLimit.fromMegabytes(manualLimitText) }
    val manualLimitFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var savingLimit by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    val importLimit by produceState<ImportFileLimit?>(null, settings) {
        try {
            settings.limit.collect { value = it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            settingsError = "Не удалось прочитать настройки импорта"
        }
    }
    fun saveLimit(limit: ImportFileLimit) {
        if (savingLimit) return
        limitMenu = false
        savingLimit = true
        settingsError = null
        scope.launch {
            try {
                settings.setLimit(limit)
                manualLimitOpen = false
                keyboard?.hide()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                settingsError = "Не удалось сохранить лимит импорта"
            } finally {
                savingLimit = false
            }
        }
    }
    LaunchedEffect(manualLimitOpen) {
        if (manualLimitOpen) manualLimitFocus.requestFocus()
    }
    AlertDialog(onDismissRequest={if(!savingLimit) onDismiss()},title={Text("404BNF / Настройки")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Версия ${context.packageManager.getPackageInfo(context.packageName,0).versionName}")
            Text("Аудиозаписи и расшифровки хранятся на устройстве. Аудио обрабатывается локально.")
            HorizontalDivider()
            Text("Максимальный размер аудиофайла",style=MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick={limitMenu=true},enabled=importLimit!=null && !savingLimit,
                    modifier=Modifier.fillMaxWidth()) {
                    Text(if(savingLimit) "Сохранение…" else importLimit?.label ?: "Загрузка настроек…",
                        modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center)
                    Spacer(Modifier.width(12.dp))
                    ArchiveIcon(ArchiveSymbol.Down,Modifier.size(14.dp),ArchiveColors.Gold)
                }
                DropdownMenu(expanded=limitMenu,onDismissRequest={limitMenu=false}) {
                    ImportFileLimit.presets.forEach { limit ->
                        DropdownMenuItem(text={Text(limit.label,
                            color=if(importLimit==limit) ArchiveColors.Gold else ArchiveColors.Text)},
                            enabled=!savingLimit,onClick={saveLimit(limit)})
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text={Text("Без ограничений",color=if(importLimit?.isUnlimited==true) ArchiveColors.Gold else ArchiveColors.Text)},
                        enabled=!savingLimit,onClick={saveLimit(ImportFileLimit.UNLIMITED)})
                    DropdownMenuItem(
                        text={Text("Указать вручную…",color=if(importLimit!=null &&
                            importLimit !in ImportFileLimit.presets && importLimit?.isUnlimited==false) ArchiveColors.Gold else ArchiveColors.Text)},
                        enabled=!savingLimit,onClick={
                            limitMenu=false
                            manualLimitText=importLimit?.megabytes?.toString().orEmpty()
                            manualLimitOpen=true
                        })
                }
            }
            if (manualLimitOpen) {
                OutlinedTextField(
                    value = manualLimitText,
                    onValueChange = { manualLimitText = it },
                    enabled = !savingLimit,
                    modifier = Modifier.fillMaxWidth().focusRequester(manualLimitFocus),
                    singleLine = true,
                    label = { Text("Свой размер") },
                    suffix = { Text("МБ") },
                    trailingIcon = {
                        ArchiveIconButton(ArchiveSymbol.Check, "Сохранить размер",
                            { manualLimit?.let(::saveLimit) }, !savingLimit && manualLimit != null)
                    },
                    isError = manualLimitText.isNotEmpty() && manualLimit == null,
                    supportingText = if (manualLimitText.isNotEmpty() && manualLimit == null) {
                        { Text("Введите целое число МБ больше 0") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { manualLimit?.let(::saveLimit) })
                )
            }
            Text("Применяется к новым импортам. Сохранённые записи не изменяются.",
                style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
            settingsError?.let { Text(it,color=ArchiveColors.Red,style=MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text(if(modelState.ready) "Офлайн-модели установлены" else "Офлайн-модели требуют подготовки",color=ArchiveColors.Gold)
            ArchiveButton("Модели распознавания",onManageModels,Modifier.fillMaxWidth(),enabled=!savingLimit)
        }
    },confirmButton={ TextButton(onClick=onDismiss,enabled=!savingLimit) {Text("Закрыть")} })
}
