package dev.kanorin.gallery.index

import dev.kanorin.gallery.data.MediaEntity
import kotlinx.coroutines.flow.Flow

/** A volume's MediaStore state now. A new [version] means its generations restarted. */
data class VolumeGeneration(val volume: String, val version: String, val generation: Long)

/** Android's media index, read-only. Rows come back with `hidden = false`; the index decides. */
interface MediaStoreSource {
    fun currentGenerations(): List<VolumeGeneration>

    /** Images and videos on [volume] changed after [generation] (0 = all), newest first. */
    fun changedSince(volume: String, generation: Long): Sequence<MediaEntity>

    /** Every image/video id on every attached external volume. */
    fun allIds(): Set<Long>

    /** Emits whenever MediaStore reports a change. */
    fun changes(): Flow<Unit>
}
