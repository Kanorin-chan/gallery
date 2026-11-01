package dev.kanorin.gallery.ui.viewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.gif.AnimatedImageDecoder
import dev.kanorin.gallery.thumbs.ThumbRequest
import dev.kanorin.gallery.thumbs.thumbImageRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnimatedImageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Final review I4: a GIF or animated WebP moves in the viewer; grid tiles stay still.
    @Test fun theViewersRestingRequestDecodesAnimationsAndTheGridsDoesNot() {
        val gif = File("/storage/emulated/0/Download/cat.gif")
        assertTrue(restingRequest(context, gif, 1080, 2400).decoderFactory is AnimatedImageDecoder.Factory)
        val tile = thumbImageRequest(context, ThumbRequest(gif.path, null, false, 256, 1L))
        assertFalse(tile.decoderFactory is AnimatedImageDecoder.Factory)
    }
}
