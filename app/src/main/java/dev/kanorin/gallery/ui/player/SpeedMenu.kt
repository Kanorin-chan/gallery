package dev.kanorin.gallery.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.kanorin.gallery.logic.PlaybackSpeed
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.theme.GalleryColors

const val SPEED_MENU_TAG = "player-speed-menu"
const val SPEED_OPTION_TAG_PREFIX = "player-speed-option-"

fun speedOptionTag(speed: Float) = SPEED_OPTION_TAG_PREFIX + speed

private val MenuShape = RoundedCornerShape(16.dp)
private val OptionShape = RoundedCornerShape(10.dp)
private val HeaderStyle = TextStyle(color = GalleryColors.Muted, fontSize = 11.sp, letterSpacing = 0.8.sp)
private val OptionStyle = TextStyle(color = GalleryColors.Text, fontSize = 13.5.sp, fontFeatureSettings = "tnum")
private val NormalStyle = SpanStyle(color = GalleryColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Normal)

/**
 * The speed list (spec 5.8, mockup `.spdmenu`): pops up above its parent (the controls' right column), right edges
 * lined up, two columns sideways. The current speed has a tick; a pick reports and closes; a tap outside closes.
 */
@Composable
fun SpeedMenu(current: Float, landscape: Boolean, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val position = remember(density) { with(density) { SpeedMenuPosition(gap = 10.dp.roundToPx(), outset = 6.dp.roundToPx()) } }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .testTag(SPEED_MENU_TAG)
                .width(if (landscape) 250.dp else 150.dp)
                .shadow(16.dp, MenuShape)
                .background(GalleryColors.SpeedMenuBg, MenuShape)
                .padding(6.dp),
        ) {
            Text("Playback speed".uppercase(), style = HeaderStyle, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp))
            PlaybackSpeed.OPTIONS.chunked(if (landscape) 2 else 1).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { speed ->
                        SpeedOption(
                            speed, on = speed == current, height = if (landscape) 28.dp else 32.dp,
                            onClick = { onPick(speed); onDismiss() }, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedOption(speed: Float, on: Boolean, height: Dp, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .height(height)
            .clip(OptionShape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp)
            .testTag(speedOptionTag(speed)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            optionText(speed),
            style = if (on) OptionStyle.copy(color = GalleryColors.Accent, fontWeight = FontWeight.SemiBold) else OptionStyle,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (on) Icon(GalleryIcons.Check, contentDescription = null, tint = GalleryColors.Accent, modifier = Modifier.size(16.dp))
    }
}

private fun optionText(speed: Float): AnnotatedString = buildAnnotatedString {
    append(PlaybackSpeed.label(speed))
    if (speed == 1f) withStyle(NormalStyle) { append(" Normal") }
}

/** Above the anchor with a [gap], its right edge [outset] past the anchor's; kept inside the window (px). */
internal class SpeedMenuPosition(private val gap: Int, private val outset: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.right + outset - popupContentSize.width
        val y = anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)), y.coerceAtLeast(0))
    }
}
