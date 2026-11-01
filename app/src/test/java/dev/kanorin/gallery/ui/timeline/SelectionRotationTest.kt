package dev.kanorin.gallery.ui.timeline

import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ActivityScenario
import dev.kanorin.gallery.GalleryApp
import dev.kanorin.gallery.MainActivity
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.index.FakeMediaProvider
import dev.kanorin.gallery.ui.common.SelectionState
import dev.kanorin.gallery.ui.permission.ShadowMediaStore
import dev.kanorin.gallery.ui.permission.ShadowStorageManager
import kotlinx.coroutines.runBlocking
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

/** Review Focus 4: a selection survives the activity being recreated (rotation). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", shadows = [ShadowStorageManager::class, ShadowMediaStore::class])
class SelectionRotationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp

    private lateinit var camera: File

    @Before fun setUp() {
        val storage = tmp.newFolder("storage", "emulated", "0")
        camera = File(storage, "DCIM/Camera").apply { mkdirs() }
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
        cancelAndDrain(app.graph.appScope)
        app.graph.db.close()
    }

    /** [n] photos on disk, indexed already exactly as the walk records them, so the walk writes nothing. */
    private fun seed(n: Int) {
        val now = System.currentTimeMillis()
        val files = List(n) { i ->
            File(camera, "IMG_$i.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)); setLastModified(now - i * 60_000L) }
        }
        // A write racing the grid's first page load can leave the pages stale (see the Task 7 report).
        runBlocking {
            app.graph.db.mediaDao().insertWalkerRows(
                files.map {
                    MediaEntity(
                        path = it.path, folderPath = camera.path, name = it.name, mimeType = "image/jpeg", isVideo = false,
                        dateTaken = it.lastModified(), dateModified = it.lastModified(), size = it.length(), width = 0,
                        height = 0, durationMs = 0, orientation = 0, mediaStoreId = null, hidden = false,
                        volume = "external_primary",
                    )
                },
            )
        }
    }

    private fun centre(r: DpRect): Offset =
        with(compose.density) { Offset(((r.left + r.right) / 2).toPx(), ((r.top + r.bottom) / 2).toPx()) }

    private fun tile(i: Int) = centre(compose.onNodeWithTag(tileTag(i)).getUnclippedBoundsInRoot())

    private fun hold(i: Int) {
        val p = tile(i)
        compose.onRoot().performTouchInput { down(p) }
        compose.mainClock.advanceTimeBy(400)
    }

    @Test fun sevenSelectedSurvivesRecreation() {
        seed(12)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tileTag(11))).fetchSemanticsNodes().isNotEmpty() }
            hold(0)
            val five = tile(5)
            compose.onRoot().performTouchInput { moveTo(five); up() }
            compose.onNodeWithText("6 selected").assertExists()
            val seven = tile(7)
            compose.onRoot().performTouchInput { click(seven) }
            compose.onNodeWithText("7 selected").assertExists()

            scenario.recreate()

            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tileTag(7))).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("7 selected").assertExists()
        }
    }

    @Test fun aSelectionPastTheSaveLimitSurvivesRecreationThroughTheViewModel() {
        val n = SelectionState.SAVE_LIMIT + 5
        seed(n)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tileTag(11))).fetchSemanticsNodes().isNotEmpty() }
            hold(0)
            compose.onRoot().performTouchInput { up() }
            compose.onNodeWithContentDescription("Select all").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("$n selected")).fetchSemanticsNodes().isNotEmpty() }

            // The Saver keeps nothing above the limit; only the surviving ViewModel can carry this.
            scenario.recreate()

            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tileTag(7))).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("$n selected").assertExists()
        }
    }
}
