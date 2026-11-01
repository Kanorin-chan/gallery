package dev.kanorin.gallery.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import dev.kanorin.gallery.logic.FolderOrdering
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.TimelineLayout
import dev.kanorin.gallery.ui.bin.BIN_ROUTE
import dev.kanorin.gallery.ui.common.DeleteDialogHost
import dev.kanorin.gallery.ui.common.EmptyText
import dev.kanorin.gallery.ui.common.SelectingBar
import dev.kanorin.gallery.ui.common.MainMode
import dev.kanorin.gallery.ui.common.NewFolderDialog
import dev.kanorin.gallery.ui.common.RenameFolderDialog
import dev.kanorin.gallery.ui.common.RefreshWhenStale
import dev.kanorin.gallery.ui.common.SearchPill
import dev.kanorin.gallery.ui.common.TopBarHeight
import dev.kanorin.gallery.ui.common.lastNonNull
import dev.kanorin.gallery.ui.folders.FoldersGrid
import dev.kanorin.gallery.ui.folders.HIDDEN_FOLDERS_ROUTE
import dev.kanorin.gallery.ui.folders.folderRoute
import dev.kanorin.gallery.ui.settings.SETTINGS_ROUTE
import dev.kanorin.gallery.ui.theme.GalleryColors
import dev.kanorin.gallery.ui.timeline.TimelineGrid
import dev.kanorin.gallery.ui.viewer.tileRoute
import java.io.File

const val MAIN_EMPTY_TAG = "main-empty"

/** Search pill (or the selection bar in its slot) over the All timeline or the folders grid (spec 5.1, 5.2, 5.3). */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    /** Route to open (a folder card, a tile: the viewer or the player); the host navigates. */
    onNavigate: (String) -> Unit = {},
    /** The bar's Move / Copy to folder, after [MainViewModel.beginTransfer]: the host opens the folder picker. */
    onPickFolder: () -> Unit = {},
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val folderColumns by viewModel.folderColumns.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val addTarget by viewModel.addTarget.collectAsStateWithLifecycle()
    val firstSync by viewModel.firstSync.collectAsStateWithLifecycle()
    val foldersState = rememberLazyGridState()
    // Held here, not in the grid, so a trip to Folders keeps the scroll position and loaded pages.
    val items = viewModel.items.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    val headers = filter.effectiveGroupBy != GroupBy.NONE
    // Drawn: the last answered sections, so a filter change never blanks the grid while the new ones load.
    val drawn = lastNonNull(sections, emptyList())
    val layout = remember(drawn, headers) { TimelineLayout(drawn, headers) }
    // The current filter's item count; null until Room answers it. Only the safety net and empty state read it.
    val answered = sections?.sumOf { it.count }
    // Safety net if a page ever misses a write: headers keep updating, so their total exposes a stale count.
    RefreshWhenStale(answered, items)
    val selection = viewModel.selection
    // Saveable: a rotation keeps an open dialog (and its typed name).
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = selection.active) { viewModel.endSelection() }

    Column(
        Modifier
            .fillMaxSize()
            .background(GalleryColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        // One fixed slot: the bar replaces the pill and the grid below never moves.
        Box(Modifier.fillMaxWidth().height(TopBarHeight)) {
            if (selection.active) {
                SelectingBar(viewModel.actions, onPickFolder)
            } else {
                SearchPill(
                    mode = mode,
                    query = query,
                    onQuery = viewModel::setQuery,
                    onToggleMode = viewModel::toggleMode,
                    onMenu = { menu = true },
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                MainMode.ALL -> {
                    columns?.let {
                        TimelineGrid(
                            layout, items, it, state = gridState, onColumns = viewModel::setColumns,
                            selection = selection, onToggleSection = viewModel::toggleSection, pathsInOrder = viewModel::pathsInOrder,
                            bottomClearance = if (addTarget != null) AddButtonClearance else 0.dp,
                            onOpen = { i, item -> onNavigate(tileRoute(item, viewModel.filter.value, null, i)) },
                        )
                    }
                    // Once Room has answered for this filter, an empty grid says why; "No photos yet" waits for the first pass.
                    if (answered == 0) {
                        if (filter.narrowed) EmptyText("No matching items", MAIN_EMPTY_TAG)
                        else if (!firstSync) EmptyText("No photos yet", MAIN_EMPTY_TAG)
                    }
                    addTarget?.let { target ->
                        AddingButton(
                            viewModel, File(target).name, onClick = { choosing = true },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(bottom = AddButtonLift),
                        )
                    }
                }
                MainMode.FOLDERS -> {
                    val model = folders
                    val shown = remember(model, query) {
                        model?.let { it.copy(arranged = FolderOrdering.filter(it.arranged, query)) }
                    }
                    val cols = folderColumns
                    val canRename by viewModel.renameCheck.collectAsStateWithLifecycle()
                    if (shown != null && cols != null) {
                        FoldersGrid(
                            shown, cols, canRename,
                            onColumns = viewModel::setFolderColumns,
                            onOpen = { onNavigate(folderRoute(it)) },
                            onNewFolder = { newFolder = true },
                            onTogglePin = viewModel::togglePin,
                            onHide = viewModel::toggleFolderHidden,
                            onRename = { renaming = it.path },
                            state = foldersState,
                        )
                    }
                }
            }
        }
    }

    if (menu) {
        val binCount by viewModel.binCount.collectAsStateWithLifecycle()
        val hiddenCount by viewModel.hiddenFolderCount.collectAsStateWithLifecycle()
        // The sheet closes as the screen is left, so coming back finds the main screen, not the sheet.
        val go = { route: String -> menu = false; onNavigate(route) }
        MenuSheet(
            filter = filter, binCount = binCount, hiddenCount = hiddenCount,
            onSort = viewModel::setSort, onFlipOrder = viewModel::flipOrder, onChip = viewModel::setChip, onGroup = viewModel::setGroupBy,
            onBin = { go(BIN_ROUTE) }, onHiddenFolders = { go(HIDDEN_FOLDERS_ROUTE) }, onSettings = { go(SETTINGS_ROUTE) },
            onDismiss = { menu = false },
        )
    }
    if (newFolder) {
        val siblings by produceState<Set<String>?>(null) { value = viewModel.newFolderSiblings() }
        NewFolderDialog(siblings, onConfirm = viewModel::createFolder, onDismiss = { newFolder = false })
    }
    renaming?.let { path ->
        RenameFolderDialog(path, viewModel::renameSiblings, onRename = { viewModel.renameFolder(path, it) }, onDismiss = { renaming = null })
    }
    DeleteDialogHost(viewModel.actions)
    val target = addTarget
    if (choosing && target != null) {
        MoveOrCopyDialog(
            File(target).name,
            onPick = { kind -> choosing = false; viewModel.transfer(kind, target) },
            onDismiss = { choosing = false },
        )
    }
}

/** Its own scope, like [SelectingBar]: only the button recomposes as the selection changes. */
@Composable
private fun AddingButton(viewModel: MainViewModel, name: String, onClick: () -> Unit, modifier: Modifier) {
    AddToFolderButton(name, enabled = viewModel.selection.selected.isNotEmpty(), onClick = onClick, modifier = modifier)
}
