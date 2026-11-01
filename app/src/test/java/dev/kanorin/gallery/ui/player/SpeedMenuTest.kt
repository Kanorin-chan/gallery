package dev.kanorin.gallery.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.kanorin.gallery.logic.PlaybackSpeed
import dev.kanorin.gallery.ui.theme.GalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec 5.8: the speed button's list pops up above it; the current speed has a tick; a pick closes it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-port-xhdpi")
class SpeedMenuTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var speed by mutableStateOf(1f)
    private var open by mutableStateOf(false)
    private val picks = mutableListOf<Float>()

    private fun show(landscape: Boolean = false) = rule.setContent {
        GalleryTheme {
            PlayerControls(
                ControlsState(paused = true, rotationLocked = false, speed = speed, speedMenu = open, landscape = landscape),
                onRotationLock = {}, onSkipBack = {}, onPlayPause = {}, onSkipForward = {},
                onSpeed = { open = !open },
                onRotate = {},
                onPickSpeed = { picks += it; speed = it },
                onDismissSpeed = { open = false },
            )
        }
    }

    private fun options() = rule.onAllNodes(
        SemanticsMatcher("a speed option") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(SPEED_OPTION_TAG_PREFIX) == true },
    ).fetchSemanticsNodes()

    @Test fun eightOptionsPickingOneSetsTheLabelAndTheTick() {
        show()
        rule.onNodeWithText("1.0×").assertExists()
        rule.onNodeWithTag(SPEED_TAG).performClick()
        rule.onNodeWithTag(SPEED_MENU_TAG).assertExists()
        rule.onNodeWithText("PLAYBACK SPEED").assertExists()
        assertEquals(8, options().size)
        rule.onNodeWithText("1.0× Normal").assertExists()
        rule.onNodeWithTag(speedOptionTag(1f)).assertIsSelected()
        rule.onNodeWithTag(speedOptionTag(1.5f)).assertIsNotSelected()

        rule.onNodeWithTag(speedOptionTag(1.5f)).performClick()
        assertEquals(listOf(1.5f), picks)
        rule.waitForIdle()
        assertTrue("a pick closes the list", options().isEmpty())
        rule.onNodeWithTag(SPEED_TAG).assertExists()
        rule.onNodeWithText("1.5×").assertExists()

        rule.onNodeWithTag(SPEED_TAG).performClick()
        rule.onNodeWithTag(speedOptionTag(1.5f)).assertIsSelected()
        rule.onNodeWithTag(speedOptionTag(1f)).assertIsNotSelected()
        rule.onNodeWithTag(speedOptionTag(1.5f)).performClick()
    }

    @Test fun uprightItIsOneColumnAboveTheButtonAndOnItsRight() {
        show()
        rule.onNodeWithTag(SPEED_TAG).performClick()
        val a = rule.onNodeWithTag(speedOptionTag(0.25f)).getUnclippedBoundsInRoot()
        val b = rule.onNodeWithTag(speedOptionTag(0.5f)).getUnclippedBoundsInRoot()
        assertEquals(a.left, b.left)
        assertTrue(b.top > a.top)
        rule.onNodeWithTag(SPEED_MENU_TAG).assertWidthIs(150f)
    }

    @Test fun sidewaysItIsTwoColumns() {
        show(landscape = true)
        rule.onNodeWithTag(SPEED_TAG).performClick()
        val a = rule.onNodeWithTag(speedOptionTag(0.25f)).getUnclippedBoundsInRoot()
        val b = rule.onNodeWithTag(speedOptionTag(0.5f)).getUnclippedBoundsInRoot()
        val c = rule.onNodeWithTag(speedOptionTag(0.75f)).getUnclippedBoundsInRoot()
        assertEquals("0.25 and 0.5 share a row", a.top, b.top)
        assertTrue(b.left > a.left)
        assertTrue("0.75 starts the next row", c.top > a.top)
        assertEquals(a.left, c.left)
        rule.onNodeWithTag(SPEED_MENU_TAG).assertWidthIs(250f)
    }

    @Test fun theMenuAloneReportsThePickAndCloses() {
        var dismissed = 0
        val picked = mutableListOf<Float>()
        rule.setContent { GalleryTheme { SpeedMenu(current = 0.75f, landscape = false, onPick = { picked += it }, onDismiss = { dismissed++ }) } }
        rule.onNodeWithTag(speedOptionTag(0.75f)).assertIsSelected()
        PlaybackSpeed.OPTIONS.forEach { rule.onNodeWithText(if (it == 1f) "1.0× Normal" else PlaybackSpeed.label(it)).assertExists() }
        rule.onNodeWithTag(speedOptionTag(2f)).performClick()
        assertEquals(listOf(2f), picked)
        assertEquals(1, dismissed)
    }

    // Mockup: bottom 10 px above the right column, right edge 6 px past it.
    @Test fun itOpensUpwardOnTheRightOfTheSpeedButton() {
        val p = SpeedMenuPosition(gap = 20, outset = 12)
        val column = IntRect(left = 500, top = 1100, right = 690, bottom = 1172)
        val window = IntSize(720, 1280)
        assertEquals(IntOffset(690 + 12 - 300, 1100 - 20 - 600), p.calculatePosition(column, window, LayoutDirection.Ltr, IntSize(300, 600)))
        // Never off the screen.
        assertEquals(IntOffset(720 - 300, 0), p.calculatePosition(IntRect(600, 100, 715, 172), window, LayoutDirection.Ltr, IntSize(300, 600)))
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertWidthIs(dp: Float) {
        val bounds = getUnclippedBoundsInRoot()
        assertEquals(dp, (bounds.right - bounds.left).value, 0.5f)
    }
}
