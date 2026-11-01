package dev.kanorin.gallery.thumbs

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Precision
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.index.FsWalker
import dev.kanorin.gallery.index.MediaProbe
import dev.kanorin.gallery.logic.reachesTarget
import dev.kanorin.gallery.logic.sampleSizeFor
import dev.kanorin.gallery.logic.thumbBoxPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okio.Path.Companion.toOkioPath
import okio.buffer
import java.io.File
import java.io.IOException

/** What one grid tile asks for. [dateModified] (ms) makes an edited file get a fresh thumbnail. */
data class ThumbRequest(
    val path: String,
    val mediaStoreId: Long?,
    val isVideo: Boolean,
    val sizePx: Int,
    val dateModified: Long,
)

fun MediaEntity.thumbRequest(sizePx: Int) = ThumbRequest(path, mediaStoreId, isVideo, sizePx, dateModified)

fun ThumbRequest.cacheKey(): String = "$path|$dateModified|$sizePx"

/** [sampled] = the bitmap is smaller than the source image (Coil then needs it as large as the tile). */
class ThumbBitmap(val bitmap: Bitmap, val sampled: Boolean)

/** Produces a bitmap at least [ThumbRequest.sizePx] on its short side where the source allows. Throws IOException on failure. */
interface ThumbnailSource {
    fun load(request: ThumbRequest): ThumbBitmap
}

/** A thumbnail whose short side reached the target was scaled down; a smaller one is the whole image. */
private fun reachedTarget(bitmap: Bitmap, sizePx: Int) = reachesTarget(bitmap.width, bitmap.height, sizePx)

/** MediaStore's own thumbnail (orientation handled by the system). */
class MediaStoreThumbs(private val resolver: ContentResolver) : ThumbnailSource {
    override fun load(request: ThumbRequest): ThumbBitmap {
        val base = if (request.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ContentUris.withAppendedId(base, request.mediaStoreId ?: throw IOException("no MediaStore id"))
        return try {
            // loadThumbnail fits inside the box; a 4:3 box-fit keeps the short side >= sizePx for a sharp crop.
            val box = thumbBoxPx(request.sizePx)
            resolver.loadThumbnail(uri, android.util.Size(box, box), null).let {
                ThumbBitmap(it, reachedTarget(it, request.sizePx))
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("loadThumbnail failed", e)
        }
    }
}

/** Walker-found files: downsampled decode for images, a scaled frame for videos. */
class FileThumbs : ThumbnailSource {
    override fun load(request: ThumbRequest): ThumbBitmap {
        val file = File(request.path)
        if (!file.isFile || file.length() == 0L) throw IOException("missing or empty: ${request.path}")
        return if (request.isVideo) videoFrame(file, request.sizePx) else image(file, request.sizePx)
    }

    private fun image(file: File, sizePx: Int): ThumbBitmap {
        val mime = FsWalker.mimeTypeOf(file.name) ?: "image/jpeg"
        val facts = MediaProbe.image(file, mime)
        if (facts.width <= 0 || facts.height <= 0) throw IOException("not a decodable image: ${file.path}")
        val sample = sampleSizeFor(facts.width, facts.height, sizePx)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = try {
            BitmapFactory.decodeFile(file.path, opts)
        } catch (e: OutOfMemoryError) {
            // A failed tile beats a dead app.
            throw IOException("out of memory decoding ${file.path}")
        } ?: throw IOException("decode failed: ${file.path}")
        return ThumbBitmap(rotated(decoded, facts.orientation), sampled = sample > 1)
    }

    private fun videoFrame(file: File, sizePx: Int): ThumbBitmap = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.path)
            fun int(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
            var w = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            var h = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0) w = h.also { h = w }
            // Scaled frames are stretched to the given size, so keep the aspect ourselves.
            val scale = if (w > 0 && h > 0) minOf(1f, sizePx.toFloat() / minOf(w, h)) else 1f
            val dw = (w * scale).toInt().coerceAtLeast(1)
            val dh = (h * scale).toInt().coerceAtLeast(1)
            fun frame(us: Long) = if (w > 0 && h > 0) {
                r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dw, dh)
            } else {
                r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            // Clips shorter than 1 s have no frame there.
            val frame = frame(1_000_000) ?: frame(0) ?: throw IOException("no video frame: ${file.path}")
            ThumbBitmap(frame, sampled = scale < 1f)
        }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("video frame failed: ${file.path}", e)
    }

    private fun rotated(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (out !== src) src.recycle()
        return out
    }
}

/** MediaStore when the file has an id, files otherwise; a failed MediaStore call still gets the file route. */
class ThumbnailRouter(private val mediaStore: ThumbnailSource, private val files: ThumbnailSource) : ThumbnailSource {
    override fun load(request: ThumbRequest): ThumbBitmap {
        if (request.mediaStoreId == null) return files.load(request)
        return try {
            mediaStore.load(request)
        } catch (e: IOException) {
            files.load(request)
        }
    }
}

internal class ThumbKeyer : Keyer<ThumbRequest> {
    override fun key(data: ThumbRequest, options: Options): String = data.cacheKey()
}

/** Reads and writes the on-disk cache itself: Coil's own disk cache only serves its network fetcher. */
internal class ThumbFetcher(
    private val request: ThumbRequest,
    private val source: ThumbnailSource,
    private val diskCache: DiskCache?,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val key = request.cacheKey()
        diskCache?.let { cache ->
            val cached = try {
                readCached(cache, key)
            } catch (e: Exception) {
                null // A broken cache must not fail the tile; decode afresh.
            }
            if (cached != null) return result(cached, DataSource.DISK)
        }
        val thumb = source.load(request)
        diskCache?.let { writeCached(it, key, thumb.bitmap) }
        return result(thumb, DataSource.MEMORY)
    }

    private fun result(thumb: ThumbBitmap, from: DataSource) =
        ImageFetchResult(thumb.bitmap.asImage(), isSampled = thumb.sampled, dataSource = from)

    private fun readCached(cache: DiskCache, key: String): ThumbBitmap? {
        val snapshot = cache.openSnapshot(key) ?: return null
        return snapshot.use { s ->
            val bitmap = BitmapFactory.decodeFile(s.data.toFile().path)
            if (bitmap == null) {
                // Unreadable entry: drop it and decode afresh.
                cache.remove(key)
                null
            } else {
                ThumbBitmap(bitmap, reachedTarget(bitmap, request.sizePx))
            }
        }
    }

    private fun writeCached(cache: DiskCache, key: String, bitmap: Bitmap) {
        val editor = cache.openEditor(key) ?: return
        try {
            cache.fileSystem.sink(editor.data).buffer().use { sink ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, sink.outputStream())
            }
            editor.commit()
        } catch (e: Exception) {
            // Best-effort cache write.
            editor.abort()
        }
    }

    class Factory(private val source: ThumbnailSource) : Fetcher.Factory<ThumbRequest> {
        override fun create(data: ThumbRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbFetcher(data, source, imageLoader.diskCache)
    }
}

private const val MEMORY_PERCENT = 0.25
private const val DISK_BYTES = 512L * 1024 * 1024
private const val DECODE_THREADS = 4

@OptIn(ExperimentalCoroutinesApi::class)
fun buildImageLoader(context: Context): ImageLoader {
    val app = context.applicationContext
    val source = ThumbnailRouter(MediaStoreThumbs(app.contentResolver), FileThumbs())
    return ImageLoader.Builder(app)
        .components {
            add(ThumbKeyer())
            add(ThumbFetcher.Factory(source))
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(app, MEMORY_PERCENT).build() }
        .diskCache { DiskCache.Builder().directory(File(app.cacheDir, "thumbs").toOkioPath()).maxSizeBytes(DISK_BYTES).build() }
        // Off the main thread, and few at a time: a fast fling must not queue dozens of decodes in RAM.
        .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(DECODE_THREADS))
        .build()
}

/** INEXACT, because Coil's EXACT default re-decodes whenever the tile px differs from the cached bitmap's size. */
fun thumbImageRequest(context: Context, request: ThumbRequest): ImageRequest =
    ImageRequest.Builder(context).data(request).precision(Precision.INEXACT).build()
