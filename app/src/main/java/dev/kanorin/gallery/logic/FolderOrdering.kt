package dev.kanorin.gallery.logic

import dev.kanorin.gallery.data.FavouritesSummary
import dev.kanorin.gallery.data.FolderPrefsEntity
import dev.kanorin.gallery.data.FolderSummary

/** One card. [coverPath] null = empty folder. [subtitle] is set only when another card has the same name. */
data class FolderCard(
    val path: String,
    val name: String,
    val subtitle: String?,
    val count: Int,
    val coverPath: String?,
    val latestDate: Long,
    val pinned: Boolean,
    /** Effective: the override if set, else the rule. */
    val hidden: Boolean,
) {
    val isFavourites: Boolean get() = path == FAVORITES_FOLDER
}

/** [hidden] lists every hidden folder whatever the show-hidden setting (the Hidden folders screen). */
data class Arranged(val pinned: List<FolderCard>, val others: List<FolderCard>, val hidden: List<FolderCard> = emptyList())

object FolderOrdering {
    const val FAVOURITES_NAME = "Favourites"

    fun arrange(
        folders: List<FolderSummary>,
        prefs: List<FolderPrefsEntity>,
        created: List<String>,
        storageRoot: String,
        showHidden: Boolean = false,
        favourites: FavouritesSummary = FavouritesSummary(0, null),
    ): Arranged {
        val root = storageRoot.trimEnd('/')
        val prefOf = prefs.associateBy { it.path }
        val cards = folders.map { f ->
            FolderCard(f.path, f.name, null, f.count, f.coverPath, f.latestDate, false, prefOf[f.path]?.hiddenOverride ?: f.hidden)
        } + created.filter { c -> folders.none { it.path == c } }.distinct().map { c ->
            val hidden = prefOf[c]?.hiddenOverride ?: HiddenRules.isHidden(c, root) { false }
            FolderCard(c, c.substringAfterLast('/'), null, 0, null, 0L, false, hidden)
        }
        val visible = cards.filter { showHidden || !it.hidden }

        val camera = visible.firstOrNull { it.path == "$root/DCIM/Camera" && prefOf[it.path]?.pinned != false }
        val shots = listOf("$root/DCIM/Screenshots", "$root/Pictures/Screenshots")
            .firstNotNullOfOrNull { p -> visible.firstOrNull { it.path == p && prefOf[p]?.pinned != false } }
        val auto = listOfNotNull(camera, shots)
        val byNewest = compareByDescending<FolderCard> { it.latestDate }.thenBy { it.name.lowercase() }.thenBy { it.path }
        val userPinned = visible.filter { prefOf[it.path]?.pinned == true && it !in auto }.sortedWith(byNewest)
        val pinnedFolders = auto + userPinned
        val others = (visible - pinnedFolders.toSet()).sortedWith(byNewest)

        val clashing = (pinnedFolders + others).groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.keys
        fun withSubtitle(c: FolderCard) = if (c.name.lowercase() in clashing) c.copy(subtitle = relativeParent(c.path, root)) else c
        val favouritesCard = FolderCard(
            FAVORITES_FOLDER, FAVOURITES_NAME, null, favourites.count, favourites.coverPath, 0L, pinned = true, hidden = false,
        )
        return Arranged(
            pinned = listOf(favouritesCard) + pinnedFolders.map { withSubtitle(it).copy(pinned = true) },
            others = others.map(::withSubtitle),
            hidden = cards.filter { it.hidden }.map { it.copy(pinned = prefOf[it.path]?.pinned == true) }
                .sortedWith(compareBy({ it.name.lowercase() }, { it.path })),
        )
    }

    /** Cards whose name contains [query], case-insensitively. Favourites is filtered like the rest. */
    fun filter(arranged: Arranged, query: String): Arranged {
        val q = query.trim()
        if (q.isEmpty()) return arranged
        fun keep(c: FolderCard) = c.name.contains(q, ignoreCase = true)
        return Arranged(arranged.pinned.filter(::keep), arranged.others.filter(::keep), arranged.hidden.filter(::keep))
    }

    // Parent folder below the storage root: "DCIM" for .../DCIM/Screenshots. Other volumes are prefixed "SD card".
    private fun relativeParent(path: String, root: String): String {
        val parent = path.substringBeforeLast('/', "")
        if (parent == root) return "Storage"
        if (parent.startsWith("$root/")) return parent.substring(root.length + 1)
        val below = parent.replace(Regex("^/storage/[^/]+/?"), "")
        return if (below.isEmpty()) SD_CARD else "$SD_CARD · $below"
    }

    private const val SD_CARD = "SD card"
}
