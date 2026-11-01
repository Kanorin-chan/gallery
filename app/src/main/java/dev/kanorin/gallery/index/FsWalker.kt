package dev.kanorin.gallery.index

import java.io.File
import java.io.IOException

/** Finds media files under the storage roots by name, including ones MediaStore never indexed. */
open class FsWalker(private val rootsOf: () -> List<File>) {
    constructor(roots: List<File>) : this({ roots })

    /** Read fresh on every call: SD cards come and go. */
    val roots: List<File> get() = rootsOf()

    /** Lazy, depth-first. Never lists `Android/data`, `Android/obb` or the bin. */
    open fun walk(): Sequence<File> = sequence {
        val visited = HashSet<String>() // real paths: a symlink loop is walked once
        for (root in roots) {
            val androidDir = File(root, "Android").path
            val pending = ArrayDeque<File>().apply { add(root) }
            while (pending.isNotEmpty()) {
                val dir = pending.removeLast()
                if (!visited.add(realPath(dir))) continue
                val names = dir.list() ?: continue
                val inAndroid = dir.path.equals(androidDir, ignoreCase = true)
                for (name in names) {
                    // A media name is taken as a file without a stat: saves ~100k syscalls per walk.
                    if (mimeTypeOf(name) != null) {
                        if (!name.startsWith(TRASHED) && !name.startsWith(PENDING) && !name.startsWith(StorageLayout.TEMP_PREFIX)) {
                            yield(File(dir, name))
                        }
                        continue
                    }
                    if (name == StorageLayout.BIN_DIR_NAME) continue
                    if (inAndroid && name.lowercase() in BLOCKED_IN_ANDROID) continue
                    val child = File(dir, name)
                    if (child.isDirectory) pending.add(child)
                }
            }
        }
    }

    private fun realPath(dir: File): String = try {
        dir.toPath().toRealPath().toString()
    } catch (e: IOException) {
        dir.absolutePath
    }

    companion object {
        // Android's own trash and half-written files: MediaStore hides them, so do we.
        private const val TRASHED = ".trashed-"
        private const val PENDING = ".pending-"
        private val BLOCKED_IN_ANDROID = setOf("data", "obb")

        val MIME_BY_EXTENSION: Map<String, String> = mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "heic" to "image/heic",
            "heif" to "image/heif",
            "avif" to "image/avif",
            "bmp" to "image/bmp",
            "dng" to "image/x-adobe-dng",
            "mp4" to "video/mp4",
            "mkv" to "video/x-matroska",
            "webm" to "video/webm",
            "3gp" to "video/3gpp",
            "mov" to "video/quicktime",
            "m4v" to "video/x-m4v",
            "avi" to "video/avi",
            "ts" to "video/mp2t",
        )

        /** null = not a media file name. */
        fun mimeTypeOf(name: String): String? {
            val dot = name.lastIndexOf('.')
            if (dot < 0) return null
            return MIME_BY_EXTENSION[name.substring(dot + 1).lowercase()]
        }
    }
}
