package dev.kanorin.gallery.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Small key/value facts about the index itself (MediaStore version + generation per volume). */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
