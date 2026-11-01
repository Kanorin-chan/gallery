package dev.kanorin.gallery.index

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidMediaStoreSourceTest {
    private lateinit var source: AndroidMediaStoreSource

    @Before fun setUp() {
        FakeMediaProvider.rows.clear()
        FakeMediaProvider.queries.clear()
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        source = AndroidMediaStoreSource(ApplicationProvider.getApplicationContext())
    }

    @Test fun rowsMapToEntitiesInAppUnits() {
        FakeMediaProvider.rows += FakeRow(
            id = 5, generation = 1, path = "/storage/emulated/0/DCIM/Camera/IMG_1.jpg",
            mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, mime = "image/jpeg",
            dateTaken = 1_790_000_000_123L, dateModifiedSec = 1_790_000_100L, size = 4_200_000,
            width = 4000, height = 3000, duration = 0, orientation = 90,
        )
        FakeMediaProvider.rows += FakeRow(
            id = 6, generation = 2, path = "/storage/emulated/0/Movies/旅行 🌸.mp4",
            mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, mime = "video/mp4",
            dateTaken = 0, dateModifiedSec = 1_790_000_200L, size = 9, width = 1920, height = 1080,
            duration = 3_853_000, orientation = 0,
        )
        val rows = source.changedSince("external_primary", 0).toList()

        assertEquals(listOf(6L, 5L), rows.map { it.mediaStoreId }) // newest first
        val photo = rows[1]
        assertEquals("/storage/emulated/0/DCIM/Camera/IMG_1.jpg", photo.path)
        assertEquals("/storage/emulated/0/DCIM/Camera", photo.folderPath)
        assertEquals("IMG_1.jpg", photo.name)
        assertEquals("image/jpeg", photo.mimeType)
        assertFalse(photo.isVideo)
        assertEquals(1_790_000_000_123L, photo.dateTaken)
        assertEquals(1_790_000_100_000L, photo.dateModified) // MediaStore seconds -> ms
        assertEquals(4_200_000L, photo.size)
        assertEquals(4000, photo.width)
        assertEquals(3000, photo.height)
        assertEquals(90, photo.orientation)
        assertEquals("external_primary", photo.volume)
        assertFalse(photo.hidden)
        val clip = rows[0]
        assertTrue(clip.isVideo)
        assertEquals("旅行 🌸.mp4", clip.name)
        assertEquals(3_853_000L, clip.durationMs)
        assertEquals(0L, clip.dateTaken) // the index resolves missing dates, not the source
    }

    @Test fun changedSinceReadsOnlyNewerGenerationsOfImagesAndVideos() {
        FakeMediaProvider.rows += FakeRow(id = 1, generation = 3, path = "/s/0/a.jpg")
        FakeMediaProvider.rows += FakeRow(id = 2, generation = 8, path = "/s/0/b.jpg")
        FakeMediaProvider.rows += FakeRow(id = 3, generation = 9, path = "/s/0/song.mp3", mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)
        FakeMediaProvider.rows += FakeRow(id = 4, generation = 9, path = null) // no path: skipped
        assertEquals(listOf(2L), source.changedSince("external_primary", 5).map { it.mediaStoreId }.toList())
        assertEquals(Uri.parse("content://media/external_primary/file"), FakeMediaProvider.queries.last().first)
    }

    @Test fun changedSincePagesThroughLargeLibraries() {
        val n = 1_234
        (1..n).forEach { FakeMediaProvider.rows += FakeRow(id = it.toLong(), generation = 1, path = "/s/0/DCIM/$it.jpg") }
        val ids = source.changedSince("external_primary", 0).map { it.mediaStoreId!! }.toList()
        assertEquals((n downTo 1).map { it.toLong() }, ids)
        // Bounded pages, not one 100k-row cursor.
        assertTrue(FakeMediaProvider.queries.size > 1)
        assertTrue(FakeMediaProvider.queries.all { it.second.getInt(ContentResolver.QUERY_ARG_LIMIT) in 1..1_000 })
    }

    @Test fun allIdsCoversEveryVolumeButOnlyImagesAndVideos() {
        FakeMediaProvider.rows += FakeRow(id = 1, generation = 1, path = "/s/0/a.jpg")
        FakeMediaProvider.rows += FakeRow(id = 2, generation = 1, path = "/s/0/b.mp4", mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
        FakeMediaProvider.rows += FakeRow(id = 3, generation = 1, path = "/s/0/c.mp3", mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)
        assertEquals(setOf(1L, 2L), source.allIds())
        assertEquals(Uri.parse("content://media/external/file"), FakeMediaProvider.queries.last().first)
    }
}

data class FakeRow(
    val id: Long,
    val generation: Long,
    val path: String?,
    val mediaType: Int = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
    val mime: String = "image/jpeg",
    val dateTaken: Long = 1L,
    val dateModifiedSec: Long = 1L,
    val size: Long = 1,
    val width: Int = 1,
    val height: Int = 1,
    val duration: Long = 0,
    val orientation: Int = 0,
    val volume: String = "external_primary",
)

/** Answers the two query shapes AndroidMediaStoreSource sends, from [rows]. */
class FakeMediaProvider : ContentProvider() {
    companion object {
        val rows = mutableListOf<FakeRow>()
        val queries = mutableListOf<Pair<Uri, Bundle>>()
    }

    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
        val args = queryArgs ?: Bundle()
        queries += uri to args
        val selection = args.getString(ContentResolver.QUERY_ARG_SQL_SELECTION).orEmpty()
        val bind = args.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS).orEmpty()
        check("media_type IN (1,3)" in selection) { "unexpected selection: $selection" }
        var match = rows.filter { it.mediaType == 1 || it.mediaType == 3 }
        if ("generation_modified > ?" in selection) {
            check(selection.endsWith("generation_modified > ? AND _id < ?")) { "unexpected selection: $selection" }
            match = match.filter { it.generation > bind[0].toLong() && it.id < bind[1].toLong() }
        }
        if (args.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER) == "_id DESC") match = match.sortedByDescending { it.id }
        if (args.containsKey(ContentResolver.QUERY_ARG_LIMIT)) match = match.take(args.getInt(ContentResolver.QUERY_ARG_LIMIT))
        val cols = projection!!
        return MatrixCursor(cols).apply {
            match.forEach { r -> addRow(cols.map { valueOf(r, it) }.toTypedArray()) }
        }
    }

    private fun valueOf(r: FakeRow, column: String): Any? = when (column) {
        MediaStore.MediaColumns._ID -> r.id
        MediaStore.MediaColumns.DATA -> r.path
        MediaStore.MediaColumns.MIME_TYPE -> r.mime
        MediaStore.Files.FileColumns.MEDIA_TYPE -> r.mediaType
        MediaStore.MediaColumns.DATE_TAKEN -> r.dateTaken
        MediaStore.MediaColumns.DATE_MODIFIED -> r.dateModifiedSec
        MediaStore.MediaColumns.SIZE -> r.size
        MediaStore.MediaColumns.WIDTH -> r.width
        MediaStore.MediaColumns.HEIGHT -> r.height
        MediaStore.MediaColumns.DURATION -> r.duration
        MediaStore.MediaColumns.ORIENTATION -> r.orientation
        MediaStore.MediaColumns.VOLUME_NAME -> r.volume
        else -> error("unexpected column $column")
    }

    // MediaStore.getVersion / getGeneration go through call().
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = Bundle().apply {
        putString(Intent.EXTRA_TEXT, "v1")
        putLong(Intent.EXTRA_INDEX, 0L)
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        error("AndroidMediaStoreSource must use Bundle queries (LIMIT needs QUERY_ARG_LIMIT on API 30+)")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
