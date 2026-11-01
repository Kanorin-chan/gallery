package dev.kanorin.gallery.logic

/** Turns a pinch's scale into whole column steps: spread past 1.25x drops a column, squeeze below 0.8x adds one. */
class PinchStepper(val min: Int, val max: Int, var columns: Int) {
    init {
        require(min in 1..max) { "bad range $min..$max" }
    }

    private var scale = 1f

    /** Multiplies the scale since the last step; returns the new column count when it changes, else null. */
    fun onScale(factor: Float): Int? {
        scale *= factor
        val next = when {
            scale >= SPREAD -> columns - 1
            scale <= SQUEEZE -> columns + 1
            else -> return null
        }.coerceIn(min, max)
        // A clamped step still restarts the count, so pinching back the other way needs a full step.
        scale = 1f
        if (next == columns) return null
        columns = next
        return next
    }

    /** Call when the fingers lift. */
    fun reset() {
        scale = 1f
    }

    private companion object {
        const val SPREAD = 1.25f
        const val SQUEEZE = 0.8f
    }
}
