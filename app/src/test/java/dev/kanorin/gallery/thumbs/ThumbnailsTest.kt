package dev.kanorin.gallery.thumbs

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import dev.kanorin.gallery.data.MediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
// Native graphics: the default legacy shadow fakes a 100x100 bitmap for garbage bytes.
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun jpeg(name: String, w: Int, h: Int): File {
        val f = tmp.newFile(name)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        f.outputStream().use { bmp.compress(CompressFormat.JPEG, 80, it) }
        bmp.recycle()
        return f
    }

    private fun req(path: String, id: Long? = null, video: Boolean = false, size: Int = 256, mod: Long = 1L) =
        ThumbRequest(path, id, video, size, mod)

    @Test fun decodesDownsampledFromFile() {
        val f = jpeg("big.jpg", 4000, 3000)
        val bmp = FileThumbs().load(req(f.path)).bitmap
        assertTrue("long edge ${bmp.width}x${bmp.height}", maxOf(bmp.width, bmp.height) <= 512)
        assertTrue(minOf(bmp.width, bmp.height) >= 256)
    }

    @Test fun exifOrientationIsApplied() {
        val f = jpeg("rot.jpg", 800, 400)
        ExifInterface(f.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val bmp = FileThumbs().load(req(f.path, size = 100)).bitmap
        assertTrue("${bmp.width}x${bmp.height}", bmp.height > bmp.width)
    }

    @Test fun zeroByteFileGivesErrorNotCrash() {
        val f = tmp.newFile("empty.jpg")
        assertThrowsIo { FileThumbs().load(req(f.path)) }
    }

    @Test fun corruptJpegGivesErrorNotCrash() {
        val f = tmp.newFile("bad.jpg").apply { writeBytes(ByteArray(2048) { (it * 31).toByte() }) }
        assertThrowsIo { FileThumbs().load(req(f.path)) }
    }

    @Test fun missingFileGivesError() {
        assertThrowsIo { FileThumbs().load(req(File(tmp.root, "nope.jpg").path)) }
    }

    @Test fun missingVideoGivesError() {
        assertThrowsIo { FileThumbs().load(req(File(tmp.root, "nope.mp4").path, video = true)) }
    }

    private fun assertThrowsIo(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    // --- source selection, through the interface boundary ---

    private class Fake(val tag: String, val fail: Boolean = false) : ThumbnailSource {
        val calls = mutableListOf<ThumbRequest>()
        override fun load(request: ThumbRequest): ThumbBitmap {
            calls += request
            if (fail) throw IOException("$tag failed")
            return ThumbBitmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), sampled = false)
        }
    }

    @Test fun mediaStoreIdSelectsMediaStore() {
        val ms = Fake("ms")
        val files = Fake("files")
        ThumbnailRouter(ms, files).load(req("/a.jpg", id = 7))
        assertEquals(1, ms.calls.size)
        assertEquals(0, files.calls.size)
    }

    @Test fun noMediaStoreIdSelectsFiles() {
        val ms = Fake("ms")
        val files = Fake("files")
        ThumbnailRouter(ms, files).load(req("/a.jpg", id = null))
        assertEquals(0, ms.calls.size)
        assertEquals(1, files.calls.size)
    }

    @Test fun mediaStoreFailureFallsBackToFiles() {
        val ms = Fake("ms", fail = true)
        val files = Fake("files")
        ThumbnailRouter(ms, files).load(req("/a.jpg", id = 7))
        assertEquals(1, files.calls.size)
    }

    @Test fun bothFailingIsAnError() {
        assertThrowsIo { ThumbnailRouter(Fake("ms", true), Fake("files", true)).load(req("/a.jpg", id = 7)) }
    }

    // --- key ---

    @Test fun keyChangesWithModifiedTimeAndSize() {
        val base = req("/a.jpg").cacheKey()
        assertNotEquals(base, req("/a.jpg", mod = 2L).cacheKey())
        assertNotEquals(base, req("/a.jpg", size = 128).cacheKey())
        assertNotEquals(base, req("/b.jpg").cacheKey())
        assertEquals(base, req("/a.jpg").cacheKey())
    }

    @Test fun entityMapsToRequest() {
        val e = MediaEntity(
            path = "/s/a.mp4", folderPath = "/s", name = "a.mp4", mimeType = "video/mp4", isVideo = true,
            dateTaken = 1, dateModified = 99, size = 5, width = 1, height = 1, durationMs = 1, orientation = 0,
            mediaStoreId = 12, hidden = false, volume = "external_primary",
        )
        assertEquals(ThumbRequest("/s/a.mp4", 12, true, 300, 99), e.thumbRequest(300))
    }

    // --- through the real Coil loader ---

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private fun load(r: ThumbRequest) = runBlocking {
        buildImageLoader(ctx).execute(ImageRequest.Builder(ctx).data(r).build())
    }

    @Test fun loaderDecodesFile() {
        val f = jpeg("ok.jpg", 4000, 3000)
        val result = load(req(f.path))
        assertTrue(result.toString(), result is SuccessResult)
        val bmp = (result as SuccessResult).image.toBitmap()
        assertTrue(maxOf(bmp.width, bmp.height) <= 512)
    }

    @Test fun loaderReturnsErrorResultForBrokenFiles() {
        val zero = tmp.newFile("z.jpg")
        val bad = tmp.newFile("c.jpg").apply { writeBytes(ByteArray(500) { 7 }) }
        assertTrue(load(req(zero.path)) is ErrorResult)
        assertTrue(load(req(bad.path)) is ErrorResult)
    }

    @Test fun secondLoadComesFromDiskCacheAfterFileIsGone() {
        val f = jpeg("cached.jpg", 1000, 800)
        val r = req(f.path)
        // A fresh loader each time, so the memory cache cannot answer.
        assertTrue(load(r) is SuccessResult)
        f.delete()
        assertTrue(load(r) is SuccessResult)
    }

    @Test fun sampledFlagIsTrueOnlyWhenSubsampled() {
        assertTrue(FileThumbs().load(req(jpeg("big2.jpg", 4000, 3000).path)).sampled)
        assertTrue(!FileThumbs().load(req(jpeg("small.jpg", 120, 80).path)).sampled)
    }

    @Test fun smallImageIsServedFromMemoryCacheOnSecondLoad() {
        val loader = buildImageLoader(ctx)
        val r = thumbImageRequest(ctx, req(jpeg("tiny.jpg", 120, 80).path)).newBuilder().size(300, 300).build()
        val first = runBlocking { loader.execute(r) } as SuccessResult
        val second = runBlocking { loader.execute(r) } as SuccessResult
        assertEquals(coil3.decode.DataSource.MEMORY, first.dataSource)
        assertEquals(coil3.decode.DataSource.MEMORY_CACHE, second.dataSource)
    }

    @Test fun corruptDiskEntryIsReplacedByAFreshDecode() {
        val f = jpeg("c2.jpg", 1000, 800)
        val r = req(f.path)
        val loader = buildImageLoader(ctx)
        val cache = loader.diskCache!!
        cache.openEditor(r.cacheKey())!!.apply {
            cache.fileSystem.write(data) { write(ByteArray(300) { 9 }) }
            commit()
        }
        assertTrue(runBlocking { loader.execute(ImageRequest.Builder(ctx).data(r).build()) } is SuccessResult)
        f.delete()
        // The bad entry was dropped and rewritten, so a new loader now finds a good one.
        assertTrue(load(r) is SuccessResult)
    }

    // --- MediaStore's thumbnail box ---

    @Test fun mediaStoreAsksForAFourThirdsBoxSoA43PhotoKeepsItsShortSide() {
        Robolectric.setupContentProvider(SizedThumbProvider::class.java, MediaStore.AUTHORITY)
        // What a provider returns for a 4:3 photo fitted inside the box it was asked for.
        SizedThumbProvider.reply = jpeg("fit.jpg", 342, 256)
        val thumb = MediaStoreThumbs(ctx.contentResolver).load(req("/x.jpg", id = 7, size = 256))
        assertEquals(Point(342, 342), SizedThumbProvider.asked)
        assertTrue("${thumb.bitmap.width}x${thumb.bitmap.height}", minOf(thumb.bitmap.width, thumb.bitmap.height) >= 256)
        assertTrue(thumb.sampled)
    }
}

/** Records the size loadThumbnail asks for and serves [reply]. */
class SizedThumbProvider : ContentProvider() {
    companion object {
        var asked: Point? = null
        var reply: File? = null
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor {
        asked = opts?.getParcelable(ContentResolver.EXTRA_SIZE, Point::class.java)
        val f = reply!!
        return AssetFileDescriptor(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, f.length())
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
