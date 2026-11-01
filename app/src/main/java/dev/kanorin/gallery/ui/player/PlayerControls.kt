package dev.kanorin.gallery.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.logic.PlaybackSpeed
import dev.kanorin.gallery.logic.TimeFormat
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.theme.GalleryColors

const val PLAY_PAUSE_TAG = "player-play-pause"
const val SKIP_BACK_TAG = "player-skip-back"
const val SKIP_FORWARD_TAG = "player-skip-forward"
const val ROTATION_LOCK_TAG = "player-rotation-lock"
const val ROTATE_TAG = "player-rotate"
const val SPEED_TAG = "player-speed"
const val SEEK_BAR_TAG = "player-seek-bar"
const val TIME_CURRENT_TAG = "player-time-current"
const val TIME_TOTAL_TAG = "player-time-total"

private val ButtonSize = 36.dp
private val PlayDiscSize = 56.dp
private val BigIcon = 26.dp
private val TrackHeight = 22.dp
private val SpeedStyle = TextStyle(color = GalleryColors.PlayerText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
private val TimeStyle = TextStyle(color = GalleryColors.PlayerText, fontSize = 12.sp, fontFeatureSettings = "tnum")

/** What the button row shows: [speed] is the speed list's choice, [speedMenu] whether that list is open. */
data class ControlsState(
    val paused: Boolean,
    val rotationLocked: Boolean,
    val speed: Float = 1f,
    val speedMenu: Boolean = false,
    val landscape: Boolean = false,
    /** The skip buttons' step (Settings.skipSeconds, as the double-tap): their labels say it. */
    val skipSeconds: Int = Settings.DEFAULT_SKIP_SECONDS,
)

/**
 * The player's button row (spec 5.8, mockup phone 7 `.cbtns`): rotation lock · skip back, play / pause, skip
 * forward · speed, rotate. The outer columns share the leftover width equally (`minmax(0,1fr) auto minmax(0,1fr)`),
 * so the play disc's centre is the row's centre whatever sits on either side. The speed list opens above the right one.
 */
@Composable
fun PlayerControls(
    state: ControlsState,
    onRotationLock: () -> Unit,
    onSkipBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    /** The speed label's tap: open or close the list. */
    onSpeed: () -> Unit,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier,
    onPickSpeed: (Float) -> Unit = {},
    onDismissSpeed: () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            ControlButton(
                GalleryIcons.RotationLock, if (state.rotationLocked) "Unlock rotation" else "Lock rotation", onRotationLock,
                ROTATION_LOCK_TAG, lit = state.rotationLocked,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ControlButton(GalleryIcons.Rewind, "Skip back ${state.skipSeconds} s", onSkipBack, SKIP_BACK_TAG, iconSize = BigIcon)
            Box(
                Modifier
                    .size(PlayDiscSize)
                    .clip(CircleShape)
                    .background(GalleryColors.PlayDisc)
                    .clickable(role = Role.Button, onClick = onPlayPause)
                    .testTag(PLAY_PAUSE_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.paused) GalleryIcons.Play else GalleryIcons.Pause,
                    contentDescription = if (state.paused) "Play" else "Pause",
                    tint = GalleryColors.Text,
                    modifier = Modifier.size(BigIcon),
                )
            }
            ControlButton(GalleryIcons.FastForward, "Skip forward ${state.skipSeconds} s", onSkipForward, SKIP_FORWARD_TAG, iconSize = BigIcon)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp, ButtonSize).clickable(role = Role.Button, onClick = onSpeed).testTag(SPEED_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Text(PlaybackSpeed.label(state.speed), style = SpeedStyle, maxLines = 1, modifier = Modifier.width(34.dp))
            }
            ControlButton(GalleryIcons.Rotate, "Rotate", onRotate, ROTATE_TAG)
            // Its parent is this column: the list anchors to it (mockup .cr).
            if (state.speedMenu) SpeedMenu(state.speed, state.landscape, onPickSpeed, onDismissSpeed)
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, onClick: () -> Unit, tag: String, iconSize: Dp = 20.dp, lit: Boolean = false) {
    Box(
        Modifier
            .size(ButtonSize)
            .clip(CircleShape)
            .then(if (lit) Modifier.background(GalleryColors.ButtonOn) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (lit) GalleryColors.Accent else GalleryColors.Text, modifier = Modifier.size(iconSize))
    }
}

/**
 * "current / total" over the bar (mockup `.times` + `.track`): played, buffered, knob. A press anywhere on the times or
 * the bar starts a scrub: the bar and the time follow the finger, [preview] shows above the thumb, and lifting seeks.
 */
@Composable
internal fun SeekBar(
    playback: State<Playback>,
    onScrubbing: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    preview: @Composable (positionMs: Long, fraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val p = playback.value
    val duration = p.durationMs
    val fraction = scrub ?: if (duration > 0) (p.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val position = scrub?.let { (it * duration).toLong() } ?: p.positionMs
    val buffered = if (duration > 0) (p.bufferedMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val scrubbing by rememberUpdatedState(onScrubbing)
    val seek by rememberUpdatedState(onSeek)
    Column(
        modifier
            .fillMaxWidth()
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    down.consume()
                    scrub = at(down.position.x)
                    scrubbing(true)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                seek((at(change.position.x) * duration).toLong())
                                break
                            }
                            scrub = at(change.position.x)
                            change.consume()
                        }
                    } finally {
                        // A cancelled drag ends without a seek.
                        scrub = null
                        scrubbing(false)
                    }
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(TimeFormat.position(position, duration), style = TimeStyle, modifier = Modifier.testTag(TIME_CURRENT_TAG))
            Spacer(Modifier.weight(1f))
            Text(TimeFormat.duration(duration), style = TimeStyle, modifier = Modifier.testTag(TIME_TOTAL_TAG))
        }
        Box(Modifier.fillMaxWidth().height(TrackHeight).testTag(SEEK_BAR_TAG)) {
            Track(fraction, buffered)
            if (scrub != null) preview(position, fraction)
        }
    }
}

@Composable
private fun Track(played: Float, buffered: Float) {
    Canvas(Modifier.fillMaxWidth().height(TrackHeight)) {
        val top = 9.dp.toPx()
        val h = 4.dp.toPx()
        val r = CornerRadius(2.dp.toPx())
        drawRoundRect(GalleryColors.TrackBg, Offset(0f, top), Size(size.width, h), r)
        drawRoundRect(GalleryColors.TrackBuffered, Offset(0f, top), Size(size.width * maxOf(buffered, played), h), r)
        drawRoundRect(GalleryColors.Accent, Offset(0f, top), Size(size.width * played, h), r)
        val knob = Offset(size.width * played, top + h / 2)
        drawCircle(GalleryColors.KnobRing, radius = 12.dp.toPx(), center = knob)
        drawCircle(GalleryColors.Accent, radius = 7.dp.toPx(), center = knob)
    }
}
