package dev.kanorin.gallery.data

import androidx.room.Dao
import androidx.room.Query

/** The player's resume points (spec 5.8); clearing one is [PathDao.deleteVideoProgress]. */
@Dao
abstract class VideoProgressDao {
    /** ms; null = never saved. */
    @Query("SELECT positionMs FROM video_progress WHERE path = :path")
    abstract suspend fun positionOf(path: String): Long?

    /** Written only while the index has the file: a save landing after a delete or move leaves no orphan row. */
    @Query(
        "INSERT OR REPLACE INTO video_progress (path, positionMs, updatedAt) " +
            "SELECT :path, :positionMs, :updatedAt WHERE EXISTS (SELECT 1 FROM media WHERE path = :path)",
    )
    abstract suspend fun save(path: String, positionMs: Long, updatedAt: Long)
}
