package dev.kanorin.gallery.ui.folders

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.kanorin.gallery.ui.theme.GalleryColors

const val MENU_PIN_TAG = "folder-menu-pin"
const val MENU_HIDE_TAG = "folder-menu-hide"
const val MENU_RENAME_TAG = "folder-menu-rename"

/** Long-press menu of a folder card (spec 5.3). Rename is offered only when [canRename]. Anchored to the composable it sits in. */
@Composable
fun FolderActionsMenu(
    expanded: Boolean,
    pinned: Boolean,
    hidden: Boolean,
    canRename: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onHide: () -> Unit,
    onRename: () -> Unit,
) {
    DropdownMenu(expanded, onDismissRequest = onDismiss, containerColor = GalleryColors.Surface2) {
        DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin") }, onClick = onTogglePin, modifier = Modifier.testTag(MENU_PIN_TAG))
        DropdownMenuItem(text = { Text(if (hidden) "Unhide" else "Hide") }, onClick = onHide, modifier = Modifier.testTag(MENU_HIDE_TAG))
        if (canRename) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = onRename, modifier = Modifier.testTag(MENU_RENAME_TAG))
        }
    }
}
