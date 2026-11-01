package dev.kanorin.gallery.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A folder made by the app, shown even while it holds no media. */
@Entity(tableName = "created_folders")
data class CreatedFolderEntity(
    @PrimaryKey val path: String,
)
