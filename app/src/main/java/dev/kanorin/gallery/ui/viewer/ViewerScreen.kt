package dev.kanorin.gallery.ui.viewer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.logic.DeleteText
import dev.kanorin.gallery.logic.DetailsText
import dev.kanorin.gallery.logic.Transfer
import dev.kanorin.gallery.logic.Zoom
import dev.kanorin.gallery.logic.upright
import dev.kanorin.gallery.ui.common.BarButton
import dev.kanorin.gallery.ui.common.DeleteDialogHost
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.common.LocalToasts
import dev.kanorin.gallery.ui.bin.BinActions
import dev.kanorin.gallery.ui.bin.BinConfirm
import dev.kanorin.gallery.ui.common.SystemBars
import dev.kanorin.gallery.ui.common.launch
import dev.kanorin.gallery.ui.common.handOffIntent
import dev.kanorin.gallery.ui.common.mediaUri
import dev.kanorin.gallery.ui.common.shareSelection
import dev.kanorin.gallery.ui.common.shareUriIntent
import dev.kanorin.gallery.ui.player.playerRoute
import dev.kanorin.gallery.ui.theme.GalleryColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

const val VIEWER_PAGER_TAG = "viewer-pager"
const val VIEWER_TOP_BAR_TAG = "viewer-top-bar"
const val VIEWER_ACTIONS_TAG = "viewer-actions"
const val VIEWER_TITLE_TAG = "viewer-title"
const val VIEWER_TIME_TAG = "viewer-time"
const val VIEWER_MENU_TAG = "viewer-menu"
const val ACTION_SHARE_TAG = "viewer-share"
const val ACTION_FAVOURITE_TAG = "viewer-favourite"
const val ACTION_EDIT_TAG = "viewer-edit"
const val ACTION_DELETE_TAG = "viewer-delete"
const val ACTION_MORE_TAG = "viewer-more"
const val MENU_INFO_TAG = "viewer-menu-info"
const val MENU_OPEN_WITH_TAG = "viewer-menu-open-with"
const val MENU_COPY_TAG = "viewer-menu-copy"
const val MENU_MOVE_TAG = "viewer-menu-move"
const val DETAILS_SHEET_TAG = "viewer-details"
const val MISSING_TAG = "viewer-missing"
const val PLAY_TAG = "viewer-play"

fun viewerPageTag(key: String) = "viewer-page-$key"
fun viewerImageTag(key: String) = "viewer-image-$key"

/** The photo's zoom, for tests. */
val ZoomKey = SemanticsPropertyKey<Zoom>("ViewerZoom")

private const val TAG = "ViewerScreen"
private val SwipeDetails = 48.dp
private val PageGap = 12.dp
private val TitleStyle = TextStyle(color = GalleryColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
private val TimeStyle = TextStyle(color = GalleryColors.Text, fontSize = 13.sp)
private val ActionStyle = TextStyle(color = GalleryColors.ActionText, fontSize = 10.5.sp)

private enum class MenuAt { TOP, BOTTOM }

/**
 * The photo viewer (spec 5.7, mockup phone 6): black, full screen, swipe sideways through the grid it came from.
 * Top: back, day + time, ⋮. Bottom: Share · Favourite · Edit · Delete · More. A tap hides / shows the bars and the
 * system bars; a swipe up (or Info) opens the details. Another app's content shows alone, with Share and Edit only.
 */
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    /** A video's play button: the player's route. */
    onNavigate: (String) -> Unit = {},
    /** Copy / Move to folder, after the choice is begun: the host opens the folder picker. */
    onPickFolder: () -> Unit = {},
) {
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    var bars by rememberSaveable { mutableStateOf(true) }
    var details by rememberSaveable { mutableStateOf(false) }
    SystemBars(visible = bars || details)
    BackHandler(enabled = details) { details = false }
    val swipe = with(LocalDensity.current) { SwipeDetails.toPx() }
    val onVertical = { dy: Float ->
        if (dy < -swipe) details = true else if (dy > swipe) details = false
    }
    Box(Modifier.fillMaxSize().background(GalleryColors.ViewerBg)) {
        when (val b = browse) {
            null -> Unit
            is Browse.Grid -> GridViewer(
                viewModel, bars || details, details,
                onToggleBars = { bars = !bars }, onVertical = onVertical, onDetails = { details = it },
                onBack = onBack, onNavigate = onNavigate, onPickFolder = onPickFolder,
            )
            is Browse.Single -> SingleViewer(
                b, bars, onToggleBars = { bars = !bars }, onBack = onBack,
                onRestore = { b.binPath?.let(viewModel::restoreBinned); onBack() },
                onDeleteForever = { b.binPath?.let(viewModel::deleteBinnedForever); onBack() },
            )
        }
    }
}

@Composable
private fun BoxScope.GridViewer(
    vm: ViewerViewModel,
    bars: Boolean,
    details: Boolean,
    onToggleBars: () -> Unit,
    onVertical: (Float) -> Unit,
    onDetails: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onPickFolder: () -> Unit,
) {
    val items = vm.items.collectAsLazyPagingItems()
    val count = items.itemCount
    val refresh = items.loadState.refresh
    // The pager is made once the first page is in: made at 0 pages it would lose its start page.
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(count) { if (count > 0) ready = true }
    // Deleted or moved away until nothing is left: close.
    LaunchedEffect(count, refresh) { if (count == 0 && refresh is LoadState.NotLoading) onBack() }
    if (!ready) return

    val start = remember { vm.page.coerceIn(0, count - 1) }
    val pager = rememberPagerState(initialPage = start) { items.itemCount }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect(vm::setPage) }
    val exists = remember { mutableStateMapOf<String, Boolean>() }
    val current = pager.currentPage.takeIf { it < count }?.let { items.peek(it) }
    var actionsHeight by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            pager,
            key = items.itemKey { it.path },
            pageSpacing = PageGap,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(VIEWER_PAGER_TAG),
        ) { i ->
            val item = items[i]
            if (item != null) {
                Page(item, exists, onToggleBars, onVertical, onPlay = { onNavigate(playerRoute(item.path)) })
            }
        }
        if (details && current != null) {
            Details(vm, current, onClose = { onDetails(false) }, modifier = Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(with(LocalDensity.current) { actionsHeight.toDp() }))
        }
    }
    DeleteDialogHost(vm.actions)
    if (current == null) return
    var menu by remember { mutableStateOf<MenuAt?>(null) }
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val favourite by remember(current.path) { vm.isFavorite(current.path) }.collectAsStateWithLifecycle(false)
    val moreItems: @Composable (MenuAt) -> Unit = { at ->
        MoreMenu(
            expanded = menu == at,
            onDismiss = { menu = null },
            onInfo = { onDetails(true) },
            onOpenWith = {
                launch(context, { toasts.show(it) }, "Couldn't open it") {
                    mediaUri(context, current)?.let { handOffIntent(Intent.ACTION_VIEW, it, current.mimeType) }
                }
            },
            onCopy = { vm.beginTransfer(Transfer.COPY, current.path); onPickFolder() },
            onMove = { vm.beginTransfer(Transfer.MOVE, current.path); onPickFolder() },
        )
    }
    AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
        TopBar(vm.dayLabel(current.dateTaken), DetailsText.time(current.dateTaken, vm.zone()), onBack, onMenu = { menu = MenuAt.TOP }) {
            moreItems(MenuAt.TOP)
        }
    }
    AnimatedVisibility(bars, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
        ActionsBar(
            solid = details,
            modifier = Modifier.onSizeChanged { actionsHeight = it.height },
            onShare = { scope.launch { shareSelection(context, listOf(current.path), vm.actions::rows, { toasts.show(it) }) } },
            favourite = favourite,
            onFavourite = { vm.setFavorite(current.path, it) },
            onEdit = {
                launch(context, { toasts.show(it) }, "Couldn't open the editor") {
                    mediaUri(context, current)?.let { handOffIntent(Intent.ACTION_EDIT, it, current.mimeType, write = true) }
                }
            },
            // Review Focus 3: nothing to delete once the file is gone.
            onDelete = if (exists[current.path] == false) null else { { vm.delete(current.path) } },
            onMore = { menu = MenuAt.BOTTOM },
        ) { moreItems(MenuAt.BOTTOM) }
    }
}

/** One page: the photo, a video's poster, or "File no longer exists". Existence is checked once per path, off Main. */
@Composable
private fun Page(
    item: MediaEntity,
    exists: SnapshotStateMap<String, Boolean>,
    onTap: () -> Unit,
    onVertical: (Float) -> Unit,
    onPlay: () -> Unit,
) {
    LaunchedEffect(item.path) { exists[item.path] = withContext(Dispatchers.IO) { File(item.path).isFile } }
    Box(Modifier.fillMaxSize().testTag(viewerPageTag(item.path))) {
        when {
            exists[item.path] == false -> Missing(onTap, onVertical)
            item.isVideo -> Poster(item, onPlay, onTap, onVertical)
            else -> {
                val (w, h) = upright(item.width, item.height, item.orientation)
                ZoomableImage(File(item.path), w, h, item.mimeType, viewerImageTag(item.path), onTap, onVertical)
            }
        }
    }
}

/** The sheet for [item]: the row's facts at once, EXIF and the place once read (on IO). */
@Composable
private fun Details(vm: ViewerViewModel, item: MediaEntity, onClose: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationAccess(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // Asked the first time details open in this viewer; denied, the location row says so.
    LaunchedEffect(Unit) {
        if (!granted && !vm.locationAsked) {
            vm.markLocationAsked()
            try {
                ask.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            } catch (e: Exception) {
                Log.w(TAG, "couldn't ask for media location", e)
            }
        }
    }
    val geocoder = remember { if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null }
    val details by produceState(PhotoDetails.basic(item), item.path, granted) {
        value = readDetails(item, geocoder, vm.resolver)
    }
    DetailsSheet(details, vm.folderLabel(item.folderPath), item.folderPath, locationHidden = !granted, vm.zone(), onClose, modifier)
}

/** Another app's content (a VIEW intent we can't place in a folder): view, Share and Edit; no file ops. */
@Composable
private fun BoxScope.SingleViewer(
    b: Browse.Single,
    bars: Boolean,
    onToggleBars: () -> Unit,
    onBack: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val key = b.uri.toString()
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val binned = b.binPath
    Box(Modifier.fillMaxSize().testTag(viewerPageTag(key))) {
        if (binned != null && b.mimeType?.startsWith("video/") == true) {
            // A bin item is read-only: a video shows its first frame, never plays.
            PosterFrame(binned, null, b.binModified, onPlay = null, onTap = onToggleBars, onVertical = {})
        } else {
            ZoomableImage(binned?.let(::File) ?: b.uri, 0, 0, b.mimeType, viewerImageTag(key), onTap = onToggleBars, onVertical = {})
        }
    }
    AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
        TopBar(title = "", time = "", onBack = onBack, onMenu = null) {}
    }
    if (binned != null) {
        var confirm by rememberSaveable { mutableStateOf(false) }
        AnimatedVisibility(bars, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            BinActions(true, onRestore, onDelete = { confirm = true }, Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (confirm) {
            BinConfirm(DeleteText.forever(1), "Delete forever", onConfirm = { confirm = false; onDeleteForever() }, onDismiss = { confirm = false })
        }
        return
    }
    AnimatedVisibility(bars, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
        ActionsBar(
            solid = false,
            onShare = { launch(context, { toasts.show(it) }, "Couldn't open sharing") { shareUriIntent(b.uri, b.mimeType) } },
            favourite = null,
            onFavourite = {},
            // Not ours to write: the editor gets read access only.
            onEdit = { launch(context, { toasts.show(it) }, "Couldn't open the editor") { handOffIntent(Intent.ACTION_EDIT, b.uri, b.mimeType) } },
            onDelete = null,
            onMore = null,
        ) {}
    }
}

@Composable
private fun TopBar(title: String, time: String, onBack: () -> Unit, onMenu: (() -> Unit)?, menu: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(GalleryColors.ViewerScrim, Color.Transparent)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .testTag(VIEWER_TOP_BAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(GalleryIcons.Back, "Back", onBack)
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(title, style = TitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(VIEWER_TITLE_TAG))
            Text(time, style = TimeStyle, maxLines = 1, modifier = Modifier.testTag(VIEWER_TIME_TAG))
        }
        if (onMenu != null) {
            Box {
                BarButton(GalleryIcons.Dots, "More options", onMenu, Modifier.testTag(VIEWER_MENU_TAG))
                menu()
            }
        }
    }
}

/** Five fixed slots, so nothing moves when one is missing (spec 4: controls never jump). */
@Composable
private fun ActionsBar(
    solid: Boolean,
    onShare: () -> Unit,
    /** null = no favourite here (another app's content). */
    favourite: Boolean?,
    onFavourite: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().background(if (solid) GalleryColors.Surface else GalleryColors.ViewerScrim).testTag(VIEWER_ACTIONS_TAG)) {
        if (solid) HorizontalDivider(color = GalleryColors.RowDivider)
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(top = 10.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            Slot { Action(GalleryIcons.Share, "Share", ACTION_SHARE_TAG, Modifier.clickable(role = Role.Button, onClick = onShare)) }
            Slot {
                if (favourite != null) {
                    Action(
                        if (favourite) GalleryIcons.Heart else GalleryIcons.HeartOutline, "Favourite", ACTION_FAVOURITE_TAG,
                        Modifier.toggleable(favourite, role = Role.Checkbox, onValueChange = onFavourite),
                        tint = if (favourite) GalleryColors.Heart else GalleryColors.ActionText,
                    )
                }
            }
            Slot { Action(GalleryIcons.Edit, "Edit", ACTION_EDIT_TAG, Modifier.clickable(role = Role.Button, onClick = onEdit)) }
            Slot { if (onDelete != null) Action(GalleryIcons.Trash, "Delete", ACTION_DELETE_TAG, Modifier.clickable(role = Role.Button, onClick = onDelete)) }
            Slot {
                if (onMore != null) {
                    Box {
                        Action(GalleryIcons.Dots, "More", ACTION_MORE_TAG, Modifier.clickable(role = Role.Button, onClick = onMore))
                        menu()
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Slot(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Action(icon: ImageVector, label: String, tag: String, click: Modifier, tint: Color = GalleryColors.ActionText) {
    Column(
        click.padding(horizontal = 8.dp, vertical = 2.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = ActionStyle, maxLines = 1)
    }
}

/** The viewer's ⋮ / More (spec 9: the builder's): Info, Open with, Copy to folder, Move to folder. */
@Composable
private fun MoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onInfo: () -> Unit,
    onOpenWith: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
) {
    DropdownMenu(expanded, onDismissRequest = onDismiss, containerColor = GalleryColors.Surface2) {
        listOf(
            Triple("Info", MENU_INFO_TAG, onInfo),
            Triple("Open with", MENU_OPEN_WITH_TAG, onOpenWith),
            Triple("Copy to folder", MENU_COPY_TAG, onCopy),
            Triple("Move to folder", MENU_MOVE_TAG, onMove),
        ).forEach { (label, tag, action) ->
            DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); action() }, modifier = Modifier.testTag(tag))
        }
    }
}

private fun hasLocationAccess(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
