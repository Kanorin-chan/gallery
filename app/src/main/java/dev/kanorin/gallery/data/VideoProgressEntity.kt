package dev.kanorin.gallery.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_progress")
data class VideoProgressEntity(
    @PrimaryKey val path: String,
    val positionMs: Long,
    val updatedAt: Long,
)
