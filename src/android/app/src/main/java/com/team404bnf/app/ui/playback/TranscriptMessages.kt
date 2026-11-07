package com.team404bnf.app.ui.playback

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

import com.team404bnf.app.transcription.TranscriptSegment

@Composable
fun TranscriptMessages(
    segments: List<TranscriptSegment>,
    speakerLabels: Map<String, String>,
    originalPositions: Map<String, Int>,
    splitBeforeSegmentIds: Set<String>,
    playbackPositionMs: Long,
    isPlaybackRunning: Boolean,
    onSegmentClick: (Long) -> Unit,
    onEditText: (String, String) -> Unit,
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

    val colors = MaterialTheme.colorScheme

    val palette = listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
        colors.surfaceVariant to colors.onSurfaceVariant
    )

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val messageTextStyle = MaterialTheme.typography.bodyMedium.copy(
        lineBreak = LineBreak.Paragraph.copy(
            strategy = LineBreak.Strategy.Balanced
        )
    )

    // Рассчитываем максимальную ширину сообщений
    // относительно доступной ширины экрана.
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
    ) {
        val maxBubbleWidth = maxWidth * 0.88f

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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

                val isRight = speakerIds.size == 2 &&
                    speakerIndex == 1

                val (backgroundColor, contentColor) =
                    if (speakerIndex == -1) {
                        colors.surfaceVariant to
                            colors.onSurfaceVariant
                    } else {
                        palette[speakerIndex % palette.size]
                    }

                val speakerName =
                    if (segment.speakerId == null) {
                        "Неизвестный участник"
                    } else {
                        speakerLabels[segment.speakerId]
                            ?: segment.speakerLabel
                            ?: "Участник ${speakerIndex + 1}"
                    }

                val calculatedWidth = remember(
                    group.text,
                    maxBubbleWidth,
                    messageTextStyle,
                    density,
                    textMeasurer
                ) {
                    calculateBubbleWidth(
                        text = group.text,
                        maxBubbleWidth = maxBubbleWidth,
                        textMeasurer = textMeasurer,
                        textStyle = messageTextStyle,
                        density = density
                    )
                }

                var menuExpanded by remember(group.id) {
                    mutableStateOf(false)
                }

                var selectedPart by remember(group.id) {
                    mutableStateOf<TranscriptSegment?>(null)
                }

                var choosingPart by remember(group.id) {
                    mutableStateOf(false)
                }

                val canPlaySegment =
                    group.startMs != null &&
                        group.endMs != null

                val displayedBackground by animateColorAsState(
                    targetValue = if (isActive) {
                        lerp(
                            backgroundColor,
                            colors.onSurface,
                            0.10f
                        )
                    } else {
                        backgroundColor
                    },
                    label = "transcriptBackground"
                )

                val highlightBorder by animateColorAsState(
                    targetValue = if (isActive) {
                        colors.primary
                    } else {
                        Color.Transparent
                    },
                    label = "transcriptBorder"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isRight) {
                        Arrangement.End
                    } else {
                        Arrangement.Start
                    }
                ) {
                    Box {
                        // Ширина определяется содержимым,
                        // но не превышает 88% ширины экрана.
                        Column(
                            modifier = Modifier
                                .then(
                                    if (calculatedWidth == null) {
                                        Modifier.widthIn(max = maxBubbleWidth)
                                    } else {
                                        Modifier.width(calculatedWidth)
                                    }
                                )
                                .border(2.dp, highlightBorder, RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp))
                                .background(displayedBackground)
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
                                            selectedPart = null
                                            menuExpanded = true
                                        }
                                    }
                                )
                                .padding(12.dp)
                        ) {

                            Text(
                                text = speakerName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = contentColor
                            )

                            Spacer(Modifier.height(6.dp))

                            Text(
                                text = group.text,
                                style = messageTextStyle,
                                color = contentColor
                            )

                            val start = group.startMs
                            val end = group.endMs

                            if (start != null && end != null) {

                                Spacer(Modifier.height(6.dp))

                                Text(
                                    text = "${formatSegmentTime(start)}" +
                                        " – ${formatSegmentTime(end)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor.copy(alpha = 0.75f),
                                    modifier = Modifier.align(Alignment.Start),
                                    textAlign = TextAlign.Start
                                )
                            }

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
                                selectedPart = null
                            },
                            modifier = Modifier.heightIn(max = 360.dp)
                        ) {

                            if (
                                group.segments.size > 1 &&
                                !choosingPart
                            ) {

                                DropdownMenuItem(
                                    text = { Text("Сменить участника") },
                                    onClick = {
                                        menuExpanded = false
                                        groupSpeakerRequest = group
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Разъединить сообщение") },
                                    onClick = {
                                        menuExpanded = false
                                        onSplitGroup(group.segments.map { it.id })
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Выбрать отдельный фрагмент") },
                                    onClick = {
                                        choosingPart = true
                                        selectedPart = null
                                    }
                                )

                                HorizontalDivider()

                                DropdownMenuItem(
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
                                                text = {

                                                    Column {

                                                        if (part.startMs != null) {
                                                            Text(
                                                                text = formatSegmentTime(part.startMs),
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
                                                    selectedPart = part
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
                                                selectedPart = null
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
                                                "${formatSegmentTime(target.startMs)} · ${target.text}"
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
                                    text = { Text("Изменить текст") },
                                    onClick = {
                                        menuExpanded = false
                                        selectedPart = null

                                        editRequest =
                                            TranscriptEditRequest.TextEdit(target)
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Сменить участника") },
                                    onClick = {
                                        menuExpanded = false
                                        selectedPart = null

                                        editRequest =
                                            TranscriptEditRequest.SpeakerChange(target)
                                    }
                                )

                                target.speakerId?.let { speakerId ->

                                    DropdownMenuItem(
                                        text = {
                                            Text("Переименовать участника")
                                        },

                                        onClick = {
                                            menuExpanded = false
                                            selectedPart = null

                                            editRequest =
                                                TranscriptEditRequest.SpeakerRename(speakerId)
                                        }
                                    )
                                }

                                if (
                                    group.segments.size == 1 &&
                                    segment.id in splitBeforeSegmentIds
                                ) {
                                    DropdownMenuItem(
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
                                    text = {
                                        Text(
                                            text = "Удалить",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },

                                    onClick = {
                                        menuExpanded = false
                                        selectedPart = null
                                        deleteRequest = target
                                    }
                                )
                            }
                        }
                    }
                }
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
            onTextSave = onEditText,
            onSpeakerChange = onChangeSpeaker,
            onSpeakerRename = onRenameSpeaker
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
            onDismiss = {
                groupSpeakerRequest = null
            }
        )
    }

    groupDeleteRequest?.let { group ->
        GroupDeleteDialog(
            group = group,
            onConfirm = onDeleteGroup,
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

private fun calculateBubbleWidth(
    text: String,
    maxBubbleWidth: Dp,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    density: Density
): Dp? {

    val paddingPx = with(density) {
        24.dp.roundToPx()
    }

    val safetyPx = with(density) {
        3.dp.roundToPx()
    }

    val maxBubblePx = with(density) {
        maxBubbleWidth.roundToPx()
    }

    val maxTextWidth = (
        maxBubblePx - paddingPx
    ).coerceAtLeast(1)

    val original = textMeasurer.measure(
        text = text,
        style = textStyle,
        constraints = Constraints(
            maxWidth = maxTextWidth
        )
    )

    // Однострочные сообщения не изменяем.
    if (original.lineCount <= 1 || original.hasVisualOverflow) {
        return null
    }

    var selectedWidth = maxTextWidth

    // Сужаем максимум на 30%.
    // Шаг составляет 5% от доступной ширины.
    for (percent in 95 downTo 70 step 5) {

        val candidate = (
            maxTextWidth * percent / 100f
        ).roundToInt().coerceAtLeast(1)

        val layout = textMeasurer.measure(
            text = text,
            style = textStyle,
            constraints = Constraints(
                maxWidth = candidate
            )
        )

        // Берём более узкую ширину только
        // при сохранении количества строк.
        if (
            layout.lineCount == original.lineCount &&
            !layout.hasVisualOverflow
        ) {
            selectedWidth = candidate
        }
    }

    val resultPx = (
        selectedWidth + paddingPx + safetyPx
    ).coerceAtMost(maxBubblePx)

    return with(density) {
        resultPx.toDp()
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