package dev.kanorin.gallery.ui.viewer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/** MediaStore for the viewer's tests: an id's DATA path, and its bytes (refusing the original on request). */
class ViewerMediaProvider : ContentProvider() {
    companion object {
        val files = mutableMapOf<Long, File>()
        val opened = mutableListOf<Uri>()
        var refuseOriginal = false

        fun reset() {
            files.clear()
            opened.clear()
            refuseOriginal = false
        }
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val cols = projection ?: arrayOf(MediaStore.MediaColumns.DATA)
        val file = uri.lastPathSegment?.toLongOrNull()?.let { files[it] }
        return MatrixCursor(cols).apply {
            if (file != null) addRow(cols.map { if (it == MediaStore.MediaColumns.DATA) file.path else null }.toTypedArray())
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        opened += uri
        if (refuseOriginal && uri.getQueryParameter("requireOriginal") == "1") throw SecurityException("no ACCESS_MEDIA_LOCATION")
        val file = uri.lastPathSegment?.toLongOrNull()?.let { files[it] } ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
