package dev.kanorin.gallery.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The Recycle bin's rows; RecycleBin keeps them in step with the files. */
@Dao
interface BinDao {
    @Insert
    suspend fun insert(row: BinEntity)

    @Query("DELETE FROM bin WHERE binPath = :binPath")
    suspend fun delete(binPath: String)

    @Query("SELECT * FROM bin WHERE binPath = :binPath")
    suspend fun byBinPath(binPath: String): BinEntity?

    /** Newest first; held "Delete permanently" rows are left out. */
    @Query("SELECT * FROM bin WHERE pendingErase = 0 ORDER BY deletedAt DESC, binPath")
    fun items(): Flow<List<BinEntity>>

    /** What the Recycle bin screen lists: held rows aren't counted. */
    @Query("SELECT COUNT(*) FROM bin WHERE pendingErase = 0")
    fun count(): Flow<Int>

    @Query("SELECT * FROM bin")
    suspend fun all(): List<BinEntity>

    /** Held rows among [binPaths] become ordinary bin items binned at [now] (ms); other rows are left as they are. */
    @Query("UPDATE bin SET pendingErase = 0, deletedAt = :now WHERE pendingErase = 1 AND binPath IN (:binPaths)")
    suspend fun unhold(binPaths: List<String>, now: Long)
}
