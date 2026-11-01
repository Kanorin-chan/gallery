package dev.kanorin.gallery

import android.os.Environment
import android.os.Looper
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import dev.kanorin.gallery.data.BinEntity
import dev.kanorin.gallery.index.FakeMediaProvider
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.logic.BinPolicy
import dev.kanorin.gallery.ui.bin.BIN_RESTORE_TAG
import dev.kanorin.gallery.ui.bin.binTileTag
import dev.kanorin.gallery.ui.common.ToastHost
import dev.kanorin.gallery.ui.permission.ShadowStorageManager
import dev.kanorin.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/** The real graph: the index's cold start purges the bin (Ruling AA), and the `bin` route shows it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class BinWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private lateinit var binDir: File
    private lateinit var camera: File

    @Before fun setUp() {
        val storage = tmp.newFolder("storage", "emulated", "0")
        binDir = File(storage, StorageLayout.BIN_DIR_NAME).apply { mkdirs() }
        File(binDir, ".nomedia").createNewFile()
        camera = File(storage, "DCIM/Camera").apply { mkdirs() }
        shadowOf(app.getSystemService(StorageManager::class.java)).addStorageVolume(
            StorageVolumeBuilder("emulated;0", storage, "Internal", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setIsPrimary(true).setIsEmulated(true).build(),
        )
        FakeMediaProvider.rows.clear()
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
    }

    @After fun tearDown() {
        ShadowStorageManager.granted = false
        cancelAndDrain(app.graph.appScope)
        app.graph.db.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun binned(name: String, deletedAt: Long): File = File(binDir, name).apply {
        writeBytes(byteArrayOf(1, 2, 3))
        runBlocking { app.graph.db.binDao().insert(BinEntity(path, File(camera, "orig-$name").path, deletedAt, 3, "image/jpeg", false, 0)) }
    }

    @Test fun theIndexsColdStartPurgesExpiredBinFiles() {
        val now = System.currentTimeMillis()
        val old = binned("11111111-1111-1111-1111-111111111111.jpg", now - BinPolicy.RETENTION_MS - 60_000)
        val young = binned("22222222-2222-2222-2222-222222222222.jpg", now - BinPolicy.DAY_MS)
        ShadowStorageManager.granted = true
        ActivityScenario.launch(MainActivity::class.java).use {
            val end = System.currentTimeMillis() + 10_000
            while (old.exists()) {
                check(System.currentTimeMillis() < end) { "the expired file was never purged" }
                Thread.sleep(20)
            }
        }
        assertTrue(young.isFile)
        assertTrue(runBlocking { app.graph.db.binDao().all() }.map { it.binPath } == listOf(young.path))
    }

    // Final review M9: through the real NavHost, a bin tile opens the viewer's bin mode.
    @Test fun aBinTileOpensItsFileInTheViewer() {
        binned("44444444-4444-4444-4444-444444444444.jpg", System.currentTimeMillis())
        compose.setContent { GalleryTheme { ToastHost(app.graph.toasts) { GalleryNavHost(Routes.BIN) } } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(binTileTag(0))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(binTileTag(0)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(BIN_RESTORE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Recycle bin").assertDoesNotExist()
    }

    @Test fun theBinRouteShowsTheRecycleBin() {
        binned("33333333-3333-3333-3333-333333333333.jpg", System.currentTimeMillis())
        compose.setContent { GalleryTheme { ToastHost(app.graph.toasts) { GalleryNavHost(Routes.BIN) } } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("30 days left")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Recycle bin").assertExists()
    }
}
