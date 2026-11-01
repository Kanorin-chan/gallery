package dev.kanorin.gallery.ui.viewer

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.ui.common.resolveViewPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A VIEW intent's URI -> a file under our roots (folder context), or null (a single-item viewer). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewIntentTest {
    @get:Rule val tmp = TemporaryFolder()
    private val resolver get() = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    private lateinit var internal: File
    private lateinit var card: File
    private lateinit var photo: File
    private val roots get() = listOf(internal, card)

    @Before fun setUp() {
        internal = tmp.newFolder("storage", "emulated", "0")
        card = tmp.newFolder("storage", "1234-ABCD")
        photo = File(internal, "DCIM/Camera/a b #1.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        ViewerMediaProvider.reset()
        Robolectric.setupContentProvider(ViewerMediaProvider::class.java, MediaStore.AUTHORITY)
    }

    @After fun tearDown() = ViewerMediaProvider.reset()

    private fun resolve(uri: Uri) = resolveViewPath(uri, resolver, roots)

    @Test fun aFileUriUnderARootIsThatFile() {
        assertEquals(photo.path, resolve(Uri.fromFile(photo)))
    }

    @Test fun aMediaStoreUriIsItsDataPath() {
        ViewerMediaProvider.files[7] = photo
        assertEquals(photo.path, resolve(Uri.parse("content://media/external/images/media/7")))
    }

    @Test fun anExternalStorageDocumentMapsToItsVolume() {
        val authority = "com.android.externalstorage.documents"
        assertEquals(photo.path, resolve(DocumentsContract.buildDocumentUri(authority, "primary:DCIM/Camera/a b #1.jpg")))
        val onCard = File(card, "Pictures/x.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertEquals(onCard.path, resolve(DocumentsContract.buildDocumentUri(authority, "1234-ABCD:Pictures/x.jpg")))
    }

    @Test fun aRawDownloadsDocumentIsItsPath() {
        val uri = DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents", "raw:${photo.path}")
        assertEquals(photo.path, resolve(uri))
    }

    @Test fun aMediaDocumentIsLookedUpInMediaStore() {
        ViewerMediaProvider.files[9] = photo
        assertEquals(photo.path, resolve(DocumentsContract.buildDocumentUri("com.android.providers.media.documents", "image:9")))
    }

    // Fix round 1: one file, one path; and no way round the Android/data rule.
    @Test fun dotSegmentsResolveToTheSamePath() {
        assertEquals(photo.path, resolve(Uri.fromFile(File(internal, "DCIM/./Camera/a b #1.jpg"))))
        assertEquals(photo.path, resolve(Uri.fromFile(File(internal, "Pictures/../DCIM/Camera/a b #1.jpg"))))
    }

    @Test fun dotDotCantReachIntoAndroidData() {
        val hidden = File(internal, "Android/data/org.telegram/x.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertNull(resolve(Uri.fromFile(File(internal, "DCIM/Camera/../../Android/data/org.telegram/x.jpg"))))
        assertNull(resolve(Uri.fromFile(File(internal, "Android/./data/org.telegram/x.jpg"))))
        assertEquals(true, hidden.isFile)
    }

    @Test fun aFileInTheBinOrAnUnfinishedCopyIsNotOurs() {
        val binned = File(internal, ".gallery-bin/1234.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertNull(resolve(Uri.fromFile(binned)))
        val temp = File(internal, "DCIM/Camera/.gallery-tmp-0a1b2c3d.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertNull(resolve(Uri.fromFile(temp)))
    }

    @Test fun anotherAppsProviderCantBeResolved() {
        assertNull(resolve(Uri.parse("content://com.whatsapp.provider.media/item/123")))
    }

    @Test fun aFileOutsideOurRootsOrGoneOrInAndroidDataIsNotOurs() {
        val outside = tmp.newFile("elsewhere.jpg")
        assertNull(resolve(Uri.fromFile(outside)))
        assertNull(resolve(Uri.fromFile(File(internal, "DCIM/Camera/gone.jpg"))))
        val hidden = File(internal, "Android/data/org.telegram/x.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertNull(resolve(Uri.fromFile(hidden)))
        assertNull(resolve(Uri.fromFile(File(internal, "DCIM"))))
    }
}
