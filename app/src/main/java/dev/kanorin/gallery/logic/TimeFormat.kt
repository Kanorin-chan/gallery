package dev.kanorin.gallery.logic

import java.util.Locale

object TimeFormat {
    /** "03:41", or "1:04:13" when [withHours] (pass [showsHours] of the whole duration). Rounds to the nearest second. */
    fun clock(ms: Long, withHours: Boolean): String {
        val total = roundedSeconds(ms)
        val s = total % 60
        return if (withHours) {
            "%d:%02d:%02d".format(Locale.ROOT, total / 3600, total / 60 % 60, s)
        } else {
            "%02d:%02d".format(Locale.ROOT, total / 60, s)
        }
    }

    /** True when [durationMs] rounds to an hour or more, so 59:59.6 shows "1:00:00", never "60:00". */
    fun showsHours(durationMs: Long): Boolean = roundedSeconds(durationMs) >= 3600

    /** A whole duration on its own, e.g. the grid's video badge. */
    fun duration(ms: Long): String = clock(ms, showsHours(ms))

    /** The player's current time: hours iff [durationMs] has them, so "0:03:41 / 1:04:13" never mixes styles. */
    fun position(ms: Long, durationMs: Long): String =
        // An unknown duration (<= 0) can't bound the position: the time still counts up.
        clock(if (durationMs > 0) ms.coerceIn(0, durationMs) else ms, showsHours(durationMs))

    private fun roundedSeconds(ms: Long) = (ms.coerceAtLeast(0) + 500) / 1000
}
