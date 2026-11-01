package dev.kanorin.gallery.index

import android.provider.MediaStore
import dev.kanorin.gallery.data.MediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** In-memory MediaStore: one generation counter shared by all volumes, like the real external db. */
class FakeMediaStoreSource : MediaStoreSource {
    var version = "v1"
    var generation = 0L
    var attached = listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    var failNextRead = false
    var failAllIds = false
    var onRead: () -> Unit = {}
    var beforeAllIds: () -> Unit = {}

    private val rows = LinkedHashMap<Long, Pair<Long, MediaEntity>>() // id -> (generation_modified, row)
    val changedSinceCalls: MutableList<Pair<String, Long>> = Collections.synchronizedList(mutableListOf())
    val reads = AtomicInteger()
    val readTimesNs: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    fun put(row: MediaEntity) {
        generation++
        rows[row.mediaStoreId!!] = generation to row
    }

    fun remove(id: Long) {
        generation++
        rows.remove(id)
    }

    override fun currentGenerations(): List<VolumeGeneration> {
        reads.incrementAndGet()
        readTimesNs += System.nanoTime()
        onRead()
        if (failNextRead) {
            failNextRead = false
            error("MediaStore unavailable")
        }
        return attached.map { VolumeGeneration(it, version, generation) }
    }

    override fun changedSince(volume: String, generation: Long): Sequence<MediaEntity> {
        changedSinceCalls += volume to generation
        return rows.values.filter { (gen, row) -> gen > generation && row.volume == volume }
            .map { it.second }.reversed().asSequence()
    }

    override fun allIds(): Set<Long> {
        beforeAllIds()
        check(!failAllIds) { "MediaStore unavailable" }
        return rows.values.filter { it.second.volume in attached }.map { it.second.mediaStoreId!! }.toSet()
    }

    override fun changes(): Flow<Unit> = changes

    companion object {
        fun storeRow(
            file: File,
            id: Long,
            dateTaken: Long = 1_790_000_000_000L,
            dateModified: Long = 1_790_000_100_000L,
            mimeType: String = "image/jpeg",
            durationMs: Long = 0,
            volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
        ) = MediaEntity(
            path = file.path,
            folderPath = file.parent!!,
            name = file.name,
            mimeType = mimeType,
            isVideo = mimeType.startsWith("video/"),
            dateTaken = dateTaken,
            dateModified = dateModified,
            size = 3,
            width = 4000,
            height = 3000,
            durationMs = durationMs,
            orientation = 0,
            mediaStoreId = id,
            hidden = false,
            volume = volume,
        )
    }
}
