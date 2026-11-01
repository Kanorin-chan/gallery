package dev.kanorin.gallery.data

import androidx.paging.PagingConfig
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.kanorin.gallery.logic.FAVORITES_FOLDER
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.SearchQuery
import dev.kanorin.gallery.logic.SortField
import java.time.ZoneOffset

/** One timeline group as SQL counts it: [groupKey] is the group's start in epoch ms. */
data class SectionCount(val groupKey: Long, val itemCount: Int)

/** Builds the timeline's two queries from one filter, so the item order and the groups always agree. */
object MediaQuery {
    /**
     * Placeholders on so the grid knows its full height; page of 200. At most 2,000 rows held (100k rows would
     * be ~60 MB), and a far jump loads there directly instead of every page in between.
     */
    val PAGING = PagingConfig(pageSize = 200, enablePlaceholders = true, maxSize = 2_000, jumpThreshold = 600)

    private const val DAY_MS = 86_400_000L

    fun items(filter: MediaFilter, folderPath: String?): SupportSQLiteQuery = ordered("media.*", filter, folderPath)

    /** [items]' paths in the same order, so index i here is grid item i; [limit] -1 = all. */
    fun paths(filter: MediaFilter, folderPath: String?, offset: Int = 0, limit: Int = -1): SupportSQLiteQuery =
        ordered("media.path", filter, folderPath, " LIMIT ? OFFSET ?", listOf(limit, offset))

    /** [path]'s 0-based place in [items]' order; no row when that grid doesn't hold it. */
    fun position(filter: MediaFilter, folderPath: String?, path: String): SupportSQLiteQuery {
        val w = where(filter, folderPath)
        val sql = "SELECT pos FROM (SELECT media.path AS p, ROW_NUMBER() OVER (ORDER BY ${orderBy(filter)}) - 1 AS pos " +
            "FROM media${w.join} WHERE ${w.sql}) WHERE p = ?"
        return SimpleSQLiteQuery(sql, (w.args + path).toTypedArray())
    }

    private fun ordered(
        columns: String,
        filter: MediaFilter,
        folderPath: String?,
        tail: String = "",
        tailArgs: List<Any> = emptyList(),
    ): SupportSQLiteQuery {
        val w = where(filter, folderPath)
        val sql = "SELECT $columns FROM media${w.join} WHERE ${w.sql} ORDER BY ${orderBy(filter)}$tail"
        return SimpleSQLiteQuery(sql, (w.args + tailArgs).toTypedArray())
    }

    // rowid breaks ties and is the tail of every index entry, so (hidden, dateTaken) serves the order with no sort.
    private fun orderBy(filter: MediaFilter): String {
        val dir = direction(filter)
        // Photos have no length: by length the videos come first either way round, the photos newest first.
        if (filter.sort == SortField.DURATION) return "media.isVideo DESC, media.durationMs $dir, media.dateTaken DESC, media.rowid $dir"
        return "${sortColumn(filter.sort)} $dir, media.rowid $dir"
    }

    /** Group counts in [items] order. [zone] is one fixed offset: SQLite's 'localtime' depends on the C library. */
    fun sections(filter: MediaFilter, folderPath: String?, zone: ZoneOffset): SupportSQLiteQuery {
        val w = where(filter, folderPath)
        val sql = "SELECT ${groupKey(filter, zone)} AS groupKey, COUNT(*) AS itemCount FROM media${w.join} " +
            "WHERE ${w.sql} GROUP BY groupKey ORDER BY groupKey ${direction(filter)}"
        return SimpleSQLiteQuery(sql, w.args.toTypedArray())
    }

    private class Where(val join: String, val sql: String, val args: List<Any>)

    private fun where(filter: MediaFilter, folderPath: String?): Where {
        val clauses = mutableListOf<String>()
        val joins = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (folderPath == null || folderPath == FAVORITES_FOLDER) {
            // media.hidden already holds the effective value (override ?: rule), so the index serves the order.
            clauses += "media.hidden = 0"
        } else {
            // Hidden is per folder, so a folder opened by path shows its rows even when it is hidden.
            clauses += "media.folderPath = ?"
            args += folderPath
        }
        val kinds = buildList {
            if (filter.photos) add("(media.isVideo = 0 AND media.mimeType != 'image/gif')")
            if (filter.videos) add("media.isVideo = 1")
            if (filter.gifs) add("(media.isVideo = 0 AND media.mimeType = 'image/gif')")
        }
        if (kinds.size < 3) clauses += if (kinds.isEmpty()) "0" else kinds.joinToString(" OR ", "(", ")")
        if (filter.screenshotsOnly) clauses += "(media.folderPath LIKE '%/Screenshots' OR media.name LIKE 'Screenshot%')"
        val favourites = filter.favouritesOnly || folderPath == FAVORITES_FOLDER
        if (favourites) joins += " INNER JOIN favorites ON favorites.path = media.path"
        filter.search?.let { search ->
            search.text?.let { t ->
                // No stored column: the last path segment is cut out in SQL, and only for rows whose name missed.
                clauses += "(media.name LIKE ? ESCAPE '\\' OR $FOLDER_NAME LIKE ? ESCAPE '\\')"
                SearchQuery.likePattern(t).let { args += it; args += it }
            }
            search.from?.let { clauses += "media.dateTaken >= ?"; args += it }
            search.until?.let { clauses += "media.dateTaken < ?"; args += it }
        }
        return Where(joins.joinToString(""), clauses.joinToString(" AND "), args)
    }

    // folderPath after its last '/': rtrim strips the trailing non-slash run (of the path with its slashes removed), leaving the parent.
    private const val FOLDER_NAME = "replace(media.folderPath, rtrim(media.folderPath, replace(media.folderPath, '/', '')), '')"

    private fun direction(filter: MediaFilter) = if (filter.descending) "DESC" else "ASC"

    private fun sortColumn(sort: SortField) = when (sort) {
        SortField.DATE_TAKEN -> "media.dateTaken"
        SortField.DATE_MODIFIED -> "media.dateModified"
        SortField.NAME -> "media.name COLLATE NOCASE"
        SortField.SIZE -> "media.size"
        SortField.DURATION -> "media.durationMs"
    }

    /** Same keys as TimelineGrouping.groupKey in a fixed offset: the local start of the group, in epoch ms. */
    private fun groupKey(filter: MediaFilter, zone: ZoneOffset): String {
        val by = filter.effectiveGroupBy
        if (by == GroupBy.NONE) return "0"
        val off = zone.totalSeconds * 1000L
        val local = "(${sortColumn(filter.sort)} + ($off))"
        return when (by) {
            GroupBy.DAY -> "(${floorDiv(local, DAY_MS)} * $DAY_MS - ($off))"
            GroupBy.MONTH -> startOf(local, "start of month", off)
            GroupBy.YEAR -> startOf(local, "start of year", off)
            GroupBy.NONE -> "0"
        }
    }

    private fun startOf(local: String, modifier: String, off: Long) =
        "(CAST(strftime('%s', ${floorDiv(local, 1000)}, 'unixepoch', '$modifier') AS INTEGER) * 1000 - ($off))"

    // SQLite's / truncates toward zero; floor keeps pre-1970 times in the right group, as java.time does.
    private fun floorDiv(x: String, d: Long) = "(($x - ((($x % $d) + $d) % $d)) / $d)"
}
