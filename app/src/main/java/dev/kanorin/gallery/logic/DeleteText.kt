package dev.kanorin.gallery.logic

import java.util.Locale

/** The delete dialog's lines and the toasts of delete, Recycle bin and restore ops (spec 5.5, mockup phone 5). */
object DeleteText {
    /** "Delete 3 items?" */
    fun title(n: Int) = "Delete ${items(n)}?"

    /** "2 photos, 1 video"; a kind with none is left out; null when both are 0. */
    fun subline(photos: Int, videos: Int): String? =
        listOfNotNull(photos.takeIf { it > 0 }?.let { plural(it, "photo") }, videos.takeIf { it > 0 }?.let { plural(it, "video") })
            .joinToString(", ").ifEmpty { null }

    /** The Recycle bin screen's confirm: "Delete 2 items forever?" */
    fun forever(n: Int) = "Delete ${items(n)} forever?"

    /** "Deleting 120 of 3,400…": [current] is the file being worked on, from 1. */
    fun progress(current: Int, total: Int) = "Deleting ${count(current)} of ${count(total)}…"

    fun restoring(current: Int, total: Int) = "Restoring ${count(current)} of ${count(total)}…"

    /** "3 moved to Recycle bin", "2 moved to Recycle bin · 1 couldn't be deleted", "3 couldn't be deleted". */
    fun binned(done: Int, failed: Int): String = result("${count(done)} moved to Recycle bin", done, failed, "deleted")

    /** "3 deleted permanently" (+ " · 1 couldn't be deleted"); [lagging] adds the late-index warning. */
    fun deleted(done: Int, failed: Int, lagging: Int = 0): String {
        val line = result("${count(done)} deleted permanently", done, failed, "deleted")
        return if (lagging > 0 && done > 0) "$line · some may take a moment to show" else line
    }

    /** "Restored 3 items" (+ " · 1 couldn't be restored"); [cardOut] = every failure was its SD card being out. */
    fun restored(done: Int, failed: Int, cardOut: Boolean = false): String =
        if (done == 0 && failed > 0 && cardOut) "The SD card isn't available"
        else result("Restored ${items(done)}", done, failed, "restored")

    /** UNDO's failures: they stay visible in the bin, to retry from there. */
    fun undoFailed(failed: Int) = "${count(failed)} couldn't be restored — they're in the Recycle bin"

    private fun result(success: String, done: Int, failed: Int, verb: String): String {
        val parts = ArrayList<String>(2)
        if (done > 0 || failed == 0) parts += success
        if (failed > 0) parts += "${count(failed)} couldn't be $verb"
        return parts.joinToString(" · ")
    }

    private fun items(n: Int) = plural(n, "item")

    private fun plural(n: Int, noun: String) = "${count(n)} $noun${if (n == 1) "" else "s"}"

    private fun count(n: Int) = String.format(Locale.ENGLISH, "%,d", n)
}
