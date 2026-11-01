package dev.kanorin.gallery.logic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** The viewer's details sheet text (spec 5.7, mockup phone 6). English, as the rest of the UI. */
object DetailsText {
    private const val DOT = " · "
    private val DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val CLOCK_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)

    /** "f/1.8"; null when the camera didn't say. */
    fun aperture(fNumber: Double): String? = if (fNumber > 0) "f/" + "%.1f".format(Locale.ROOT, fNumber) else null

    /** "1/120 s" under a second (rounded to a whole denominator), "2.5 s" from a second up. */
    fun shutter(seconds: Double): String? = when {
        seconds <= 0 -> null
        seconds >= 1 -> "${trim(seconds)} s"
        else -> "1/${(1 / seconds).roundToLong()} s"
    }

    fun focal(mm: Float): String = "${trim(mm.toDouble())} mm"

    /** "f/1.8 · 1/120 s · ISO 200 · 26 mm · no flash" from whatever is known; null when nothing is. */
    fun camera(aperture: String?, shutter: String?, iso: Int?, focalMm: Float?, flash: Boolean?): String? = listOfNotNull(
        aperture,
        shutter,
        iso?.let { "ISO $it" },
        focalMm?.let(::focal),
        flash?.let { if (it) "flash" else "no flash" },
    ).joinToString(DOT).ifEmpty { null }

    fun megapixels(width: Int, height: Int): Float = width.toLong() * height / 1_000_000f

    fun megapixelsText(mp: Float): String = "${trim(mp.toDouble())} MP"

    /** "4.1 MB · 4000 × 3000 · 12 MP"; just the size when the resolution is unknown. [size] is already formatted. */
    fun sizeLine(size: String, width: Int, height: Int): String =
        if (width <= 0 || height <= 0) size else "$size$DOT$width × $height$DOT${megapixelsText(megapixels(width, height))}"

    fun coordinates(lat: Double, lon: Double): String = "%.4f, %.4f".format(Locale.ROOT, lat, lon)

    /** "Locality, AdminArea", whichever parts exist; null when neither. */
    fun place(locality: String?, adminArea: String?): String? =
        listOf(locality, adminArea).mapNotNull { it?.trim()?.ifEmpty { null } }.joinToString(", ").ifEmpty { null }

    /** "Fri, 30 Oct 2026 · 14:32:07". */
    fun dateLine(ms: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return DATE.format(t) + DOT + CLOCK_SECONDS.format(t)
    }

    /** "Taken · modified 14:32", with the modified date too when it's another day. */
    fun modifiedLine(taken: Long, modified: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(taken).atZone(zone)
        val m = Instant.ofEpochMilli(modified).atZone(zone)
        val shown = if (t.toLocalDate() == m.toLocalDate()) CLOCK.format(m) else DATE.format(m) + DOT + CLOCK.format(m)
        return "Taken${DOT}modified $shown"
    }

    /** "14:32", the top bar's second line. */
    fun time(ms: Long, zone: ZoneId): String = CLOCK.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** The player's Info: "H.264" for "video/avc"; an unknown MIME type as it is. */
    fun codec(mimeType: String?): String? = mimeType?.let { CODECS[it] ?: it }

    /** "1920 × 1080"; null when unknown. */
    fun resolution(width: Int, height: Int): String? = if (width > 0 && height > 0) "$width × $height" else null

    private val CODECS = mapOf(
        "video/avc" to "H.264",
        "video/hevc" to "HEVC",
        "video/av01" to "AV1",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/mp4v-es" to "MPEG-4",
        "video/3gpp" to "H.263",
        "video/dolby-vision" to "Dolby Vision",
    )

    // One decimal, dropping ".0": 12.0 -> "12", 12.19 -> "12.2".
    private fun trim(v: Double): String = "%.1f".format(Locale.ROOT, v).removeSuffix(".0")
}
