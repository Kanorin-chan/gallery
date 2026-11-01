package dev.kanorin.gallery.logic

/** Auto-scroll while hold-dragging near a grid edge (spec 5.2), the mockup's numbers. Units are dp. */
object EdgeScroll {
    const val EDGE = 50f
    const val CAP = 25f
    private const val DIVISOR = 3f

    /** The mockup's speeds are per 60 Hz frame. */
    const val FRAME_MS = 1000f / 60

    /** A stalled frame scrolls at most this much time's worth, never a leap. */
    const val MAX_FRAME_MS = 50f

    /** [step] for [frameMs] of time: the same speed at 60, 120 or 165 Hz. */
    fun step(y: Float, top: Float, bottom: Float, frameMs: Float): Float = step(y, top, bottom) * (frameMs / FRAME_MS)

    /** Time since the last frame (ms) from `withFrameNanos` stamps; the first frame counts as one 60 Hz frame. */
    fun frameMs(lastNanos: Long?, nowNanos: Long): Float =
        if (lastNanos == null) FRAME_MS else ((nowNanos - lastNanos) / 1_000_000f).coerceIn(0f, MAX_FRAME_MS)

    /** One frame's scroll for a finger at [y] in a grid spanning [top]..[bottom]: depth into the zone / 3, capped. */
    fun step(y: Float, top: Float, bottom: Float): Float {
        val v = when {
            y < top + EDGE -> -(top + EDGE - y) / DIVISOR
            y > bottom - EDGE -> (y - (bottom - EDGE)) / DIVISOR
            else -> 0f
        }
        return v.coerceIn(-CAP, CAP)
    }
}
