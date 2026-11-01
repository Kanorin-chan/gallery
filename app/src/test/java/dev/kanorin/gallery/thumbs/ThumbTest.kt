package dev.kanorin.gallery.thumbs

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import dev.kanorin.gallery.data.MediaEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(DelicateCoilApi::class)
    @Test fun brokenFileShowsErrorIcon() {
        SingletonImageLoader.setUnsafe(buildImageLoader(ApplicationProvider.getApplicationContext<Application>()))
        val item = MediaEntity(
            path = "/nope/missing.jpg", folderPath = "/nope", name = "missing.jpg", mimeType = "image/jpeg",
            isVideo = false, dateTaken = 1, dateModified = 1, size = 1, width = 1, height = 1, durationMs = 0,
            orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
        )
        compose.setContent { Thumb(item, 128, Modifier.size(90.dp)) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(THUMB_ERROR_TAG)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(THUMB_ERROR_TAG).assertIsDisplayed()
    }
}
