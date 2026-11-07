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
        _state.value = PlaybackUiState()

        player.setMediaItem(
            MediaItem.fromUri(
                Uri.fromFile(file)
            )
        )

        player.prepare()
        updateState()
    }

    fun play() {
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
        updateState()
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration

        val safePosition = if (
            duration == C.TIME_UNSET || duration <= 0
        ) {
            positionMs.coerceAtLeast(0)
        } else {
            positionMs.coerceIn(0, duration)
        }

        player.seekTo(safePosition)
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

        val safeDuration = if (
            duration == C.TIME_UNSET || duration < 0
        ) {
            0L
        } else {
            duration
        }

        _state.update {
            it.copy(
                positionMs = player.currentPosition
                    .coerceAtLeast(0L),

                durationMs = safeDuration,

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