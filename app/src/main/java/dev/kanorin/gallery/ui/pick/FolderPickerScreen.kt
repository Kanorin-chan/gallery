package dev.kanorin.gallery.ui.pick

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.ui.common.BigBarButton
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.common.NewFolderDialog
import dev.kanorin.gallery.ui.folders.FoldersGrid
import dev.kanorin.gallery.ui.folders.FoldersModel
import dev.kanorin.gallery.ui.theme.GalleryColors

const val PICK_FOLDER_ROUTE = "pick-folder"

private val TitleStyle = TextStyle(color = GalleryColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

/**
 * Where Move to folder / Copy to folder go (spec 5.2): the Folders view's cards in its order, without Favourites
 * (not a real folder) and without the long-press menu. "+ New folder" makes one and picks it.
 */
@Composable
fun FolderPickerScreen(
    title: String,
    model: FoldersModel?,
    columns: Int?,
    onColumns: (Int) -> Unit,
    onPick: (String) -> Unit,
    newFolderSiblings: suspend () -> Set<String>,
    onCreateFolder: (String) -> Unit,
    onBack: () -> Unit,
) {
    var newFolder by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(GalleryColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BigBarButton(GalleryIcons.Back, "Back", onBack)
            Text(title, style = TitleStyle, modifier = Modifier.padding(start = 6.dp))
        }
        val shown = remember(model) {
            model?.copy(arranged = model.arranged.copy(pinned = model.arranged.pinned.filterNot { it.isFavourites }))
        }
        if (shown != null && columns != null) {
            FoldersGrid(
                shown, columns, canRename = { false },
                onColumns = onColumns,
                onOpen = { onPick(it.path) },
                onNewFolder = { newFolder = true },
                onTogglePin = {}, onHide = {}, onRename = {},
                actions = false,
            )
        }
    }
    if (newFolder) {
        val siblings by produceState<Set<String>?>(null) { value = newFolderSiblings() }
        NewFolderDialog(siblings, onConfirm = onCreateFolder, onDismiss = { newFolder = false })
    }
}
