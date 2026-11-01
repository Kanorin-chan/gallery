package dev.kanorin.gallery.logic

import java.time.LocalDate
import java.time.ZoneId

/** A parsed search: [text] matches a file or folder name; [from] (inclusive) to [until] (exclusive) bound dateTaken, epoch ms. */
data class SearchQuery(val text: String?, val from: Long?, val until: Long?) {
    val isEmpty: Boolean get() = text == null && from == null

    companion object {
        /** [text] as a LIKE pattern for `ESCAPE '\'`: % _ and \ in the text match themselves. */
        fun likePattern(text: String): String =
            "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }
}

/** Turns what the user typed into a [SearchQuery] (spec 5.1): dates are recognised, everything else is text. */
object SearchParser {
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val ISO = Regex("""(\d{4})(?:-(\d{1,2})(?:-(\d{1,2}))?)?""")
    private val YEARS = 1900..2999

    private class Range(val from: LocalDate, val until: LocalDate)

    // A date found at tokens[start] and how many tokens it used.
    private class Hit(val range: Range, val used: Int)

    fun parse(raw: String, now: Long, zone: ZoneId): SearchQuery {
        val tokens = raw.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var start = -1
        var hit: Hit? = null
        for (i in tokens.indices) {
            hit = dateAt(tokens, i, today)
            if (hit != null) { start = i; break }
        }
        val h = hit ?: return SearchQuery(tokens.joinToString(" ").ifEmpty { null }, null, null)
        val rest = tokens.filterIndexed { i, _ -> i < start || i >= start + h.used }.joinToString(" ").ifEmpty { null }
        return SearchQuery(rest, ms(h.range.from, zone), ms(h.range.until, zone))
    }

    private fun ms(day: LocalDate, zone: ZoneId) = day.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun dateAt(t: List<String>, i: Int, today: LocalDate): Hit? {
        val w = t[i].lowercase()
        when (w) {
            "today" -> return Hit(Range(today, today.plusDays(1)), 1)
            "yesterday" -> return Hit(Range(today.minusDays(1), today), 1)
        }
        ISO.matchEntire(w)?.let { m ->
            val y = m.groupValues[1].toInt()
            if (y !in YEARS) return null
            val month = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
            val day = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            return runCatching {
                when {
                    month == null -> Range(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1))
                    day == null -> LocalDate.of(y, month, 1).let { Range(it, it.plusMonths(1)) }
                    else -> LocalDate.of(y, month, day).let { Range(it, it.plusDays(1)) }
                }
            }.getOrNull()?.let { Hit(it, 1) }
        }
        // "28 oct" / "28 oct 2026": a day number, then the month.
        w.toIntOrNull()?.takeIf { w.length <= 2 }?.let { day ->
            val month = t.getOrNull(i + 1)?.let(::monthOf) ?: return null
            val year = t.getOrNull(i + 2)?.let(::yearOf)
            val date = runCatching { LocalDate.of(year ?: today.year, month, day) }.getOrNull() ?: return null
            return Hit(Range(date, date.plusDays(1)), if (year != null) 3 else 2)
        }
        val month = monthOf(w) ?: return null
        // "31 feb" is a bad date, not a day-less "feb".
        if (t.getOrNull(i - 1)?.let { it.length <= 2 && it.toIntOrNull() != null } == true) return null
        val year = t.getOrNull(i + 1)?.let(::yearOf)
        if (year != null) return LocalDate.of(year, month, 1).let { Hit(Range(it, it.plusMonths(1)), 2) }
        // A bare month: the latest one that has begun.
        val thisYear = LocalDate.of(today.year, month, 1)
        val first = if (thisYear <= today) thisYear else thisYear.minusYears(1)
        return Hit(Range(first, first.plusMonths(1)), 1)
    }

    private fun yearOf(s: String): Int? = if (s.length == 4) s.toIntOrNull()?.takeIf { it in YEARS } else null

    // Full names, or the first three letters ("sept" too); "oct" must not match "octopus".
    private fun monthOf(word: String): Int? {
        val w = word.lowercase()
        if (w == "sept") return 9
        val i = MONTHS.indexOfFirst { it == w || (w.length == 3 && it.startsWith(w)) }
        return if (i >= 0) i + 1 else null
    }
}
