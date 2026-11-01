package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DetailsTextTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, kl).toInstant().toEpochMilli()

    @Test fun cameraSettingsReadLikeACamera() {
        assertEquals("f/1.8", DetailsText.aperture(1.8))
        assertEquals("f/2.0", DetailsText.aperture(2.0))
        assertNull(DetailsText.aperture(0.0))
        assertEquals("1/120 s", DetailsText.shutter(1.0 / 120))
        assertEquals("1/120 s", DetailsText.shutter(0.0083)) // a rational stored as 83/10000
        assertEquals("1/4000 s", DetailsText.shutter(0.00025))
        assertEquals("1 s", DetailsText.shutter(1.0))
        assertEquals("2.5 s", DetailsText.shutter(2.5))
        assertNull(DetailsText.shutter(0.0))
        assertEquals("26 mm", DetailsText.focal(26f))
        assertEquals("5.4 mm", DetailsText.focal(5.4f))
    }

    @Test fun theCameraLineJoinsWhatIsKnown() {
        assertEquals(
            "f/1.8 · 1/120 s · ISO 200 · 26 mm · no flash",
            DetailsText.camera("f/1.8", "1/120 s", 200, 26f, false),
        )
        assertEquals("ISO 50 · flash", DetailsText.camera(null, null, 50, null, true))
        assertNull(DetailsText.camera(null, null, null, null, null))
    }

    @Test fun megapixelsRoundToOneDecimal() {
        assertEquals(12f, DetailsText.megapixels(4000, 3000), 0.001f)
        assertEquals("12 MP", DetailsText.megapixelsText(12f))
        assertEquals("12.2 MP", DetailsText.megapixelsText(DetailsText.megapixels(4032, 3024)))
        assertEquals("0.3 MP", DetailsText.megapixelsText(DetailsText.megapixels(640, 480)))
    }

    @Test fun theSizeLineDropsAnUnknownResolution() {
        assertEquals("4.1 MB · 4000 × 3000 · 12 MP", DetailsText.sizeLine("4.1 MB", 4000, 3000))
        assertEquals("4 KB", DetailsText.sizeLine("4 KB", 0, 0))
    }

    @Test fun coordinatesShowFourDecimals() {
        assertEquals("3.1073, 101.6067", DetailsText.coordinates(3.1073, 101.6067))
        assertEquals("-33.8688, 151.2093", DetailsText.coordinates(-33.86882, 151.20930))
    }

    @Test fun thePlaceIsLocalityThenAdminArea() {
        assertEquals("Petaling Jaya, Selangor", DetailsText.place("Petaling Jaya", "Selangor"))
        assertEquals("Selangor", DetailsText.place(null, "Selangor"))
        assertEquals("Petaling Jaya", DetailsText.place("Petaling Jaya", " "))
        assertNull(DetailsText.place(null, null))
    }

    @Test fun datesReadAsTheMockupShows() {
        val taken = at(2026, 10, 30, 14, 32, 7)
        assertEquals("Fri, 30 Oct 2026 · 14:32:07", DetailsText.dateLine(taken, kl))
        assertEquals("Taken · modified 14:32", DetailsText.modifiedLine(taken, at(2026, 10, 30, 14, 32, 40), kl))
        assertEquals("Taken · modified Sun, 1 Nov 2026 · 09:05", DetailsText.modifiedLine(taken, at(2026, 11, 1, 9, 5), kl))
        assertEquals("14:32", DetailsText.time(taken, kl))
        assertEquals("09:05", DetailsText.time(at(2026, 11, 1, 9, 5), kl))
    }

    @Test fun aVideoCodecReadsByItsCommonName() {
        assertEquals("H.264", DetailsText.codec("video/avc"))
        assertEquals("HEVC", DetailsText.codec("video/hevc"))
        assertEquals("AV1", DetailsText.codec("video/av01"))
        assertEquals("VP9", DetailsText.codec("video/x-vnd.on2.vp9"))
        assertEquals("video/x-unknown", DetailsText.codec("video/x-unknown"))
        assertEquals(null, DetailsText.codec(null))
    }

    @Test fun aResolutionIsWidthByHeight() {
        assertEquals("1920 × 1080", DetailsText.resolution(1920, 1080))
        assertEquals(null, DetailsText.resolution(0, 1080))
    }
}
