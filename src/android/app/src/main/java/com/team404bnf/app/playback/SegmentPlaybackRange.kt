package com.team404bnf.app.playback

import androidx.media3.common.MediaItem

/** Absolute recording timestamps. The clipped player's timeline starts at zero. */
internal class SegmentPlaybackRange private constructor(val startMs: Long, val endMs: Long) {
    fun absolutePosition(relativePositionMs: Long): Long =
        startMs + relativePositionMs.coerceIn(0L, endMs - startMs)

    fun clippingConfiguration(): MediaItem.ClippingConfiguration =
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()

    companion object {
        fun create(startMs: Long?, endMs: Long?, durationMs: Long): SegmentPlaybackRange? {
            if (startMs == null || endMs == null || durationMs <= 0L ||
                startMs < 0L || endMs <= startMs || startMs >= durationMs) return null
            return SegmentPlaybackRange(startMs, minOf(endMs, durationMs))
        }
    }
}
