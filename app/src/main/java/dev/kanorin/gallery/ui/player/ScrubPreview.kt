package dev.kanorin.gallery.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.logic.TimeFormat
import dev.kanorin.gallery.logic.coverSize
import dev.kanorin.gallery.ui.theme.GalleryColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

const val SCRUB_PREVIEW_TAG = "player-scrub-preview"
const val SCRUB_FRAME_TAG = "player-scrub-frame"

/** The frame bubble (mockup `.track .prev`): 96 x 130 dp upright, 124 x 70 dp sideways. */
fun previewSize(landscape: Boolean): DpSize = if (landscape) DpSize(124.dp, 70.dp) else DpSize(96.dp, 130.dp)

private const val TAG = "ScrubPreview"
private val FrameShape = RoundedCornerShape(10.dp)
private val TimeStyle = TextStyle(color = GalleryColors.Text, fontSize = 11.sp, fontFeatureSettings = "tnum")
private val GapAboveTrack = 4.dp

/**
 * The scrub bubble, drawn above the bar's thumb and kept inside the bar's ends. Its frames come from one
 * [MediaMetadataRetriever] opened for this drag and released after it: at most one frame in flight, the latest
 * position wins.
 */
@Composable
internal fun ScrubPreview(source: PlayerSource, positionMs: Long, durationMs: Long, fraction: Float, landscape: Boolean) {
    val box = previewSize(landscape)
    val frame = rememberScrubFrame(source, positionMs, box)
    val gap = with(LocalDensity.current) { GapAboveTrack.roundToPx() }
    Column(
        Modifier
            .layout { measurable, constraints ->
                val track = constraints.maxWidth
                val bubble = measurable.measure(Constraints())
                layout(0, 0) {
                    val half = bubble.width / 2
                    val centre = (fraction * track).roundToInt().coerceIn(half, maxOf(half, track - half))
                    bubble.place(centre - half, -gap - bubble.height)
                }
            }
            .testTag(SCRUB_PREVIEW_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(box).clip(FrameShape).background(GalleryColors.PreviewBg).border(2.dp, GalleryColors.PreviewBorder, FrameShape)) {
            if (frame != null) {
                Image(frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().testTag(SCRUB_FRAME_TAG))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(TimeFormat.position(positionMs, durationMs), style = TimeStyle)
    }
}

@Composable
private fun rememberScrubFrame(source: PlayerSource, positionMs: Long, box: DpSize): ImageBitmap? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val boxW = with(density) { box.width.roundToPx() }
    val boxH = with(density) { box.height.roundToPx() }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val position by rememberUpdatedState(positionMs)
    LaunchedEffect(source, boxW, boxH) {
        val retriever = MediaMetadataRetriever()
        try {
            val frames = withContext(Dispatchers.IO) { Frames.open(retriever, context, source, boxW, boxH) } ?: return@LaunchedEffect
            // Sequential: a slow frame holds the queue at one; positions in between are dropped.
            snapshotFlow { position }.conflate().collect { pos ->
                withContext(Dispatchers.IO) { frames.at(pos) }?.let { frame = it.asImageBitmap() }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { retriever.release() }
        }
    }
    return frame
}

/** Frames at cover size for the bubble (aspect kept), or the box itself when the video's size is unknown. */
private class Frames(private val retriever: MediaMetadataRetriever, private val width: Int, private val height: Int) {
    fun at(positionMs: Long): Bitmap? = try {
        retriever.getScaledFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
    } catch (e: RuntimeException) {
        Log.i(TAG, "no frame at $positionMs: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        fun open(retriever: MediaMetadataRetriever, context: Context, source: PlayerSource, boxW: Int, boxH: Int): Frames? = try {
            when (source) {
                is PlayerSource.Path -> retriever.setDataSource(source.path)
                is PlayerSource.Content -> retriever.setDataSource(context, source.uri)
            }
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            var w = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            var h = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            // Frames come out upright, so size them upright too.
            if (int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0) w = h.also { h = w }
            val (fw, fh) = coverSize(w, h, boxW, boxH) ?: (boxW to boxH)
            Frames(retriever, fw, fh)
        } catch (e: RuntimeException) {
            Log.i(TAG, "can't read frames of $source: ${e.javaClass.simpleName}")
            null
        }
    }
}
