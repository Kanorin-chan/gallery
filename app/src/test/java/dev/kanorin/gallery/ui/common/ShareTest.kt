package dev.kanorin.gallery.ui.common

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.data.MediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.xmlpull.v1.XmlPullParser
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun row(path: String, video: Boolean = false, storeId: Long? = null, volume: String = "external_primary") = MediaEntity(
        path = path, folderPath = File(path).parent!!, name = File(path).name,
        mimeType = if (video) "video/mp4" else "image/jpeg", isVideo = video, dateTaken = 1, dateModified = 1, size = 1,
        width = 4, height = 3, durationMs = 0, orientation = 0, mediaStoreId = storeId, hidden = false, volume = volume,
    )

    @Suppress("DEPRECATION")
    private fun target(chooser: Intent): Intent = chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!

    @Suppress("DEPRECATION")
    private fun streams(send: Intent): List<Uri> = send.getParcelableArrayListExtra(Intent.EXTRA_STREAM)!!

    @Test fun mediaStoreRowsUseMediaStoreUrisAndTheRestUseTheFileProvider() {
        val rows = listOf(
            row("/storage/emulated/0/DCIM/Camera/a.jpg", storeId = 42),
            row("/storage/1a2b-3c4d/DCIM/clip.mp4", video = true, storeId = 7, volume = "1a2b-3c4d"),
            row("/storage/emulated/0/Download/walked.jpg"),
            row("/somewhere/else.jpg"),
        )
        // FileProvider matches roots with '/' and can't resolve a Windows host path, so it is faked here.
        val asked = mutableListOf<File>()
        val fileUri = { f: File -> asked += f; if (f.name == "walked.jpg") Uri.parse("content://$FILES_AUTHORITY/x/${f.name}") else null }
        val chooser = shareIntent(context, rows, fileUri)!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = target(chooser)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("*/*", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        assertEquals(listOf(File("/storage/emulated/0/Download/walked.jpg"), File("/somewhere/else.jpg")), asked)
        val uris = listOf(
            Uri.parse("content://media/external_primary/images/media/42"),
            Uri.parse("content://media/1a2b-3c4d/video/media/7"),
            Uri.parse("content://$FILES_AUTHORITY/x/walked.jpg"),
        )
        // A file no root covers is left out; the rest still share.
        assertEquals(uris, streams(send))
        assertEquals(uris, (0 until send.clipData!!.itemCount).map { send.clipData!!.getItemAt(it).uri })
        // The chooser is what gets started: it must carry the grant and the URIs itself.
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uris, (0 until chooser.clipData!!.itemCount).map { chooser.clipData!!.getItemAt(it).uri })
    }

    private fun paths(n: Int) = (0 until n).map { "/storage/emulated/0/DCIM/Camera/$it.jpg" }

    private val toasts = mutableListOf<String>()

    @Test fun overTheCapAToastSaysSoAndNothingLoadsOrStarts() {
        var loaded = false
        val started = mutableListOf<Intent>()
        runBlocking {
            shareSelection(context, paths(SHARE_LIMIT + 1), rows = { loaded = true; emptyList() }, toast = { toasts += it }, start = { started += it })
        }
        assertEquals(listOf("You can share up to 500 items at once"), toasts)
        assertNull("the app's own toast, not Android's", ShadowToast.getTextOfLatestToast())
        assertFalse("rows must not load past the cap", loaded)
        assertTrue(started.isEmpty())
    }

    @Test fun atTheCapTheChooserStarts() {
        val started = mutableListOf<Intent>()
        runBlocking {
            shareSelection(
                context, paths(SHARE_LIMIT), rows = { p -> p.mapIndexed { i, it -> row(it, storeId = i + 1L) } },
                toast = { toasts += it }, start = { started += it },
            )
        }
        assertEquals(Intent.ACTION_CHOOSER, started.single().action)
        assertEquals(SHARE_LIMIT, streams(target(started.single())).size)
        assertTrue(toasts.isEmpty())
    }

    @Test fun aStartThatThrowsIsAToastNotACrash() {
        runBlocking {
            shareSelection(context, paths(3), rows = { p -> p.mapIndexed { i, it -> row(it, storeId = i + 1L) } }, toast = { toasts += it }) {
                throw RuntimeException("android.os.TransactionTooLargeException: data parcel size 1290000 bytes")
            }
        }
        assertEquals(listOf("Couldn't open sharing"), toasts)
    }

    // Round 2 (F): the rows lookup failing is the same toast, never a crash.
    @Test fun aRowsLookupThatFailsIsAToastNotACrash() {
        val toasts = mutableListOf<String>()
        val started = mutableListOf<Intent>()
        runBlocking {
            shareSelection(context, paths(3), rows = { throw IllegalStateException("db closed") }, toast = { toasts += it }, start = { started += it })
        }
        assertEquals(listOf("Couldn't open sharing"), toasts)
        assertTrue(started.isEmpty())
    }

    @Test fun theFileProviderIsDeclaredWithInternalStorageAndEveryVolumeAsRoots() {
        val info = context.packageManager.resolveContentProvider(FILES_AUTHORITY, PackageManager.GET_META_DATA)!!
        assertEquals("androidx.core.content.FileProvider", info.name)
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        val roots = mutableListOf<Pair<String, String>>()
        context.resources.getXml(info.metaData.getInt("android.support.FILE_PROVIDER_PATHS")).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name != "paths") roots += xml.name to xml.getAttributeValue(null, "path")
            }
        }
        assertEquals(listOf("external-path" to ".", "root-path" to "storage"), roots)
    }

    @Test fun theTypeNarrowsToImagesOrVideos() {
        assertEquals("image/*", target(shareIntent(context, listOf(row("/storage/emulated/0/a.jpg", storeId = 1)))!!).type)
        val videos = listOf(row("/storage/emulated/0/a.mp4", video = true, storeId = 1), row("/storage/emulated/0/b.mp4", video = true, storeId = 2))
        assertEquals("video/*", target(shareIntent(context, videos)!!).type)
    }

    @Test fun nothingToShareGivesNoIntent() = assertNull(shareIntent(context, emptyList()))
}
