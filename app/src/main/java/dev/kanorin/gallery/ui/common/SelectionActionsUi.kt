package dev.kanorin.gallery.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kanorin.gallery.logic.Transfer
import kotlinx.coroutines.launch

/** The selection bar wired to [actions]; its own scope: the count and size change on every drag step, and only the bar should recompose. */
@Composable
fun SelectingBar(actions: SelectionActions, onPickFolder: () -> Unit) {
    val selection = actions.selection
    val bytes by actions.selectedBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    SelectionBar(
        count = selection.selected.size,
        totalBytes = bytes,
        onClose = actions::endSelection,
        onSelectAll = actions::toggleAll,
        onShare = { scope.launch { shareSelection(context, selection.selected.toSet(), actions::rows, { toasts.show(it) }) } },
        onDelete = actions::askDelete,
        onMoveToFolder = { actions.beginTransfer(Transfer.MOVE); onPickFolder() },
        onCopyToFolder = { actions.beginTransfer(Transfer.COPY); onPickFolder() },
    )
}

/** The delete dialog while [actions] is asking. */
@Composable
fun DeleteDialogHost(actions: SelectionActions) {
    val ask by actions.deleteAsk.collectAsStateWithLifecycle()
    ask?.let { DeleteDialog(it.photos, it.videos, onChoice = actions::delete, onCancel = actions::cancelDelete) }
}
