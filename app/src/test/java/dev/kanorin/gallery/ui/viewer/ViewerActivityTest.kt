package dev.kanorin.gallery.ui.viewer

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ActivityScenario
import dev.kanorin.gallery.GalleryApp
import dev.kanorin.gallery.MainActivity
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.index.FakeMediaProvider
import dev.kanorin.gallery.ui.folders.folderCardTag
import dev.kanorin.gallery.ui.permission.ShadowMediaStore
import dev.kanorin.gallery.ui.permission.ShadowStorageManager
import dev.kanorin.gallery.ui.player.FakePlayer
import dev.kanorin.gallery.ui.player.PLAY_PAUSE_TAG
import dev.kanorin.gallery.ui.timeline.tileTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** Through the real app: VIEW intents (cold and while running), and grid -> viewer -> Move to folder -> back. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", shadows = [ShadowStorageManager::class, ShadowMediaStore::class])
class ViewerActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp

    private lateinit var trip: File
    private lateinit var other: File

    @Before fun setUp() {
        val storage = tmp.newFolder("storage", "emulated", "0")
        trip = File(storage, "Pictures/日本 #1 100%").apply { mkdirs() }
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

    // a is the newest, so the folder's order is a, b.
    private fun seed(vararg files: File) {
        val now = System.currentTimeMillis()
        files.forEachIndexed { i, f -> f.writeBytes(byteArrayOf(1, 2, 3)); f.setLastModified(now - i * 60_000L) }
        runBlocking {
            app.graph.db.mediaDao().insertWalkerRows(
                files.map {
                    MediaEntity(
                        path = it.path, folderPath = it.parent!!, name = it.name, mimeType = "image/jpeg", isVideo = false,
                        dateTaken = it.lastModified(), dateModified = it.lastModified(), size = it.length(), width = 4,
                        height = 3, durationMs = 0, orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
                    )
                },
            )
        }
    }

    private fun view(uri: Uri, type: String = "image/jpeg") =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).setClass(app, MainActivity::class.java)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitForTag(tag: String) = compose.waitUntil(10_000) { exists(tag) }
    private fun waitForText(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun centre(r: DpRect): Offset = with(compose.density) { Offset(((r.left + r.right) / 2).toPx(), ((r.top + r.bottom) / 2).toPx()) }

    @Test fun aViewIntentForOurFileOpensItBrowsableInItsFolderAndBackReturnsToTheCaller() {
        val a = File(trip, "a.jpg")
        val b = File(trip, "b.jpg")
        seed(a, b)
        ActivityScenario.launch<MainActivity>(view(Uri.fromFile(b))).use { scenario ->
            waitForTag(viewerPageTag(b.path))
            compose.onNodeWithTag(VIEWER_PAGER_TAG).performTouchInput { swipeRight() }
            waitForTag(viewerPageTag(a.path))
            // Every file op is there for our own file.
            assertTrue(exists(ACTION_DELETE_TAG))
            var activity: MainActivity? = null
            scenario.onActivity { activity = it; it.onBackPressedDispatcher.onBackPressed() }
            assertTrue(activity!!.isFinishing)
        }
    }

    @Test fun aFileTheIndexHasNotMetYetStillOpensInItsFolder() {
        val a = File(trip, "a.jpg")
        seed(a)
        val fresh = File(trip, "fresh.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        ActivityScenario.launch<MainActivity>(view(Uri.fromFile(fresh))).use {
            waitForTag(viewerPageTag(fresh.path))
            assertNotNull(runBlocking { app.graph.db.mediaDao().byPath(fresh.path) })
        }
    }

    @Test fun anotherAppsContentOpensAsOneItemWithShareAndEditOnly() {
        val uri = Uri.parse("content://com.example.chat.provider/media/123.jpg")
        ActivityScenario.launch<MainActivity>(view(uri)).use {
            waitForTag(viewerPageTag(uri.toString()))
            waitForTag(ACTION_SHARE_TAG)
            assertTrue(exists(ACTION_EDIT_TAG))
            assertFalse(exists(ACTION_FAVOURITE_TAG))
            assertFalse(exists(ACTION_DELETE_TAG))
            assertFalse(exists(ACTION_MORE_TAG))
            assertFalse(exists(VIEWER_MENU_TAG))
        }
    }

    @Test fun aViewIntentWhileRunningOpensTheViewer() {
        val a = File(trip, "a.jpg")
        seed(a)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        waitForText("Search photos")
        controller.newIntent(view(Uri.fromFile(a)))
        waitForTag(viewerPageTag(a.path))
        controller.pause().stop().destroy()
    }

    // Final review M10: cameras hand over their last shot with REVIEW; it resolves to us and opens like VIEW.
    @Test fun reviewIntentsAreDeclaredAndOpenLikeView() {
        val a = File(trip, "a.jpg")
        seed(a)
        for (action in listOf(MediaStore.ACTION_REVIEW, "com.android.camera.action.REVIEW")) {
            for (type in listOf("image/jpeg", "video/mp4")) {
                val ask = Intent(action).setDataAndType(Uri.fromFile(a), type).addCategory(Intent.CATEGORY_DEFAULT)
                val found = app.packageManager.queryIntentActivities(ask, 0)
                assertTrue("$action $type", found.any { it.activityInfo.name == MainActivity::class.java.name })
            }
            val review = Intent(action).setDataAndType(Uri.fromFile(a), "image/jpeg").setClass(app, MainActivity::class.java)
            ActivityScenario.launch<MainActivity>(review).use { waitForTag(viewerPageTag(a.path)) }
        }
    }

    @Test fun aVideoViewIntentOpensThePlayerNotTheViewer() {
        val clip = File(trip, "clip.mp4").apply { writeBytes(byteArrayOf(1)) }
        val fake = FakePlayer()
        app.graph.newPlayer = { fake }
        ActivityScenario.launch<MainActivity>(view(Uri.fromFile(clip), "video/mp4")).use {
            waitForTag(PLAY_PAUSE_TAG)
            assertFalse(exists(VIEWER_PAGER_TAG))
            // The controls draw before the open (a lookup off Main) hands the item over: wait for it, don't race it.
            compose.waitUntil(10_000) { compose.waitForIdle(); fake.item != null }
            assertTrue(fake.item?.localConfiguration?.uri.toString().endsWith("clip.mp4"))
        }
    }

    @Test fun aTileOpensTheViewerAndMoveToFolderGoesThroughThePickerBackToTheViewer() {
        val a = File(trip, "a.jpg")
        val b = File(trip, "b.jpg")
        seed(a, b, File(other, "o.jpg"))
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Search photos")
            compose.onNodeWithText("All").performClick() // -> Folders
            waitForTag(folderCardTag(trip.path))
            compose.onNodeWithTag(folderCardTag(trip.path)).performClick()
            waitForTag(tileTag(1))
            val p = centre(compose.onNodeWithTag(tileTag(0)).getUnclippedBoundsInRoot())
            compose.onRoot().performTouchInput { click(p) }
            waitForTag(viewerPageTag(a.path))

            compose.onNodeWithTag(ACTION_MORE_TAG).performClick()
            compose.onNodeWithTag(MENU_MOVE_TAG).performClick()
            waitForText("Move to folder")
            waitForTag(folderCardTag(other.path))
            compose.onNodeWithTag(folderCardTag(other.path)).performClick()

            compose.waitUntil(10_000) { File(other, "a.jpg").isFile }
            assertFalse(a.exists())
            waitForText("Moved 1 item")
            // Back on the viewer, which moved on to the next photo of the folder.
            waitForTag(viewerPageTag(b.path))
        }
    }
}
