package dev.kanorin.gallery.ui.common

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import dev.kanorin.gallery.data.MediaEntity
import kotlinx.coroutines.CancellationException
import java.io.File

/** The FileProvider in the manifest; its roots are `res/xml/file_paths.xml`. */
const val FILES_AUTHORITY = "dev.kanorin.gallery.files"

/** Each URI rides the intent three times (stream, ClipData, chooser); thousands overflow the 1 MB binder buffer. */
const val SHARE_LIMIT = 500

/** The bar's share: over [SHARE_LIMIT] a [toast] and nothing loads; a failed start is a [toast], never a crash. */
suspend fun shareSelection(
    context: Context,
    paths: Collection<String>,
    rows: suspend (Collection<String>) -> List<MediaEntity>,
    toast: (String) -> Unit,
    start: (Intent) -> Unit = context::startActivity,
) {
    if (paths.size > SHARE_LIMIT) return toast("You can share up to $SHARE_LIMIT items at once")
    try {
        shareIntent(context, rows(paths))?.let(start)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "share failed", e)
        toast("Couldn't open sharing")
    }
}

private const val TAG = "Share"

/**
 * The share sheet for [rows] (spec 5.2): a MediaStore URI where MediaStore knows the file, else this app's
 * FileProvider (files only the walk found). Null when nothing can be shared.
 */
fun shareIntent(
    context: Context,
    rows: List<MediaEntity>,
    fileUri: (File) -> Uri? = { providerUri(context, it) },
): Intent? {
    val shared = rows.mapNotNull { row -> contentUri(row, fileUri)?.let { row to it } }
    if (shared.isEmpty()) return null
    val uris = shared.map { it.second }
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = when {
            shared.all { it.first.isVideo } -> "video/*"
            shared.none { it.first.isVideo } -> "image/*"
            else -> "*/*"
        }
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        // The read grant applies to ClipData; createChooser copies both onto the chooser.
        clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, null)
}

/** [row] as a URI another app can open: MediaStore's when it knows the file, else this app's FileProvider. */
fun mediaUri(context: Context, row: MediaEntity): Uri? = contentUri(row) { providerUri(context, it) }

/** A chooser handing [uri] to another app for [action] (ACTION_VIEW, ACTION_EDIT); [write] lets an editor save over it. */
fun handOffIntent(action: String, uri: Uri, mimeType: String?, write: Boolean = false): Intent {
    val target = Intent(action).apply {
        setDataAndType(uri, mimeType)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
    }
    return Intent.createChooser(target, null)
}

/** Shares a URI with no row behind it (another app's content opened in the viewer). */
fun shareUriIntent(uri: Uri, mimeType: String?): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType ?: "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, null)
}

private fun contentUri(row: MediaEntity, fileUri: (File) -> Uri?): Uri? {
    val id = row.mediaStoreId ?: return fileUri(File(row.path))
    val collection = if (row.isVideo) MediaStore.Video.Media.getContentUri(row.volume) else MediaStore.Images.Media.getContentUri(row.volume)
    return ContentUris.withAppendedId(collection, id)
}

private fun providerUri(context: Context, file: File): Uri? = try {
    FileProvider.getUriForFile(context, FILES_AUTHORITY, file)
} catch (_: IllegalArgumentException) {
    null // outside every file_paths.xml root: left out so the rest still share
}
