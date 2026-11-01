package dev.kanorin.gallery.logic

/** Folders other apps write into can't be renamed: the app would just recreate the old name (spec 5.3). */
object RenamePolicy {
    private val APP_NAMES = listOf(
        "whatsapp", "telegram", "instagram", "messenger", "facebook", "snapchat",
        "tiktok", "discord", "twitter", "signal", "viber", "wechat",
    )

    /** [roots] = every mounted storage root (StorageLayout.roots): none of them is renamed, nor is [storageRoot]. */
    fun canRename(folderPath: String, storageRoot: String, roots: Collection<String> = emptyList()): Boolean {
        val path = norm(folderPath)
        val all = (roots + storageRoot).map(::norm)
        if (path in all) return false
        // Under a root, only the part below it counts; under none, the whole path.
        val root = all.firstOrNull { path.startsWith("$it/") }
        val below = if (root != null) path.substring(root.length + 1) else path
        val segments = below.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        if (segments.last() == "Screenshots") return false
        if (below == "DCIM/Camera" || below.endsWith("/DCIM/Camera")) return false
        if (hasAndroidMedia(segments)) return false
        return segments.none { s -> s == "LINE" || APP_NAMES.any { s.contains(it, ignoreCase = true) } }
    }

    private fun norm(p: String) = p.replace('\\', '/').trimEnd('/')

    private fun hasAndroidMedia(segments: List<String>) =
        segments.zipWithNext().any { (a, b) -> a == "Android" && b == "media" }
}
