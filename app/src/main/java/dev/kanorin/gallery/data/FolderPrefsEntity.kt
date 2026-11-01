package dev.kanorin.gallery.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Per-folder choices; null = not set, use the default. */
@Entity(tableName = "folder_prefs")
data class FolderPrefsEntity(
    @PrimaryKey val path: String,
    val pinned: Boolean?,
    val hiddenOverride: Boolean?,
    val gridColumns: Int?,
    val sort: String?,
    val descending: Boolean?,
)
