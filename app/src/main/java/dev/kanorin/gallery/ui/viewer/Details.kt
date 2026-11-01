package dev.kanorin.gallery.ui.viewer

import android.content.ContentResolver
import android.content.ContentUris
import android.location.Geocoder
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.index.MediaProbe
import dev.kanorin.gallery.logic.DetailsText
import dev.kanorin.gallery.logic.upright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

/** What the details sheet shows (spec 5.7). Times ms, size bytes, width/height upright px; EXIF fields null when absent. */
data class PhotoDetails(
    val dateTaken: Long,
    val dateModified: Long,
    val name: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val megapixels: Float,
    val path: String,
    val aperture: String?,
    val shutter: String?,
    val iso: Int?,
    val focalMm: Float?,
    val flash: Boolean?,
    val lat: Double?,
    val lon: Double?,
    val place: String?,
) {
    companion object {
        /** The row's own facts, before the file is read (the sheet shows these at once). */
        fun basic(item: MediaEntity, width: Int = item.width, height: Int = item.height): PhotoDetails {
            val (w, h) = upright(width, height, item.orientation)
            return PhotoDetails(
                item.dateTaken, item.dateModified, item.name, item.size, w, h, DetailsText.megapixels(w, h), item.path,
                null, null, null, null, null, null, null, null,
            )
        }
    }
}

private const val TAG = "Details"

/**
 * Reads [item]'s EXIF on IO. A MediaStore row is opened as the original through [resolver] (else Android redacts its
 * GPS); without ACCESS_MEDIA_LOCATION that is refused and the file is read instead. The place comes from [geocoder]
 * when the phone has one ("Locality, AdminArea"); any failure there is just no place.
 */
suspend fun readDetails(item: MediaEntity, geocoder: Geocoder?, resolver: ContentResolver? = null): PhotoDetails =
    withContext(Dispatchers.IO) {
        val base = if (item.width > 0 && item.height > 0) {
            PhotoDetails.basic(item)
        } else {
            val file = File(item.path)
            val facts = if (item.isVideo) MediaProbe.video(file) else MediaProbe.image(file, item.mimeType)
            PhotoDetails.basic(item.copy(orientation = facts.orientation), facts.width, facts.height)
        }
        val tags = (if (item.isVideo) null else exif(item, resolver)) ?: return@withContext base
        val latLong = tags.latLong
        base.copy(
            aperture = DetailsText.aperture(fNumber(tags)),
            shutter = DetailsText.shutter(tags.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)),
            iso = tags.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
            focalMm = focal(tags),
            flash = if (tags.hasAttribute(ExifInterface.TAG_FLASH)) (tags.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1) != 0 else null,
            lat = latLong?.get(0),
            lon = latLong?.get(1),
            place = latLong?.let { place(geocoder, it[0], it[1]) },
        )
    }

private fun exif(item: MediaEntity, resolver: ContentResolver?): ExifInterface? {
    val id = item.mediaStoreId
    if (resolver != null && id != null) {
        val uri = MediaStore.setRequireOriginal(ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(item.volume), id))
        try {
            resolver.openInputStream(uri)?.use { return MediaProbe.exif(it, item.mimeType) }
        } catch (e: Exception) {
            // No ACCESS_MEDIA_LOCATION (or MediaStore lost it): the file still has everything but maybe GPS.
            Log.i(TAG, "original refused, reading the file: ${e.javaClass.simpleName}")
        }
    }
    return MediaProbe.exif(item.path, item.mimeType)
}

// F-number, else the APEX aperture value (f = 2^(Av/2)).
private fun fNumber(tags: ExifInterface): Double {
    val f = tags.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
    if (f > 0) return f
    val apex = tags.getAttributeDouble(ExifInterface.TAG_APERTURE_VALUE, 0.0)
    return if (apex > 0) 2.0.pow(apex / 2) else 0.0
}

// The 35 mm equivalent when the camera wrote one (what phones advertise), else the lens's own.
private fun focal(tags: ExifInterface): Float? =
    tags.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 }?.toFloat()
        ?: tags.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.toFloat()

private fun place(geocoder: Geocoder?, lat: Double, lon: Double): String? {
    if (geocoder == null || !Geocoder.isPresent()) return null
    return try {
        @Suppress("DEPRECATION") // the listener form is API 33+; this already runs on IO
        val address = geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        DetailsText.place(address.locality, address.adminArea)
    } catch (e: Exception) {
        Log.i(TAG, "no place for the coordinates: ${e.javaClass.simpleName}")
        null
    }
}
