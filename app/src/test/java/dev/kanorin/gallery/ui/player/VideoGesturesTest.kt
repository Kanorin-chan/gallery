package dev.kanorin.gallery.ui.player

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.media3.common.util.UnstableApi
import dev.kanorin.gallery.logic.SpeedGesture
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
import kotlin.math.abs

/** Spec 5.8 on the real screen: hold for speed, the slides, the lock, pinch zoom and pan, the speed list. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-port-xhdpi")
class VideoGesturesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var kit: PlayerKit
    private val minute = 60_000L
    private val player get() = kit.players.last()
    private val surface get() = rule.onNodeWithTag(PLAYER_TAG)

    @Before fun setUp() {
        kit = PlayerKit(tmp.newFolder("storage"))
    }

    @After fun tearDown() {
        kit.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun open(): PlayerViewModel {
        val clip = kit.video("clip.mp4", 61 * minute)
        val vm = kit.vm(clip.path)
        rule.setContent { GalleryTheme { PlayerScreen(vm, onBack = {}) } }
        until("the player opened") { kit.players.lastOrNull()?.item != null }
        return vm
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
    private fun px(dp: Float) = with(rule.density) { dp * density }
    private fun frame(): Rect = rule.onNodeWithTag(VIDEO_FRAME_TAG).frameInRoot()
    private fun screenWidth() = rule.activity.window.decorView.width.toFloat()

    // Paused, the controls never hide on their own: a stray tap would be the only thing hiding them.
    private fun pause() {
        rule.onNodeWithTag(PLAY_PAUSE_TAG).performClick()
        assertFalse(player.playing)
    }

    private fun afterTheDoubleTapWindow() {
        rule.mainClock.advanceTimeBy(DOUBLE_TAP_MS + 600)
        rule.waitForIdle()
    }

    private fun holdDown() {
        surface.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(SpeedGesture.HOLD_MS + 50)
        rule.waitForIdle()
    }

    private fun pinchToDouble() = surface.performTouchInput {
        pinch(center - Offset(60f, 0f), center - Offset(120f, 0f), center + Offset(60f, 0f), center + Offset(120f, 0f), 300)
    }

    @Test fun holdingPlaysAtTwoAndLettingGoComesBack() {
        open()
        pause()
        holdDown()
        until("the pill") { showing("2× speed") }
        assertEquals(2f, player.speed)
        surface.performTouchInput { up() }
        until("1× again") { player.speed == 1f }
        until("the pill gone") { !exists(SPEED_PILL_TAG) }
        afterTheDoubleTapWindow()
        assertTrue("the hold was no tap: the controls stay", exists(PLAYER_CONTROLS_TAG))
    }

    @Test fun slideUpIsSlowSlideDownLocksATapShowsTheControlsAndAHoldUnlocks() {
        open()
        pause()
        holdDown()
        surface.performTouchInput { moveBy(Offset(0f, -px(50f))) }
        until("slow") { showing("0.5× slow") }
        assertEquals(0.5f, player.speed)
        surface.performTouchInput { moveBy(Offset(0f, px(100f))) }
        until("the lock offer") { showing("Release to lock 2×") }
        assertEquals(2f, player.speed)
        assertFalse("no hint text explains the slides", showing("Slide"))
        surface.performTouchInput { up() }
        until("locked") { showing("2× locked · hold to unlock") }
        assertEquals(2f, player.speed)
        afterTheDoubleTapWindow()
        assertTrue(exists(PLAYER_CONTROLS_TAG))
        assertEquals("still locked after the finger left", 2f, player.speed)

        surface.performTouchInput { click(center) }
        afterTheDoubleTapWindow()
        until("a tap hides the controls") { !exists(PLAYER_CONTROLS_TAG) }
        surface.performTouchInput { click(center) }
        afterTheDoubleTapWindow()
        until("and shows them") { exists(PLAYER_CONTROLS_TAG) }
        assertEquals("taps never unlock", 2f, player.speed)
        assertTrue(showing("2× locked · hold to unlock"))

        holdDown()
        until("the hold took over") { showing("2× speed") }
        assertEquals(2f, player.speed)
        surface.performTouchInput { up() }
        until("unlocked") { player.speed == 1f }
        until("the pill gone") { !exists(SPEED_PILL_TAG) }
        afterTheDoubleTapWindow()
        assertTrue("the unlocking hold toggles nothing", exists(PLAYER_CONTROLS_TAG))
    }

    @Test fun theListsSpeedIsWhereAHoldComesBackTo() {
        open()
        pause()
        rule.onNodeWithTag(SPEED_TAG).performClick()
        rule.onNodeWithTag(speedOptionTag(1.5f)).performClick()
        until("1.5×") { player.speed == 1.5f }
        rule.onNodeWithText("1.5×").assertExists()
        holdDown()
        until("the hold") { player.speed == 2f }
        rule.onNodeWithText("1.5×").assertExists() // the label keeps the list's speed
        surface.performTouchInput { up() }
        until("back to the list's speed") { player.speed == 1.5f }
    }

    @Test fun theControlsStayWhileTheSpeedListIsOpen() {
        open()
        assertTrue(player.playing)
        rule.onNodeWithTag(SPEED_TAG).performClick()
        rule.onNodeWithTag(SPEED_MENU_TAG).assertExists()
        rule.mainClock.advanceTimeBy(HIDE_CONTROLS_MS + 2_000)
        rule.waitForIdle()
        assertTrue(exists(PLAYER_CONTROLS_TAG))
        rule.onNodeWithTag(speedOptionTag(0.75f)).performClick()
        until("0.75×") { player.speed == 0.75f }
        assertFalse(exists(SPEED_MENU_TAG))
    }

    @Test fun theHoldSpeedSettingIsUsed() {
        runBlocking { kit.settings.setHoldSpeed(3f) }
        open()
        pause()
        holdDown()
        until("3×") { player.speed == 3f }
        assertTrue(showing("3× speed"))
        surface.performTouchInput { up() }
    }

    @Test fun pinchZoomsTheVideoAndThePillResetsIt() {
        open()
        pause()
        val before = frame()
        assertEquals(screenWidth(), before.width, 1f)
        assertFalse(exists(VIDEO_ZOOM_PILL_TAG))
        pinchToDouble()
        rule.waitForIdle()
        val zoomed = frame()
        assertEquals("twice as wide", before.width * 2, zoomed.width, 2f)
        assertEquals("the centre stays put", before.center.x, zoomed.center.x, 2f)
        assertTrue(showing("2.0×"))
        assertTrue(showing("Reset"))
        assertTrue("a pinch never holds", player.speeds.none { it != 1f })

        rule.onNodeWithTag(VIDEO_ZOOM_PILL_TAG).performClick()
        rule.waitForIdle()
        assertEquals(before.width, frame().width, 1f)
        assertEquals(before.left, frame().left, 1f)
        until("the pill gone") { !exists(VIDEO_ZOOM_PILL_TAG) }
        assertTrue("the pill's tap is no video tap", exists(PLAYER_CONTROLS_TAG))
    }

    // Kano 2026-10-31: the zoom pill "should fade away quick"; it comes back with the controls.
    @Test fun theZoomPillFadesSoonAfterAPinchAndComesBackWithTheControls() {
        open()
        pause()
        surface.performTouchInput { click(center) }
        afterTheDoubleTapWindow()
        until("the controls hidden") { !exists(PLAYER_CONTROLS_TAG) }
        rule.mainClock.autoAdvance = false
        pinchToDouble()
        rule.waitForIdle() // the pinch's events, before the clock moves
        rule.mainClock.advanceTimeBy(100)
        assertTrue("shown while the zoom changes", exists(VIDEO_ZOOM_PILL_TAG))
        rule.mainClock.advanceTimeBy(ZOOM_PILL_MS + 400)
        assertFalse("gone soon after", exists(VIDEO_ZOOM_PILL_TAG))
        rule.mainClock.autoAdvance = true
        surface.performTouchInput { click(center) }
        afterTheDoubleTapWindow()
        until("back with the controls") { exists(PLAYER_CONTROLS_TAG) && exists(VIDEO_ZOOM_PILL_TAG) }
    }

    @Test fun zoomedAQuickDragPansUnzoomedItDoesNothing() {
        open()
        pause()
        val still = frame()
        surface.performTouchInput { swipe(center, center + Offset(150f, 0f), 600) }
        rule.waitForIdle()
        assertEquals("nothing to pan", still, frame())
        assertTrue("moved before the hold: no 2×", player.speeds.none { it != 1f })

        pinchToDouble()
        rule.waitForIdle()
        val zoomed = frame()
        surface.performTouchInput { swipe(center, center + Offset(150f, 0f), 600) }
        rule.waitForIdle()
        val moved = frame().left - zoomed.left
        assertTrue("panned right by $moved", moved > 100f && moved <= 150f)
        assertTrue("a pan never holds", player.speeds.none { it != 1f })
        afterTheDoubleTapWindow()
        assertTrue(exists(PLAYER_CONTROLS_TAG))
    }

    @Test fun zoomedAStillFingerStillHolds() {
        open()
        pause()
        pinchToDouble()
        rule.waitForIdle()
        holdDown()
        until("2×") { player.speed == 2f }
        surface.performTouchInput { up() }
        until("1×") { player.speed == 1f }
    }

    @Test fun zoomedADoubleTapStillSkips() {
        open()
        pause()
        pinchToDouble()
        rule.waitForIdle()
        val zoomed = frame()
        val start = player.positionMs
        surface.performTouchInput { doubleClick(centerRight - Offset(40f, 0f)) }
        until("the skip") { player.positionMs == start + 10_000 }
        assertEquals(zoomed, frame())
    }

    @Test fun aSecondFingerEndsAHoldAndZooms() {
        open()
        pause()
        surface.performTouchInput { down(0, center) }
        rule.mainClock.advanceTimeBy(SpeedGesture.HOLD_MS + 50)
        until("2×") { player.speed == 2f }
        surface.performTouchInput {
            down(1, center + Offset(100f, 0f))
            moveTo(1, center + Offset(200f, 0f))
        }
        until("back to 1×") { player.speed == 1f }
        assertTrue(abs(frame().width - 2 * screenWidth()) < 2f)
        surface.performTouchInput {
            up(1)
            up(0)
        }
        rule.waitForIdle()
        assertEquals("a pinch never locks", 1f, player.speed)
    }

    // Final review (Task 15): a double-tap's second press held past 350 ms skips once and never also holds.
    @Test fun aDoubleTapWhoseSecondPressIsHeldSkipsButNeverHolds() {
        open()
        pause()
        val start = player.positionMs
        val right = surface.fetchSemanticsNode().size.let { Offset(it.width * 0.8f, it.height / 2f) }
        surface.performTouchInput { click(right) }
        rule.mainClock.advanceTimeBy(100)
        surface.performTouchInput { down(right) }
        rule.mainClock.advanceTimeBy(SpeedGesture.HOLD_MS + 100)
        rule.waitForIdle()
        assertEquals("no hold from a second tap", 1f, player.speed)
        assertFalse(exists(SPEED_PILL_TAG))
        surface.performTouchInput { up() }
        until("one skip") { player.positionMs == start + 10_000 }
        afterTheDoubleTapWindow()
        assertEquals(start + 10_000, player.positionMs)
        assertEquals(1f, player.speed)
    }

    // Final review (Task 15): a tap, then a pinch inside the 250 ms window, is a pinch: no skip.
    @Test fun aTapThenAPinchWithinTheWindowZoomsWithoutSkipping() {
        open()
        pause()
        val start = player.positionMs
        surface.performTouchInput { click(centerRight - Offset(40f, 0f)) }
        rule.mainClock.advanceTimeBy(100)
        // Fingers land one after the other, as real ones do: the first is alone for a moment.
        surface.performTouchInput { down(0, center + Offset(60f, 0f)) }
        rule.mainClock.advanceTimeBy(30)
        surface.performTouchInput {
            down(1, center - Offset(60f, 0f))
            moveTo(0, center + Offset(120f, 0f))
            moveTo(1, center - Offset(120f, 0f))
            up(0)
            up(1)
        }
        rule.waitForIdle()
        afterTheDoubleTapWindow()
        assertTrue("zoomed", frame().width > screenWidth() * 1.5f)
        assertEquals("no skip", start, player.positionMs)
    }

    // Raw MotionEvents: the test clock stays put, so a late move reaches the gesture before its hold timer runs.
    private fun raw(action: Int, at: Offset, downTime: Long, time: Long) {
        val view = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val e = MotionEvent.obtain(downTime, time, action, at.x, at.y, 0)
        rule.runOnUiThread { view.dispatchTouchEvent(e) }
        e.recycle()
    }

    // Round 2 (A): moves or the lift landing after the hold deadline, before its timer runs, must not start it either.
    @Test fun aHeldSecondPressStaysNoHoldThroughLateMovesAndItsLift() {
        open()
        pause()
        val start = player.positionMs
        val box = surface.fetchSemanticsNode().boundsInRoot
        val right = Offset(box.left + box.width * 0.8f, box.top + box.height / 2f)
        val t0 = SystemClock.uptimeMillis()
        raw(MotionEvent.ACTION_DOWN, right, t0, t0)
        raw(MotionEvent.ACTION_UP, right, t0, t0 + 50)
        val t1 = t0 + 120
        raw(MotionEvent.ACTION_DOWN, right, t1, t1)
        raw(MotionEvent.ACTION_MOVE, right + Offset(2f, 0f), t1, t1 + SpeedGesture.HOLD_MS + 100)
        raw(MotionEvent.ACTION_MOVE, right, t1, t1 + SpeedGesture.HOLD_MS + 150)
        rule.waitForIdle()
        assertEquals("no hold from a late move", 1f, player.speed)
        assertFalse(exists(SPEED_PILL_TAG))
        raw(MotionEvent.ACTION_UP, right, t1, t1 + SpeedGesture.HOLD_MS + 200)
        until("one skip") { player.positionMs == start + 10_000 }
        assertEquals(1f, player.speed)
        afterTheDoubleTapWindow()
        // A plain long-press, no tap before it, still holds.
        holdDown()
        until("2×") { player.speed == 2f }
        surface.performTouchInput { up() }
        until("1×") { player.speed == 1f }
    }
}
