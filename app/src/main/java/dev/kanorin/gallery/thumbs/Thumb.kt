package dev.kanorin.gallery.thumbs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.ui.theme.GalleryColors

const val THUMB_ERROR_TAG = "thumb-error"

/** Square, crop-filled. The tile is surface2 in every state, so nothing shifts when a load finishes or fails. */
@Composable
fun Thumb(item: MediaEntity, sizePx: Int, modifier: Modifier = Modifier) =
    Thumb(item.path, item.isVideo, item.dateModified, sizePx, modifier, item.mediaStoreId)

/** A file by path, with no index row (the Recycle bin's tiles); [dateModified] (ms) keys the cache. */
@Composable
fun Thumb(path: String, isVideo: Boolean, dateModified: Long, sizePx: Int, modifier: Modifier = Modifier, mediaStoreId: Long? = null) {
    val context = LocalContext.current
    val request = remember(path, mediaStoreId, isVideo, dateModified, sizePx) {
        thumbImageRequest(context, ThumbRequest(path, mediaStoreId, isVideo, sizePx, dateModified))
    }
    var failed by remember(request) { mutableStateOf(false) }
    Box(modifier.aspectRatio(1f).clipToBounds().background(GalleryColors.Surface2)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
            onState = { failed = it is AsyncImagePainter.State.Error },
        )
        if (failed) {
            Icon(
                painter = rememberVectorPainter(BrokenImage),
                contentDescription = null,
                tint = GalleryColors.Muted,
                modifier = Modifier.align(Alignment.Center).testTag(THUMB_ERROR_TAG),
            )
        }
    }
}

// Material "broken image" glyph (Apache 2.0); icons-extended is not worth ~10 MB of dex for one icon.
private val BrokenImage: ImageVector = ImageVector.Builder(
    name = "BrokenImage",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(
        "M21,5v6.59l-3,-3.01 -4,4.01 -4,-4 -4,4 -3,-3.01L3,5c0,-1.1 0.9,-2 2,-2h14c1.1,0 2,0.9 2,2z" +
            "M18,11.42l3,3.01L21,19c0,1.1 -0.9,2 -2,2L5,21c-1.1,0 -2,-0.9 -2,-2v-6.58l3,2.99 4,-4 4,4 4,-3.99z",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()
