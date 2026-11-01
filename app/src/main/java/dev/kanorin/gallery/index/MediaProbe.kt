package dev.kanorin.gallery.index

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import dev.kanorin.gallery.logic.DateTaken
import java.io.File
import java.io.InputStream
import java.time.ZoneId

/** Reads what MediaStore would know from the file itself. Broken files give zeros, never throw. */
object MediaProbe {
    class Facts(
        val width: Int = 0,
        val height: Int = 0,
        val durationMs: Long = 0,
        val orientation: Int = 0,
        val exifDate: Long? = null,
    )

    fun image(file: File, mimeType: String): Facts {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val tags = exif(file.path, mimeType)
        return Facts(
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
            orientation = tags?.rotationDegrees ?: 0,
            exifDate = tags?.dateTimeOriginal(),
        )
    }

    fun video(file: File): Facts = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.path)
            fun int(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
            Facts(
                width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                orientation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
            )
        }
    } catch (e: Exception) {
        Facts()
    }

    /** EXIF DateTimeOriginal in the device zone, or null. */
    fun exifDateTaken(path: String, mimeType: String): Long? = exif(path, mimeType)?.dateTimeOriginal()

    private fun ExifInterface.dateTimeOriginal(): Long? =
        DateTaken.parseExif(getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), ZoneId.systemDefault())

    /** A file's EXIF, or null (unsupported type, corrupt, gone). */
    fun exif(path: String, mimeType: String): ExifInterface? {
        if (!ExifInterface.isSupportedMimeType(mimeType)) return null
        // Corrupt files throw all sorts; a broken file still gets a row, just without these facts.
        return try {
            ExifInterface(path)
        } catch (e: Exception) {
            null
        }
    }

    /** The same from a stream the caller opened (and closes), e.g. MediaStore's unredacted original. */
    fun exif(stream: InputStream, mimeType: String): ExifInterface? {
        if (!ExifInterface.isSupportedMimeType(mimeType)) return null
        return try {
            ExifInterface(stream)
        } catch (e: Exception) {
            null
        }
    }
}
