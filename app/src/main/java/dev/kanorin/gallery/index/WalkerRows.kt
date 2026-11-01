package dev.kanorin.gallery.index

import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.logic.DateTaken
import dev.kanorin.gallery.logic.HiddenRules
import java.io.File

/** A row for a file the app learns by itself (the walk, a Recycle bin restore): the same facts either way. */
internal object WalkerRows {
    private const val NOMEDIA = ".nomedia"

    /** null = not a media file under a known root. Probes the file; the rule hidden only (the DAO applies overrides). */
    fun build(file: File, roots: List<File>, hasNomedia: (String) -> Boolean = nomediaLookup()): MediaEntity? {
        val folder = file.parent ?: return null
        val root = StorageLayout.rootOf(folder, roots) ?: return null
        val mimeType = FsWalker.mimeTypeOf(file.name) ?: return null
        if (!file.isFile) return null // a folder with a media-like name
        val isVideo = mimeType.startsWith("video/")
        val modified = file.lastModified()
        val facts = if (isVideo) MediaProbe.video(file) else MediaProbe.image(file, mimeType)
        return MediaEntity(
            path = file.path,
            folderPath = folder,
            name = file.name,
            mimeType = mimeType,
            isVideo = isVideo,
            dateTaken = DateTaken.resolve(0, { facts.exifDate }, modified),
            dateModified = modified,
            size = file.length(),
            width = facts.width,
            height = facts.height,
            durationMs = facts.durationMs,
            orientation = facts.orientation,
            mediaStoreId = null,
            hidden = isHidden(folder, roots, hasNomedia),
            volume = StorageLayout.volumeNameOf(root),
        )
    }

    // The storage root is never itself a hidden folder, so a .nomedia at an SD card's top hides nothing.
    fun isHidden(folder: String, roots: List<File>, hasNomedia: (String) -> Boolean): Boolean =
        HiddenRules.isHidden(folder, StorageLayout.rootOf(folder, roots)?.path.orEmpty(), hasNomedia)

    /** One stat per folder per pass. */
    fun nomediaLookup(): (String) -> Boolean {
        val cache = HashMap<String, Boolean>()
        return { dir -> cache.getOrPut(dir) { File(dir, NOMEDIA).exists() } }
    }
}
