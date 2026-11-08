package com.team404bnf.app.ui.playback

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

import com.team404bnf.app.transcription.TranscriptSegment
import com.team404bnf.app.ui.theme.ArchiveColors
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset

@Composable
fun TranscriptMessages(
    segments: List<TranscriptSegment>,
    speakerLabels: Map<String, String>,
    originalPositions: Map<String, Int>,
    splitBeforeSegmentIds: Set<String>,
    playbackPositionMs: Long,
    isPlaybackRunning: Boolean,
    onSegmentClick: (Long) -> Unit,
    onRequestTextEdit: (TranscriptSegment) -> Unit,
    onChangeSpeaker: (String, String?) -> Unit,
    onRenameSpeaker: (String, String) -> Unit,
    onDeleteSegment: (String) -> Unit,
    onChangeGroupSpeaker: (List<String>, String?) -> Unit,
    onDeleteGroup: (List<String>) -> Unit,
    onSplitGroup: (List<String>) -> Unit,
    onRestoreAutoMerge: (String) -> Unit,
    enabled: Boolean = true,
    editingEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var editRequest by remember {
        mutableStateOf<TranscriptEditRequest?>(null)
    }

    var deleteRequest by remember {
        mutableStateOf<TranscriptSegment?>(null)
    }

    var groupSpeakerRequest by remember {
        mutableStateOf<TranscriptMessageGroup?>(null)
    }

    var groupDeleteRequest by remember {
        mutableStateOf<TranscriptMessageGroup?>(null)
    }

    val messageGroups = remember(
        segments,
        originalPositions,
        splitBeforeSegmentIds
    ) {
        groupTranscriptSegments(
            segments = segments,
            originalPositions = originalPositions,
            splitBeforeSegmentIds = splitBeforeSegmentIds
        )
    }

    val restorableStarts = remember(segments, originalPositions, splitBeforeSegmentIds) {
        restorableMergeStarts(segments, originalPositions, splitBeforeSegmentIds)
    }

    val activeGroupIndices = remember(
        messageGroups,
        playbackPositionMs
    ) {
        findActiveTranscriptGroups(
            messageGroups,
            playbackPositionMs
        )
    }

    val listState = rememberLazyListState()

    val isUserDragging by
        listState.interactionSource.collectIsDraggedAsState()

    val followIndex = activeGroupIndices.maxOrNull()

    val latestFollowIndex by rememberUpdatedState(
        followIndex
    )



    val marginPx = with(LocalDensity.current) {
        12.dp.roundToPx()
    }

    val minimumVisiblePx = with(LocalDensity.current) {
        48.dp.roundToPx()
    }

    var autoFollowEnabled by remember {
        mutableStateOf(true)
    }

    var skipCurrentIndex by remember {
        mutableStateOf<Int?>(null)
    }

    var returnToTopRequest by remember {
        mutableStateOf(0)
    }

    var handledReturnRequest by remember {
        mutableStateOf(0)
    }

    LaunchedEffect(
        isUserDragging,
        isPlaybackRunning
    ) {
        if (isUserDragging) {

            autoFollowEnabled = false
            skipCurrentIndex = null

            return@LaunchedEffect
        }

        if (autoFollowEnabled || !isPlaybackRunning) {
            return@LaunchedEffect
        }

        // Сначала заканчивается инерция ручной прокрутки.
        snapshotFlow {
            listState.isScrollInProgress
        }.first { scrolling ->
            !scrolling
        }

        val current = latestFollowIndex

        if (
            current != null &&
            listState.isTranscriptItemVisibleEnough(
                current,
                minimumVisiblePx
            )
        ) {

            // Активная реплика видна.
            // Никакого ожидания и возврата наверх.
            skipCurrentIndex = current
            autoFollowEnabled = true

        } else {

            // Активная реплика ушла из поля зрения.
            delay(5000L)

            val latest = latestFollowIndex

            if (
                latest != null &&
                listState.isTranscriptItemVisibleEnough(
                    latest,
                    minimumVisiblePx
                )
            ) {

                // Пока ожидали, реплика снова оказалась
                // видимой. Ничего не перемещаем.
                skipCurrentIndex = latest

            } else if (latest != null) {

                // Запрашиваем отдельный возврат наверх.
                returnToTopRequest += 1
                skipCurrentIndex = null
            }

            autoFollowEnabled = true
        }
    }

    LaunchedEffect(
        followIndex,
        autoFollowEnabled,
        isPlaybackRunning,
        isUserDragging,
        returnToTopRequest,
        messageGroups
    ) {

        val target = followIndex ?: return@LaunchedEffect

        if (
            !autoFollowEnabled ||
            !isPlaybackRunning ||
            isUserDragging
        ) {
            return@LaunchedEffect
        }

        // Особый режим: пользователь уходил далеко.
        if (returnToTopRequest != handledReturnRequest) {

            handledReturnRequest = returnToTopRequest

            listState.animateScrollToItem(
                index = target,
                scrollOffset = 0
            )

            return@LaunchedEffect
        }

        // После небольшой ручной прокрутки
        // текущую реплику не трогаем.
        // Следующая активная реплика снова
        // включит обычное сопровождение.
        if (target == skipCurrentIndex) {
            return@LaunchedEffect
        }

        listState.followTranscriptEdge(
            targetIndex = target,
            marginPx = marginPx,
            minimumVisiblePx = minimumVisiblePx
        )
    }

    val speakerIds = remember(segments) {
        segments.mapNotNull {
            it.speakerId
        }.distinct()
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(
                items = messageGroups,
                key = { _, group -> group.id }
            ) { index, group ->

                val isActive = index in activeGroupIndices

                val segment = group.segments.first()

                val speakerIndex = speakerIds.indexOf(
                    segment.speakerId
                )

                val speakerColor = ArchiveColors.speaker(speakerIndex)
                val backgroundColor = ArchiveColors.Background
                val contentColor = ArchiveColors.Text

                val speakerName =
                    if (segment.speakerId == null) {
                        "Неизвестный участник"
                    } else {
                        speakerLabels[segment.speakerId]
                            ?: segment.speakerLabel
                            ?: "Участник ${speakerIndex + 1}"
                    }

                var menuExpanded by remember(group.id) {
                    mutableStateOf(false)
                }

                var selectedPartId by remember(group.id) {
                    mutableStateOf<String?>(null)
                }
                val selectedPart = group.segments.firstOrNull { it.id == selectedPartId }

                var choosingPart by remember(group.id) {
                    mutableStateOf(false)
                }

                val canPlaySegment =
                    group.startMs != null &&
                        group.endMs != null

                val displayedBackground by animateColorAsState(
                    targetValue = if (isActive) {
                        ArchiveColors.Active
                    } else {
                        backgroundColor
                    },
                    label = "transcriptBackground"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Box {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(displayedBackground)
                                .drawBehind {
                                    if(isActive) drawLine(ArchiveColors.Red, Offset(0f,8.dp.toPx()),Offset(0f,size.height-8.dp.toPx()),2.dp.toPx())
                                }
                                .combinedClickable(
                                    enabled = enabled || editingEnabled,
                                    onClickLabel = if (canPlaySegment) "Воспроизвести реплику" else null,
                                    onLongClickLabel = "Изменить реплику",
                                    onClick = {
                                        if (enabled && canPlaySegment) {
                                            group.startMs?.let {
                                                onSegmentClick(it)
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (editingEnabled) {
                                            choosingPart = false
                                            selectedPartId = null
                                            menuExpanded = true
                                        }
                                    }
                                )
                                .padding(12.dp)
                        ) {

                            Row(verticalAlignment=Alignment.CenterVertically) {
                                group.startMs?.let { start ->
                                    Text(formatSegmentTime(start),style=MaterialTheme.typography.labelSmall,color=speakerColor)
                                    Spacer(Modifier.width(24.dp))
                                }
                                Text(speakerName.uppercase(),style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.SemiBold,color=speakerColor)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(group.text,style=MaterialTheme.typography.bodyMedium,color=contentColor)

                            val overlapSegments = group.segments.filter {
                                it.overlap == true
                            }

                            val ambiguousSegments = group.segments.filter {
                                it.speakerAmbiguous == true
                            }

                            val markers = buildList {

                                if (overlapSegments.isNotEmpty()) {

                                    val label = if (group.segments.size == 1) {
                                        "Наложение речи"
                                    } else {
                                        val timed = overlapSegments.mapNotNull {
                                            if (it.startMs != null && it.endMs != null) {
                                                "${formatSegmentTime(it.startMs)}–${formatSegmentTime(it.endMs)}"
                                            } else {
                                                null
                                            }
                                        }

                                        if (timed.isNotEmpty()) {
                                            "Наложение речи: " + timed.joinToString(", ")
                                        } else {
                                            "Наложение речи"
                                        }
                                    }

                                    add(label)
                                }

                                if (ambiguousSegments.isNotEmpty()) {

                                    val label = if (group.segments.size == 1) {
                                        "Говорящий определён неточно"
                                    } else {
                                        val timed = ambiguousSegments.mapNotNull {
                                            if (it.startMs != null && it.endMs != null) {
                                                "${formatSegmentTime(it.startMs)}–${formatSegmentTime(it.endMs)}"
                                            } else {
                                                null
                                            }
                                        }

                                        if (timed.isNotEmpty()) {
                                            "Говорящий определён неточно: " + timed.joinToString(", ")
                                        } else {
                                            "Говорящий определён неточно"
                                        }
                                    }

                                    add(label)
                                }
                            }

                            markers.forEach { marker ->

                                Spacer(Modifier.height(4.dp))

                                Text(
                                    text = marker,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = {
                                menuExpanded = false
                                choosingPart = false
                                selectedPartId = null
                            },
                            modifier = Modifier.heightIn(max = 360.dp)
                        ) {

                            if (
                                group.segments.size > 1 &&
                                !choosingPart && selectedPart == null
                            ) {

                                DropdownMenuItem(
                                    enabled = editingEnabled,
                                    text = { Text("Сменить участника") },
                                    onClick = {
                                        menuExpanded = false
                                        groupSpeakerRequest = group
                                    }
                                )

                                DropdownMenuItem(
                                    enabled = editingEnabled,
                                    text = { Text("Разъединить сообщение") },
                                    onClick = {
                                        menuExpanded = false
                                        onSplitGroup(group.segments.map { it.id })
                                    }
                                )

                                DropdownMenuItem(
                                    enabled = editingEnabled,
                                    text = { Text("Редактировать фрагмент") },
                                    onClick = {
                                        choosingPart = true
                                        selectedPartId = null
                                    }
                                )

                                if (group.id in restorableStarts) {
                                    DropdownMenuItem(
                                        enabled = editingEnabled,
                                        text = { Text("Объединить с предыдущим") },
                                        onClick = {
                                            menuExpanded = false
                                            onRestoreAutoMerge(group.id)
                                        }
                                    )
                                }

                                HorizontalDivider()

                                DropdownMenuItem(
                                    enabled = editingEnabled,
                                    text = {
                                        Text(
                                            text = "Удалить",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        groupDeleteRequest = group
                                    }
                                )

                            } else if (
                                group.segments.size > 1 &&
                                selectedPart == null
                            ) {

                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                ) {

                                    // Неподвижный заголовок
                                    Text(
                                        text = "Выберите фрагмент",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp,
                                            vertical = 10.dp
                                        )
                                    )

                                    HorizontalDivider()

                                    // Прокручивается только список
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 264.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {

                                        group.segments.forEach { part ->

                                            DropdownMenuItem(
                                                enabled = editingEnabled,
                                                text = {

                                                    Column {

                                                        if (part.startMs != null) {
                                                            Text(
                                                                text = "${formatSegmentTime(part.startMs)}–${part.endMs?.let(::formatSegmentTime) ?: "—"}",
                                                                style = MaterialTheme.typography.labelSmall
                                                            )
                                                        }

                                                        Text(
                                                            text = part.text,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                },

                                                onClick = {
                                                    selectedPartId = part.id
                                                }
                                            )
                                        }
                                    }
                                }

                            } else {

                                val target = selectedPart ?: segment

                                if (group.segments.size > 1) {

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(40.dp)
                                            .clickable {
                                                selectedPartId = null
                                                choosingPart = true
                                            }
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "← К выбору фрагмента",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    HorizontalDivider()

                                    Column(
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp,
                                            vertical = 8.dp
                                        )
                                    ) {

                                        Text(
                                            text = "Выбранный фрагмент",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Spacer(Modifier.height(4.dp))

                                        Text(
                                            text = if (target.startMs != null) {
                                                "${formatSegmentTime(target.startMs)}–${target.endMs?.let(::formatSegmentTime) ?: "—"} · ${target.text}"
                                            } else {
                                                target.text
                                            },
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    HorizontalDivider()
                                }

                                DropdownMenuItem(
                                    enabled = editingEnabled,
                                    text = { Text("Изменить текст") },
                                    onClick = {
                                        menuExpanded = false
                                        selectedPartId = null

                                        onRequestTextEdit(target)
                                    }
                                )

                                DropdownMenuItem(
                                    enabled = editingEnabled,
                                    text = { Text("Сменить участника") },
                                    onClick = {
                                        menuExpanded = false
                                        selectedPartId = null

                                        editRequest =
                                            TranscriptEditRequest.SpeakerChange(target)
                                    }
                                )

                                target.speakerId?.let { speakerId ->

                                    DropdownMenuItem(
                                        enabled = editingEnabled,
                                        text = {
                                            Text("Переименовать участника")
                                        },

                                        onClick = {
                                            menuExpanded = false
                                            selectedPartId = null

                                            editRequest =
                                                TranscriptEditRequest.SpeakerRename(speakerId)
                                        }
                                    )
                                }

                                if (
                                    group.segments.size == 1 &&
                                    segment.id in restorableStarts
                                ) {
                                    DropdownMenuItem(
                                        enabled = editingEnabled,
                                        text = {
                                            Text("Объединить с предыдущим")
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onRestoreAutoMerge(segment.id)
                                        }
                                    )
                                }

                                HorizontalDivider()

                                DropdownMenuItem(
                                    enabled = editingEnabled,
                                    text = {
                                        Text(
                                            text = "Удалить",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },

                                    onClick = {
                                        menuExpanded = false
                                        selectedPartId = null
                                        deleteRequest = target
                                    }
                                )
                            }
                        }
                    }
                }
                if(!isActive) HorizontalDivider(color=ArchiveColors.Divider,modifier=Modifier.padding(top=8.dp))
            }
        }
    }

    editRequest?.let { request ->

        TranscriptEditDialog(
            request = request,
            speakerLabels = speakerLabels,
            onDismiss = {
                editRequest = null
            },
            onSpeakerChange = onChangeSpeaker,
            onSpeakerRename = onRenameSpeaker,
            enabled = editingEnabled
        )
    }

    deleteRequest?.let { segment ->

        AlertDialog(
            onDismissRequest = {
                deleteRequest = null
            },
            title = {
                Text("Удалить сообщение?")
            },
            text = {
                Text(
                    "Сообщение исчезнет из расшифровки. " +
                        "Исходный результат модели сохранится."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editingEnabled,
                    onClick = {
                        onDeleteSegment(segment.id)
                        deleteRequest = null
                    }
                ) {
                    Text(
                        "Удалить",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deleteRequest = null
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    groupSpeakerRequest?.let { group ->
        GroupSpeakerDialog(
            group = group,
            speakerLabels = speakerLabels,
            onConfirm = onChangeGroupSpeaker,
            enabled = editingEnabled,
            onDismiss = {
                groupSpeakerRequest = null
            }
        )
    }

    groupDeleteRequest?.let { group ->
        GroupDeleteDialog(
            group = group,
            onConfirm = onDeleteGroup,
            enabled = editingEnabled,
            onDismiss = {
                groupDeleteRequest = null
            }
        )
    }
}

private fun LazyListState.isTranscriptItemVisibleEnough(
    index: Int,
    minimumVisiblePx: Int
): Boolean {
    val layout = layoutInfo

    val item = layout.visibleItemsInfo
        .firstOrNull { it.index == index }
        ?: return false

    val visibleStart = maxOf(
        item.offset,
        layout.viewportStartOffset
    )

    val visibleEnd = minOf(
        item.offset + item.size,
        layout.viewportEndOffset
    )

    val overlap = (visibleEnd - visibleStart)
        .coerceAtLeast(0)

    return overlap >= minOf(
        minimumVisiblePx,
        (item.size / 3).coerceAtLeast(1)
    )
}

private suspend fun LazyListState.followTranscriptEdge(
    targetIndex: Int,
    marginPx: Int,
    minimumVisiblePx: Int
) {
    if (targetIndex !in 0 until layoutInfo.totalItemsCount) {
        return
    }

    val layout = layoutInfo

    val viewportStart = layout.viewportStartOffset
    val viewportEnd = layout.viewportEndOffset

    val viewportHeight = (
        viewportEnd - viewportStart
    ).coerceAtLeast(1)

    val item = layout.visibleItemsInfo
        .firstOrNull { it.index == targetIndex }

    if (item == null) {

        // Редкий случай: резкая перемотка аудио
        // или переход к ещё не видимой реплике.
        // Размещаем её в нижней части экрана,
        // а не у верхней границы.
        animateScrollToItem(
            index = targetIndex,
            scrollOffset = -(
                viewportHeight * 0.55f
            ).toInt()
        )

        return
    }

    val safeTop = viewportStart + marginPx
    val safeBottom = viewportEnd - marginPx

    val availableHeight = (
        safeBottom - safeTop
    ).coerceAtLeast(1)

    val delta = if (item.size > availableHeight) {

        // Высокое сообщение может не помещаться
        // целиком. Достаточно показать заметную часть.
        val requiredVisible = minOf(
            minimumVisiblePx,
            viewportHeight / 3
        )

        val visibleStart = maxOf(
            item.offset,
            viewportStart
        )

        val visibleEnd = minOf(
            item.offset + item.size,
            viewportEnd
        )

        val visibleHeight = (
            visibleEnd - visibleStart
        ).coerceAtLeast(0)

        if (visibleHeight >= requiredVisible) {
            0f
        } else if (item.offset >= viewportEnd - requiredVisible) {
            (
                item.offset -
                    (viewportEnd - requiredVisible)
            ).toFloat()
        } else {
            (
                item.offset + item.size -
                    (viewportStart + requiredVisible)
            ).toFloat()
        }

    } else {

        when {
            item.offset + item.size > safeBottom -> {
                (
                    item.offset + item.size -
                        safeBottom
                ).toFloat()
            }

            item.offset < safeTop -> {
                (item.offset - safeTop).toFloat()
            }

            else -> 0f
        }
    }

    if (kotlin.math.abs(delta) > 2f) {
        animateScrollBy(
            value = delta,
            animationSpec = tween(
                durationMillis = 380,
                easing = FastOutSlowInEasing
            )
        )
    }
}

private fun formatSegmentTime(
    milliseconds: Long
): String {

    val totalSeconds =
        milliseconds.coerceAtLeast(0L) / 1000

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
