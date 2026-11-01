package dev.kanorin.gallery.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.ui.theme.GalleryColors

enum class MainMode { ALL, FOLDERS }

const val PILL_MENU_TAG = "pill-menu"

private val Gap = 6.dp
private val ModeStartMargin = 6.dp
private val QueryStyle = TextStyle(color = GalleryColors.Text, fontSize = 14.sp)
private val PlaceholderStyle = QueryStyle.copy(color = GalleryColors.Muted)
private val ModeStyle = TextStyle(color = GalleryColors.Text, fontSize = 12.sp)

/** `[ search | mode | ⋮ ]` (spec 5.1). The query box takes the slack, so ⋮ never moves when the mode label changes. */
@Composable
fun SearchPill(
    mode: MainMode,
    query: String,
    onQuery: (String) -> Unit,
    onToggleMode: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(GalleryColors.Surface2)
            .padding(start = 15.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QueryField(mode, query, onQuery, Modifier.weight(1f))
        Spacer(Modifier.width(Gap))
        Box(Modifier.size(width = 1.dp, height = 22.dp).background(GalleryColors.PillDivider))
        Spacer(Modifier.width(Gap + ModeStartMargin))
        ModeButton(mode, onToggleMode)
        // Gap 6 dp minus the mode button's -6 dp end margin: it sits flush against ⋮.
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onMenu)
                .testTag(PILL_MENU_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GalleryIcons.Dots, contentDescription = "Menu", tint = GalleryColors.Text, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun QueryField(mode: MainMode, query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(GalleryIcons.Search, contentDescription = null, tint = GalleryColors.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(9.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = QueryStyle,
            cursorBrush = SolidColor(GalleryColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The search is live while typing, so the keyboard's search key just gets it out of the way.
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus(force = true) }),
            modifier = Modifier.weight(1f),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(if (mode == MainMode.ALL) "Search photos" else "Search folders", style = PlaceholderStyle, maxLines = 1)
                    }
                    field()
                }
            },
        )
    }
}

@Composable
private fun ModeButton(mode: MainMode, onToggleMode: () -> Unit) {
    Row(
        Modifier
            .height(36.dp)
            .defaultMinSize(minWidth = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(GalleryColors.Surface3)
            .clickable(role = Role.Button, onClick = onToggleMode)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            if (mode == MainMode.ALL) GalleryIcons.Grid else GalleryIcons.Folder,
            contentDescription = null,
            tint = GalleryColors.Text,
            modifier = Modifier.size(20.dp),
        )
        Text(if (mode == MainMode.ALL) "All" else "Folders", style = ModeStyle, maxLines = 1)
    }
}
