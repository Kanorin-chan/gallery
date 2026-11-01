package dev.kanorin.gallery.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val path: String,
    val addedAt: Long,
)
