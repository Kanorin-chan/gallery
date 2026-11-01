package dev.kanorin.gallery.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.logic.Transfer
import dev.kanorin.gallery.ui.common.DialogTextButton
import dev.kanorin.gallery.ui.common.GalleryDialog
import dev.kanorin.gallery.ui.theme.GalleryColors

const val ADD_TO_TAG = "add-to-folder"

private val ButtonHeight = 48.dp
/** The button's gap above the nav bar. */
val AddButtonLift = 20.dp
/** Grid room the button needs below the last row: its height, its lift and a gap. */
val AddButtonClearance = ButtonHeight + AddButtonLift + 12.dp

private val ButtonShape = RoundedCornerShape(24.dp)
private val OptionShape = RoundedCornerShape(14.dp)
private val ButtonStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
private val OptionStyle = TextStyle(color = GalleryColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

/** ADD PHOTOS' bottom button "Add to <name>" (spec 5.3); greyed until something is selected. */
@Composable
fun AddToFolderButton(name: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .widthIn(max = 320.dp)
            .height(ButtonHeight)
            .clip(ButtonShape)
            .background(if (enabled) GalleryColors.Accent else GalleryColors.Surface3)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp)
            .testTag(ADD_TO_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Add to $name", maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = ButtonStyle.copy(color = if (enabled) GalleryColors.OnAccent else GalleryColors.Muted),
        )
    }
}

/** "Move" or "Copy" into [name] (spec 5.3: ADD PHOTOS asks which). Move is first and highlighted, like the mockup's .dopt.primary. */
@Composable
fun MoveOrCopyDialog(name: String, onPick: (Transfer) -> Unit, onDismiss: () -> Unit) {
    GalleryDialog("Add to $name", subline = null, onDismiss = onDismiss) {
        Option("Move", primary = true) { onPick(Transfer.MOVE) }
        Option("Copy", primary = false) { onPick(Transfer.COPY) }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, end = 2.dp), horizontalArrangement = Arrangement.End) {
            DialogTextButton("Cancel", onDismiss)
        }
    }
}

@Composable
private fun Option(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(OptionShape)
            .background(if (primary) GalleryColors.OptionPrimary else GalleryColors.Surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
    ) {
        Text(text, style = if (primary) OptionStyle.copy(color = GalleryColors.Accent) else OptionStyle)
    }
}
