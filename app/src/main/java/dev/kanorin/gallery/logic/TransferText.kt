package dev.kanorin.gallery.logic

import java.util.Locale

/** Move to folder / Copy to folder (spec 5.2). */
enum class Transfer { MOVE, COPY }

/** The toast lines for a move or copy. */
object TransferText {
    /** "Moving 120 of 3,400…": [current] is the file being worked on, from 1. */
    fun progress(kind: Transfer, current: Int, total: Int): String {
        val verb = if (kind == Transfer.MOVE) "Moving" else "Copying"
        return "$verb ${count(current)} of ${count(total)}…"
    }

    /** "Moved 3 items", "Moved 2 items · 1 couldn't be moved", "3 couldn't be copied"; [lagging] adds the late-index warning. */
    fun result(kind: Transfer, done: Int, failed: Int, lagging: Int = 0): String {
        val past = if (kind == Transfer.MOVE) "Moved" else "Copied"
        val parts = ArrayList<String>(3)
        if (done > 0 || failed == 0) parts += "$past ${count(done)} item${if (done == 1) "" else "s"}"
        if (failed > 0) parts += "${count(failed)} couldn't be ${if (kind == Transfer.MOVE) "moved" else "copied"}"
        if (lagging > 0 && done > 0) parts += "some may take a moment to show"
        return parts.joinToString(" · ")
    }

    private fun count(n: Int) = String.format(Locale.ENGLISH, "%,d", n)
}
