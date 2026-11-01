package dev.kanorin.gallery.ui.bin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kanorin.gallery.data.BinEntity
import dev.kanorin.gallery.logic.BinPolicy
import dev.kanorin.gallery.logic.DeleteText
import dev.kanorin.gallery.logic.Section
import dev.kanorin.gallery.logic.TimelineLayout
import dev.kanorin.gallery.thumbs.Thumb
import dev.kanorin.gallery.ui.common.BigBarButton
import dev.kanorin.gallery.ui.common.DialogTextButton
import dev.kanorin.gallery.ui.common.DragSelect
import dev.kanorin.gallery.ui.common.GalleryDialog
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.common.SelectableTile
import dev.kanorin.gallery.ui.common.SelectionState
import dev.kanorin.gallery.ui.common.VideoBadge
import dev.kanorin.gallery.ui.common.rememberTileLabelStyle
import dev.kanorin.gallery.ui.common.selectGestures
import dev.kanorin.gallery.ui.theme.GalleryColors
import dev.kanorin.gallery.ui.viewer.binViewerRoute
import java.util.Locale

/** The ⋮ sheet's "Recycle bin" row opens this (Task 11). */
const val BIN_ROUTE = "bin"
const val BIN_GRID_TAG = "bin-grid"
const val BIN_RESTORE_TAG = "bin-restore"
const val BIN_DELETE_TAG = "bin-delete-forever"
const val EMPTY_BIN_TAG = "bin-empty"
const val BIN_CONFIRM_TAG = "bin-confirm"
fun binTileTag(index: Int) = "bin-tile-$index"

private const val COLUMNS = 3
private val TileGap = 2.dp
private val BarHeight = 56.dp
private val ActionsHeight = 56.dp
private val TitleStyle = TextStyle(color = GalleryColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
private val EmptyStyle = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp)
private val ActionStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

/**
 * Binned items with their days left (spec 5.5). Hold / drag / tap select as in the All view; while selecting,
 * "Restore" and "Delete forever" sit at the bottom. ⋮ holds "Empty bin". Both deletes ask first.
 */
@Composable
fun BinScreen(
    viewModel: BinViewModel,
    onBack: () -> Unit,
    /** A tile's tap while not selecting: the viewer's route for that file alone. */
    onOpen: (String) -> Unit = {},
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val selection = viewModel.selection
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = selection.active) { viewModel.endSelection() }

    Column(
        Modifier
            .fillMaxSize()
            .background(GalleryColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        // One fixed row: the selecting bar takes the title's place, so the grid never moves.
        Row(Modifier.fillMaxWidth().height(BarHeight), verticalAlignment = Alignment.CenterVertically) {
            if (selection.active) {
                BigBarButton(GalleryIcons.Close, "Close", viewModel::endSelection)
                Text(String.format(Locale.ENGLISH, "%,d selected", selection.selected.size), style = TitleStyle, modifier = Modifier.padding(start = 6.dp))
                Spacer(Modifier.weight(1f))
                BigBarButton(GalleryIcons.SelectAll, "Select all", viewModel::toggleAll)
            } else {
                BigBarButton(GalleryIcons.Back, "Back", onBack)
                Text("Recycle bin", style = TitleStyle, modifier = Modifier.padding(start = 6.dp))
                Spacer(Modifier.weight(1f))
                EmptyMenu(enabled = !items.isNullOrEmpty()) { confirmEmpty = true }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = items
            when {
                list == null -> Unit
                list.isEmpty() -> Text("Recycle bin is empty", style = EmptyStyle, modifier = Modifier.padding(16.dp))
                else -> BinGrid(list, viewModel.clock(), selection, if (selection.active) ActionsHeight else 0.dp) { entry ->
                    onOpen(binViewerRoute(entry.binPath, entry.mimeType))
                }
            }
            if (selection.active) {
                BinActions(
                    enabled = selection.selected.isNotEmpty(),
                    onRestore = viewModel::restoreSelected,
                    onDelete = { confirmDelete = true },
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }
    }

    val count = selection.selected.size
    if (confirmDelete && count > 0) {
        BinConfirm(DeleteText.forever(count), "Delete forever", onConfirm = { confirmDelete = false; viewModel.deleteSelected() }, onDismiss = { confirmDelete = false })
    }
    if (confirmEmpty) {
        BinConfirm("Empty the Recycle bin?", "Empty bin", onConfirm = { confirmEmpty = false; viewModel.emptyBin() }, onDismiss = { confirmEmpty = false })
    }
}

@Composable
private fun BinGrid(items: List<BinEntity>, now: Long, selection: SelectionState, bottomClearance: Dp, onOpen: (BinEntity) -> Unit) {
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // The All view's hold / drag / tap, over one headerless section: a flat index is an item index.
    val drag = remember(selection) { DragSelect(selection, scope) }
    DisposableEffect(drag) { onDispose { drag.dispose() } }
    SideEffect {
        drag.layout = TimelineLayout(listOf(Section(0, "", items.size, 0)), headers = false)
        drag.loadedKey = { i -> items.getOrNull(i)?.binPath }
        drag.pathsInOrder = { items.map { it.binPath } }
        drag.onOpen = { i -> items.getOrNull(i)?.let(onOpen) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val gapPx = with(density) { TileGap.roundToPx() }
        val tilePx = (constraints.maxWidth - (COLUMNS - 1) * gapPx + COLUMNS - 1) / COLUMNS
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomPx = with(density) { (bottom + bottomClearance).roundToPx() }
        val label = rememberTileLabelStyle()
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(TileGap),
            verticalArrangement = Arrangement.spacedBy(TileGap),
            contentPadding = PaddingValues(bottom = bottom + bottomClearance),
            modifier = Modifier.fillMaxSize().testTag(BIN_GRID_TAG).selectGestures(state, bottomPx, drag),
        ) {
            items(items.size, key = { items[it].binPath }) { i ->
                val entry = items[i]
                val selected by remember(selection, entry.binPath) { derivedStateOf { entry.binPath in selection.selected } }
                SelectableTile(selection.active, selected, Modifier.testTag(binTileTag(i))) {
                    // deletedAt keys the thumbnail cache: a bin path is never reused.
                    Thumb(entry.binPath, entry.isVideo, entry.deletedAt, tilePx, Modifier.fillMaxSize())
                    Text(
                        BinPolicy.daysLeftLabel(entry.deletedAt, now), style = label, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 3.dp),
                    )
                    if (entry.isVideo) VideoBadge(entry.durationMs, Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 3.dp))
                }
            }
        }
    }
}

/** "Restore" · "Delete forever": the selecting bar's bottom row, and the viewer's bar for a bin item. */
@Composable
internal fun BinActions(enabled: Boolean, onRestore: () -> Unit, onDelete: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(ActionsHeight).background(GalleryColors.Surface)) {
        Action("Restore", GalleryColors.Accent, enabled, onRestore, Modifier.weight(1f).testTag(BIN_RESTORE_TAG))
        Action("Delete forever", GalleryColors.Danger, enabled, onDelete, Modifier.weight(1f).testTag(BIN_DELETE_TAG))
    }
}

@Composable
private fun Action(text: String, color: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.fillMaxHeight().clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = ActionStyle.copy(color = if (enabled) color else GalleryColors.Muted))
    }
}

@Composable
private fun EmptyMenu(enabled: Boolean, onEmpty: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    // The menu shares the button's Box, so it drops from the ⋮.
    Box(Modifier.padding(end = 6.dp)) {
        BigBarButton(GalleryIcons.Dots, "More", onClick = { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = GalleryColors.Surface2) {
            DropdownMenuItem(
                text = { Text("Empty bin") }, enabled = enabled, onClick = { open = false; onEmpty() },
                modifier = Modifier.testTag(EMPTY_BIN_TAG),
            )
        }
    }
}

/** "Delete N items forever?" / "Empty the Recycle bin?": Cancel, or the red confirm. */
@Composable
internal fun BinConfirm(title: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GalleryDialog(title, subline = null, onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.End),
        ) {
            DialogTextButton("Cancel", onDismiss)
            DialogTextButton(confirm, onConfirm, bold = true, modifier = Modifier.testTag(BIN_CONFIRM_TAG), color = GalleryColors.Danger)
        }
    }
}
