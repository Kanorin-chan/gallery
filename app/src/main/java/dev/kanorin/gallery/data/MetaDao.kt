package dev.kanorin.gallery.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MetaDao {
    @Query("SELECT `value` FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(rows: List<MetaEntity>)

    @Query("DELETE FROM meta WHERE `key` LIKE :prefix || '%'")
    suspend fun deleteWithPrefix(prefix: String)
}
