package dev.kanorin.gallery.ui.viewer

import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.kanorin.gallery.GalleryApp
import dev.kanorin.gallery.GalleryNavHost
import dev.kanorin.gallery.Routes
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.ui.player.FakePlayer
import dev.kanorin.gallery.ui.player.PLAYER_INTENT_ROUTE
import dev.kanorin.gallery.ui.player.PLAYER_ROUTE
import dev.kanorin.gallery.ui.player.PLAY_PAUSE_TAG
import dev.kanorin.gallery.ui.player.playerRoute
import dev.kanorin.gallery.ui.theme.GalleryTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Fix round 1: a second VIEW intent replaces the viewer it lands on; viewers never pile up. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class ViewerStackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp

    private var pending: String? by mutableStateOf(null)
    private var nav: NavHostController? = null

    @After fun tearDown() {
        cancelAndDrain(app.graph.appScope)
        app.graph.db.close()
    }

    private fun showing(uri: Uri) = rule.waitUntil(10_000) {
        rule.onAllNodes(hasTestTag(viewerPageTag(uri.toString()))).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun aSecondViewIntentReplacesTheFirstViewer() {
        rule.setContent {
            val controller = rememberNavController().also { nav = it }
            GalleryTheme { GalleryNavHost(pendingView = pending, onViewOpened = { pending = null }, nav = controller) }
        }
        val first = Uri.parse("content://com.example.chat.provider/media/1.jpg")
        val second = Uri.parse("content://com.example.chat.provider/media/2.jpg")
        rule.runOnUiThread { pending = viewerRoute(first, "image/jpeg") }
        showing(first)
        rule.runOnUiThread { pending = viewerRoute(second, "image/jpeg") }
        showing(second)
        rule.waitForIdle()
        val stack = nav!!.currentBackStack.value.mapNotNull { it.destination.route }.filter { it != nav!!.graph.route }
        assertEquals(listOf(Routes.MAIN, VIEWER_ROUTE), stack)
    }

    @Test fun aVideoIntentReplacesTheViewerAndAPhotoIntentThePlayer() {
        app.graph.newPlayer = { FakePlayer() }
        rule.setContent {
            val controller = rememberNavController().also { nav = it }
            GalleryTheme { GalleryNavHost(pendingView = pending, onViewOpened = { pending = null }, nav = controller) }
        }
        val photo = Uri.parse("content://com.example.chat.provider/media/1.jpg")
        val video = Uri.parse("content://com.example.chat.provider/media/2.mp4")
        rule.runOnUiThread { pending = viewerRoute(photo, "image/jpeg") }
        showing(photo)
        rule.runOnUiThread { pending = playerRoute(video, "video/mp4") }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(PLAY_PAUSE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        assertEquals(listOf(Routes.MAIN, PLAYER_INTENT_ROUTE), stack())
        rule.runOnUiThread { pending = viewerRoute(photo, "image/jpeg") }
        showing(photo)
        rule.waitForIdle()
        assertEquals(listOf(Routes.MAIN, VIEWER_ROUTE), stack())
    }

    // A player replacing a player takes the orientation from before the first; the viewer after them gets it back.
    @Test fun replacedPlayersGiveTheOrientationBack() {
        app.graph.newPlayer = { FakePlayer() }
        rule.setContent {
            val controller = rememberNavController().also { nav = it }
            GalleryTheme { GalleryNavHost(pendingView = pending, onViewOpened = { pending = null }, nav = controller) }
        }
        rule.waitForIdle()
        val before = rule.activity.requestedOrientation
        rule.runOnUiThread { pending = playerRoute(Uri.parse("content://com.example.chat.provider/media/1.mp4"), "video/mp4") }
        rule.waitUntil(10_000) { rule.activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR }
        rule.runOnUiThread { pending = playerRoute(Uri.parse("content://com.example.chat.provider/media/2.mp4"), "video/mp4") }
        rule.waitForIdle()
        assertEquals(listOf(Routes.MAIN, PLAYER_INTENT_ROUTE), stack())
        val photo = Uri.parse("content://com.example.chat.provider/media/3.jpg")
        rule.runOnUiThread { pending = viewerRoute(photo, "image/jpeg") }
        showing(photo)
        rule.waitForIdle()
        assertEquals(before, rule.activity.requestedOrientation)
    }

    // MainActivity tracks the lowest viewer / player itself (Navigation's back-stack list is restricted API).
    @Test fun anOpenWithReplacesTheLowestViewerNotThePlayerOnIt() {
        app.graph.newPlayer = { FakePlayer() }
        rule.setContent {
            val controller = rememberNavController().also { nav = it }
            GalleryTheme { GalleryNavHost(pendingView = pending, onViewOpened = { pending = null }, nav = controller) }
        }
        val first = Uri.parse("content://com.example.chat.provider/media/1.jpg")
        rule.runOnUiThread { pending = viewerRoute(first, "image/jpeg") }
        showing(first)
        rule.runOnUiThread { nav!!.navigate(playerRoute("/storage/emulated/0/Movies/clip.mp4")) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(PLAY_PAUSE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        assertEquals(listOf(Routes.MAIN, VIEWER_ROUTE, PLAYER_ROUTE), stack())
        val second = Uri.parse("content://com.example.chat.provider/media/2.jpg")
        rule.runOnUiThread { pending = viewerRoute(second, "image/jpeg") }
        showing(second)
        rule.waitForIdle()
        assertEquals(listOf(Routes.MAIN, VIEWER_ROUTE), stack())
        rule.runOnUiThread { nav!!.popBackStack() }
        rule.waitForIdle()
        rule.runOnUiThread { pending = viewerRoute(first, "image/jpeg") }
        showing(first)
        rule.waitForIdle()
        assertEquals("a popped viewer is forgotten", listOf(Routes.MAIN, VIEWER_ROUTE), stack())
    }

    // Final review I2: the cross-fade disposes the viewer after the player hid the bars; that must not show them.
    @Test fun aPlayerOpenedFromTheViewerStaysImmersiveAndBackRestoresTheViewersBars() {
        app.graph.newPlayer = { FakePlayer() }
        rule.setContent {
            val controller = rememberNavController().also { nav = it }
            GalleryTheme { GalleryNavHost(pendingView = pending, onViewOpened = { pending = null }, nav = controller) }
        }
        val photo = Uri.parse("content://com.example.chat.provider/media/1.jpg")
        val clip = "/storage/emulated/0/Movies/clip.mp4"
        fun player() {
            rule.runOnUiThread { nav!!.navigate(playerRoute(clip)) }
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(PLAY_PAUSE_TAG)).fetchSemanticsNodes().isNotEmpty() }
            rule.waitForIdle()
        }
        fun back() {
            rule.runOnUiThread { nav!!.popBackStack() }
            showing(photo)
            rule.waitForIdle()
        }
        rule.runOnUiThread { nav!!.navigate(viewerRoute(photo, "image/jpeg")) }
        showing(photo)
        rule.waitForIdle()
        assertTrue("the viewer opens with its bars", barsShown())

        player()
        assertFalse("the player is immersive once the viewer is gone", barsShown())
        back()
        assertTrue("back on the viewer: its bars", barsShown())

        rule.onNodeWithTag(viewerImageTag(photo.toString())).performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        assertFalse("a tap hid them", barsShown())
        player()
        assertFalse(barsShown())
        back()
        assertFalse("the viewer's own choice survives the player", barsShown())
    }

    // What the window was last asked for (InsetsController keeps it even with no window manager to apply it).
    private fun barsShown(): Boolean {
        val controller = rule.activity.window.insetsController!!
        val visible = controller.javaClass.getMethod("getRequestedVisibleTypes").invoke(controller) as Int
        return visible and WindowInsets.Type.systemBars() == WindowInsets.Type.systemBars()
    }

    private fun stack() = nav!!.currentBackStack.value.mapNotNull { it.destination.route }.filter { it != nav!!.graph.route }
}
