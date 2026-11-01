package dev.kanorin.gallery.logic

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** The one rule for an item's "date taken" (epoch ms). */
object DateTaken {
    private val EXIF_FORMATS = listOf(
        DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"),
    )

    /** MediaStore DATE_TAKEN when > 0, else EXIF DateTimeOriginal, else [dateModified]. [exif] runs only when needed. */
    fun resolve(storeValue: Long, exif: () -> Long?, dateModified: Long): Long =
        if (storeValue > 0) storeValue else exif()?.takeIf { it > 0 } ?: dateModified

    /** EXIF DateTimeOriginal has no zone: read it as wall-clock time in [zone]. Junk gives null. */
    fun parseExif(value: String?, zone: ZoneId): Long? {
        val text = value?.trim { it <= ' ' }?.take(19) ?: return null
        for (format in EXIF_FORMATS) {
            try {
                return LocalDateTime.parse(text, format).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // try the next layout
            }
        }
        return null
    }
}
