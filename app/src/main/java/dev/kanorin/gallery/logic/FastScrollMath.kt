package dev.kanorin.gallery.logic

import kotlin.math.roundToInt

/** Fast-scroll handle position <-> flat grid index, linear in entries (not pixels). */
object FastScrollMath {
    /** Handle travel 0..1 for a grid showing [visible] of [total] entries from [first]; [atEnd] pins it to 1. */
    fun fraction(first: Int, visible: Int, total: Int, atEnd: Boolean): Float {
        val span = total - visible
        if (span <= 0) return 0f
        if (atEnd) return 1f
        return (first.toFloat() / span).coerceIn(0f, 1f)
    }

    /** Flat index to jump to for handle travel [fraction]; the bottom is the last entry. */
    fun target(fraction: Float, visible: Int, total: Int): Int {
        if (total <= 0) return 0
        if (fraction >= 1f) return total - 1
        val span = (total - visible).coerceAtLeast(0)
        return (fraction.coerceAtLeast(0f) * span).roundToInt().coerceIn(0, total - 1)
    }
}
