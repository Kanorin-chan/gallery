package dev.kanorin.gallery.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One image or video. Times are epoch ms, size is bytes, orientation is clockwise degrees. */
@Entity(
    tableName = "media",
    indices = [
        Index(value = ["hidden", "dateTaken"]),
        Index(value = ["folderPath"]),
        // Sync deletes and dedupes by MediaStore id on every batch.
        Index(value = ["mediaStoreId"]),
    ],
)
data class MediaEntity(
    @PrimaryKey val path: String,
    val folderPath: String,
    val name: String,
    val mimeType: String,
    val isVideo: Boolean,
    val dateTaken: Long,
    val dateModified: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val orientation: Int,
    /** null = found by the filesystem walk, unknown to MediaStore. */
    val mediaStoreId: Long?,
    val hidden: Boolean,
    val volume: String,
)

/** One folder's hidden flag as stored (a folder can show twice if its rows disagree). */
data class FolderHidden(val folderPath: String, val hidden: Boolean)

/** A walker row's change markers: bytes and ms, as last seen on disk. */
data class WalkerFact(val path: String, val size: Long, val dateModified: Long)
