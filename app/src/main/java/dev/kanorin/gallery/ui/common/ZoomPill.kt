package dev.kanorin.gallery.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.ui.theme.GalleryColors
import kotlinx.coroutines.delay

const val ZOOM_PILL_TAG = "zoom-pill"

private const val FLASH_MS = 1_200L
private const val FADE_MS = 200
private val PillShape = RoundedCornerShape(22.dp)
private val LabelStyle = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp)

/**
 * "Grid 2 3 4 5 6" with [current] lit. [onPick] null = flash mode: shows on each change of [current] and hides
 * 1.2 s after the last. Otherwise always shown with tappable numbers. [visible] false hides it in both modes.
 */
@Composable
fun ZoomPill(
    min: Int,
    max: Int,
    current: Int,
    visible: Boolean,
    onPick: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    /** An accent ring, to point at the pill (the folder menu's "Grid size"). */
    highlight: Boolean = false,
) {
    var flashing by remember { mutableStateOf(false) }
    var flashed by remember { mutableIntStateOf(current) }
    if (onPick == null) {
        LaunchedEffect(current) {
            if (current == flashed) return@LaunchedEffect
            flashed = current
            flashing = true
            // A newer change cancels this wait, so it hides 1.2 s after the last one.
            delay(FLASH_MS)
            flashing = false
        }
    }
    AnimatedVisibility(
        visible = visible && (onPick != null || flashing),
        enter = fadeIn(tween(FADE_MS)),
        exit = fadeOut(tween(FADE_MS)),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .testTag(ZOOM_PILL_TAG)
                .semantics(mergeDescendants = true) {}
                .shadow(10.dp, PillShape)
                .background(GalleryColors.Surface2.copy(alpha = 0.94f), PillShape)
                .then(if (highlight) Modifier.border(1.5.dp, GalleryColors.Accent, PillShape) else Modifier)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Grid", style = LabelStyle, modifier = Modifier.padding(start = 8.dp, end = 6.dp))
            for (n in min..max) {
                val on = n == current
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (on) GalleryColors.Accent else Color.Transparent)
                        .then(if (onPick != null) Modifier.clickable { onPick(n) } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$n",
                        style = LabelStyle.copy(
                            color = if (on) GalleryColors.OnAccent else GalleryColors.Muted,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}
