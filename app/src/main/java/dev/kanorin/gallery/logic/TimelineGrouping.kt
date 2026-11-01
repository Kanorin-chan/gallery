package dev.kanorin.gallery.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimelineGrouping {
    private val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    private val dayYearFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    private val monthFormat = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    /** Start of the local day/month/year holding [ms], in epoch ms; NONE -> 0. */
    fun groupKey(ms: Long, by: GroupBy, zone: ZoneId): Long {
        val date = localDate(ms, zone)
        val start = when (by) {
            GroupBy.DAY -> date
            GroupBy.MONTH -> date.withDayOfMonth(1)
            GroupBy.YEAR -> date.withDayOfYear(1)
            GroupBy.NONE -> return 0
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** "Today", "Yesterday", "Wed, 28 Oct", "Tue, 28 Oct 2025"; "Oct 2026"; "2026"; NONE -> "". */
    fun label(key: Long, by: GroupBy, now: Long, zone: ZoneId): String {
        val date = localDate(key, zone)
        val today = localDate(now, zone)
        return when (by) {
            GroupBy.DAY -> when {
                date == today -> "Today"
                date == today.minusDays(1) -> "Yesterday"
                date.year == today.year -> dayFormat.format(date)
                else -> dayYearFormat.format(date)
            }
            GroupBy.MONTH -> monthFormat.format(date)
            GroupBy.YEAR -> date.year.toString()
            GroupBy.NONE -> ""
        }
    }

    /** [zone]'s offset at [now]: SQL groups with this one offset, so keys and labels must use it too. */
    fun fixedZone(zone: ZoneId, now: Long): ZoneOffset = zone.rules.getOffset(Instant.ofEpochMilli(now))

    private fun localDate(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
