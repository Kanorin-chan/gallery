package dev.kanorin.gallery.ui.viewer

import android.graphics.Bitmap
import android.location.Address
import android.location.Geocoder
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.data.MediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowGeocoder
import java.io.File
import java.util.Locale

/** Spec 5.7 details from real JPEGs carrying EXIF (written here, so the fixture is in the test). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun setUp() {
        ViewerMediaProvider.reset()
        Robolectric.setupContentProvider(ViewerMediaProvider::class.java, MediaStore.AUTHORITY)
    }

    @After fun tearDown() = ViewerMediaProvider.reset()

    private fun jpeg(name: String, exif: (ExifInterface.() -> Unit)? = null): File {
        val f = tmp.newFile(name)
        f.outputStream().use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (exif != null) ExifInterface(f.path).apply { exif(); saveAttributes() }
        return f
    }

    /** The brief's fixture: f/1.8, 1/120 s, ISO 200, 26 mm, no flash, 3.1073, 101.6067. */
    private fun camera(name: String = "IMG_20261030_143207.jpg") = jpeg(name) {
        setAttribute(ExifInterface.TAG_F_NUMBER, "1.8")
        setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (1.0 / 120).toString())
        setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "200")
        setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "54/10")
        setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "26")
        setAttribute(ExifInterface.TAG_FLASH, "16") // off, did not fire
        setLatLong(3.1073, 101.6067)
    }

    private fun row(f: File, id: Long? = null, width: Int = 4000, height: Int = 3000, orientation: Int = 0) = MediaEntity(
        path = f.path, folderPath = f.parent!!, name = f.name, mimeType = "image/jpeg", isVideo = false,
        dateTaken = 1_793_349_127_000L, dateModified = 1_793_349_160_000L, size = 4_100_000, width = width, height = height,
        durationMs = 0, orientation = orientation, mediaStoreId = id, hidden = false, volume = "external_primary",
    )

    private fun read(item: MediaEntity, geocoder: Geocoder? = null, resolver: Boolean = false) =
        runBlocking { readDetails(item, geocoder, if (resolver) context.contentResolver else null) }

    @Test fun cameraFieldsAndCoordinatesParse() {
        val f = camera()
        val d = read(row(f))
        assertEquals("f/1.8", d.aperture)
        assertEquals("1/120 s", d.shutter)
        assertEquals(200, d.iso)
        assertEquals(26f, d.focalMm)
        assertEquals(false, d.flash)
        assertEquals(3.1073, d.lat!!, 1e-4)
        assertEquals(101.6067, d.lon!!, 1e-4)
        // The row's own facts ride along.
        assertEquals(f.name, d.name)
        assertEquals(f.path, d.path)
        assertEquals(4_100_000L, d.size)
        assertEquals(1_793_349_127_000L, d.dateTaken)
        assertEquals(1_793_349_160_000L, d.dateModified)
        assertEquals(4000, d.width)
        assertEquals(3000, d.height)
        assertEquals(12f, d.megapixels, 0.001f)
    }

    @Test fun aJpegWithNoExifGivesNullsNotACrash() {
        val d = read(row(jpeg("plain.jpg")))
        assertNull(d.aperture)
        assertNull(d.shutter)
        assertNull(d.iso)
        assertNull(d.focalMm)
        assertNull(d.flash)
        assertNull(d.lat)
        assertNull(d.lon)
        assertNull(d.place)
        assertEquals("plain.jpg", d.name)
    }

    @Test fun aMissingOrCorruptFileGivesNullsNotACrash() {
        val gone = File(tmp.root, "gone.jpg")
        assertNull(read(row(gone)).aperture)
        val bad = tmp.newFile("bad.jpg").apply { writeBytes(ByteArray(2048) { (it * 31).toByte() }) }
        assertNull(read(row(bad)).lat)
    }

    @Test fun theLensFocalLengthIsUsedWhenThereIsNo35mmValue() {
        val f = jpeg("lens.jpg") { setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "26/1") }
        assertEquals(26f, read(row(f)).focalMm)
    }

    @Test fun aFiredFlashIsTrue() {
        val f = jpeg("flash.jpg") { setAttribute(ExifInterface.TAG_FLASH, "1") }
        assertEquals(true, read(row(f)).flash)
    }

    @Test fun aSidewaysPhotoReportsItsUprightResolution() {
        val d = read(row(jpeg("rot.jpg"), width = 4000, height = 3000, orientation = 90))
        assertEquals(3000, d.width)
        assertEquals(4000, d.height)
    }

    @Test fun anUnknownResolutionIsReadFromTheFile() {
        val d = read(row(jpeg("small.jpg"), width = 0, height = 0))
        assertEquals(40, d.width)
        assertEquals(30, d.height)
    }

    @Test fun thePlaceIsLocalityAndAdminAreaFromTheGeocoder() {
        val geocoder = Geocoder(context, Locale.ENGLISH)
        shadowOf(geocoder).setFromLocation(
            listOf(Address(Locale.ENGLISH).apply { locality = "Petaling Jaya"; adminArea = "Selangor" }),
        )
        assertEquals("Petaling Jaya, Selangor", read(row(camera()), geocoder).place)
    }

    @Test fun noGeocoderOrAFailingOneGivesNoPlace() {
        val f = camera()
        assertNull(read(row(f), null).place)
        val failing = Geocoder(context, Locale.ENGLISH)
        shadowOf(failing).setErrorMessage("offline")
        assertNull(read(row(f), failing).place)
        ShadowGeocoder.setIsPresent(false)
        val absent = Geocoder(context, Locale.ENGLISH)
        shadowOf(absent).setFromLocation(listOf(Address(Locale.ENGLISH).apply { locality = "X" }))
        assertNull(read(row(f), absent).place)
        // Coordinates still show without a place.
        assertEquals(3.1073, read(row(f), absent).lat!!, 1e-4)
    }

    @Test fun aPhotoWithoutCoordinatesNeverAsksTheGeocoder() {
        val geocoder = Geocoder(context, Locale.ENGLISH)
        shadowOf(geocoder).setFromLocation(listOf(Address(Locale.ENGLISH).apply { locality = "Nowhere" }))
        assertNull(read(row(jpeg("nogps.jpg")), geocoder).place)
    }

    @Test fun aMediaStoreRowIsReadAsTheOriginalSoItsLocationIsntRedacted() {
        val f = camera()
        ViewerMediaProvider.files[42] = f
        val d = read(row(f, id = 42), resolver = true)
        assertEquals(3.1073, d.lat!!, 1e-4)
        val asked = ViewerMediaProvider.opened.single()
        assertEquals("1", asked.getQueryParameter("requireOriginal"))
        assertEquals("42", asked.lastPathSegment)
    }

    @Test fun aRefusedOriginalFallsBackToTheFile() {
        val f = camera()
        ViewerMediaProvider.files[42] = f
        ViewerMediaProvider.refuseOriginal = true
        val d = read(row(f, id = 42), resolver = true)
        assertTrue(ViewerMediaProvider.opened.isNotEmpty())
        assertEquals("f/1.8", d.aperture)
        assertEquals(101.6067, d.lon!!, 1e-4)
    }
}
