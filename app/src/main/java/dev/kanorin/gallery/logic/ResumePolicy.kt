package dev.kanorin.gallery.logic

/** Where a video opens (spec 5.8): videos over 10 min resume, unless barely started or nearly done. */
object ResumePolicy {
    const val MIN_DURATION_MS = 600_000L
    const val MIN_SAVED_MS = 5_000L
    const val END_MARGIN_MS = 10_000L

    /** [savedMs] when [durationMs] is over 10 min and it lies within 5 s .. duration − 10 s (both ends in); else 0. */
    fun startPosition(durationMs: Long, savedMs: Long?): Long {
        if (savedMs == null || durationMs <= MIN_DURATION_MS) return 0
        return if (savedMs in MIN_SAVED_MS..durationMs - END_MARGIN_MS) savedMs else 0
    }
}
