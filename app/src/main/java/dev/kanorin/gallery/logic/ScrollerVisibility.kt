package dev.kanorin.gallery.logic

/** When the fast-scroll handle shows: while scrolling or dragging, gone [hideDelayMs] after the last of either. */
class ScrollerVisibility(val hideDelayMs: Long = 1500) {
    private var lastActivity: Long? = null
    private var dragging = false

    fun onScroll(now: Long) {
        lastActivity = now
    }

    fun onDragStart() {
        dragging = true
    }

    fun onDragEnd(now: Long) {
        dragging = false
        lastActivity = now
    }

    fun visible(now: Long): Boolean = dragging || msUntilHidden(now) != null

    /** Same as [visible]: a hidden handle must never catch a touch (spec 5.2, "easy miss click"). */
    fun pressable(now: Long): Boolean = visible(now)

    /** How long until it hides on its own; null when already hidden or held by a drag. */
    fun msUntilHidden(now: Long): Long? {
        if (dragging) return null
        val last = lastActivity ?: return null
        return (last + hideDelayMs - now).takeIf { it > 0 }
    }
}
