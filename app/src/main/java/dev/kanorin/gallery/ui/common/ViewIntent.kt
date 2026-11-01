package dev.kanorin.gallery.ui.common

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import dev.kanorin.gallery.data.MediaDao
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.index.WalkerRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ViewIntent"
private const val EXTERNAL_DOCS = "com.android.externalstorage.documents"
private const val DOWNLOAD_DOCS = "com.android.providers.downloads.documents"
private const val MEDIA_DOCS = "com.android.providers.media.documents"
private const val RAW = "raw:"
private const val PRIMARY = "primary"

/**
 * The file a VIEW intent's [uri] names, when it is one of ours: an existing file under [roots], outside
 * Android/data and Android/obb. Knows file URIs, MediaStore, and the system's storage / downloads / media documents.
 * Null for anything else (another app's provider): that opens as a single item.
 */
fun resolveViewPath(uri: Uri, resolver: ContentResolver, roots: List<File>): String? {
    val path = try {
        pathOf(uri, resolver, roots)
    } catch (e: Exception) {
        Log.i(TAG, "can't resolve $uri: ${e.javaClass.simpleName}")
        null
    } ?: return null
    return ours(File(path), roots)
}

private fun pathOf(uri: Uri, resolver: ContentResolver, roots: List<File>): String? = when {
    uri.scheme == ContentResolver.SCHEME_FILE -> uri.path
    uri.scheme != ContentResolver.SCHEME_CONTENT -> null
    uri.authority == MediaStore.AUTHORITY -> dataPath(uri, resolver)
    uri.authority == EXTERNAL_DOCS -> volumePath(DocumentsContract.getDocumentId(uri), roots)
    uri.authority == DOWNLOAD_DOCS -> DocumentsContract.getDocumentId(uri).takeIf { it.startsWith(RAW) }?.removePrefix(RAW)
    uri.authority == MEDIA_DOCS -> mediaDocument(DocumentsContract.getDocumentId(uri), resolver)
    else -> null
}

// With all-files access MediaStore still hands out DATA, the same path the index uses.
@Suppress("DEPRECATION")
private fun dataPath(uri: Uri, resolver: ContentResolver): String? =
    resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

// "primary:DCIM/a.jpg" or "1234-ABCD:Pictures/a.jpg".
private fun volumePath(docId: String, roots: List<File>): String? {
    val parts = docId.split(':', limit = 2)
    if (parts.size != 2) return null
    val (volume, relative) = parts
    val root = if (volume == PRIMARY) {
        roots.firstOrNull { StorageLayout.volumeNameOf(it) == MediaStore.VOLUME_EXTERNAL_PRIMARY } ?: StorageLayout.primaryRoot()
    } else {
        roots.firstOrNull { it.name.equals(volume, ignoreCase = true) } ?: return null
    }
    return File(root, relative).path
}

// "image:42" = MediaStore's image 42.
private fun mediaDocument(docId: String, resolver: ContentResolver): String? {
    val parts = docId.split(':', limit = 2)
    if (parts.size != 2) return null
    val base = when (parts[0]) {
        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> return null
    }
    return dataPath(ContentUris.withAppendedId(base, parts[1].toLongOrNull() ?: return null), resolver)
}

// One file, one path: "." / ".." and links ("/sdcard") resolved, then written under its root as the index writes it.
private fun ours(file: File, roots: List<File>): String? {
    val real = runCatching { file.canonicalFile }.getOrNull() ?: return null
    if (!real.isFile || FileOps.blocked(real)) return null
    for (root in roots) {
        val realRoot = runCatching { root.canonicalFile }.getOrNull() ?: continue
        if (!real.path.startsWith(realRoot.path + File.separator)) continue
        val path = File(root, real.path.substring(realRoot.path.length + 1))
        val hidden = StorageLayout.underBin(path.path) || path.name.startsWith(StorageLayout.TEMP_PREFIX)
        return if (FileOps.blocked(path) || hidden) null else path.path
    }
    return null
}

/** A VIEW intent's file the index hasn't met yet gets the row the walk would give it. */
internal suspend fun indexIfNew(dao: MediaDao, path: String, roots: List<File>) {
    if (dao.byPath(path) != null) return
    withContext(Dispatchers.IO) { WalkerRows.build(File(path), roots) }?.let { dao.insertWalkerRows(listOf(it)) }
}
