package com.team404bnf.app.playback

import org.junit.Assert.*
import org.junit.Test

class SegmentPlaybackRangeTest {
    @Test
    fun clipsAtRealMillisecondTimestamps() {
        val range = requireNotNull(SegmentPlaybackRange.create(15_200L, 21_700L, 60_000L))
        val clip = range.clippingConfiguration()
        assertEquals(15_200L, clip.startPositionMs)
        assertEquals(21_700L, clip.endPositionMs)
        assertFalse(clip.relativeToDefaultPosition)
        assertFalse(clip.relativeToLiveWindow)
        assertEquals(15_200L, range.absolutePosition(0))
        assertEquals(21_700L, range.absolutePosition(6_500))
        assertEquals(21_700L, range.absolutePosition(Long.MAX_VALUE))
    }

    @Test
    fun invalidAndMissingTimestampsDoNotCreateAnUnboundedPreview() {
        assertNull(SegmentPlaybackRange.create(null, 2_000L, 10_000L))
        assertNull(SegmentPlaybackRange.create(1_000L, null, 10_000L))
        assertNull(SegmentPlaybackRange.create(-1L, 2_000L, 10_000L))
        assertNull(SegmentPlaybackRange.create(2_000L, 2_000L, 10_000L))
        assertNull(SegmentPlaybackRange.create(3_000L, 2_000L, 10_000L))
        assertNull(SegmentPlaybackRange.create(10_000L, 20_000L, 10_000L))
        assertNull(SegmentPlaybackRange.create(0L, 2_000L, 0L))
    }

    @Test
    fun endPastTheRecordingIsClampedToItsActualDuration() {
        val range = requireNotNull(SegmentPlaybackRange.create(1_000L, 30_000L, 5_000L))
        assertEquals(5_000L, range.clippingConfiguration().endPositionMs)
        assertEquals(5_000L, range.absolutePosition(10_000))
    }
}
