package dev.kanorin.gallery.data

/** One folder's row in the Folders view. [hidden] is the stored rule flag; prefs can override it. Size in bytes, date in epoch ms. */
data class FolderSummary(
    val path: String,
    val name: String,
    val count: Int,
    val totalSize: Long,
    val coverPath: String,
    val latestDate: Long,
    val hidden: Boolean,
)

/** The virtual Favourites folder: favourites that still have an indexed file, and the latest added one as cover. */
data class FavouritesSummary(val count: Int, val coverPath: String?)

// name = the text after the last '/': rtrim(path, path-without-slashes) keeps "…/", replace() cuts that off.
// Cover = the newest item by dateTaken (rowid breaks ties), found through the folderPath index.
internal const val FOLDERS_SQL =
    "SELECT folderPath AS path, " +
        "REPLACE(folderPath, RTRIM(folderPath, REPLACE(folderPath, '/', '')), '') AS name, " +
        "COUNT(*) AS count, COALESCE(SUM(size), 0) AS totalSize, " +
        "(SELECT m.path FROM media m WHERE m.folderPath = media.folderPath ORDER BY m.dateTaken DESC, m.rowid DESC LIMIT 1) AS coverPath, " +
        "MAX(dateTaken) AS latestDate, MAX(hidden) AS hidden " +
        "FROM media GROUP BY folderPath"

/** A folder's totals for its app bar: rows, bytes, and whether its rows are flagged hidden. */
data class FolderStats(val count: Int, val totalSize: Long, val hidden: Boolean)
