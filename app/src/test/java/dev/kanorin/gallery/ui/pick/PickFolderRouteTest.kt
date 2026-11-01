package dev.kanorin.gallery.ui.pick

import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import dev.kanorin.gallery.ui.common.MOVE_TO_FOLDER_TAG
import dev.kanorin.gallery.ui.folders.folderCardTag
import dev.kanorin.gallery.ui.permission.ShadowMediaStore
import dev.kanorin.gallery.ui.permission.ShadowStorageManager
import dev.kanorin.gallery.ui.timeline.tileTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
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

/** Spec 5.2 through the real app: selection ⋮ -> Move to folder -> the pick-folder route -> back, moved, toast. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", shadows = [ShadowStorageManager::class, ShadowMediaStore::class])
class PickFolderRouteTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp

    private lateinit var camera: File
    private lateinit var trip: File

    @Before fun setUp() {
        val storage = tmp.newFolder("storage", "emulated", "0")
        camera = File(storage, "DCIM/Camera").apply { mkdirs() }
        trip = File(storage, "Pictures/Trip").apply { mkdirs() }
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

    /** Files on disk, indexed exactly as the walk records them, so the walk writes nothing. */
    private fun seed(files: List<File>) {
        val now = System.currentTimeMillis()
        files.forEachIndexed { i, f -> f.writeBytes(byteArrayOf(1, 2, 3)); f.setLastModified(now - i * 60_000L) }
        runBlocking {
            app.graph.db.mediaDao().insertWalkerRows(
                files.map {
                    MediaEntity(
                        path = it.path, folderPath = it.parent!!, name = it.name, mimeType = "image/jpeg", isVideo = false,
                        dateTaken = it.lastModified(), dateModified = it.lastModified(), size = it.length(), width = 0,
                        height = 0, durationMs = 0, orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
                    )
                },
            )
        }
    }

    private fun centre(r: DpRect): Offset =
        with(compose.density) { Offset(((r.left + r.right) / 2).toPx(), ((r.top + r.bottom) / 2).toPx()) }

    private fun waitFor(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun moveToFolderGoesThroughThePickerAndComesBack() {
        seed(listOf(File(camera, "IMG_0.jpg"), File(camera, "IMG_1.jpg"), File(trip, "t.jpg")))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tileTag(2))).fetchSemanticsNodes().isNotEmpty() }
            val p = centre(compose.onNodeWithTag(tileTag(0)).getUnclippedBoundsInRoot())
            compose.onRoot().performTouchInput { down(p) }
            compose.mainClock.advanceTimeBy(400)
            compose.onRoot().performTouchInput { up() }
            compose.onNodeWithContentDescription("More").performClick()
            compose.onNodeWithTag(MOVE_TO_FOLDER_TAG).performClick()

            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(folderCardTag(trip.path))).fetchSemanticsNodes().isNotEmpty() }
            waitFor("Move to folder")
            compose.onNodeWithTag(folderCardTag(trip.path)).performClick()

            compose.waitUntil(10_000) { File(trip, "IMG_0.jpg").isFile }
            assertFalse(File(camera, "IMG_0.jpg").exists())
            waitFor("Moved 1 item")
            waitFor("Search photos")
        }
    }
}
