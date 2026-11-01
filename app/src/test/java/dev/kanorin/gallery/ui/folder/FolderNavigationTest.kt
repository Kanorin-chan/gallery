package dev.kanorin.gallery.ui.folder

import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextEquals
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
import dev.kanorin.gallery.ui.common.MOVE_TO_FOLDER_TAG
import dev.kanorin.gallery.ui.folders.folderCardTag
import dev.kanorin.gallery.ui.permission.ShadowMediaStore
import dev.kanorin.gallery.ui.permission.ShadowStorageManager
import dev.kanorin.gallery.ui.timeline.tileTag
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/** Through the real app: Folders -> a folder card -> the folder route -> pick a folder to move into -> back in the folder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", shadows = [ShadowStorageManager::class, ShadowMediaStore::class])
class FolderNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp

    private lateinit var awkward: File
    private lateinit var other: File

    @Before fun setUp() {
        val storage = tmp.newFolder("storage", "emulated", "0")
        // Spaces, '#', '%' and CJK all travel in the route.
        awkward = File(storage, "Pictures/日本 #1 100%").apply { mkdirs() }
        other = File(storage, "Pictures/Other").apply { mkdirs() }
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

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitForTag(tag: String) = compose.waitUntil(10_000) { exists(tag) }
    private fun waitForText(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun aTappedFolderOpensAndMovesThroughThePickerBackToItself() {
        seed(listOf(File(awkward, "a.jpg"), File(awkward, "b.jpg"), File(other, "o.jpg")))
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Search photos")
            compose.onNodeWithText("All").performClick() // the mode button -> Folders
            waitForTag(folderCardTag(awkward.path))
            compose.onNodeWithTag(folderCardTag(awkward.path)).performClick()

            // The route found the folder whatever its name holds.
            waitForTag(FOLDER_TITLE_TAG)
            compose.onNodeWithTag(FOLDER_TITLE_TAG).assertTextEquals("日本 #1 100%")
            waitForTag(tileTag(1))

            val p = centre(compose.onNodeWithTag(tileTag(0)).getUnclippedBoundsInRoot())
            compose.onRoot().performTouchInput { down(p) }
            compose.mainClock.advanceTimeBy(400)
            compose.onRoot().performTouchInput { up() }
            compose.onNodeWithContentDescription("More").performClick()
            compose.onNodeWithTag(MOVE_TO_FOLDER_TAG).performClick()

            waitForTag(folderCardTag(other.path))
            waitForText("Move to folder")
            compose.onNodeWithTag(folderCardTag(other.path)).performClick()

            compose.waitUntil(10_000) { File(other, "a.jpg").isFile }
            assertFalse(File(awkward, "a.jpg").exists())
            assertTrue(File(awkward, "b.jpg").isFile)
            waitForText("Moved 1 item")
            // Back on the folder screen, now one item lighter.
            waitForTag(FOLDER_SUBTITLE_TAG)
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("1 item", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun favouritesOpensItsOwnRoute() {
        val fav = File(other, "o.jpg")
        seed(listOf(fav))
        runBlocking { app.graph.db.openHelper.writableDatabase.execSQL("INSERT INTO favorites(path, addedAt) VALUES (?, 1)", arrayOf(fav.path)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Search photos")
            compose.onNodeWithText("All").performClick()
            waitForTag(folderCardTag("favorites://"))
            compose.onNodeWithTag(folderCardTag("favorites://")).performClick()
            waitForTag(FOLDER_TITLE_TAG)
            compose.onNodeWithTag(FOLDER_TITLE_TAG).assertTextEquals("Favourites")
            waitForTag(tileTag(0))
        }
    }
}
