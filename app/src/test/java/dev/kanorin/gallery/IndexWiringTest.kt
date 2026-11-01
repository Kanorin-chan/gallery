package dev.kanorin.gallery

import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import dev.kanorin.gallery.index.FakeMediaProvider
import dev.kanorin.gallery.ui.permission.ShadowStorageManager
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File

/** The real graph: granting all-files access makes MainActivity start the index over mounted storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndexWiringTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var photo: File
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private val imagesUri: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    @Before fun setUp() {
        val storage = tmp.newFolder("storage", "emulated", "0")
        photo = File(storage, "Download/unscanned.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        shadowOf(app.getSystemService(StorageManager::class.java)).addStorageVolume(
            StorageVolumeBuilder("emulated;0", storage, "Internal", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setIsPrimary(true).setIsEmulated(true).build(),
        )
        FakeMediaProvider.rows.clear()
        FakeMediaProvider.queries.clear()
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
    }

    @After fun tearDown() {
        ShadowStorageManager.granted = false
        // Every query has ended before the pool closes (an unclosed db logs SQLiteConnection leaks in later suites).
        cancelAndDrain(app.graph.appScope)
        app.graph.db.close()
    }

    @Test fun grantingAllFilesStartsTheIndexAndCancellingTheScopeStopsIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertFalse("no index without the grant", app.graph.mediaIndex.started)
        }

        ShadowStorageManager.granted = true
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(app.graph.mediaIndex.started)
            waitFor { runBlocking { app.graph.db.mediaDao().byPath(photo.path) } != null }
            waitFor { shadowOf(app.contentResolver).getContentObservers(imagesUri).isNotEmpty() }
        }
        val row = runBlocking { app.graph.db.mediaDao().byPath(photo.path) }!!
        assertEquals("external_primary", row.volume)
        // The real MediaStore source was asked for this volume's delta.
        assertTrue(FakeMediaProvider.queries.any { it.first == Uri.parse("content://media/external_primary/file") })

        runBlocking { app.graph.appScope.coroutineContext.job.cancelAndJoin() }
        assertTrue("observer leaked", shadowOf(app.contentResolver).getContentObservers(imagesUri).isEmpty())
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not met in $timeoutMs ms" }
            Thread.sleep(20)
        }
    }
}
