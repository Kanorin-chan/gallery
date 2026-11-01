package dev.kanorin.gallery.ui.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.size.pxOrElse
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.Zoom
import dev.kanorin.gallery.logic.ZoomMath
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.theme.GalleryTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Collections

/** Fix round 1: an image with no index row (size unknown) is measured from its header before it zooms. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DelicateCoilApi::class)
class ViewerBoundsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var db: GalleryDb
    private lateinit var vm: ViewerViewModel
    /** Every size Coil was asked to decode at, px; nothing is decoded. */
    private val asked: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())

    @After fun tearDown() {
        if (::vm.isInitialized) cancelAndDrain(vm.viewModelScope)
        if (::db.isInitialized) db.close()
        SingletonImageLoader.reset()
    }

    /** A real 16 x 12 JPEG whose frame header says [w] x [h]: only the header is ever read. */
    private fun headerOnlyJpeg(w: Int, h: Int): File {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, out)
        val bytes = out.toByteArray()
        val sof = (0 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && (bytes[it + 1] == 0xC0.toByte() || bytes[it + 1] == 0xC2.toByte()) }
        bytes[sof + 5] = (h shr 8).toByte(); bytes[sof + 6] = h.toByte()
        bytes[sof + 7] = (w shr 8).toByte(); bytes[sof + 8] = w.toByte()
        return File(tmp.root, "huge.jpg").apply { writeBytes(bytes) }
    }

    private fun recordingLoader() = ImageLoader.Builder(context).components {
        add(object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                asked += chain.size.width.pxOrElse { -1 } to chain.size.height.pxOrElse { -1 }
                return ErrorResult(null, chain.request, IOException("not decoded in this test"))
            }
        })
    }.build()

    private fun until(condition: () -> Boolean) = rule.waitUntil(10_000) { rule.waitForIdle(); condition() }

    @Test fun anUnindexed16kImageAsksForAHalvedSizeAndPansOnlyOverItself() {
        SingletonImageLoader.setUnsafe(recordingLoader())
        val file = headerOnlyJpeg(16320, 12240)
        val uri = Uri.fromFile(file)
        db = Room.inMemoryDatabaseBuilder(context, GalleryDb::class.java).allowMainThreadQueries().build()
        val ops = FileOps(db, {})
        vm = ViewerViewModel(
            ViewerArgs(MediaFilter(), null, 0, uri, "image/jpeg"), db.mediaDao(), db.pathDao(), Settings(MemoryStore()),
            SavedStateHandle(), ops, RecycleBin(db, ops), roots = { emptyList() }, resolver = context.contentResolver,
        )
        rule.setContent { GalleryTheme { ViewerScreen(vm, onBack = {}) } }
        val tag = viewerImageTag(uri.toString())
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        fun zoom(): Zoom = rule.onNodeWithTag(tag).fetchSemanticsNode().config[ZoomKey]

        // Zoom waits for the header (a double tap before it is two taps); then the sharp decode is a clean quarter.
        until {
            if (zoom() == Zoom()) rule.onNodeWithTag(tag).performTouchInput { doubleClick(center) }
            rule.waitForIdle()
            zoom().scale == ZoomMath.DOUBLE_TAP_SCALE
        }
        until { 4080 to 3060 in asked }
        assertTrue("$asked", asked.all { (w, h) -> w in 1..ZoomMath.HI_RES_EDGE && h in 1..ZoomMath.HI_RES_EDGE })

        // Fitted 720 wide = 540 tall; at 2.5x 1350 tall: it pans only as far as its own edge, never into black.
        val page = rule.onNodeWithTag(tag).fetchSemanticsNode().size
        assertEquals(720, page.width)
        rule.onNodeWithTag(tag).performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 300f) }
        rule.waitForIdle()
        assertEquals(-((1350f - page.height) / 2f).coerceAtLeast(0f), zoom().y, 1f)
    }
}
