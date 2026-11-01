package dev.kanorin.gallery.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A file sitting in the app's recycle bin; [originalPath] is where Restore puts it back. Times are epoch ms. */
@Entity(tableName = "bin")
data class BinEntity(
    @PrimaryKey val binPath: String,
    val originalPath: String,
    val deletedAt: Long,
    val size: Long,
    val mimeType: String,
    val isVideo: Boolean,
    val durationMs: Long,
    /** A remembered "Delete permanently" held for its UNDO: hidden from the bin screen, erased when the toast goes. */
    @ColumnInfo(defaultValue = "0") val pendingErase: Boolean = false,
)
