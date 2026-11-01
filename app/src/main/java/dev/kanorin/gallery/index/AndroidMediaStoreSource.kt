package dev.kanorin.gallery.index

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import dev.kanorin.gallery.data.MediaEntity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Images and videos from MediaStore.Files on every attached external volume. */
class AndroidMediaStoreSource(private val context: Context) : MediaStoreSource {
    private val resolver: ContentResolver = context.contentResolver

    override fun currentGenerations(): List<VolumeGeneration> =
        MediaStore.getExternalVolumeNames(context).map { volume ->
            VolumeGeneration(volume, MediaStore.getVersion(context, volume), MediaStore.getGeneration(context, volume))
        }

    // Pages of PAGE rows by descending id: the newest land first and no cursor stays open between pages.
    override fun changedSince(volume: String, generation: Long): Sequence<MediaEntity> = sequence {
        var beforeId = Long.MAX_VALUE
        do {
            val page = ArrayList<MediaEntity>(PAGE)
            var read = 0
            query(
                MediaStore.Files.getContentUri(volume),
                ROW_PROJECTION,
                "$IMAGES_OR_VIDEOS AND ${MediaColumns.GENERATION_MODIFIED} > ? AND ${MediaColumns._ID} < ?",
                arrayOf(generation.toString(), beforeId.toString()),
                limit = PAGE,
            ).use { cursor ->
                val columns = Columns(cursor)
                while (cursor.moveToNext()) {
                    read++
                    beforeId = cursor.getLong(columns.id)
                    columns.toEntity(cursor)?.let(page::add)
                }
            }
            yieldAll(page)
        } while (read == PAGE)
    }

    override fun allIds(): Set<Long> =
        query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaColumns._ID), IMAGES_OR_VIDEOS, null, null)
            .use { cursor ->
                HashSet<Long>(cursor.count * 2).apply { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }

    override fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        for (uri in OBSERVED) resolver.registerContentObserver(uri, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    // A null cursor must fail the sync: an empty answer would read as "everything was deleted".
    private fun query(uri: Uri, projection: Array<String>, selection: String, args: Array<String>?, limit: Int?): Cursor {
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            if (limit != null) {
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaColumns._ID} DESC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
        }
        return resolver.query(uri, projection, queryArgs, null) ?: error("MediaStore returned no cursor for $uri")
    }

    private class Columns(c: Cursor) {
        val id = c.getColumnIndexOrThrow(MediaColumns._ID)
        private val data = c.getColumnIndexOrThrow(MediaColumns.DATA)
        private val mime = c.getColumnIndexOrThrow(MediaColumns.MIME_TYPE)
        private val mediaType = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
        private val dateTaken = c.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
        private val dateModified = c.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)
        private val size = c.getColumnIndexOrThrow(MediaColumns.SIZE)
        private val width = c.getColumnIndexOrThrow(MediaColumns.WIDTH)
        private val height = c.getColumnIndexOrThrow(MediaColumns.HEIGHT)
        private val duration = c.getColumnIndexOrThrow(MediaColumns.DURATION)
        private val orientation = c.getColumnIndexOrThrow(MediaColumns.ORIENTATION)
        private val volume = c.getColumnIndexOrThrow(MediaColumns.VOLUME_NAME)

        fun toEntity(c: Cursor): MediaEntity? {
            val path = c.getString(data) ?: return null
            val slash = path.lastIndexOf('/')
            if (slash <= 0 || slash == path.length - 1) return null
            val name = path.substring(slash + 1)
            val isVideo = c.getInt(mediaType) == FileColumns.MEDIA_TYPE_VIDEO
            return MediaEntity(
                path = path,
                folderPath = path.substring(0, slash),
                name = name,
                mimeType = c.getString(mime) ?: FsWalker.mimeTypeOf(name) ?: if (isVideo) "video/*" else "image/*",
                isVideo = isVideo,
                dateTaken = c.getLong(dateTaken), // null reads as 0; the index fills it in
                dateModified = c.getLong(dateModified) * 1000, // MediaStore stores seconds
                size = c.getLong(size),
                width = c.getInt(width),
                height = c.getInt(height),
                durationMs = c.getLong(duration),
                orientation = c.getInt(orientation),
                mediaStoreId = c.getLong(id),
                hidden = false,
                volume = c.getString(volume) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
            )
        }
    }

    private companion object {
        const val PAGE = 500
        const val IMAGES_OR_VIDEOS =
            "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE},${FileColumns.MEDIA_TYPE_VIDEO})"
        val ROW_PROJECTION = arrayOf(
            MediaColumns._ID, MediaColumns.DATA, MediaColumns.MIME_TYPE, FileColumns.MEDIA_TYPE,
            MediaColumns.DATE_TAKEN, MediaColumns.DATE_MODIFIED, MediaColumns.SIZE, MediaColumns.WIDTH,
            MediaColumns.HEIGHT, MediaColumns.DURATION, MediaColumns.ORIENTATION, MediaColumns.VOLUME_NAME,
        )
        val OBSERVED: List<Uri> = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        )
    }
}
