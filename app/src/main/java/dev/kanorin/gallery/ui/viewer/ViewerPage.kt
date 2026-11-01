package dev.kanorin.gallery.ui.viewer

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.gif.AnimatedImageDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.index.FsWalker
import dev.kanorin.gallery.index.MediaProbe
import dev.kanorin.gallery.logic.DragOwner
import dev.kanorin.gallery.logic.Frame
import dev.kanorin.gallery.logic.Zoom
import dev.kanorin.gallery.logic.ZoomMath
import dev.kanorin.gallery.logic.upright
import dev.kanorin.gallery.thumbs.ThumbRequest
import dev.kanorin.gallery.thumbs.thumbImageRequest
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.theme.GalleryColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val ZOOM_ANIM_MS = 220
private const val JPEG = "image/jpeg"
private val MissingStyle = TextStyle(color = GalleryColors.Muted, fontSize = 14.sp)

/** One photo's zoom; gestures write it, the layer reads it. Not saved: a page that leaves comes back fitted. */
@Stable
internal class ZoomState {
    var zoom by mutableStateOf(Zoom())
    /** Set by the layout; plain, since only gestures read it. */
    var frame = Frame(0f, 0f, 0f, 0f)
    private var anim: Job? = null

    fun stop() {
        anim?.cancel()
    }

    fun animateTo(scope: CoroutineScope, target: Zoom) {
        stop()
        val from = zoom
        anim = scope.launch {
            animate(0f, 1f, animationSpec = tween(ZOOM_ANIM_MS)) { t, _ ->
                zoom = Zoom(lerp(from.scale, target.scale, t), lerp(from.x, target.x, t), lerp(from.y, target.y, t))
            }
            zoom = target
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/**
 * A photo filling the page (spec 5.7): fitted, decoded at about the screen's size; while zoomed past 1x a sharper
 * decode (long edge at most 4096 px) is drawn over it. Pinch 1-8x, double-tap 1x <-> 2.5x, pan clamped.
 * Sideways drags pan while the photo can still move that way, else they're left for the pager.
 * Zoom needs the photo's size: an unknown one is read from the file's header once, off Main; unreadable = no zoom.
 */
@Composable
internal fun ZoomableImage(
    /** A File or a Uri. */
    model: Any,
    /** Upright px from the index; 0 = unknown. */
    imageW: Int,
    imageH: Int,
    mimeType: String?,
    tag: String,
    onTap: () -> Unit,
    /** A finished up/down drag at 1x: its travel in px, negative = up. */
    onVertical: (Float) -> Unit,
) {
    val state = remember { ZoomState() }
    val scope = rememberCoroutineScope()
    val tap = rememberUpdatedState(onTap)
    val vertical = rememberUpdatedState(onVertical)
    var failed by remember(model) { mutableStateOf(false) }
    val context = LocalContext.current
    val size by produceState(if (imageW > 0 && imageH > 0) imageW to imageH else null, model) {
        if (value == null) value = withContext(Dispatchers.IO) { imageBounds(context, model, mimeType) }
    }
    val known = size
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(state, known != null) { viewerGestures(state, known != null, scope, tap, vertical) },
    ) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        SideEffect { state.frame = Frame.fit(w.toFloat(), h.toFloat(), known?.first ?: 0, known?.second ?: 0) }
        val resting = remember(model, w, h) { restingRequest(context, model, w, h) }
        // Only crossing 1x recomposes; the layer and the semantics node read the zoom themselves.
        val zoomed by remember { derivedStateOf { state.zoom.zoomed } }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val now = state.zoom
                    scaleX = now.scale
                    scaleY = now.scale
                    translationX = now.x
                    translationY = now.y
                }
                .then(ZoomSemantics(state))
                .testTag(tag),
        ) {
            AsyncImage(
                model = resting,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onState = { failed = it is AsyncImagePainter.State.Error },
            )
            val sharp = if (zoomed && known != null) {
                remember(model, known) {
                    ZoomMath.hiResSize(known.first, known.second)?.let { (tw, th) ->
                        // Never cached in memory: one such bitmap is up to 64 MB.
                        ImageRequest.Builder(context).data(model).size(tw, th).scale(Scale.FIT).precision(Precision.INEXACT)
                            .memoryCachePolicy(CachePolicy.DISABLED).build()
                    }
                }
            } else {
                null
            }
            if (sharp != null) {
                AsyncImage(model = sharp, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        if (failed) BrokenImage(Modifier.align(Alignment.Center))
    }
}

/** The page-sized decode of [model] at rest; GIFs and animated WebP play here only (the zoomed layer and tiles stay still). */
internal fun restingRequest(context: Context, model: Any, w: Int, h: Int): ImageRequest =
    ImageRequest.Builder(context).data(model).size(w.coerceAtLeast(1), h.coerceAtLeast(1)).scale(Scale.FIT)
        .precision(Precision.INEXACT).decoderFactory(AnimatedImageDecoder.Factory()).build()

/** Publishes [ZoomKey] for tests, re-read when the zoom changes (a plain semantics block isn't re-run). */
private data class ZoomSemantics(val state: ZoomState) : ModifierNodeElement<ZoomSemanticsNode>() {
    override fun create() = ZoomSemanticsNode(state)

    override fun update(node: ZoomSemanticsNode) {
        node.state = state
        node.invalidateSemantics()
    }
}

private class ZoomSemanticsNode(var state: ZoomState) : Modifier.Node(), SemanticsModifierNode, ObserverModifierNode {
    override fun SemanticsPropertyReceiver.applySemantics() {
        var zoom = Zoom()
        observeReads { zoom = state.zoom }
        this[ZoomKey] = zoom
    }

    override fun onObservedReadsChanged() = invalidateSemantics()
}

/** [model]'s upright size from its header alone (bounds + EXIF rotation), or null when it can't be read. */
private fun imageBounds(context: Context, model: Any, mimeType: String?): Pair<Int, Int>? = when (model) {
    is File -> MediaProbe.image(model, mimeType ?: FsWalker.mimeTypeOf(model.name) ?: JPEG)
        .takeIf { it.width > 0 && it.height > 0 }
        ?.let { upright(it.width, it.height, it.orientation) }
    is Uri -> uriBounds(context.contentResolver, model, mimeType)
    else -> null
}

private fun uriBounds(resolver: ContentResolver, uri: Uri, mimeType: String?): Pair<Int, Int>? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        val rotation = resolver.openInputStream(uri)?.use { MediaProbe.exif(it, mimeType ?: JPEG)?.rotationDegrees } ?: 0
        upright(bounds.outWidth, bounds.outHeight, rotation)
    }
} catch (e: Exception) {
    null // another app's provider can refuse or vanish: no zoom, still shown
}

/** A video met while swiping: its poster frame (the thumbnail pipeline at page size) and a play button. */
@Composable
internal fun Poster(item: MediaEntity, onPlay: () -> Unit, onTap: () -> Unit, onVertical: (Float) -> Unit) =
    PosterFrame(item.path, item.mediaStoreId, item.dateModified, onPlay, onTap, onVertical)

/** [path]'s poster frame at page size; a play button only when [onPlay] is given. */
@Composable
internal fun PosterFrame(
    path: String,
    mediaStoreId: Long?,
    dateModified: Long,
    onPlay: (() -> Unit)?,
    onTap: () -> Unit,
    onVertical: (Float) -> Unit,
) {
    val state = remember { ZoomState() }
    val scope = rememberCoroutineScope()
    val tap = rememberUpdatedState(onTap)
    val vertical = rememberUpdatedState(onVertical)
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(state) { viewerGestures(state, zoomable = false, scope, tap, vertical) },
        contentAlignment = Alignment.Center,
    ) {
        val sizePx = minOf(constraints.maxWidth, constraints.maxHeight).coerceAtLeast(1)
        val context = LocalContext.current
        val request = remember(path, dateModified, sizePx) {
            thumbImageRequest(context, ThumbRequest(path, mediaStoreId, true, sizePx, dateModified))
        }
        AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        if (onPlay != null) Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(GalleryColors.BadgeScrim)
                .clickable(role = Role.Button, onClickLabel = "Play", onClick = onPlay)
                .testTag(PLAY_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GalleryIcons.Play, contentDescription = "Play", tint = GalleryColors.Text, modifier = Modifier.size(32.dp))
        }
    }
}

/** Review Focus 3: the index still has a row, the file is gone. The bars still work; nothing crashes. */
@Composable
internal fun Missing(onTap: () -> Unit, onVertical: (Float) -> Unit) {
    val state = remember { ZoomState() }
    val scope = rememberCoroutineScope()
    val tap = rememberUpdatedState(onTap)
    val vertical = rememberUpdatedState(onVertical)
    Box(
        Modifier.fillMaxSize().pointerInput(state) { viewerGestures(state, zoomable = false, scope, tap, vertical) },
        contentAlignment = Alignment.Center,
    ) {
        Text("File no longer exists", style = MissingStyle, modifier = Modifier.testTag(MISSING_TAG))
    }
}

@Composable
private fun BrokenImage(modifier: Modifier) {
    Icon(GalleryIcons.Image, contentDescription = null, tint = GalleryColors.Muted, modifier = modifier.size(48.dp))
}

/**
 * Tap (after the double-tap window), double tap, pinch, pan, and up/down drags. A one-finger drag is given away
 * untouched when [ZoomMath.owner] says it's the pager's, so the pager's own slop detection takes it.
 */
private suspend fun PointerInputScope.viewerGestures(
    state: ZoomState,
    zoomable: Boolean,
    scope: CoroutineScope,
    onTap: State<() -> Unit>,
    onVertical: State<(Float) -> Unit>,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    // The pager caught it while still settling: its drag.
    if (down.isConsumed) return@awaitEachGesture
    state.stop()
    val slop = viewConfiguration.touchSlop
    var owner: DragOwner? = null
    var tap = true
    var travel = Offset.Zero
    var dy = 0f
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) break
        if (owner == DragOwner.PAGER) continue
        if (pressed.size > 1) {
            tap = false
            if (!zoomable) continue
            owner = DragOwner.PAN
            val c = event.calculateCentroid()
            val zoomed = ZoomMath.zoomBy(state.zoom, event.calculateZoom(), c.x, c.y, state.frame)
            val pan = event.calculatePan()
            state.zoom = ZoomMath.panBy(zoomed, pan.x, pan.y, state.frame)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
            continue
        }
        val change = pressed[0]
        val delta = change.positionChange()
        if (owner == null) {
            travel += delta
            if (travel.getDistance() <= slop) continue
            tap = false
            owner = ZoomMath.owner(state.zoom, state.frame, travel.x, travel.y)
            if (owner == DragOwner.PAGER) continue
            if (owner == DragOwner.VERTICAL) dy = travel.y - delta.y
        }
        change.consume()
        if (owner == DragOwner.PAN) state.zoom = ZoomMath.panBy(state.zoom, delta.x, delta.y, state.frame) else dy += delta.y
    }
    if (owner == DragOwner.VERTICAL) onVertical.value(dy)
    if (!tap) return@awaitEachGesture
    if (!zoomable) return@awaitEachGesture onTap.value()
    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
        ?: return@awaitEachGesture onTap.value()
    // A double tap only if the second press lifts where it went down.
    var moved = Offset.Zero
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == second.id } ?: return@awaitEachGesture
        if (!change.pressed) {
            change.consume()
            state.animateTo(scope, ZoomMath.doubleTap(state.zoom, second.position.x, second.position.y, state.frame))
            return@awaitEachGesture
        }
        moved += change.positionChange()
        if (moved.getDistance() > slop || event.changes.count { it.pressed } > 1) return@awaitEachGesture
    }
}
