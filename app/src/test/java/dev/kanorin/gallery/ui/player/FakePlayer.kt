package dev.kanorin.gallery.ui.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** A player the test drives: positions only move when the test or the screen moves them. Main looper only. */
@UnstableApi
class FakePlayer(var durationMs: Long = 61 * 60_000L) : SimpleBasePlayer(Looper.getMainLooper()) {
    var item: MediaItem? = null
    var startPositionMs: Long? = null
    var positionMs = 0L
    var bufferedMs = 0L
    var playing = false
    var phase = Player.STATE_IDLE
    var error: PlaybackException? = null
    var released = false
    val seeks = mutableListOf<Long>()

    /** The playback speed, and every speed it was set to. */
    var speed = 1f
    val speeds = mutableListOf<Float>()

    /** Why playing is held back (audio focus); a play request wins focus back, as ExoPlayer's does. */
    var suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    override fun getState(): State {
        val b = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs { positionMs }
            .setContentBufferedPositionMs { bufferedMs }
            .setPlaybackParameters(PlaybackParameters(speed))
            .setPlaybackSuppressionReason(suppression)
        val current = item
        if (current == null || error != null) {
            b.setPlaybackState(Player.STATE_IDLE).setPlayerError(error)
        } else {
            b.setPlaybackState(phase)
        }
        if (current != null) {
            val us = if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000
            b.setPlaylist(listOf(MediaItemData.Builder("fake").setMediaItem(current).setDurationUs(us).build()))
        }
        return b.build()
    }

    /** Changes state as the real player would on its own (reaching the end, an error), then tells listeners. */
    fun update(block: FakePlayer.() -> Unit) {
        block()
        invalidateState()
    }

    fun fail() = update { error = PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        item = mediaItems.firstOrNull()
        if (startPositionMs != C.TIME_UNSET) {
            this.startPositionMs = startPositionMs
            positionMs = startPositionMs
        }
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (phase == Player.STATE_IDLE) phase = Player.STATE_READY
        return done()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        if (playWhenReady) suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // seekToDefaultPosition: the start of the one item.
        val to = if (positionMs == C.TIME_UNSET) 0 else positionMs
        this.positionMs = to
        seeks += to
        if (phase == Player.STATE_ENDED) phase = Player.STATE_READY
        return done()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        speeds += speed
        return done()
    }

    override fun handleStop(): ListenableFuture<*> = done()

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        return done()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = done()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = done()

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
