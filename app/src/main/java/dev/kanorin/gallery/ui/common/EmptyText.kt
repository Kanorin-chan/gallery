package dev.kanorin.gallery.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.ui.theme.GalleryColors

private val EmptyStyle = TextStyle(color = GalleryColors.Muted, fontSize = 14.sp)

/** A grid's empty state: centred muted text (the inside-folder screen and the All view). */
@Composable
internal fun EmptyText(text: String, tag: String) {
    // Centred above the keyboard: a search with no results is read while typing.
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Text(text, style = EmptyStyle, modifier = Modifier.testTag(tag))
    }
}
