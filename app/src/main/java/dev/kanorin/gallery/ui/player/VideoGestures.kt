package dev.kanorin.gallery.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.logic.PlaybackSpeed
import dev.kanorin.gallery.logic.SpeedGesture
import dev.kanorin.gallery.logic.SpeedMode
import dev.kanorin.gallery.logic.VideoZoom
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.theme.GalleryColors
import kotlinx.coroutines.delay

const val SPEED_PILL_TAG = "player-speed-pill"
const val VIDEO_ZOOM_PILL_TAG = "player-zoom-pill"
const val VIDEO_FRAME_TAG = "player-video-frame"

/** How far a held finger slides for slow (up) or the lock (down): the mockup's 40 px. */
val SLIDE_DISTANCE = 40.dp

/** How long the zoom pill stays after the scale last changed (the controls hidden). */
const val ZOOM_PILL_MS = 700L

private val ZoomPillShape = RoundedCornerShape(16.dp)
private val ZoomPillStyle = TextStyle(color = GalleryColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

/** The video's hold-for-speed and zoom (spec 5.8): [SpeedGesture] and [VideoZoom], readable by the UI. */
@Stable
internal class VideoGestureState(private val gesture: SpeedGesture) {
    var mode by mutableStateOf(gesture.mode)
        private set

    private var hold by mutableFloatStateOf(gesture.holdSpeed)
    var holdSpeed: Float
        get() = hold
        set(value) {
            hold = value
            gesture.holdSpeed = value
        }

    var zoom by mutableStateOf(VideoZoom())
        private set

    /** The player's speed for the list's [base]. */
    fun rate(base: Float): Float = SpeedGesture.rateOf(mode, base, holdSpeed)

    fun resetZoom() {
        zoom = VideoZoom()
    }

    val consumed get() = gesture.consumed
    val panning get() = gesture.panning
    val holdPending get() = gesture.holdPending

    fun down(at: Offset, t: Long) = sync { gesture.down(at.x, at.y, t, zoom.zoomed) }
    fun move(at: Offset, t: Long) = sync { gesture.move(at.x, at.y, t) }
    fun tick(t: Long) = sync { gesture.tick(t) }
    fun pinch() = sync { gesture.pinch() }
    fun tapped() = sync { gesture.tapped() }
    fun up(t: Long) = sync { gesture.up(t) }

    fun zoomTo(scale: Float, focus: Offset, w: Float, h: Float) {
        zoom = zoom.zoomAt(scale, focus.x, focus.y, w, h)
    }

    fun panBy(d: Offset, w: Float, h: Float) {
        zoom = zoom.pan(d.x, d.y, w, h)
    }

    /** A pinch let go a hair above 1x (no pill, no pan) is 1x. */
    fun settleZoom() {
        if (!zoom.zoomed) zoom = VideoZoom()
    }

    private inline fun sync(block: () -> Unit) {
        block()
        mode = gesture.mode
    }

    companion object {
        // Only a locked speed outlives a recreation (a rotation); a hold can't, and the zoom starts over.
        fun saver(holdSpeed: Float, slidePx: Float, slopPx: Float) = Saver<VideoGestureState, Boolean>(
            save = { it.mode == SpeedMode.LOCKED },
            restore = { VideoGestureState(SpeedGesture(holdSpeed, slidePx, slopPx, locked = it)) },
        )
    }
}

@Composable
internal fun rememberVideoGestures(holdSpeed: Float): VideoGestureState {
    val slide = with(LocalDensity.current) { SLIDE_DISTANCE.toPx() }
    val slop = LocalViewConfiguration.current.touchSlop
    val state = rememberSaveable(slide, slop, saver = VideoGestureState.saver(holdSpeed, slide, slop)) {
        VideoGestureState(SpeedGesture(holdSpeed, slide, slop))
    }
    SideEffect { state.holdSpeed = holdSpeed }
    return state
}

/**
 * Hold, slides, pinch and pan on the video. Put it after (inside) the tap detector: it sees each event first and
 * consumes the touches it takes, so those are never taps. A plain tap is left alone.
 */
internal fun Modifier.videoGestures(state: VideoGestureState): Modifier = pointerInput(state) {
    awaitEachGesture { followTouch(state) }
}

/** The video's zoom on its surface (a TextureView draws in this layer). */
internal fun Modifier.videoZoom(state: VideoGestureState): Modifier = graphicsLayer {
    val z = state.zoom
    scaleX = z.scale
    scaleY = z.scale
    translationX = z.tx
    translationY = z.ty
    transformOrigin = TransformOrigin(0f, 0f)
}

private class Pinch(val distance: Float, val scale: Float)

private suspend fun AwaitPointerEventScope.followTouch(state: VideoGestureState) {
    val first = awaitFirstDown(requireUnconsumed = false)
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    state.down(first.position, first.uptimeMillis)
    val holdAt = first.uptimeMillis + SpeedGesture.HOLD_MS
    var last = first.uptimeMillis
    var pinch: Pinch? = null
    var pinched = false
    var ended = false
    try {
        while (true) {
            val event = if (!pinched && state.holdPending) {
                val wait = holdAt - last
                if (wait > 0) withTimeoutOrNull(wait) { awaitPointerEvent() } else null
            } else {
                awaitPointerEvent()
            }
            // The tap detector consumed this down as a double-tap's second press: nothing on it may start a hold
            // (the timer, a late move or the lift, whichever comes first).
            if (first.isConsumed) state.tapped()
            if (event == null) {
                state.tick(holdAt)
                continue
            }
            last = event.changes.maxOf { it.uptimeMillis }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val a = pressed[0].position
                val b = pressed[1].position
                val gap = (a - b).getDistance()
                // Scale from where the pinch began (mockup): coming back from the 5x cap starts at once.
                val p = pinch ?: Pinch(gap.coerceAtLeast(1f), state.zoom.scale).also {
                    if (!pinched) state.pinch()
                    pinched = true
                    pinch = it
                }
                state.zoomTo(p.scale * gap / p.distance, (a + b) / 2f, w, h)
                event.changes.forEach { it.consume() }
                continue
            }
            pinch = null
            if (pinched) {
                // A pinch keeps its touch to the last finger: one finger left does nothing.
                event.changes.forEach { it.consume() }
                if (pressed.isEmpty()) {
                    ended = true
                    state.up(last)
                    state.settleZoom()
                    return
                }
                continue
            }
            val change = event.changes.firstOrNull { it.id == first.id } ?: continue
            if (!change.pressed) {
                ended = true
                state.up(change.uptimeMillis)
                if (state.consumed) change.consume()
                return
            }
            state.move(change.position, change.uptimeMillis)
            if (state.panning) state.panBy(change.position - change.previousPosition, w, h)
            if (state.consumed) change.consume()
        }
    } finally {
        // Cancelled mid-touch: no hold stays on.
        if (!ended) {
            if (first.isConsumed) state.tapped()
            state.up(last)
        }
    }
}

/** Mockup `.speedpill`: what a held finger does, under the top bar. */
@Composable
internal fun BoxScope.SpeedPill(state: VideoGestureState, landscape: Boolean) {
    val mode = state.mode
    // Fading out, it keeps saying what it said.
    val shown = remember { arrayOf(SpeedMode.HOLD) }
    if (mode != SpeedMode.NORMAL) shown[0] = mode
    AnimatedVisibility(
        mode != SpeedMode.NORMAL,
        Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(top = if (landscape) 48.dp else 58.dp),
        enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.9f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.9f),
    ) {
        val m = shown[0]
        Row(
            Modifier.background(GalleryColors.PlayerPill, PillShape).padding(horizontal = 14.dp, vertical = 7.dp).testTag(SPEED_PILL_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val icon = when (m) {
                SpeedMode.SLOW -> GalleryIcons.Slow
                SpeedMode.PENDING_LOCK, SpeedMode.LOCKED -> GalleryIcons.Lock
                else -> GalleryIcons.FastForward
            }
            Icon(icon, contentDescription = null, tint = GalleryColors.Text, modifier = Modifier.size(16.dp))
            Text(PlaybackSpeed.pill(m, state.holdSpeed).orEmpty(), style = PillStyle)
        }
    }
}

/** Mockup `.zpill`: "2.3× · Reset" while zoomed, with the [controls] or briefly after a pinch; a tap resets the zoom. */
@Composable
internal fun BoxScope.VideoZoomPill(state: VideoGestureState, controls: Boolean, landscape: Boolean) {
    val zoomed by remember(state) { derivedStateOf { state.zoom.zoomed } }
    val scale by remember(state) { derivedStateOf { state.zoom.scale } }
    var pinching by remember { mutableStateOf(false) }
    LaunchedEffect(scale) {
        pinching = true
        // A newer scale cancels this wait: it fades soon after the fingers stop.
        delay(ZOOM_PILL_MS)
        pinching = false
    }
    AnimatedVisibility(
        zoomed && (controls || pinching),
        Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(bottom = if (landscape) 100.dp else 170.dp),
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
    ) {
        Row(
            Modifier
                .clip(ZoomPillShape)
                .background(GalleryColors.PlayerPill)
                .clickable(role = Role.Button, onClick = state::resetZoom)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag(VIDEO_ZOOM_PILL_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(GalleryIcons.Search, contentDescription = null, tint = GalleryColors.Text, modifier = Modifier.size(14.dp))
            Text(state.zoom.label, style = ZoomPillStyle)
            Text("·", style = ZoomPillStyle.copy(color = GalleryColors.Muted))
            Text("Reset", style = ZoomPillStyle.copy(color = GalleryColors.Accent))
        }
    }
}
