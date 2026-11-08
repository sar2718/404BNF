package com.team404bnf.app.playback

import android.content.Context
import android.net.Uri

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

import java.io.File

data class PlaybackUiState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val isPlayRequested: Boolean = false,
    val speed: Float = 1f,
    val error: String? = null
)

class PlaybackController(context: Context) {

    private var fullItem: MediaItem? = null
    private var fullDurationMs = 0L
    private var previewRange: SegmentPlaybackRange? = null

    private val player = ExoPlayer.Builder(
        context.applicationContext
    ).build()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate
    )

    private val _state = MutableStateFlow(
        PlaybackUiState()
    )

    val state = _state.asStateFlow()

    init {
        player.addListener(
            object : Player.Listener {

                override fun onEvents(
                    player: Player,
                    events: Player.Events
                ) {
                    updateState()
                }

                override fun onPlayerError(
                    error: PlaybackException
                ) {
                    _state.update {
                        it.copy(
                            error = "Не удалось воспроизвести аудиофайл"
                        )
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (previewRange != null && playbackState == Player.STATE_ENDED) {
                        player.pause()
                    }
                }
            }
        )

        // Обновляем время воспроизведения каждые 250 мс.
        scope.launch {
            while (isActive) {
                updateState()
                delay(250)
            }
        }
    }

    fun load(file: File) {
        previewRange = null
        fullDurationMs = 0L
        fullItem = MediaItem.fromUri(Uri.fromFile(file))
        _state.value = PlaybackUiState()

        player.setMediaItem(requireNotNull(fullItem))

        player.prepare()
        updateState()
    }

    fun play() {
        restoreFullItem(absolutePosition())
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
        }

        player.play()
        updateState()
    }

    fun playFrom(positionMs: Long) {
        seekTo(positionMs)
        player.play()
        updateState()
    }

    fun pause() {
        player.pause()
        restoreFullItem(absolutePosition())
        updateState()
    }

    fun playRange(startMs: Long?, endMs: Long?) {
        pause() // Cancel a previous preview, including its clipping configuration.
        val item = fullItem ?: return
        val range = SegmentPlaybackRange.create(startMs, endMs, fullDurationMs) ?: return
        previewRange = range
        player.setMediaItem(item.buildUpon()
            .setClippingConfiguration(range.clippingConfiguration()).build(), 0L)
        player.prepare()
        player.play()
        updateState()
    }

    private fun absolutePosition(): Long =
        previewRange?.absolutePosition(player.currentPosition) ?: player.currentPosition.coerceAtLeast(0L)

    /** Switching back uses the same ExoPlayer and preserves the absolute recording position. */
    private fun restoreFullItem(positionMs: Long) {
        if (previewRange == null) return
        previewRange = null
        player.setMediaItem(requireNotNull(fullItem), positionMs)
        player.prepare()
    }

    fun seekTo(positionMs: Long) {
        val duration = fullDurationMs

        val safePosition = if (
            duration == C.TIME_UNSET || duration <= 0
        ) {
            positionMs.coerceAtLeast(0)
        } else {
            positionMs.coerceIn(0, duration)
        }

        if (previewRange != null) restoreFullItem(safePosition)
        else player.seekTo(safePosition)
        updateState()
    }

    fun setPlaybackSpeed(speed: Float) {
        require(speed in listOf(1f, 1.25f, 1.5f, 1.75f, 2f)) {
            "Неподдерживаемая скорость воспроизведения"
        }

        player.setPlaybackSpeed(speed)
        updateState()
    }

    private fun updateState() {
        val duration = player.duration

        if (previewRange == null && duration != C.TIME_UNSET && duration >= 0L) {
            fullDurationMs = duration
        }

        _state.update {
            it.copy(
                positionMs = absolutePosition(),

                durationMs = fullDurationMs,

                isPlaying = player.isPlaying,

                isPlayRequested = player.playWhenReady &&
                    player.playbackState != Player.STATE_ENDED,

                speed = player.playbackParameters.speed
            )
        }
    }

    fun release() {
        scope.cancel()
        player.release()
    }
}
