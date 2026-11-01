package dev.kanorin.gallery.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.SortField
import dev.kanorin.gallery.logic.Section
import dev.kanorin.gallery.logic.TimelineGrouping
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

/** The tables the grid queries read, as names. Keep in step with the observedEntities of pagingRaw and sectionCountsRaw (FilterQueryTest checks). */
internal val PAGED_TABLES = arrayOf("media", "favorites")

@Dao
abstract class MediaDao(private val db: RoomDatabase) {
    @Upsert
    abstract suspend fun upsert(rows: List<MediaEntity>)

    /** Any size; split under SQLite's 999 bind-variable limit (API 30). */
    open suspend fun deleteByPaths(paths: List<String>) {
        paths.chunked(MAX_ARGS).forEach { deletePathChunk(it) }
    }

    open suspend fun deleteByStoreIds(ids: List<Long>) {
        ids.chunked(MAX_ARGS).forEach { deleteStoreIdChunk(it) }
    }

    /**
     * MediaStore rows in: any older row with the same id (renamed elsewhere) goes first,
     * and every row already in those folders takes the folder's hidden flag.
     */
    @Transaction
    open suspend fun replaceStoreRows(rows: List<MediaEntity>) {
        deleteByStoreIds(rows.mapNotNull { it.mediaStoreId })
        upsert(rows)
        rows.associate { it.folderPath to it.hidden }.forEach { (folder, hidden) -> setFolderHidden(folder, hidden) }
        applyOverrides(rows.map { it.folderPath }.distinct())
    }

    /** The user's Hide / Unhide wins over whatever rule value a writer computed; same transaction, so nothing pre-read goes stale. */
    open suspend fun applyOverrides(folders: List<String>) {
        folders.chunked(MAX_ARGS).forEach { applyOverrideChunk(it) }
    }

    /** One folder's rule value in, its override (if any) kept. */
    @Transaction
    open suspend fun refreshFolderHidden(folder: String, ruleHidden: Boolean) {
        setFolderHidden(folder, ruleHidden)
        applyOverrides(listOf(folder))
    }

    /** Walker rows in. Never replaces a row MediaStore wrote meanwhile. */
    @Transaction
    open suspend fun insertWalkerRows(rows: List<MediaEntity>) {
        insertWalkerRaw(rows)
        applyOverrides(rows.map { it.folderPath }.distinct())
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertWalkerRaw(rows: List<MediaEntity>)

    /** A walker-found file changed on disk: rewrite its row, unless MediaStore has claimed the path. */
    @Transaction
    open suspend fun replaceWalkerRows(rows: List<MediaEntity>) {
        deleteWalkerRows(rows.map { it.path })
        insertWalkerRows(rows)
    }

    /** Deletes only walker rows: a MediaStore row at the same path stays. */
    open suspend fun deleteWalkerRows(paths: List<String>) {
        paths.chunked(MAX_ARGS).forEach { deleteWalkerChunk(it) }
    }

    @Query("SELECT path FROM media WHERE mediaStoreId IS NOT NULL")
    abstract suspend fun storePaths(): List<String>

    /** What the walk compares against: rows MediaStore doesn't know. */
    @Query("SELECT path, size, dateModified FROM media WHERE mediaStoreId IS NULL")
    abstract suspend fun walkerFacts(): List<WalkerFact>

    @Query("SELECT mediaStoreId FROM media WHERE mediaStoreId IS NOT NULL")
    abstract suspend fun storeIds(): List<Long>

    @Query("SELECT * FROM media WHERE path = :path")
    abstract suspend fun byPath(path: String): MediaEntity?

    @Query("SELECT DISTINCT folderPath, hidden FROM media")
    abstract suspend fun folderHiddenFlags(): List<FolderHidden>

    // The `hidden !=` guard makes a no-op touch no rows, so Room observers don't re-query.
    @Query("UPDATE media SET hidden = :hidden WHERE folderPath = :folderPath AND hidden != :hidden")
    abstract suspend fun setFolderHidden(folderPath: String, hidden: Boolean)

    /** A grid's items in [MediaQuery] order. [folderPath] null = every visible folder; [dev.kanorin.gallery.logic.FAVORITES_FOLDER] = favourites. */
    open fun pagingItems(filter: MediaFilter, folderPath: String?): PagingSource<Int, MediaEntity> =
        FreshPagingSource(pagingRaw(MediaQuery.items(filter, folderPath)), db.invalidationTracker, PAGED_TABLES)

    /**
     * The groups of [pagingItems], in the same order. Labels are blank: the screen fills them (Today moves at midnight).
     * [zone] must be the fixed offset the labels use.
     */
    open fun sections(
        filter: MediaFilter,
        folderPath: String?,
        zone: ZoneOffset = TimelineGrouping.fixedZone(ZoneId.systemDefault(), System.currentTimeMillis()),
    ): Flow<List<Section>> = sectionCountsRaw(MediaQuery.sections(filter, folderPath, zone)).map { rows ->
        var first = 0
        rows.map { Section(it.groupKey, "", it.itemCount, first).also { first += it.count } }
    }

    /** [pagingItems]' paths in grid order (select all, a header's group, a drag past the loaded pages). */
    open suspend fun orderedPaths(filter: MediaFilter, folderPath: String?, offset: Int = 0, limit: Int = -1): List<String> =
        pathsRaw(MediaQuery.paths(filter, folderPath, offset, limit))

    /** [path]'s item index in [pagingItems]' order; null when that grid doesn't hold it. */
    open suspend fun indexOf(filter: MediaFilter, folderPath: String?, path: String): Int? =
        positionRaw(MediaQuery.position(filter, folderPath, path))

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE path = :path)")
    abstract fun isFavorite(path: String): Flow<Boolean>

    /** A second add keeps the first time (the Favourites cover is the newest added). Removal: [PathDao.deleteFavorite]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addFavorite(row: FavoriteEntity)

    /** Total bytes of [paths] that are indexed; any size. */
    open suspend fun sumSizes(paths: Collection<String>): Long = paths.chunked(MAX_ARGS).sumOf { sumSizeChunk(it) }

    open suspend fun byPaths(paths: Collection<String>): List<MediaEntity> = paths.chunked(MAX_ARGS).flatMap { byPathChunk(it) }

    /** How many of [paths] are indexed videos (the delete dialog's subline); any size. */
    open suspend fun countVideos(paths: Collection<String>): Int = paths.chunked(MAX_ARGS).sumOf { countVideoChunk(it) }

    /** Every folder holding media, hidden ones flagged, not left out: FolderOrdering applies the rules. */
    @Query(FOLDERS_SQL)
    abstract fun folders(): Flow<List<FolderSummary>>

    @Query(
        "SELECT COUNT(*) AS count, (SELECT f.path FROM favorites f INNER JOIN media m ON m.path = f.path AND m.hidden = 0 " +
            "ORDER BY f.addedAt DESC LIMIT 1) AS coverPath " +
            "FROM favorites INNER JOIN media ON media.path = favorites.path AND media.hidden = 0",
    )
    abstract fun favouritesSummary(): Flow<FavouritesSummary>

    @Query("SELECT * FROM folder_prefs")
    abstract fun folderPrefs(): Flow<List<FolderPrefsEntity>>

    @Query("SELECT path FROM created_folders")
    abstract fun createdFolders(): Flow<List<String>>

    @Query("DELETE FROM created_folders WHERE path IN (:paths)")
    protected abstract suspend fun deleteCreatedFolders(paths: List<String>)

    /** [paths] still on disk as folders; rows of created folders deleted outside the app are dropped. Off Main (stats). */
    open suspend fun liveCreatedFolders(paths: List<String>): List<String> {
        val (live, gone) = paths.partition { File(it).isDirectory }
        if (gone.isNotEmpty()) deleteCreatedFolders(gone)
        return live
    }

    /** One folder's saved grid size and sort (null row = never set). */
    @Query("SELECT * FROM folder_prefs WHERE path = :path")
    abstract fun observePrefs(path: String): Flow<FolderPrefsEntity?>

    /** What the inside-folder app bar shows: the folder's own rows, whatever chips or search narrow the grid to. */
    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(size), 0) AS totalSize, COALESCE(MAX(hidden), 0) AS hidden FROM media WHERE folderPath = :folder")
    abstract fun folderStats(folder: String): Flow<FolderStats>

    @Query(
        "SELECT COUNT(*) AS count, COALESCE(SUM(media.size), 0) AS totalSize, 0 AS hidden " +
            "FROM favorites INNER JOIN media ON media.path = favorites.path AND media.hidden = 0",
    )
    abstract fun favouritesStats(): Flow<FolderStats>

    /** This folder's own grid size (spec 5.6). */
    @Transaction
    open suspend fun setFolderGrid(path: String, columns: Int) {
        upsertPrefs((prefsFor(path) ?: emptyPrefs(path)).copy(gridColumns = columns))
    }

    /** This folder's own sort (spec 5.6). */
    @Transaction
    open suspend fun setFolderSort(path: String, sort: SortField, descending: Boolean) {
        upsertPrefs((prefsFor(path) ?: emptyPrefs(path)).copy(sort = sort.name, descending = descending))
    }

    @Query("SELECT * FROM folder_prefs WHERE path = :path")
    protected abstract suspend fun prefsFor(path: String): FolderPrefsEntity?

    @Upsert
    protected abstract suspend fun upsertPrefs(prefs: FolderPrefsEntity)

    @Transaction
    open suspend fun setPinned(path: String, pinned: Boolean) {
        upsertPrefs((prefsFor(path) ?: emptyPrefs(path)).copy(pinned = pinned))
    }

    /** Hide / Unhide: the user's choice, stored with the rows' flag in one transaction (rows hold override ?: rule). */
    @Transaction
    open suspend fun setHiddenOverride(path: String, hidden: Boolean) {
        upsertPrefs((prefsFor(path) ?: emptyPrefs(path)).copy(hiddenOverride = hidden))
        setFolderHidden(path, hidden)
    }

    @RawQuery
    protected abstract suspend fun pathsRaw(query: SupportSQLiteQuery): List<String>

    @RawQuery
    protected abstract suspend fun positionRaw(query: SupportSQLiteQuery): Int?

    @Query("SELECT COALESCE(SUM(size), 0) FROM media WHERE path IN (:paths)")
    protected abstract suspend fun sumSizeChunk(paths: List<String>): Long

    @Query("SELECT * FROM media WHERE path IN (:paths)")
    protected abstract suspend fun byPathChunk(paths: List<String>): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM media WHERE isVideo = 1 AND path IN (:paths)")
    protected abstract suspend fun countVideoChunk(paths: List<String>): Int

    // observedEntities must name PAGED_TABLES' tables.
    @RawQuery(observedEntities = [MediaEntity::class, FavoriteEntity::class])
    protected abstract fun pagingRaw(query: SupportSQLiteQuery): PagingSource<Int, MediaEntity>

    @RawQuery(observedEntities = [MediaEntity::class, FavoriteEntity::class])
    protected abstract fun sectionCountsRaw(query: SupportSQLiteQuery): Flow<List<SectionCount>>

    @Query(
        "UPDATE media SET hidden = (SELECT hiddenOverride FROM folder_prefs WHERE path = media.folderPath) " +
            "WHERE folderPath IN (:folders) AND folderPath IN (SELECT path FROM folder_prefs WHERE hiddenOverride IS NOT NULL)",
    )
    protected abstract suspend fun applyOverrideChunk(folders: List<String>)

    @Query("DELETE FROM media WHERE path IN (:paths)")
    protected abstract suspend fun deletePathChunk(paths: List<String>)

    @Query("DELETE FROM media WHERE mediaStoreId IN (:ids)")
    protected abstract suspend fun deleteStoreIdChunk(ids: List<Long>)

    @Query("DELETE FROM media WHERE mediaStoreId IS NULL AND path IN (:paths)")
    protected abstract suspend fun deleteWalkerChunk(paths: List<String>)

    private companion object {
        const val MAX_ARGS = 500

        fun emptyPrefs(path: String) = FolderPrefsEntity(path, null, null, null, null, null)
    }
}
