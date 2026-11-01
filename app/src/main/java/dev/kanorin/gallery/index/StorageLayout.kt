package dev.kanorin.gallery.index

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File

/** Where things live on the phone's storage. */
object StorageLayout {
    /** The app's recycle bin folder, at a storage root (has a `.nomedia` inside). */
    const val BIN_DIR_NAME = ".gallery-bin"

    /** A copy in flight is `.gallery-tmp-<8 hex>.<ext>`: the walk and the MediaStore sync never index one. */
    const val TEMP_PREFIX = ".gallery-tmp-"

    private val BIN_SEGMENT = File.separator + BIN_DIR_NAME + File.separator

    /** [path] lies anywhere under a folder named like the bin: never indexed, never binned again. */
    fun underBin(path: String): Boolean = path.contains(BIN_SEGMENT)

    /** [path] is a file directly in a mounted root's bin, with no "." or ".." segment: the only kind the bin deletes. */
    fun isBinFile(path: String, roots: List<File>): Boolean {
        if (path.split('/', File.separatorChar).any { it == "." || it == ".." }) return false
        val dir = File(path).parentFile ?: return false
        return dir.name == BIN_DIR_NAME && roots.any { it.path == dir.parent }
    }

    /** Internal shared storage (/storage/emulated/0): the same prefix MediaStore's DATA paths use. */
    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    /** Every mounted shared-storage volume (internal + SD cards): where the walker looks. */
    fun roots(context: Context): List<File> =
        context.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { it.directory }

    /** The root [path] lies in (or is), or null. */
    fun rootOf(path: String, roots: List<File>): File? = roots.firstOrNull { root ->
        path == root.path || (path.startsWith(root.path) && path[root.path.length] == File.separatorChar)
    }

    /** MediaStore's volume name for a storage root: emulated = primary, SD card = its lower-cased UUID. */
    fun volumeNameOf(root: File): String =
        if (root.parentFile?.name == "emulated") MediaStore.VOLUME_EXTERNAL_PRIMARY else root.name.lowercase()
}
