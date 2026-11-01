package dev.kanorin.gallery.logic

import kotlin.math.max
import kotlin.math.min

/**
 * Hold-and-drag selection (spec 5.2): every key from [anchor] to the finger takes [select]; the rest keep [base].
 * [select] = the anchor wasn't in [base], so starting on a selected item deselects the range.
 */
class RangeSelection(private val anchor: Int, private val base: Set<String>, private val select: Boolean) {
    /** Keys to turn on and off, relative to the previous [step]. */
    data class Change(val on: Set<String>, val off: Set<String>)

    // Keys the last step put in the range.
    private var inRange: Set<String> = emptySet()

    /** [base] with the range anchor..current applied; a null key (not known yet) is skipped. */
    fun at(current: Int, keyAt: (Int) -> String?): Set<String> {
        val range = keysTo(current, keyAt)
        return if (select) base + range else base - range
    }

    /** What changed since the last step: cost follows the range, not [base], so a 100k selection stays cheap. */
    fun step(current: Int, keyAt: (Int) -> String?): Change {
        val now = keysTo(current, keyAt)
        // Keys that left the range go back to how the base had them.
        val (restoreOn, restoreOff) = (inRange - now).partition { it in base }
        inRange = now
        return if (select) Change(now + restoreOn, restoreOff.toSet()) else Change(restoreOn.toSet(), now + restoreOff)
    }

    private fun keysTo(current: Int, keyAt: (Int) -> String?): Set<String> {
        val out = LinkedHashSet<String>()
        for (i in min(anchor, current)..max(anchor, current)) keyAt(i)?.let(out::add)
        return out
    }
}
