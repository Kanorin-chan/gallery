package dev.kanorin.gallery.ui.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.kanorin.gallery.ui.common.ToastHost
import dev.kanorin.gallery.ui.theme.GalleryTheme
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.time.Duration

/** Spec 5.8 on the real screen: a fake player, Room in memory, real files. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-port-xhdpi")
class PlayerScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var kit: PlayerKit
    private var closed = 0
    private val minute = 60_000L
    private val player get() = kit.players.last()

    @Before fun setUp() {
        kit = PlayerKit(tmp.newFolder("storage"))
    }

    @After fun tearDown() {
        kit.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun show(vm: PlayerViewModel) {
        rule.setContent { GalleryTheme { ToastHost(kit.toasts) { PlayerScreen(vm, onBack = { closed++ }) } } }
        until("the player opened") { kit.players.lastOrNull()?.item != null }
    }

    private fun open(name: String = "clip.mp4", durationMs: Long = 61 * minute): Pair<File, PlayerViewModel> {
        val clip = kit.video(name, durationMs)
        val vm = kit.vm(clip.path, durationMs = durationMs)
        show(vm)
        return clip to vm
    }

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            rule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun showing(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    // With the clock paused, a change from outside needs frames plus idle syncs before it shows.
    private fun settle() = repeat(3) {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    @Test fun longVideosShowHoursOnBothTimes() {
        open(durationMs = 3_853_000)
        rule.runOnUiThread { player.update { positionMs = 221_000 } }
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick() // a pause refreshes the times
        rule.onNodeWithTag(TIME_CURRENT_TAG).assertTextEquals("0:03:41")
        rule.onNodeWithTag(TIME_TOTAL_TAG).assertTextEquals("1:04:13")
    }

    @Test fun shortVideosShowMinutes() {
        open(durationMs = 728_000)
        rule.runOnUiThread { player.update { positionMs = 221_000 } }
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        rule.onNodeWithTag(TIME_CURRENT_TAG).assertTextEquals("03:41")
        rule.onNodeWithTag(TIME_TOTAL_TAG).assertTextEquals("12:08")
    }

    @Test fun aShortVideoNeverResumes() {
        val clip = kit.video("short.mp4", 9 * minute)
        kit.progress(clip.path, 4 * minute)
        show(kit.vm(clip.path, durationMs = 9 * minute))
        assertEquals(0L, player.startPositionMs)
    }

    @Test fun aRowWithoutADurationResumesOnceThePlayerKnowsIt() {
        val clip = kit.video("unknown.mp4", 0)
        kit.progress(clip.path, 30 * minute)
        show(kit.vm(clip.path, durationMs = 61 * minute))
        until("the late resume") { player.positionMs == 30 * minute }
        assertEquals(0L, player.startPositionMs)
    }

    @Test fun doubleTapRightSkipsForwardAndRepeatsAddUp() {
        open()
        val start = player.positionMs
        rule.onNodeWithTag(PLAYER_TAG).performTouchInput { doubleClick(centerRight - Offset(40f, 0f)) }
        until("the ripple") { exists(rippleTag(forward = true)) }
        assertTrue(showing("10 s"))
        assertEquals(start + 10_000, player.positionMs)
        rule.onNodeWithTag(PLAYER_TAG).performTouchInput { doubleClick(centerRight - Offset(40f, 0f)) }
        until("the total") { showing("20 s") }
        assertEquals(start + 20_000, player.positionMs)
        assertFalse(exists(rippleTag(forward = false)))
    }

    @Test fun doubleTapLeftSkipsBackBySkipSeconds() {
        runBlocking { kit.settings.setSkipSeconds(5) }
        open()
        rule.runOnUiThread { player.update { positionMs = 20 * minute } }
        rule.onNodeWithTag(PLAYER_TAG).performTouchInput { doubleClick(centerLeft + Offset(40f, 0f)) }
        until("the ripple") { exists(rippleTag(forward = false)) }
        assertTrue(showing("5 s"))
        assertEquals(20 * minute - 5_000, player.positionMs)
    }

    @Test fun aSingleTapTogglesTheControlsAfterTheDoubleTapWindow() {
        open()
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick() // paused: no auto-hide in the way
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(PLAYER_TAG).performTouchInput { click(center) }
        settle()
        assertTrue("not before the window", exists(PLAYER_CONTROLS_TAG))
        rule.mainClock.advanceTimeBy(DOUBLE_TAP_MS + 50)
        settle()
        rule.mainClock.advanceTimeBy(500) // the fade
        settle()
        assertFalse(exists(PLAYER_CONTROLS_TAG))
        rule.onNodeWithTag(PLAYER_TAG).performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(DOUBLE_TAP_MS + 50)
        settle()
        assertTrue(exists(PLAYER_CONTROLS_TAG))
    }

    @Test fun theControlsHideAfterThreeSecondsWhilePlayingButNotWhilePaused() {
        open()
        assertTrue(player.playing)
        rule.mainClock.autoAdvance = false
        settle()
        rule.mainClock.advanceTimeBy(2_700) // spec 5.8 / the brief: 3 s
        settle()
        assertTrue(exists(PLAYER_CONTROLS_TAG))
        rule.mainClock.advanceTimeBy(1_000)
        settle()
        assertFalse(exists(PLAYER_CONTROLS_TAG))

        rule.onNodeWithTag(PLAYER_TAG).performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(DOUBLE_TAP_MS + 50)
        settle()
        rule.mainClock.autoAdvance = true
        until("the controls") { exists(PLAY_PAUSE_TAG) }
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(10_000)
        settle()
        assertTrue("paused: they stay", exists(PLAYER_CONTROLS_TAG))
    }

    @Test fun theRotationLockFlashesForOnePointTwoSeconds() {
        open()
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        rule.onNodeWithTag(ROTATION_LOCK_TAG).performClick()
        rule.onNodeWithText("Rotation locked").assertExists()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(NOTICE_MS - 200)
        settle()
        assertTrue(exists(PLAYER_NOTICE_TAG))
        rule.mainClock.advanceTimeBy(600)
        settle()
        assertFalse(exists(PLAYER_NOTICE_TAG))
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag(ROTATION_LOCK_TAG).performClick()
        rule.onNodeWithText("Rotation unlocked").assertExists()
    }

    // Ruling AK (final review): the buttons follow Settings.skipSeconds, as the double-tap does, and say how far.
    @Test fun theSkipButtonsMoveBySkipSecondsAndSaySo() {
        open()
        rule.runOnUiThread { player.update { positionMs = 5 * minute } }
        rule.onNode(hasTestTag(SKIP_FORWARD_TAG) and hasContentDescription("Skip forward 10 s")).performClick()
        assertEquals(5 * minute + 10_000, player.positionMs)
        runBlocking { kit.settings.setSkipSeconds(5) }
        until("the 5 s labels") { rule.onAllNodes(hasContentDescription("Skip back 5 s")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(SKIP_BACK_TAG).performClick()
        rule.onNodeWithTag(SKIP_BACK_TAG).performClick()
        assertEquals(5 * minute, player.positionMs)
        rule.onNode(hasTestTag(SKIP_FORWARD_TAG) and hasContentDescription("Skip forward 5 s")).assertExists()
    }

    @Test fun draggingTheBarShowsAFrameAboveTheThumbAndLiftingSeeks() {
        val (clip, _) = open()
        // xhdpi: the 96 x 130 dp bubble is 192 x 260 px; a 1920 x 1080 video covers it at 463 x 260.
        ShadowMediaMetadataRetriever.addMetadata(clip.path, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1920")
        ShadowMediaMetadataRetriever.addMetadata(clip.path, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "1080")
        val halfway = 61 * minute / 2
        ShadowMediaMetadataRetriever.addScaledFrame(
            DataSource.toDataSource(clip.path), halfway * 1000, 463, 260, Bitmap.createBitmap(463, 260, Bitmap.Config.ARGB_8888),
        )
        rule.onNodeWithTag(SEEK_BAR_TAG).performTouchInput { down(center) }
        until("the frame") { exists(SCRUB_FRAME_TAG) }
        rule.onNodeWithTag(SCRUB_FRAME_TAG).assertWidthIsEqualTo(96.dp).assertHeightIsEqualTo(130.dp)
        rule.onNodeWithTag(TIME_CURRENT_TAG).assertTextEquals("0:30:30")
        assertTrue("the drag doesn't seek yet", player.seeks.isEmpty())
        rule.onNodeWithTag(SEEK_BAR_TAG).performTouchInput { up() }
        until("the bubble gone") { !exists(SCRUB_PREVIEW_TAG) }
        assertEquals(listOf(halfway), player.seeks)
    }

    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    @Test fun sidewaysTheBubbleIsWide() {
        open()
        rule.onNodeWithTag(SEEK_BAR_TAG).performTouchInput { down(center) }
        until("the bubble") { exists(SCRUB_PREVIEW_TAG) }
        val bubble = rule.onNodeWithTag(SCRUB_PREVIEW_TAG).fetchSemanticsNode().size
        assertEquals(with(rule.density) { 124.dp.roundToPx() }, bubble.width)
        rule.onNodeWithTag(SEEK_BAR_TAG).performTouchInput { up() }
    }

    @Test fun progressIsSavedOnPauseEveryFiveSecondsAndClearedAtTheEnd() {
        val (clip, _) = open()
        rule.runOnUiThread { player.update { positionMs = 12 * minute } }
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        until("saved on pause") { kit.savedProgress(clip.path) == 12 * minute }

        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        rule.runOnUiThread { player.update { positionMs = 13 * minute } }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PlayerViewModel.SAVE_EVERY_MS))
        until("saved while playing") { kit.savedProgress(clip.path) == 13 * minute }

        rule.runOnUiThread { player.update { phase = Player.STATE_ENDED; positionMs = 61 * minute } }
        until("cleared at the end") { kit.savedProgress(clip.path) == null }
    }

    @Test fun aPlayerErrorSaysSoAndBackLeaves() {
        open()
        rule.runOnUiThread { player.fail() }
        until("the error") { showing("Can't play this video") }
        assertFalse(exists(PLAY_PAUSE_TAG))
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, closed)
    }

    @Test fun deleteGoesThroughTheDialogAndClosesThePlayer() {
        val (clip, _) = open()
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        rule.onNodeWithTag(PLAYER_MENU_TAG).performClick()
        rule.onNodeWithTag(MENU_DELETE_TAG).performClick()
        rule.onNodeWithText("Delete 1 item?").assertExists()
        rule.onNodeWithText("Move to Recycle bin").performClick()
        until("closed") { closed == 1 }
        until("binned") { !clip.exists() }
        assertTrue(File(kit.root, ".gallery-bin").listFiles().orEmpty().any { it.name.endsWith(".mp4") })
    }

    @Test fun infoShowsDurationResolutionSizeAndPath() {
        val (clip, _) = open()
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        rule.onNodeWithTag(PLAYER_MENU_TAG).performClick()
        rule.onNodeWithTag(MENU_INFO_TAG).performClick()
        rule.onNodeWithTag(INFO_DIALOG_TAG).assertExists()
        listOf("Duration", "1:01:00", "Resolution", "1920 × 1080", "Size", "Path", clip.path).forEach {
            assertTrue(it, showing(it))
        }
    }

    @Test fun anotherAppsVideoPlaysFromTheStartWithoutDelete() {
        val uri = Uri.parse("content://com.example.chat.provider/media/7.mp4")
        show(kit.vm(uri = uri, type = "video/mp4"))
        assertEquals(uri, player.item?.localConfiguration?.uri)
        assertEquals(0L, player.startPositionMs)
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        rule.onNodeWithTag(PLAYER_MENU_TAG).performClick()
        rule.onNodeWithTag(MENU_SHARE_TAG).assertExists()
        rule.onNodeWithTag(MENU_INFO_TAG).assertExists()
        assertFalse(exists(MENU_DELETE_TAG))
        rule.onNodeWithTag(PLAYER_TITLE_TAG).assertTextEquals("7.mp4")
    }

    // Task 14 review: a transient audio-focus loss holds playback back with playWhenReady still true.
    @Test fun aFocusLossShowsPausedAndPlayTakesItBack() {
        open()
        assertTrue(player.playing)
        assertTrue(screenKeptOn())
        rule.runOnUiThread { player.update { suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS } }
        until("the play button") { rule.onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty() }
        assertFalse("the screen may sleep", screenKeptOn())
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(HIDE_CONTROLS_MS + 1_000)
        settle()
        assertTrue("held back counts as paused: no auto-hide", exists(PLAYER_CONTROLS_TAG))
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        until("playing again") { rule.onAllNodes(hasContentDescription("Pause")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, player.suppression)
        assertTrue(screenKeptOn())
    }

    // Task 14 review: with no known duration the elapsed time still moves; the bar can't scrub.
    @Test fun anUnknownDurationStillCountsUpAndCantScrub() {
        val clip = kit.video("live.mp4", 0)
        show(kit.vm(clip.path, durationMs = C.TIME_UNSET))
        rule.runOnUiThread { player.update { positionMs = 65_000 } }
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        rule.onNodeWithTag(TIME_CURRENT_TAG).assertTextEquals("01:05")
        rule.onNodeWithTag(SEEK_BAR_TAG).performTouchInput { down(center) }
        rule.waitForIdle()
        assertFalse(exists(SCRUB_PREVIEW_TAG))
        rule.onNodeWithTag(SEEK_BAR_TAG).performTouchInput { up() }
        assertTrue(player.seeks.isEmpty())
    }

    private fun screenKeptOn(): Boolean {
        fun View.on(): Boolean = keepScreenOn || (this is ViewGroup && (0 until childCount).any { getChildAt(it).on() })
        return rule.activity.window.decorView.on()
    }

    @Test fun aFileOfOursOpenedByUriPlaysAsOurs() {
        val clip = kit.video("ours.mp4", 61 * minute)
        kit.progress(clip.path, 30 * minute)
        show(kit.vm(uri = Uri.fromFile(clip), type = "video/mp4"))
        assertEquals(30 * minute, player.startPositionMs)
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        rule.onNodeWithTag(PLAYER_MENU_TAG).performClick()
        assertTrue(exists(MENU_DELETE_TAG))
    }
}
