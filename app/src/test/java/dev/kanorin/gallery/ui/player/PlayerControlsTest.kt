package dev.kanorin.gallery.ui.player

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import dev.kanorin.gallery.ui.theme.GalleryTheme
import org.junit.After
import org.junit.Assert.assertEquals
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

/** Spec 5.8: skip back · play/pause · skip forward exactly centred on the screen, upright and sideways. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var kit: PlayerKit

    @Before fun setUp() {
        kit = PlayerKit(tmp.newFolder("storage"))
    }

    @After fun tearDown() {
        kit.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertPlayIsCentred() {
        val clip = kit.video("clip.mp4", 728_000)
        val vm = kit.vm(clip.path)
        rule.setContent { GalleryTheme { PlayerScreen(vm, onBack = {}) } }
        rule.waitForIdle()
        val screen = rule.activity.window.decorView.width.toFloat()
        val play = rule.onNodeWithTag(PLAY_PAUSE_TAG).getUnclippedBoundsInRoot()
        val centre = with(rule.density) { ((play.left + play.right) / 2).toPx() }
        assertTrue("play centre $centre vs screen ${screen / 2}", abs(centre - screen / 2) <= 1f)
        // The side columns are not the same width: only a real three-column layout centres it.
        val lock = rule.onNodeWithTag(ROTATION_LOCK_TAG).getUnclippedBoundsInRoot()
        val rotate = rule.onNodeWithTag(ROTATE_TAG).getUnclippedBoundsInRoot()
        val speed = rule.onNodeWithTag(SPEED_TAG).getUnclippedBoundsInRoot()
        assertTrue((lock.right - lock.left) < (rotate.right - speed.left))
    }

    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    @Test fun thePlayButtonIsCentredUpright() = assertPlayIsCentred()

    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    @Test fun thePlayButtonIsCentredSideways() = assertPlayIsCentred()

    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    @Test fun eachButtonCallsItsOwnAction() {
        val calls = mutableListOf<String>()
        rule.setContent {
            GalleryTheme {
                PlayerControls(
                    ControlsState(paused = true, rotationLocked = false, speed = 1f),
                    onRotationLock = { calls += "lock" },
                    onSkipBack = { calls += "back" },
                    onPlayPause = { calls += "play" },
                    onSkipForward = { calls += "fwd" },
                    onSpeed = { calls += "speed" },
                    onRotate = { calls += "rotate" },
                )
            }
        }
        rule.onNodeWithText("1.0×").assertExists()
        listOf(ROTATION_LOCK_TAG, SKIP_BACK_TAG, PLAY_PAUSE_TAG, SKIP_FORWARD_TAG, SPEED_TAG, ROTATE_TAG).forEach {
            rule.onNodeWithTag(it).performClick()
        }
        assertEquals(listOf("lock", "back", "play", "fwd", "speed", "rotate"), calls)
    }
}
