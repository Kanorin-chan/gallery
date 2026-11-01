package dev.kanorin.gallery

import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import dev.kanorin.gallery.index.FakeMediaProvider
import dev.kanorin.gallery.ui.permission.ShadowMediaStore
import dev.kanorin.gallery.ui.permission.ShadowStorageManager
import org.junit.After
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

/** Granted app: a file on storage reaches the All grid through the real index, Room, paging and NavHost. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowStorageManager::class, ShadowMediaStore::class])
class MainWiringTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp

    @Before fun setUp() {
        val storage = tmp.newFolder("storage", "emulated", "0")
        File(storage, "DCIM/Camera/fresh.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        shadowOf(app.getSystemService(StorageManager::class.java)).addStorageVolume(
            StorageVolumeBuilder("emulated;0", storage, "Internal", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setIsPrimary(true).setIsEmulated(true).build(),
        )
        FakeMediaProvider.rows.clear()
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        ShadowStorageManager.granted = true
        ShadowMediaStore.canManage = true
    }

    @After fun tearDown() {
        ShadowStorageManager.granted = false
        ShadowMediaStore.canManage = false
        // Every query has ended before the pool closes (an unclosed db logs SQLiteConnection leaks in later suites).
        cancelAndDrain(app.graph.appScope)
        app.graph.db.close()
    }

    @Test fun aFileJustWrittenShowsUnderToday() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Search photos").assertExists()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Today")).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
