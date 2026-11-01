package dev.kanorin.gallery.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.logic.TimeFormat
import dev.kanorin.gallery.ui.theme.GalleryColors

private const val SELECTED_SCALE = 0.88f
private const val SCALE_MS = 120

/** A square grid tile with the selection look (spec 5.2, mockup .ck): shrunk + accent border when selected, a check circle while selecting. */
@Composable
fun SelectableTile(selecting: Boolean, selected: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val scale by animateFloatAsState(if (selected) SELECTED_SCALE else 1f, tween(SCALE_MS), label = "tile-scale")
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { this.selected = selected }
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        content()
        if (selected) Box(Modifier.matchParentSize().border(3.dp, GalleryColors.Accent))
        if (selecting) CheckCircle(selected, Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 5.dp))
    }
}

/** Every tile shows one while selecting: a white ring, or accent with a tick when selected (mockup .ck). */
@Composable
private fun CheckCircle(on: Boolean, modifier: Modifier) {
    Box(
        modifier
            .size(18.dp)
            .background(if (on) GalleryColors.Accent else GalleryColors.CheckScrim, CircleShape)
            .border(1.5.dp, if (on) GalleryColors.Accent else GalleryColors.CheckRing, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(GalleryIcons.CheckHeavy, contentDescription = null, tint = GalleryColors.OnAccent, modifier = Modifier.size(11.dp))
    }
}

/** White 10 sp text with a drop shadow, legible over any photo (the duration badge, the bin's days left). */
@Composable
fun rememberTileLabelStyle(): TextStyle {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            TextStyle(
                color = GalleryColors.Text,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                shadow = Shadow(Color.Black, Offset(0f, 1.dp.toPx()), blurRadius = 3.dp.toPx()),
            )
        }
    }
}

/** A video's play glyph and length, for a tile's bottom-right corner. */
@Composable
fun VideoBadge(durationMs: Long, modifier: Modifier = Modifier) {
    val style = rememberTileLabelStyle()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(GalleryIcons.PlaySmall, contentDescription = null, tint = GalleryColors.Text, modifier = Modifier.size(10.dp))
        // Unknown length (0) shows the glyph alone rather than a false "00:00".
        if (durationMs > 0) Text(TimeFormat.duration(durationMs), style = style, maxLines = 1)
    }
}
