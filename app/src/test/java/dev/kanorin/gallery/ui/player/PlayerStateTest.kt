package dev.kanorin.gallery.ui.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Environment
import android.os.Looper
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import dev.kanorin.gallery.GalleryApp
import dev.kanorin.gallery.MainActivity
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.index.FakeMediaProvider
import dev.kanorin.gallery.ui.permission.ShadowMediaStore
import dev.kanorin.gallery.ui.permission.ShadowStorageManager
import dev.kanorin.gallery.ui.timeline.tileTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

/** Review Focus 4: the position and play / pause survive a recreation and the rotate button. Through the real app. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-port-xhdpi", shadows = [ShadowStorageManager::class, ShadowMediaStore::class])
class PlayerStateTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private val minute = 60_000L

    private lateinit var clip: File
    private val players = mutableListOf<FakePlayer>()
    private val player get() = players.single()

    @Before fun setUp() {
        val storage = tmp.newFolder("storage", "emulated", "0")
        clip = File(storage, "Movies/talk.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(2048)) }
        shadowOf(app.getSystemService(StorageManager::class.java)).addStorageVolume(
            StorageVolumeBuilder("emulated;0", storage, "Internal", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setIsPrimary(true).setIsEmulated(true).build(),
        )
        FakeMediaProvider.rows.clear()
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        ShadowStorageManager.granted = true
        ShadowMediaStore.canManage = true
        app.graph.newPlayer = { FakePlayer(61 * minute).also { players += it } }
        runBlocking {
            app.graph.db.mediaDao().insertWalkerRows(
                listOf(
                    MediaEntity(
                        path = clip.path, folderPath = clip.parent!!, name = clip.name, mimeType = "video/mp4", isVideo = true,
                        dateTaken = 1_000, dateModified = clip.lastModified(), size = clip.length(), width = 1920, height = 1080,
                        durationMs = 61 * minute, orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
                    ),
                ),
            )
            app.graph.db.videoProgressDao().save(clip.path, 30 * minute, 1)
        }
    }

    @After fun tearDown() {
        ShadowStorageManager.granted = false
        ShadowMediaStore.canManage = false
        cancelAndDrain(app.graph.appScope)
        app.graph.db.close()
    }

    private fun open(): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(clip), "video/mp4").setClass(app, MainActivity::class.java),
    )

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun showing(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun positionAndPauseSurviveRecreationAndTheRotateButton() {
        open().use { scenario ->
            until("the player") { players.isNotEmpty() && player.item != null && compose.onAllNodes(hasTestTag(PLAY_PAUSE_TAG)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("resumed (ResumePolicy)", 30 * minute, player.positionMs)
            assertTrue("plays on open", player.playing)

            compose.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
            assertFalse(player.playing)
            player.update { positionMs = 31 * minute }
            scenario.recreate()
            until("the player again") { compose.onAllNodes(hasTestTag(PLAY_PAUSE_TAG)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("one player for the screen", 1, players.size)
            assertFalse(player.released)
            assertFalse("still paused", player.playing)
            assertEquals(31 * minute, player.positionMs)
            until("the time") { showing("0:31:00") && showing("1:01:00") }

            compose.onNodeWithTag(ROTATE_TAG).performClick()
            compose.waitForIdle()
            var requested = -1
            scenario.onActivity { requested = it.requestedOrientation }
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested)
            assertEquals(31 * minute, player.positionMs)
            scenario.recreate()
            until("the player after the rotate") { compose.onAllNodes(hasTestTag(PLAY_PAUSE_TAG)).fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { requested = it.requestedOrientation }
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested)
            assertEquals(1, players.size)
            assertEquals(31 * minute, player.positionMs)
            assertFalse(player.playing)
        }
    }

    @Test fun playingSurvivesRecreation() {
        open().use { scenario ->
            until("the player") { players.isNotEmpty() && player.item != null }
            assertTrue(player.playing)
            scenario.recreate()
            until("the player again") { compose.onAllNodes(hasTestTag(PLAY_PAUSE_TAG)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, players.size)
            assertTrue("a rotation never pauses", player.playing)
            assertEquals(30 * minute, player.positionMs)
        }
    }

    // Task 14 review: ON_STOP through the real lifecycle, not a direct onStop() call.
    @Test fun leavingTheAppPausesAndComingBackStaysPaused() {
        open().use { scenario ->
            until("playing") { players.isNotEmpty() && player.item != null && player.playing }
            player.update { positionMs = 34 * minute }
            scenario.moveToState(Lifecycle.State.CREATED)
            until("paused") { !player.playing }
            until("the resume point") { runBlocking { app.graph.db.videoProgressDao().positionOf(clip.path) } == 34 * minute }
            scenario.moveToState(Lifecycle.State.RESUMED)
            until("the controls") { compose.onAllNodes(hasTestTag(PLAY_PAUSE_TAG)).fetchSemanticsNodes().isNotEmpty() }
            assertFalse("coming back never autoplays", player.playing)
            assertEquals(1, players.size)
        }
    }

    // A locked hold speed is the user's choice: a rotation keeps it. The zoom starts over.
    @Test fun aRotationKeepsALockedSpeedAndResetsTheZoom() {
        open().use { scenario ->
            until("the player") { players.isNotEmpty() && player.item != null && compose.onAllNodes(hasTestTag(PLAY_PAUSE_TAG)).fetchSemanticsNodes().isNotEmpty() }
            val surface = compose.onNodeWithTag(PLAYER_TAG)
            surface.performTouchInput {
                pinch(center - Offset(60f, 0f), center - Offset(120f, 0f), center + Offset(60f, 0f), center + Offset(120f, 0f), 300)
            }
            compose.waitForIdle()
            // xhdpi: 360 dp = 720 px wide; twice that zoomed.
            assertEquals(1440f, compose.onNodeWithTag(VIDEO_FRAME_TAG).frameInRoot().width, 2f)
            surface.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(500)
            surface.performTouchInput { moveBy(Offset(0f, 120f)) }
            surface.performTouchInput { up() }
            until("locked") { showing("2× locked · hold to unlock") && player.speed == 2f }

            scenario.recreate()
            until("the player again") { compose.onAllNodes(hasTestTag(PLAY_PAUSE_TAG)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, players.size)
            until("still locked") { showing("2× locked · hold to unlock") }
            assertEquals(2f, player.speed)
            assertEquals("the zoom starts over", 720f, compose.onNodeWithTag(VIDEO_FRAME_TAG).frameInRoot().width, 2f)
            assertFalse(compose.onAllNodes(hasTestTag(VIDEO_ZOOM_PILL_TAG)).fetchSemanticsNodes().isNotEmpty())
        }
    }

    // Task 14 review: process death keeps the orientation from before the first player (KEY_PREVIOUS).
    @Test fun aProcessDeathStillGivesBackTheOrientationFromBefore() {
        val kit = PlayerKit(tmp.newFolder("kit"))
        try {
            val video = kit.video("a.mp4", minute)
            val saved = SavedStateHandle()
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                val first = kit.vm(video.path, saved = saved)
                scenario.onActivity {
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    it.setContent { PlayerScreen(first, onBack = {}) }
                }
                until("the player's orientation") { requested(scenario) == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR }
            }
            // The process dies with the player on screen; the new activity starts in the player's orientation.
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            var shown by mutableStateOf(true)
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                val again = kit.vm(video.path, saved = restored)
                scenario.onActivity {
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    it.setContent { if (shown) PlayerScreen(again, onBack = {}) }
                }
                until("the player") { kit.players.getOrNull(1)?.item != null }
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, requested(scenario))
                scenario.onActivity { shown = false }
                compose.waitForIdle()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, requested(scenario))
            }
        } finally {
            kit.close()
        }
    }

    private fun requested(scenario: ActivityScenario<ComponentActivity>): Int {
        var r = 0
        scenario.onActivity { r = it.requestedOrientation }
        return r
    }

    @Test fun theDefaultFollowsTheSensorAndTheLockLocksIt() {
        open().use { scenario ->
            until("the player") { players.isNotEmpty() && player.item != null }
            var requested = -1
            scenario.onActivity { requested = it.requestedOrientation }
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, requested)
            compose.onNodeWithTag(ROTATION_LOCK_TAG).performClick()
            compose.waitForIdle()
            scenario.onActivity { requested = it.requestedOrientation }
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, requested)
            until("the flash") { showing("Rotation locked") }
        }
    }

    @Test fun aGridTileOpensThePlayerAndBackSavesAndRestoresTheOrientation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            until("the grid") { compose.onAllNodes(hasTestTag(tileTag(0))).fetchSemanticsNodes().isNotEmpty() }
            var requested = 0
            scenario.onActivity { requested = it.requestedOrientation }
            val before = requested
            val tile = compose.onNodeWithTag(tileTag(0)).getUnclippedBoundsInRoot()
            val p = with(compose.density) { Offset(((tile.left + tile.right) / 2).toPx(), ((tile.top + tile.bottom) / 2).toPx()) }
            compose.onRoot().performTouchInput { click(p) }
            until("the player") { players.isNotEmpty() && player.item != null }
            assertEquals(30 * minute, player.positionMs)
            scenario.onActivity { requested = it.requestedOrientation }
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, requested)

            player.update { positionMs = 33 * minute }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            until("the player let go") { player.released }
            scenario.onActivity { requested = it.requestedOrientation }
            assertEquals("the orientation from before", before, requested)
            until("the resume point") { runBlocking { app.graph.db.videoProgressDao().positionOf(clip.path) } == 33 * minute }
        }
    }

    @Test fun theRotateButtonOverridesTheLockAndTheLockTakesItBack() {
        val kit = PlayerKit(tmp.newFolder("kit"))
        try {
            val vm = kit.vm(kit.video("a.mp4", minute).path)
            fun requested() = orientationFor(vm.rotationLocked.value, vm.forced.value)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, requested())
            assertTrue(vm.toggleRotationLock())
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, requested())
            vm.rotate(landscapeNow = false)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested())
            vm.rotate(landscapeNow = true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, requested())
            assertFalse(vm.toggleRotationLock())
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, requested())
        } finally {
            kit.close()
        }
    }

    @Test fun aStopBeforeTheVideoOpensKeepsTheResumePointAndOpensPaused() {
        val kit = PlayerKit(tmp.newFolder("kit"))
        try {
            val video = kit.video("a.mp4", 61 * minute)
            kit.progress(video.path, 30 * minute)
            val vm = kit.vm(video.path)
            vm.onStop(changingConfigurations = false) // open() still waits on Room
            until("the player") { kit.players.single().item != null }
            assertEquals(30 * minute, kit.players[0].positionMs)
            assertFalse(kit.players[0].playing)
            assertEquals(30 * minute, kit.savedProgress(video.path))
        } finally {
            kit.close()
        }
    }

    // Process death: the ViewModel is new, its SavedStateHandle is restored; that beats the stored resume point.
    @Test fun aProcessDeathComesBackToTheSavedPositionAndPause() {
        val kit = PlayerKit(tmp.newFolder("kit"))
        try {
            val video = kit.video("a.mp4", 61 * minute)
            val saved = SavedStateHandle()
            val first = kit.vm(video.path, saved = saved)
            until("the first player") { kit.players.singleOrNull()?.item != null }
            kit.players[0].update { positionMs = 42 * minute }
            first.onStop(changingConfigurations = false)
            assertFalse("leaving the app pauses", kit.players[0].playing)
            kit.progress(video.path, 10 * minute)
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            val again = kit.vm(video.path, saved = restored)
            until("the second player") { kit.players.getOrNull(1)?.item != null }
            assertSame(again.player, kit.players[1])
            assertEquals(42 * minute, kit.players[1].positionMs)
            assertFalse(kit.players[1].playing)
        } finally {
            kit.close()
        }
    }
}
