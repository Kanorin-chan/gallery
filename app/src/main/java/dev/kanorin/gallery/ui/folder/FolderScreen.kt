package dev.kanorin.gallery.ui.folder

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import dev.kanorin.gallery.logic.FolderText
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.TimelineLayout
import dev.kanorin.gallery.ui.common.BarButton
import dev.kanorin.gallery.ui.common.DeleteDialogHost
import dev.kanorin.gallery.ui.common.EmptyText
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.common.RenameFolderDialog
import dev.kanorin.gallery.ui.common.RefreshWhenStale
import dev.kanorin.gallery.ui.common.SelectingBar
import dev.kanorin.gallery.ui.common.TopBarHeight
import dev.kanorin.gallery.ui.common.lastNonNull
import dev.kanorin.gallery.ui.theme.GalleryColors
import dev.kanorin.gallery.ui.timeline.TimelineGrid
import dev.kanorin.gallery.ui.viewer.tileRoute
import kotlinx.coroutines.delay
import java.io.File

const val FOLDER_TITLE_TAG = "folder-title"
const val FOLDER_SUBTITLE_TAG = "folder-subtitle"
const val FOLDER_SEARCH_TAG = "folder-search"
const val FOLDER_SEARCH_FIELD_TAG = "folder-search-field"
const val FOLDER_MENU_TAG = "folder-menu"
const val FOLDER_EMPTY_TAG = "folder-empty"
const val CHIP_SORT_TAG = "folder-chip-sort"
const val CHIP_SORT_DIRECTION_TAG = "folder-chip-sort-direction"
const val CHIP_TYPE_TAG = "folder-chip-type"
const val CHIP_FAVOURITES_TAG = "folder-chip-favourites"
const val MENU_GRID_SIZE_TAG = "folder-menu-grid-size"
const val MENU_RENAME_TAG = "folder-screen-menu-rename"
const val MENU_HIDE_TAG = "folder-screen-menu-hide"

/** Room under the last row for the always-shown grid pill: its lift (22 dp) plus its height. */
private val PillClearance = 72.dp
private const val PILL_HINT_MS = 1_800L
private val TitleStyle = TextStyle(color = GalleryColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
private val SubtitleStyle = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp)
private val ChipStyle = TextStyle(fontSize = 12.sp)
private val QueryStyle = TextStyle(color = GalleryColors.Text, fontSize = 16.sp)

/**
 * Inside a folder (spec 5.6): app bar (name, "N items · size", search, ⋮), sort / type / favourites chips,
 * the grid without date headers (ruling AF) and its always-shown, tappable size pill. A tile tap opens the viewer (or the player).
 */
@Composable
fun FolderScreen(
    viewModel: FolderViewModel,
    onBack: () -> Unit,
    /** The bar's Move / Copy to folder, after the choice is begun: the host opens the folder picker. */
    onPickFolder: () -> Unit = {},
    /** A tile tap's route (the viewer, or the player for a video); the host navigates. */
    onNavigate: (String) -> Unit = {},
) {
    val actions = viewModel.actions
    val selection = actions.selection
    val path by viewModel.path.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val type by viewModel.type.collectAsStateWithLifecycle()
    val favouritesOnly by viewModel.favouritesOnly.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val firstSync by viewModel.firstSync.collectAsStateWithLifecycle()
    val canRename by viewModel.canRename.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    // Drawn: the last answered sections (a chip or search change never blanks the grid); [answered] is the current one.
    val drawn = lastNonNull(sections, emptyList())
    val layout = remember(drawn) { TimelineLayout(drawn, headers = false) }
    val answered = sections?.sumOf { it.count }
    val gridState = rememberLazyGridState()
    RefreshWhenStale(answered, items)

    var searching by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var pillHint by remember { mutableStateOf(false) }
    LaunchedEffect(pillHint) {
        if (pillHint) {
            delay(PILL_HINT_MS)
            pillHint = false
        }
    }
    val closeSearch = { searching = false; viewModel.setQuery("") }

    BackHandler(enabled = selection.active) { actions.endSelection() }
    BackHandler(enabled = !selection.active && searching) { closeSearch() }

    Column(
        Modifier
            .fillMaxSize()
            .background(GalleryColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        // One fixed slot: the selection bar replaces the app bar and nothing below moves.
        Box(Modifier.fillMaxWidth().height(TopBarHeight)) {
            if (selection.active) {
                SelectingBar(actions, onPickFolder)
            } else {
                AppBar(
                    title = title,
                    subtitle = stats?.let { FolderText.subtitle(it.count, Formatter.formatShortFileSize(LocalContext.current, it.totalSize)) },
                    searching = searching,
                    query = query,
                    onQuery = viewModel::setQuery,
                    onBack = onBack,
                    onSearch = { searching = true },
                    onCloseSearch = closeSearch,
                    onMenu = { menu = true },
                ) {
                    DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = GalleryColors.Surface2) {
                        DropdownMenuItem(
                            text = { Text("Grid size") },
                            onClick = { menu = false; pillHint = true },
                            modifier = Modifier.testTag(MENU_GRID_SIZE_TAG),
                        )
                        if (canRename) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = { menu = false; renaming = true },
                                modifier = Modifier.testTag(MENU_RENAME_TAG),
                            )
                        }
                        if (!viewModel.isFavourites) {
                            DropdownMenuItem(
                                text = { Text(if (stats?.hidden == true) "Unhide folder" else "Hide folder") },
                                onClick = { menu = false; viewModel.toggleHidden() },
                                modifier = Modifier.testTag(MENU_HIDE_TAG),
                            )
                        }
                    }
                }
            }
        }
        Chips(
            sort,
            type.label,
            typeOn = type != TypeChip.ALL,
            favouritesOn = favouritesOnly,
            showFavourites = !viewModel.isFavourites,
            onSort = viewModel::cycleSort,
            onFlip = viewModel::flipOrder,
            onType = viewModel::cycleType,
            onFavourites = { viewModel.setFavouritesOnly(!favouritesOnly) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            columns?.let { cols ->
                TimelineGrid(
                    layout, items, cols,
                    state = gridState,
                    onColumns = viewModel::setColumns,
                    columnRange = INSIDE_COLUMN_RANGE,
                    selection = selection,
                    pathsInOrder = actions.pathsInOrder,
                    bottomClearance = PillClearance,
                    tappablePill = true,
                    pillHighlight = pillHint,
                    onOpen = { i, item -> viewModel.filter.value?.let { onNavigate(tileRoute(item, it, viewModel.path.value, i)) } },
                )
            }
            val count = stats?.count
            // The All view's rule: "empty" waits for the first pass, no results never wait.
            if (count == 0) {
                if (!firstSync) EmptyText("This folder is empty", FOLDER_EMPTY_TAG)
            } else if (count != null && answered == 0) {
                EmptyText("No matching items", FOLDER_EMPTY_TAG)
            }
        }
    }

    DeleteDialogHost(actions)
    if (renaming) {
        RenameFolderDialog(path, { viewModel.renameSiblings() }, onRename = viewModel::rename, onDismiss = { renaming = false })
    }
}

/** `← name · "N items · size" · search · ⋮`, or the search field in place of the name (mockup phone 3). */
@Composable
private fun AppBar(
    title: String,
    subtitle: String?,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onMenu: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(TopBarHeight).padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BarButton(GalleryIcons.Back, "Back", if (searching) onCloseSearch else onBack)
        if (searching) {
            SearchField(query, onQuery, Modifier.weight(1f))
            BarButton(GalleryIcons.Close, "Close search", onCloseSearch)
        } else {
            Column(Modifier.weight(1f)) {
                Text(title, style = TitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(FOLDER_TITLE_TAG))
                Text(subtitle.orEmpty(), style = SubtitleStyle, maxLines = 1, modifier = Modifier.testTag(FOLDER_SUBTITLE_TAG))
            }
            BarButton(GalleryIcons.Search, "Search", onSearch, Modifier.testTag(FOLDER_SEARCH_TAG))
            // The menu shares the button's Box, so it drops from the ⋮.
            Box {
                BarButton(GalleryIcons.Dots, "More", onMenu, Modifier.testTag(FOLDER_MENU_TAG))
                menu()
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = QueryStyle,
        cursorBrush = SolidColor(GalleryColors.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Live while typing, so the keyboard's search key just gets it out of the way.
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier.focusRequester(focus).testTag(FOLDER_SEARCH_FIELD_TAG),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search this folder", style = QueryStyle.copy(color = GalleryColors.Muted), maxLines = 1)
                field()
            }
        },
    )
}

/** Sort (tap = next field, hold = flip the order), type (tap = next), Favourites (toggle). Mockup .chips. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chips(
    sort: SortChoice?,
    type: String,
    typeOn: Boolean,
    favouritesOn: Boolean,
    showFavourites: Boolean,
    onSort: () -> Unit,
    onFlip: () -> Unit,
    onType: () -> Unit,
    onFavourites: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // The sort chip is always lit: a folder always has an order.
        if (sort != null) SortChip(sort, onSort, onFlip)
        Chip(type, on = typeOn, tag = CHIP_TYPE_TAG, modifier = Modifier.clickable(role = Role.Button, onClick = onType))
        if (showFavourites) {
            Chip(
                "Favourites", on = favouritesOn, tag = CHIP_FAVOURITES_TAG,
                modifier = Modifier.toggleable(favouritesOn, role = Role.Checkbox, onValueChange = { onFavourites() }),
            )
        }
    }
}

/** Two targets: the label (next field) and the arrow (flip the order). Holding the label also flips, as a shortcut. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SortChip(sort: SortChoice, onSort: () -> Unit, onFlip: () -> Unit) {
    val style = ChipStyle.copy(color = GalleryColors.Accent)
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(GalleryColors.ChipOn), verticalAlignment = Alignment.CenterVertically) {
        Text(
            FolderText.sortLabel(sort.field), style = style, maxLines = 1,
            modifier = Modifier
                .combinedClickable(role = Role.Button, onLongClickLabel = "Reverse the order", onClick = onSort, onLongClick = onFlip)
                .padding(start = 11.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                .testTag(CHIP_SORT_TAG),
        )
        val direction = FolderText.sortDirection(sort.field, sort.descending)
        Text(
            FolderText.sortArrow(sort.field, sort.descending), style = style, maxLines = 1,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onFlip)
                .semantics { contentDescription = direction }
                .padding(start = 6.dp, end = 11.dp, top = 6.dp, bottom = 6.dp)
                .testTag(CHIP_SORT_DIRECTION_TAG),
        )
    }
}

@Composable
private fun Chip(label: String, on: Boolean, tag: String, modifier: Modifier) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) GalleryColors.ChipOn else GalleryColors.Surface2)
            .then(modifier)
            .padding(horizontal = 11.dp, vertical = 6.dp)
            .testTag(tag),
    ) {
        Text(label, style = ChipStyle.copy(color = if (on) GalleryColors.Accent else GalleryColors.ChipText), maxLines = 1)
    }
}
