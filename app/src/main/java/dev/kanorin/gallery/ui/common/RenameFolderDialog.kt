package dev.kanorin.gallery.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import java.io.File

/** "Rename folder" for [path]: the names it must not clash with come from [siblings]. Shared by the Folders view and the folder screen. */
@Composable
fun RenameFolderDialog(path: String, siblings: suspend (String) -> Set<String>, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val names by produceState<Set<String>?>(null, path) { value = siblings(path) }
    NewFolderDialog(
        names, onConfirm = onRename, onDismiss = onDismiss,
        title = "Rename folder", subline = null, confirm = "Rename", initial = File(path).name,
    )
}
