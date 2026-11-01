package dev.kanorin.gallery.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Path rewrites for the app's own Move / Copy / Rename / Delete (spec 7: a file is its path in every table).
 * Folder rewrites match `:old` exactly or `:oldPrefix` (= old + separator) by substr: LIKE ignores case and treats `_` `%` as wildcards.
 * OR REPLACE: a stale row already at the new path (file gone, index behind) gives way.
 */
@Dao
abstract class PathDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addCreatedFolder(row: CreatedFolderEntity)

    @Query("SELECT path FROM created_folders")
    abstract suspend fun createdFolderPaths(): List<String>

    @Query("SELECT path FROM media WHERE folderPath = :folder")
    abstract suspend fun mediaPathsIn(folder: String): List<String>

    @Query("SELECT DISTINCT folderPath FROM media WHERE folderPath = :old OR substr(folderPath, 1, length(:oldPrefix)) = :oldPrefix")
    abstract suspend fun foldersUnder(old: String, oldPrefix: String): List<String>

    /** Which of [paths] have a favourite or video progress: the rows a move must carry file by file. */
    @Query("SELECT path FROM favorites WHERE path IN (:paths) UNION SELECT path FROM video_progress WHERE path IN (:paths)")
    abstract suspend fun trackedPaths(paths: List<String>): List<String>

    @Query("UPDATE OR REPLACE favorites SET path = :to WHERE path = :from")
    abstract suspend fun moveFavorite(from: String, to: String)

    @Query("UPDATE OR REPLACE video_progress SET path = :to WHERE path = :from")
    abstract suspend fun moveVideoProgress(from: String, to: String)

    /** A file deleted for good takes its favourite with it. */
    @Query("DELETE FROM favorites WHERE path = :path")
    abstract suspend fun deleteFavorite(path: String)

    @Query("DELETE FROM video_progress WHERE path = :path")
    abstract suspend fun deleteVideoProgress(path: String)

    @Query(
        "UPDATE OR REPLACE media SET path = :new || substr(path, length(:old) + 1), " +
            "folderPath = :new || substr(folderPath, length(:old) + 1), mediaStoreId = NULL " +
            "WHERE folderPath = :old OR substr(folderPath, 1, length(:oldPrefix)) = :oldPrefix",
    )
    abstract suspend fun renameMedia(old: String, oldPrefix: String, new: String)

    @Query("UPDATE OR REPLACE favorites SET path = :new || substr(path, length(:old) + 1) WHERE substr(path, 1, length(:oldPrefix)) = :oldPrefix")
    abstract suspend fun renameFavorites(old: String, oldPrefix: String, new: String)

    @Query("UPDATE OR REPLACE video_progress SET path = :new || substr(path, length(:old) + 1) WHERE substr(path, 1, length(:oldPrefix)) = :oldPrefix")
    abstract suspend fun renameVideoProgress(old: String, oldPrefix: String, new: String)

    @Query(
        "UPDATE OR REPLACE folder_prefs SET path = :new || substr(path, length(:old) + 1) " +
            "WHERE path = :old OR substr(path, 1, length(:oldPrefix)) = :oldPrefix",
    )
    abstract suspend fun renameFolderPrefs(old: String, oldPrefix: String, new: String)

    @Query(
        "UPDATE OR REPLACE created_folders SET path = :new || substr(path, length(:old) + 1) " +
            "WHERE path = :old OR substr(path, 1, length(:oldPrefix)) = :oldPrefix",
    )
    abstract suspend fun renameCreatedFolders(old: String, oldPrefix: String, new: String)

    // Restore then puts a binned file back into the renamed folder, not a resurrected old one.
    @Query("UPDATE bin SET originalPath = :new || substr(originalPath, length(:old) + 1) WHERE substr(originalPath, 1, length(:oldPrefix)) = :oldPrefix")
    abstract suspend fun renameBinOrigins(old: String, oldPrefix: String, new: String)
}
