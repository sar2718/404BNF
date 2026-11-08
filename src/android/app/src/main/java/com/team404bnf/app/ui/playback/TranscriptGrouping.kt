package com.team404bnf.app.ui.playback

import com.team404bnf.app.transcription.TranscriptSegment

internal data class TranscriptMessageGroup(
    val segments: List<TranscriptSegment>
) {
    init {
        require(segments.isNotEmpty())
    }

    val id: String
        get() = segments.first().id

    val speakerId: String?
        get() = segments.first().speakerId

    val startMs: Long?
        get() = segments.first().startMs

    val endMs: Long?
        get() = segments.last().endMs

    val text: String
        get() = segments.joinToString(" ") {
            it.text.trim()
        }
}

internal fun groupTranscriptSegments(
    segments: List<TranscriptSegment>,
    originalPositions: Map<String, Int>,
    maxGapMs: Long = 1500L,
    splitBeforeSegmentIds: Set<String> = emptySet()
): List<TranscriptMessageGroup> {

    if (segments.isEmpty()) return emptyList()

    val groups = mutableListOf<MutableList<TranscriptSegment>>()

    for (segment in segments) {

        val currentGroup = groups.lastOrNull()
        val previous = currentGroup?.lastOrNull()

        val shouldMerge = if (previous == null) {
            false
        } else {

            val previousPosition = originalPositions[previous.id]
            val currentPosition = originalPositions[segment.id]

            val originallyAdjacent =
                previousPosition != null &&
                    currentPosition == previousPosition + 1

            val sameSpeaker =
                segment.speakerId != null &&
                    segment.speakerId == previous.speakerId

            val currentStart = segment.startMs
            val previousEnd = previous.endMs

            val validGap = if (
                currentStart != null &&
                previousEnd != null &&
                previous.startMs != null &&
                segment.endMs != null &&
                currentStart >= previousEnd
            ) {
                currentStart - previousEnd <= maxGapMs
            } else {
                false
            }

            val notSplit = segment.id !in splitBeforeSegmentIds

            originallyAdjacent &&
                sameSpeaker &&
                validGap &&
                notSplit
        }

        if (shouldMerge && currentGroup != null) {
            currentGroup.add(segment)
        } else {
            groups.add(mutableListOf(segment))
        }
    }

    return groups.map {
        TranscriptMessageGroup(it.toList())
    }
}

internal fun findActiveTranscriptGroups(
    groups: List<TranscriptMessageGroup>,
    positionMs: Long
): Set<Int> {

    if (positionMs < 0L) return emptySet()

    return groups.mapIndexedNotNull { index, group ->

        val active = group.segments.any { segment ->

            val start = segment.startMs
            val end = segment.endMs

            start != null &&
                end != null &&
                positionMs >= start &&
                positionMs < end
        }

        if (active) index else null

    }.toSet()
}

/** A stored boundary is actionable only if removing it can join the visible neighbours. */
internal fun restorableMergeStarts(
    segments: List<TranscriptSegment>,
    originalPositions: Map<String, Int>,
    splitBeforeSegmentIds: Set<String>
): Set<String> = groupTranscriptSegments(segments, originalPositions)
    .flatMap { it.segments.drop(1).map { segment -> segment.id } }
    .filterTo(mutableSetOf()) { it in splitBeforeSegmentIds }
