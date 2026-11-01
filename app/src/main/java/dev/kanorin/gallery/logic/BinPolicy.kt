package dev.kanorin.gallery.logic

/** How long the Recycle bin keeps a file, and when it empties itself (spec 5.5). Times are epoch ms. */
object BinPolicy {
    const val RETENTION_DAYS = 30
    const val DAY_MS = 86_400_000L
    const val RETENTION_MS = RETENTION_DAYS * DAY_MS

    /** A resumed app purges at most this often; a cold start always does. */
    const val PURGE_GAP_MS = DAY_MS

    fun isExpired(deletedAt: Long, now: Long): Boolean = now - deletedAt > RETENTION_MS

    /** "N days left", N rounded up; "Last day" under a day (also once expired, while its card was out). */
    fun daysLeftLabel(deletedAt: Long, now: Long): String {
        val left = deletedAt + RETENTION_MS - now
        if (left < DAY_MS) return "Last day"
        val days = (left + DAY_MS - 1) / DAY_MS
        return if (days == 1L) "1 day left" else "$days days left"
    }

    /** [lastPurge] null = never; one in the future means the clock went back, so it isn't trusted. */
    fun purgeDue(lastPurge: Long?, now: Long): Boolean =
        lastPurge == null || lastPurge > now || now - lastPurge >= PURGE_GAP_MS
}
