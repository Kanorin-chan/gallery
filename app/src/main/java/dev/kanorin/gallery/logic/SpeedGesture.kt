package dev.kanorin.gallery.logic

import kotlin.math.hypot

/** Hold-for-speed states (spec 5.8): NORMAL plays at the list's speed. */
enum class SpeedMode { NORMAL, HOLD, SLOW, PENDING_LOCK, LOCKED }

/**
 * One finger on the playing video (spec 5.8, mockup phone 7): still for [holdMs] = HOLD; held, slide past [slidePx]
 * up = SLOW, down = PENDING_LOCK, released there = LOCKED until the next hold (a tap stays a tap). Zoomed, an early move pans. Px, ms.
 */
class SpeedGesture(
    var holdSpeed: Float,
    private val slidePx: Float,
    private val touchSlopPx: Float,
    private val holdMs: Long = HOLD_MS,
    /** Starts LOCKED: a rotation keeps a locked speed. */
    locked: Boolean = false,
) {
    var mode: SpeedMode = if (locked) SpeedMode.LOCKED else SpeedMode.NORMAL
        private set

    /** This touch moves the zoomed video. */
    var panning: Boolean = false
        private set

    /** This touch is the gesture's (hold, pan, pinch): never a tap. Kept until the next down. */
    var consumed: Boolean = false
        private set

    /** The finger is down and can still become a hold. */
    val holdPending: Boolean get() = pressed && !settled

    private var pressed = false
    private var settled = false
    private var zoomed = false
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    private val held get() = mode == SpeedMode.HOLD || mode == SpeedMode.SLOW || mode == SpeedMode.PENDING_LOCK

    fun down(x: Float, y: Float, t: Long, zoomed: Boolean) {
        pressed = true
        panning = false
        downX = x
        downY = y
        downT = t
        this.zoomed = zoomed
        // Locked stays locked: a tap shows the controls, and a hold takes over (its release ends the lock).
        consumed = false
        settled = false
    }

    fun tick(t: Long) {
        if (holdPending && t - downT >= holdMs) {
            mode = SpeedMode.HOLD
            settled = true
            consumed = true
        }
    }

    fun move(x: Float, y: Float, t: Long) {
        if (!pressed) return
        tick(t) // the finger was still until this event
        if (held) {
            val dy = y - downY
            mode = when {
                dy < -slidePx -> SpeedMode.SLOW
                dy > slidePx -> SpeedMode.PENDING_LOCK
                else -> SpeedMode.HOLD
            }
        } else if (!settled && hypot(x - downX, y - downY) > touchSlopPx) {
            settled = true
            // Unzoomed there is nothing to pan: the move is left alone, it's no tap and no hold.
            if (zoomed) {
                panning = true
                consumed = true
            }
        }
    }

    /** The tap detector took this press (a double-tap's second): it never becomes a hold. */
    fun tapped() {
        if (holdPending) settled = true
    }

    /** A second finger: the pinch takes the touch and any hold ends. */
    fun pinch() {
        if (!pressed) return
        if (held) mode = SpeedMode.NORMAL
        settled = true
        consumed = true
        panning = false
    }

    fun up(t: Long) {
        if (!pressed) return
        tick(t)
        pressed = false
        panning = false
        mode = if (mode == SpeedMode.PENDING_LOCK || mode == SpeedMode.LOCKED) SpeedMode.LOCKED else SpeedMode.NORMAL
    }

    /** What the player plays at: [base] (the speed list's choice) unless a hold says otherwise. */
    fun rate(base: Float): Float = rateOf(mode, base, holdSpeed)

    companion object {
        const val HOLD_MS = 350L
        const val SLOW_SPEED = 0.5f

        fun rateOf(mode: SpeedMode, base: Float, holdSpeed: Float): Float = when (mode) {
            SpeedMode.NORMAL -> base
            SpeedMode.SLOW -> SLOW_SPEED
            SpeedMode.HOLD, SpeedMode.PENDING_LOCK, SpeedMode.LOCKED -> holdSpeed
        }
    }
}

/** The speed list and the player's speed wording (spec 5.8, mockup phone 7). */
object PlaybackSpeed {
    val OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** "0.25×", "1.0×", "2×": the mockup's `lbl()`. */
    fun label(speed: Float): String = (if (speed == 1f) "1.0" else speed.toString().removeSuffix(".0")) + "×"

    /** The pill over the video while a finger holds; null = no pill. No text explains the slides (spec). */
    fun pill(mode: SpeedMode, holdSpeed: Float): String? = when (mode) {
        SpeedMode.NORMAL -> null
        SpeedMode.HOLD -> "${label(holdSpeed)} speed"
        SpeedMode.SLOW -> "${label(SpeedGesture.SLOW_SPEED)} slow"
        SpeedMode.PENDING_LOCK -> "Release to lock ${label(holdSpeed)}"
        SpeedMode.LOCKED -> "${label(holdSpeed)} locked · hold to unlock"
    }
}
