package dev.kanorin.gallery.ui.player

import android.app.Activity
import android.content.res.Configuration
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import dev.kanorin.gallery.logic.DetailsText
import dev.kanorin.gallery.logic.TimeFormat
import dev.kanorin.gallery.ui.common.BarButton
import dev.kanorin.gallery.ui.common.DeleteDialogHost
import dev.kanorin.gallery.ui.common.DialogTextButton
import dev.kanorin.gallery.ui.common.GalleryDialog
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.common.LocalToasts
import dev.kanorin.gallery.ui.common.SystemBars
import dev.kanorin.gallery.ui.common.shareSelection
import dev.kanorin.gallery.ui.common.shareUriIntent
import dev.kanorin.gallery.ui.theme.GalleryColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.WeakHashMap
import kotlin.math.hypot
import dev.kanorin.gallery.ui.common.launch as startOrToast

const val PLAYER_TAG = "player"
const val PLAYER_TOP_BAR_TAG = "player-top-bar"
const val PLAYER_CONTROLS_TAG = "player-controls"
const val PLAYER_TITLE_TAG = "player-title"
const val PLAYER_MENU_TAG = "player-menu"
const val MENU_INFO_TAG = "player-menu-info"
const val MENU_SHARE_TAG = "player-menu-share"
const val MENU_DELETE_TAG = "player-menu-delete"
const val INFO_DIALOG_TAG = "player-info"
const val PLAYER_ERROR_TAG = "player-error"
const val PLAYER_NOTICE_TAG = "player-notice"

fun rippleTag(forward: Boolean) = if (forward) "player-ripple-forward" else "player-ripple-back"

const val HIDE_CONTROLS_MS = 3_000L
const val DOUBLE_TAP_MS = 250L
const val RIPPLE_MS = 600L
const val NOTICE_MS = 1_200L

private val TitleStyle = TextStyle(color = GalleryColors.Text, fontSize = 13.sp)
internal val PillStyle = TextStyle(color = GalleryColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
private val ErrorStyle = TextStyle(color = GalleryColors.Text, fontSize = 15.sp)
private val InfoLabel = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp)
private val InfoValue = TextStyle(color = GalleryColors.Text, fontSize = 13.5.sp)
internal val PillShape = RoundedCornerShape(18.dp)

/** The double-tap ripple on one side: its running total and which tap it was. */
private data class SkipFlash(val forward: Boolean, val seconds: Int, val id: Int)

private data class Notice(val text: String, val id: Int)

/**
 * The video player (spec 5.8, mockup phone 7): black, immersive, the video letterboxed. Tap toggles the controls
 * (they hide after 3 s while playing); double-tap a side skips by the setting, with a ripple that adds up repeated
 * double-taps. Hold = the hold speed (slides: slow / lock), pinch zooms, a zoomed drag pans; the speed list sets
 * the speed a hold comes back to. The bar scrubs with a frame preview. Lock / rotate steer the activity's
 * orientation, which goes back as it was when the player closes.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val vm = viewModel
    val playback = vm.playback.collectAsStateWithLifecycle()
    val paused by remember { derivedStateOf { playback.value.paused } }
    val failed by remember { derivedStateOf { playback.value.failed } }
    val closed by vm.closed.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val locked by vm.rotationLocked.collectAsStateWithLifecycle()
    val skipSeconds by vm.skipSeconds.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val holdSpeed by vm.holdSpeed.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(closed) { if (closed) onBack() }
    SystemBars(visible = false)
    PlayerOrientation(vm)
    KeepScreenOn(on = !paused)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onStop(changingConfigurations = activity?.isChangingConfigurations == true) }
    val gestures = rememberVideoGestures(holdSpeed)
    PlaybackRate(vm, gestures)
    LaunchedEffect(landscape) { gestures.resetZoom() }

    var controls by rememberSaveable { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var skip by remember { mutableStateOf(SkipFlash(forward = false, seconds = 0, id = 0)) }
    var skipShown by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf(Notice("", 0)) }
    var noticeShown by remember { mutableStateOf(false) }
    val touched = { touches++ }

    // Paused by anything (the button, the end, a phone call): the controls come back.
    LaunchedEffect(paused) { if (paused) controls = true }
    LaunchedEffect(controls, paused, touches, scrubbing, menu, info, speedMenu) {
        if (controls && !paused && !scrubbing && !menu && !info && !speedMenu) {
            delay(HIDE_CONTROLS_MS)
            controls = false
        }
    }
    LaunchedEffect(skip) {
        if (skip.id > 0) {
            delay(RIPPLE_MS)
            skipShown = false
        }
    }
    LaunchedEffect(notice) {
        if (notice.id > 0) {
            delay(NOTICE_MS)
            noticeShown = false
        }
    }

    Box(Modifier.fillMaxSize().background(GalleryColors.ViewerBg)) {
        ContentFrame(vm.player, Modifier.fillMaxSize().videoZoom(gestures).testTag(VIDEO_FRAME_TAG), surfaceType = SURFACE_TYPE_TEXTURE_VIEW)
        Box(
            Modifier
                .fillMaxSize()
                .testTag(PLAYER_TAG)
                .videoTaps(
                    onTap = { controls = !controls },
                    onDoubleTap = { forward ->
                        val step = skipSeconds
                        skip = if (skipShown && skip.forward == forward) {
                            skip.copy(seconds = skip.seconds + step, id = skip.id + 1)
                        } else {
                            SkipFlash(forward, step, skip.id + 1)
                        }
                        skipShown = true
                        vm.skipBy(if (forward) step * 1000L else -step * 1000L)
                        touched()
                    },
                )
                .videoGestures(gestures),
        )
        SkipRipple(forward = false, shown = skipShown && !skip.forward, seconds = skip.seconds)
        SkipRipple(forward = true, shown = skipShown && skip.forward, seconds = skip.seconds)

        if (failed) {
            PlaybackError(onBack)
        } else {
            AnimatedVisibility(controls, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(ControlsScrim))
                    TopBar(
                        title = title,
                        onBack = onBack,
                        menuOpen = menu,
                        onMenu = { menu = it; touched() },
                        canDelete = source is PlayerSource.Path,
                        onInfo = { info = true },
                        onShare = rememberShare(vm),
                        onDelete = vm::delete,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    BottomControls(
                        playback = playback,
                        source = source,
                        landscape = landscape,
                        state = ControlsState(paused, locked, speed, speedMenu, landscape, skipSeconds),
                        onScrubbing = { scrubbing = it; touched() },
                        onSeek = vm::seekTo,
                        onRotationLock = {
                            val on = vm.toggleRotationLock()
                            notice = Notice(if (on) "Rotation locked" else "Rotation unlocked", notice.id + 1)
                            noticeShown = true
                            touched()
                        },
                        onSkipBack = { vm.skipBy(-skipSeconds * 1000L); touched() },
                        onPlayPause = { vm.playPause(); touched() },
                        onSkipForward = { vm.skipBy(skipSeconds * 1000L); touched() },
                        onRotate = { vm.rotate(landscapeNow = landscape); touched() },
                        onSpeed = { speedMenu = !speedMenu; touched() },
                        onPickSpeed = { vm.setSpeed(it); touched() },
                        onDismissSpeed = { speedMenu = false },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        AnimatedVisibility(
            noticeShown,
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = if (landscape) 48.dp else 58.dp),
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
            Text(
                notice.text,
                style = PillStyle,
                modifier = Modifier.background(GalleryColors.PlayerPill, PillShape).padding(horizontal = 14.dp, vertical = 7.dp).testTag(PLAYER_NOTICE_TAG),
            )
        }
        SpeedPill(gestures, landscape)
        // Last: above the controls, so its tap never reaches the bar or the video.
        VideoZoomPill(gestures, controls, landscape)
    }
    DeleteDialogHost(vm.actions)
    if (info) InfoDialog(remember { vm.info() }, onDismiss = { info = false })
}

// Mockup .vgrad: dark at the top for the title, clear in the middle, darker at the bottom for the bar.
private val ControlsScrim = Brush.verticalGradient(
    0f to GalleryColors.PlayerScrimTop,
    0.18f to Color.Transparent,
    0.62f to Color.Transparent,
    1f to GalleryColors.PlayerScrimBottom,
)

@Composable
private fun TopBar(
    title: String,
    onBack: () -> Unit,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    canDelete: Boolean,
    onInfo: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .testTag(PLAYER_TOP_BAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(GalleryIcons.Back, "Back", onBack)
        Text(
            title, style = TitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp).testTag(PLAYER_TITLE_TAG),
        )
        Box {
            BarButton(GalleryIcons.Dots, "More options", { onMenu(true) }, Modifier.testTag(PLAYER_MENU_TAG))
            DropdownMenu(menuOpen, onDismissRequest = { onMenu(false) }, containerColor = GalleryColors.Surface2) {
                val items = buildList {
                    add(Triple("Info", MENU_INFO_TAG, onInfo))
                    add(Triple("Share", MENU_SHARE_TAG, onShare))
                    if (canDelete) add(Triple("Delete", MENU_DELETE_TAG, onDelete))
                }
                items.forEach { (label, tag, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onMenu(false); action() }, modifier = Modifier.testTag(tag))
                }
            }
        }
    }
}

@Composable
private fun BottomControls(
    playback: State<Playback>,
    source: PlayerSource?,
    landscape: Boolean,
    state: ControlsState,
    onScrubbing: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onRotationLock: () -> Unit,
    onSkipBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onRotate: () -> Unit,
    onSpeed: () -> Unit,
    onPickSpeed: (Float) -> Unit,
    onDismissSpeed: () -> Unit,
    modifier: Modifier,
) {
    // The same inset on both sides, so a cutout on one side never moves the play button off the screen's centre.
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val side = with(density) { maxOf(safe.getLeft(density, direction), safe.getRight(density, direction)).toDp() }
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(safe.only(WindowInsetsSides.Bottom))
            .padding(horizontal = side)
            .padding(start = if (landscape) 30.dp else 16.dp, end = if (landscape) 30.dp else 16.dp, bottom = if (landscape) 10.dp else 18.dp)
            .testTag(PLAYER_CONTROLS_TAG),
    ) {
        SeekBar(playback, onScrubbing, onSeek, preview = { position, fraction ->
            if (source != null) ScrubPreview(source, position, playback.value.durationMs, fraction, landscape)
        })
        Spacer(Modifier.height(if (landscape) 2.dp else 10.dp))
        PlayerControls(state, onRotationLock, onSkipBack, onPlayPause, onSkipForward, onSpeed, onRotate, onPickSpeed = onPickSpeed, onDismissSpeed = onDismissSpeed)
    }
}

/** The player plays at the list's speed, or at a hold's rate while a finger says so. */
@Composable
private fun PlaybackRate(vm: PlayerViewModel, gestures: VideoGestureState) {
    val base by vm.speed.collectAsStateWithLifecycle()
    val rate = gestures.rate(base)
    LaunchedEffect(rate) { vm.playAt(rate) }
}

/** Share: the share sheet of our file, or the other app's URI handed straight on. */
@Composable
private fun rememberShare(vm: PlayerViewModel): () -> Unit {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    return {
        when (val s = vm.source.value) {
            is PlayerSource.Path -> scope.launch { shareSelection(context, listOf(s.path), vm.actions::rows, { toasts.show(it) }) }
            is PlayerSource.Content -> startOrToast(context, { toasts.show(it) }, "Couldn't open sharing") {
                shareUriIntent(s.uri, vm.args.type ?: "video/*")
            }
            null -> Unit
        }
    }
}

@Composable
private fun BoxScope.PlaybackError(onBack: () -> Unit) {
    Column(Modifier.align(Alignment.Center).testTag(PLAYER_ERROR_TAG), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Can't play this video", style = ErrorStyle)
        Spacer(Modifier.height(12.dp))
        DialogTextButton("Back", onBack)
    }
}

/** Mockup .ripple: a half-ellipse glow on the tapped side with the skip icon and the running total. */
@Composable
private fun BoxScope.SkipRipple(forward: Boolean, shown: Boolean, seconds: Int) {
    AnimatedVisibility(
        shown,
        Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.46f),
        enter = EnterTransition.None,
        exit = fadeOut(tween(250)),
    ) {
        val shape = remember(forward) {
            GenericShape { size, _ ->
                addOval(if (forward) Rect(0f, 0f, size.width * 2, size.height) else Rect(-size.width, 0f, size.width, size.height))
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .drawBehind {
                    val centre = Offset(size.width * if (forward) 0.7f else 0.3f, size.height / 2)
                    val corner = hypot(size.width * 0.7f, size.height / 2)
                    drawRect(Brush.radialGradient(listOf(GalleryColors.Ripple, Color.Transparent), centre, radius = corner * 0.7f))
                }
                .testTag(rippleTag(forward)),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(if (forward) GalleryIcons.FastForward else GalleryIcons.Rewind, contentDescription = null, tint = GalleryColors.Text, modifier = Modifier.size(30.dp))
            Text("$seconds s", style = PillStyle)
        }
    }
}

@Composable
private fun InfoDialog(info: VideoInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    GalleryDialog(info.name, subline = null, onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 4.dp).testTag(INFO_DIALOG_TAG), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow("Duration", TimeFormat.duration(info.durationMs))
            DetailsText.resolution(info.width, info.height)?.let { InfoRow("Resolution", it) }
            DetailsText.codec(info.codecMime)?.let { InfoRow("Codec", it) }
            info.sizeBytes?.let { InfoRow("Size", Formatter.formatShortFileSize(context, it)) }
            InfoRow("Path", info.location)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) { DialogTextButton("Close", onDismiss) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = InfoLabel)
        Text(value, style = InfoValue)
    }
}

/**
 * Tap toggles; a second tap within [DOUBLE_TAP_MS] is a double-tap on its half, decided when that press lifts, so a
 * second finger (a pinch) or a drag takes it instead. A moved finger is no tap.
 */
private fun Modifier.videoTaps(onTap: () -> Unit, onDoubleTap: (forward: Boolean) -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            up.consume()
            val second = withTimeoutOrNull(DOUBLE_TAP_MS) { awaitFirstDown() }
            if (second == null) {
                onTap()
                return@awaitEachGesture
            }
            second.consume() // the speed gesture sees it taken: no hold from this press
            val secondUp = waitForUpOrCancellation() ?: return@awaitEachGesture
            if ((secondUp.position - second.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            secondUp.consume()
            onDoubleTap(second.position.x >= size.width / 2f)
        }
    },
)

/** The lock and the rotate button steer the activity; it gets its old orientation back when the last player leaves. */
@Composable
private fun PlayerOrientation(vm: PlayerViewModel) {
    val activity = LocalActivity.current ?: return
    val locked by vm.rotationLocked.collectAsStateWithLifecycle()
    val forced by vm.forced.collectAsStateWithLifecycle()
    DisposableEffect(activity) {
        // A VIEW intent's player can replace another: both share the orientation from before the first.
        val held = heldOrientations.getOrPut(activity) { HeldOrientation(vm.previousOrientation(activity.requestedOrientation)) }
        vm.previousOrientation(held.before)
        held.players++
        onDispose {
            held.players--
            if (held.players == 0) {
                heldOrientations.remove(activity)
                // A rotation recreates the activity: restoring then would turn the screen back.
                if (!activity.isChangingConfigurations) activity.requestedOrientation = held.before
            }
        }
    }
    val wanted = orientationFor(locked, forced)
    SideEffect { if (activity.requestedOrientation != wanted) activity.requestedOrientation = wanted }
}

private class HeldOrientation(val before: Int) {
    var players = 0
}

// Main thread only (composition effects).
private val heldOrientations = WeakHashMap<Activity, HeldOrientation>()

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
