package dev.kanorin.gallery.ui.folders

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.logic.FolderCard
import dev.kanorin.gallery.logic.PinchStepper
import dev.kanorin.gallery.thumbs.Thumb
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.common.ZoomPill
import dev.kanorin.gallery.ui.common.pinchColumns
import dev.kanorin.gallery.ui.common.requestAnchorAt
import dev.kanorin.gallery.ui.theme.GalleryColors
import java.util.Locale

const val FOLDERS_GRID_TAG = "folders-grid"
const val NEW_FOLDER_TAG = "new-folder-card"
fun folderCardTag(path: String) = "folder-card-$path"
fun folderCountTag(path: String) = "folder-count-$path"
fun folderCoverTag(path: String) = "folder-cover-$path"

private val CoverShape = RoundedCornerShape(14.dp)
private val GridPadding = 10.dp
private val ColumnGap = 8.dp
private val RowGap = 14.dp
private val PillLift = 22.dp
private val SectionStyle = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp, letterSpacing = 0.6.sp)
private val NameStyle = TextStyle(color = GalleryColors.Text, fontSize = 12.5.sp)
private val CountStyle = TextStyle(color = GalleryColors.Muted, fontSize = 11.sp)
private const val SECTION = 0
private const val CARD = 1

/**
 * Pinned + "All folders" cards, then "+ New folder" last (spec 5.3). Pinch changes the column count within
 * [FOLDER_COLUMN_RANGE] and reports it to [onColumns]. Tap = [onOpen]; long-press = the actions menu.
 * [onRename] is offered only where [canRename] allows it ([dev.kanorin.gallery.fileops.FileOps.canRename]). [actions] false = no menu (the folder picker).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoldersGrid(
    model: FoldersModel,
    columns: Int,
    canRename: (String) -> Boolean,
    onColumns: (Int) -> Unit,
    onOpen: (FolderCard) -> Unit,
    onNewFolder: () -> Unit,
    onTogglePin: (FolderCard) -> Unit,
    onHide: (FolderCard) -> Unit,
    onRename: (FolderCard) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    actions: Boolean = true,
) {
    // Drawn count. A pinch sets it in the same frame as its anchor; [columns] catches up once saved.
    var cols by remember { mutableIntStateOf(columns) }
    // The pinch's latest ask until [columns] reports it back; older asks echoing meanwhile are ignored.
    var asked by remember { mutableStateOf<Int?>(null) }
    val stepper = remember { PinchStepper(FOLDER_COLUMN_RANGE.first, FOLDER_COLUMN_RANGE.last, cols) }
    val arranged = model.arranged

    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val density = LocalDensity.current
        val coverPx = remember(width, cols, density) {
            with(density) { ((width - 2 * GridPadding.toPx() - (cols - 1) * ColumnGap.toPx()) / cols).toInt().coerceAtLeast(1) }
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        SideEffect { if (asked == columns) asked = null }
        LaunchedEffect(columns) {
            if (asked == null && columns != cols) {
                state.requestAnchorAt(Offset(width / 2f, height / 3f))
                stepper.columns = columns
                cols = columns
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(ColumnGap),
            verticalArrangement = Arrangement.spacedBy(RowGap),
            contentPadding = PaddingValues(start = GridPadding, end = GridPadding, top = 0.dp, bottom = bottom + GridPadding),
            modifier = Modifier
                .fillMaxSize()
                .testTag(FOLDERS_GRID_TAG)
                .pinchColumns(stepper) { n, focus ->
                    state.requestAnchorAt(focus)
                    cols = n
                    asked = n
                    onColumns(n)
                },
        ) {
            if (arranged.pinned.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = SECTION) { SectionHead("Pinned") }
                items(arranged.pinned, key = { it.path }, contentType = { CARD }) { card ->
                    FolderTile(card, model.covers, coverPx, canRename, actions, onOpen, onTogglePin, onHide, onRename)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, contentType = SECTION) {
                SectionHead("All folders · ${arranged.others.size}")
            }
            items(arranged.others, key = { it.path }, contentType = { CARD }) { card ->
                FolderTile(card, model.covers, coverPx, canRename, actions, onOpen, onTogglePin, onHide, onRename)
            }
            item(key = NEW_FOLDER_TAG, contentType = CARD) { NewFolderTile(onNewFolder) }
        }
        ZoomPill(
            FOLDER_COLUMN_RANGE.first, FOLDER_COLUMN_RANGE.last, cols, visible = true, onPick = null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + PillLift),
        )
    }
}

@Composable
private fun SectionHead(text: String) {
    // The grid's own 10 dp plus 2 dp = the mockup's 12 dp section padding.
    Text(text, style = SectionStyle, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 12.dp, bottom = 0.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    card: FolderCard,
    covers: Map<String, MediaEntity>,
    coverPx: Int,
    canRename: (String) -> Boolean,
    actions: Boolean,
    onOpen: (FolderCard) -> Unit,
    onTogglePin: (FolderCard) -> Unit,
    onHide: (FolderCard) -> Unit,
    onRename: (FolderCard) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // Favourites is a virtual folder: nothing to pin, hide or rename.
    val hasMenu = actions && !card.isFavourites
    // The menu sits in the same Box as the card, so it anchors to the whole card.
    Box(
        Modifier
            .fillMaxWidth()
            .testTag(folderCardTag(card.path))
            .combinedClickable(onClick = { onOpen(card) }, onLongClick = if (hasMenu) ({ menu = true }) else null),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CoverShape).testTag(folderCoverTag(card.path))) {
                val cover = card.coverPath?.let(covers::get)
                if (cover != null) {
                    Thumb(cover, coverPx, Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(GalleryColors.Surface2), contentAlignment = Alignment.Center) {
                        if (card.count == 0) Icon(GalleryIcons.Folder, null, tint = GalleryColors.EmptyGlyph, modifier = Modifier.size(30.dp))
                    }
                }
                if (card.isFavourites) HeartBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            Text(card.name, style = NameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Text(
                if (card.count == 0) "Empty" else String.format(Locale.ENGLISH, "%,d", card.count),
                style = CountStyle, maxLines = 1, modifier = Modifier.testTag(folderCountTag(card.path)),
            )
            card.subtitle?.let { Text(it, style = CountStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (hasMenu) {
            FolderActionsMenu(
                expanded = menu,
                pinned = card.pinned,
                hidden = card.hidden,
                canRename = canRename(card.path),
                onDismiss = { menu = false },
                onTogglePin = { menu = false; onTogglePin(card) },
                onHide = { menu = false; onHide(card) },
                onRename = { menu = false; onRename(card) },
            )
        }
    }
}

@Composable
private fun HeartBadge(modifier: Modifier) {
    Box(modifier.size(24.dp).background(GalleryColors.BadgeScrim, CircleShape), contentAlignment = Alignment.Center) {
        Icon(GalleryIcons.Heart, contentDescription = null, tint = GalleryColors.Heart, modifier = Modifier.size(14.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewFolderTile(onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(NEW_FOLDER_TAG).combinedClickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CoverShape)
                .drawBehind {
                    val stroke = 1.5.dp.toPx()
                    drawRoundRect(
                        GalleryColors.DashedBorder,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(14.dp.toPx() - stroke / 2),
                        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(GalleryIcons.Plus, contentDescription = null, tint = GalleryColors.Accent, modifier = Modifier.size(28.dp))
        }
        Text("New folder", style = NameStyle.copy(color = GalleryColors.Accent), maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        Text(" ", style = CountStyle, maxLines = 1)
    }
}
