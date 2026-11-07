package com.team404bnf.app.ui.playback

import com.team404bnf.app.transcription.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptGroupingTest {

    @Test
    fun testThreeAdjacentSegmentsMerged() {
        val seg1 = TranscriptSegment(
            id = "seg1",
            startMs = 0,
            endMs = 1000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "Hello",
            overlap = null,
            speakerAmbiguous = null
        )
        val seg2 = TranscriptSegment(
            id = "seg2",
            startMs = 1200,
            endMs = 2000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "world",
            overlap = null,
            speakerAmbiguous = null
        )
        val seg3 = TranscriptSegment(
            id = "seg3",
            startMs = 2500,
            endMs = 3500,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "again",
            overlap = null,
            speakerAmbiguous = null
        )

        val segments = listOf(seg1, seg2, seg3)
        val positions = mapOf("seg1" to 0, "seg2" to 1, "seg3" to 2)

        val groups = groupTranscriptSegments(segments, positions)

        assertEquals(1, groups.size)
        assertEquals("seg1", groups[0].id)
        assertEquals("SPK1", groups[0].speakerId)
        assertEquals(0L, groups[0].startMs)
        assertEquals(3500L, groups[0].endMs)
        assertEquals("Hello world again", groups[0].text)
    }

    @Test
    fun testDifferentSpeakersNotMerged() {
        val seg1 = TranscriptSegment(
            id = "seg1",
            startMs = 0,
            endMs = 1000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "Hello",
            overlap = null,
            speakerAmbiguous = null
        )
        val seg2 = TranscriptSegment(
            id = "seg2",
            startMs = 1200,
            endMs = 2000,
            speakerId = "SPK2",
            speakerLabel = null,
            text = "Hi",
            overlap = null,
            speakerAmbiguous = null
        )

        val segments = listOf(seg1, seg2)
        val positions = mapOf("seg1" to 0, "seg2" to 1)

        val groups = groupTranscriptSegments(segments, positions)

        assertEquals(2, groups.size)
        assertEquals("seg1", groups[0].id)
        assertEquals("seg2", groups[1].id)
    }

    @Test
    fun testDeletedMiddleSegmentPreventsMerging() {
        val seg1 = TranscriptSegment(
            id = "seg1",
            startMs = 0,
            endMs = 1000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "First",
            overlap = null,
            speakerAmbiguous = null
        )
        // seg2 is deleted, so seg3 is not adjacent in original positions
        val seg3 = TranscriptSegment(
            id = "seg3",
            startMs = 1200,
            endMs = 2000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "Third",
            overlap = null,
            speakerAmbiguous = null
        )

        val segments = listOf(seg1, seg3)
        val positions = mapOf("seg1" to 0, "seg2" to 1, "seg3" to 2)

        val groups = groupTranscriptSegments(segments, positions)

        assertEquals(2, groups.size)
        assertEquals("seg1", groups[0].id)
        assertEquals("seg3", groups[1].id)
    }

    @Test
    fun testGapExceedingMaxGapNotMerged() {
        val seg1 = TranscriptSegment(
            id = "seg1",
            startMs = 0,
            endMs = 1000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "Hello",
            overlap = null,
            speakerAmbiguous = null
        )
        val seg2 = TranscriptSegment(
            id = "seg2",
            startMs = 3000, // gap is 2000ms > 1500ms
            endMs = 4000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "world",
            overlap = null,
            speakerAmbiguous = null
        )

        val segments = listOf(seg1, seg2)
        val positions = mapOf("seg1" to 0, "seg2" to 1)

        val groups = groupTranscriptSegments(segments, positions)

        assertEquals(2, groups.size)
        assertEquals("seg1", groups[0].id)
        assertEquals("seg2", groups[1].id)
    }

    @Test
    fun testUnknownSpeakersNotMerged() {
        val seg1 = TranscriptSegment(
            id = "seg1",
            startMs = 0,
            endMs = 1000,
            speakerId = null,
            speakerLabel = null,
            text = "Hello",
            overlap = null,
            speakerAmbiguous = null
        )
        val seg2 = TranscriptSegment(
            id = "seg2",
            startMs = 1200,
            endMs = 2000,
            speakerId = null,
            speakerLabel = null,
            text = "world",
            overlap = null,
            speakerAmbiguous = null
        )

        val segments = listOf(seg1, seg2)
        val positions = mapOf("seg1" to 0, "seg2" to 1)

        val groups = groupTranscriptSegments(segments, positions)

        assertEquals(2, groups.size)
        assertEquals("seg1", groups[0].id)
        assertEquals("seg2", groups[1].id)
    }

    @Test
    fun testSplitBeforeSegmentIdsPreventsMerging() {
        val seg1 = TranscriptSegment(
            id = "seg1",
            startMs = 0,
            endMs = 1000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "Hello",
            overlap = null,
            speakerAmbiguous = null
        )
        val seg2 = TranscriptSegment(
            id = "seg2",
            startMs = 1200,
            endMs = 2000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "world",
            overlap = null,
            speakerAmbiguous = null
        )

        val segments = listOf(seg1, seg2)
        val positions = mapOf("seg1" to 0, "seg2" to 1)

        val splitGroups = groupTranscriptSegments(
            segments = segments,
            originalPositions = positions,
            splitBeforeSegmentIds = setOf("seg2")
        )

        assertEquals(2, splitGroups.size)
        assertEquals("seg1", splitGroups[0].id)
        assertEquals("seg2", splitGroups[1].id)
    }

    @Test
    fun testRestoringAutoMergeAllowsMergingAgain() {
        val seg1 = TranscriptSegment(
            id = "seg1",
            startMs = 0,
            endMs = 1000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "Hello",
            overlap = null,
            speakerAmbiguous = null
        )
        val seg2 = TranscriptSegment(
            id = "seg2",
            startMs = 1200,
            endMs = 2000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "world",
            overlap = null,
            speakerAmbiguous = null
        )

        val segments = listOf(seg1, seg2)
        val positions = mapOf("seg1" to 0, "seg2" to 1)

        // After removing "seg2" from splitBeforeSegmentIds (emptySet)
        val mergedGroups = groupTranscriptSegments(
            segments = segments,
            originalPositions = positions,
            splitBeforeSegmentIds = emptySet()
        )

        assertEquals(1, mergedGroups.size)
        assertEquals("seg1", mergedGroups[0].id)
        assertEquals("Hello world", mergedGroups[0].text)
    }

    @Test
    fun testSegmentsWithoutTimeNotMerged() {
        val seg1 = TranscriptSegment(
            id = "seg1",
            startMs = null,
            endMs = null,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "Hello",
            overlap = null,
            speakerAmbiguous = null
        )
        val seg2 = TranscriptSegment(
            id = "seg2",
            startMs = null,
            endMs = null,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "world",
            overlap = null,
            speakerAmbiguous = null
        )

        val segments = listOf(seg1, seg2)
        val positions = mapOf("seg1" to 0, "seg2" to 1)

        val groups = groupTranscriptSegments(segments, positions)

        assertEquals(2, groups.size)
        assertEquals("seg1", groups[0].id)
        assertEquals("seg2", groups[1].id)
    }

    @Test
    fun testFindActiveTranscriptGroups() {
        val seg1 = TranscriptSegment(
            id = "seg1",
            startMs = 1000,
            endMs = 2000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "First",
            overlap = null,
            speakerAmbiguous = null
        )
        val seg2 = TranscriptSegment(
            id = "seg2",
            startMs = 1500, // overlaps with seg1
            endMs = 2500,
            speakerId = "SPK2",
            speakerLabel = null,
            text = "Overlap",
            overlap = true,
            speakerAmbiguous = null
        )
        val seg3 = TranscriptSegment(
            id = "seg3",
            startMs = 3000,
            endMs = 4000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "Third",
            overlap = null,
            speakerAmbiguous = null
        )
        val segNoTime = TranscriptSegment(
            id = "seg4",
            startMs = null,
            endMs = null,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "No time",
            overlap = null,
            speakerAmbiguous = null
        )

        val groups = listOf(
            TranscriptMessageGroup(listOf(seg1)),
            TranscriptMessageGroup(listOf(seg2)),
            TranscriptMessageGroup(listOf(seg3)),
            TranscriptMessageGroup(listOf(segNoTime))
        )

        // 1. Before seg1 start: no active groups
        assertEquals(emptySet<Int>(), findActiveTranscriptGroups(groups, 500))

        // 2. Active replica determined by time interval [1000, 2000)
        assertEquals(setOf(0), findActiveTranscriptGroups(groups, 1200))

        // 3. Overlapping replicas simultaneously active at 1600ms ([1000,2000) and [1500,2500))
        assertEquals(setOf(0, 1), findActiveTranscriptGroups(groups, 1600))

        // 4. After end of seg2 (at 2500): seg2 is inactive
        assertEquals(emptySet<Int>(), findActiveTranscriptGroups(groups, 2500))

        // 5. Replicas without timestamps are never active
        assertEquals(emptySet<Int>(), findActiveTranscriptGroups(groups, 0))
    }

    @Test
    fun testMergedMessageActiveWhenAnySegmentIsPlaying() {
        val seg1 = TranscriptSegment(
            id = "seg1",
            startMs = 1000,
            endMs = 2000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "First",
            overlap = null,
            speakerAmbiguous = null
        )
        val seg2 = TranscriptSegment(
            id = "seg2",
            startMs = 2200,
            endMs = 3000,
            speakerId = "SPK1",
            speakerLabel = null,
            text = "Second",
            overlap = null,
            speakerAmbiguous = null
        )

        val mergedGroup = TranscriptMessageGroup(listOf(seg1, seg2))
        val groups = listOf(mergedGroup)

        // Active during seg1
        assertEquals(setOf(0), findActiveTranscriptGroups(groups, 1500))
        // Active during seg2
        assertEquals(setOf(0), findActiveTranscriptGroups(groups, 2500))
        // Inactive in gap between seg1 and seg2
        assertEquals(emptySet<Int>(), findActiveTranscriptGroups(groups, 2100))
    }
}