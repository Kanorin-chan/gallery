package dev.kanorin.gallery.logic

/**
 * Safety net for a paged grid whose item count should equal its sections' total. A mismatch that outlasts
 * [STALE_MS] once both sides have loaded earns one refresh; it re-arms only when the total changes or the two
 * agree again, so a lasting mismatch can't loop.
 */
class StaleCount {
    private var refreshedFor: Int? = null

    /** [expected] null = sections not answered yet; [settled] = paging is not mid-refresh. */
    fun armed(expected: Int?, count: Int, settled: Boolean): Boolean {
        if (expected == null || !settled) return false
        if (expected == count) {
            refreshedFor = null
            return false
        }
        return refreshedFor != expected
    }

    fun refreshed(expected: Int) {
        refreshedFor = expected
    }

    companion object {
        const val STALE_MS = 500L
    }
}
