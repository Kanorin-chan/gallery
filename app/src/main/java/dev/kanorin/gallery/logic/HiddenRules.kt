package dev.kanorin.gallery.logic

import java.io.File

/** Hidden = a dot-folder, or a folder holding `.nomedia`, between an item's folder and its storage root. */
object HiddenRules {
    // '/' on Android; also the host separator so JVM tests on Windows paths work.
    private val SEPARATORS = charArrayOf('/', File.separatorChar)

    /** [hasNomedia] gets folder paths in the same form as [folderPath]. The root itself never counts. */
    fun isHidden(folderPath: String, root: String, hasNomedia: (String) -> Boolean): Boolean {
        val top = root.trimEnd(*SEPARATORS)
        var dir = folderPath.trimEnd(*SEPARATORS)
        if (dir == top) return false
        val underRoot = dir.length > top.length && dir.startsWith(top) && dir[top.length] in SEPARATORS
        val stop = if (underRoot) top.length else 0 // unknown root: check every ancestor
        while (dir.length > stop) {
            val cut = dir.lastIndexOfAny(SEPARATORS)
            if (dir.substring(cut + 1).startsWith('.') || hasNomedia(dir)) return true
            if (cut <= 0) break
            dir = dir.substring(0, cut)
        }
        return false
    }
}
