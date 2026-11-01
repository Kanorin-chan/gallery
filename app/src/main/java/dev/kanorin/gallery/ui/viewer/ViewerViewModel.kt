package dev.kanorin.gallery.ui.viewer

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.kanorin.gallery.GalleryApp
import dev.kanorin.gallery.data.FavoriteEntity
import dev.kanorin.gallery.data.MediaDao
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.data.MediaQuery
import dev.kanorin.gallery.data.PathDao
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.logic.FolderText
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.TimelineGrouping
import dev.kanorin.gallery.logic.Transfer
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.bin.deleteFromBinForever
import dev.kanorin.gallery.ui.bin.restoreFromBin
import dev.kanorin.gallery.ui.common.SelectionActions
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.common.indexIfNew
import dev.kanorin.gallery.ui.common.launchGuarded
import dev.kanorin.gallery.ui.common.resolveViewPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/** What the viewer pages over: a grid's query from [start], or one item it can't place in a folder. */
sealed interface Browse {
    data class Grid(val filter: MediaFilter, val folder: String?, val start: Int) : Browse

    /** Another app's content: view, Share and Edit only (no file ops). A [binPath] item: Restore and Delete forever only. */
    data class Single(val uri: Uri, val mimeType: String?, val binPath: String? = null, val binModified: Long = 0L) : Browse
}

/**
 * The photo viewer's state (spec 5.7). It pages over the same [MediaQuery] as the grid that opened it, so swiping
 * follows the grid's order. A VIEW intent's file pages within its folder, in that folder's saved order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModel(
    val args: ViewerArgs,
    private val dao: MediaDao,
    private val pathDao: PathDao,
    settings: Settings,
    private val saved: SavedStateHandle,
    ops: FileOps,
    private val bin: RecycleBin,
    /** Mounted storage roots: a VIEW intent's file must lie under one. */
    private val roots: () -> List<File>,
    /** Reads MediaStore originals (details) and resolves VIEW URIs; null in tests that need neither. */
    val resolver: ContentResolver?,
    private val toasts: ToastHostState = ToastHostState(),
    opScope: CoroutineScope? = null,
    val storageRoot: String = StorageLayout.primaryRoot().path,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val opScope: CoroutineScope = opScope ?: viewModelScope

    private val browsing = MutableStateFlow<Browse?>(null)

    /** null while a VIEW intent's URI is being resolved. */
    val browse: StateFlow<Browse?> = browsing.asStateFlow()

    /** The page last settled on; saved, so a rotation or process death comes back to it. */
    val page: Int get() = saved.get<Int>(KEY_PAGE) ?: (browsing.value as? Browse.Grid)?.start ?: args.index

    val items: Flow<PagingData<MediaEntity>> = browsing.filterIsInstance<Browse.Grid>().flatMapLatest { g ->
        Pager(MediaQuery.PAGING, initialKey = g.start) { dao.pagingItems(g.filter, g.folder) }.flow
    }.cachedIn(viewModelScope)

    /** Delete (dialog, remembered choice, UNDO) and Move / Copy for the photo on screen: the grids' own code. */
    val actions = SelectionActions(
        dao, settings, saved, ops, bin, toasts, viewModelScope, this.opScope,
        pathsInOrder = { emptyList() },
        newFolderParent = File(storageRoot, Environment.DIRECTORY_PICTURES),
    )

    init {
        val uri = args.uri
        val path = args.path
        val savedPage = saved.get<Int>(KEY_PAGE)
        val binned = args.binPath
        when {
            // Its time keys the poster's cache: read once, off Main.
            binned != null -> viewModelScope.launch {
                val modified = withContext(Dispatchers.IO) { File(binned).lastModified() }
                browsing.value = Browse.Single(Uri.fromFile(File(binned)), args.type, binned, modified)
            }
            uri != null -> viewModelScope.launch { browsing.value = open(uri) }
            savedPage != null || path == null -> browsing.value = Browse.Grid(args.filter, args.folder, savedPage ?: args.index)
            // The grid's index is a snapshot: rows may have come or gone above it since the tap.
            else -> viewModelScope.launch { browsing.value = Browse.Grid(args.filter, args.folder, placeOf(path) ?: args.index) }
        }
    }

    fun setPage(page: Int) {
        saved[KEY_PAGE] = page
    }

    /** ACCESS_MEDIA_LOCATION is asked once per viewer, a rotation or process death included. */
    val locationAsked: Boolean get() = saved.get<Boolean>(KEY_ASKED) == true

    fun markLocationAsked() {
        saved[KEY_ASKED] = true
    }

    private suspend fun placeOf(path: String): Int? = try {
        dao.indexOf(args.filter, args.folder, path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "couldn't find the tapped photo; using its index", e)
        null
    }

    fun isFavorite(path: String): Flow<Boolean> = dao.isFavorite(path)

    /** On the app's scope: leaving right after the tap still saves it. */
    fun setFavorite(path: String, on: Boolean) {
        toasts.launchGuarded(opScope, if (on) "Couldn't add to Favourites" else "Couldn't remove from Favourites") {
            if (on) dao.addFavorite(FavoriteEntity(path, clock())) else pathDao.deleteFavorite(path)
        }
    }

    fun delete(path: String) = actions.askDelete(listOf(path))

    /** A bin item's Restore, on the app's scope (the viewer closes as it starts): the bin screen's toasts. */
    fun restoreBinned(binPath: String) {
        opScope.launch { toasts.restoreFromBin(bin, listOf(binPath)) }
    }

    /** A bin item's confirmed Delete forever, as the bin screen does it. */
    fun deleteBinnedForever(binPath: String) {
        opScope.launch { toasts.deleteFromBinForever(bin, listOf(binPath)) }
    }

    /** Copy to folder / Move to folder: the host opens the picker next. */
    fun beginTransfer(kind: Transfer, path: String) = actions.beginTransfer(kind, listOf(path))

    /** The top bar's first line: "Today", "Yesterday", "Wed, 28 Oct", as the grid's day headers say it. */
    fun dayLabel(ms: Long): String {
        val z = zoneId()
        return TimelineGrouping.label(TimelineGrouping.groupKey(ms, GroupBy.DAY, z), GroupBy.DAY, clock(), z)
    }

    /** The zone the top bar and the details sheet show times in. */
    fun zone(): ZoneId = zoneId()

    /** The details sheet's folder line: the folder below its storage root ("DCIM/Camera"). */
    fun folderLabel(folder: String): String {
        val root = StorageLayout.rootOf(folder, roots()) ?: return folder
        return folder.removePrefix(root.path).trimStart(File.separatorChar).replace(File.separatorChar, '/').ifEmpty { folder }
    }

    // A VIEW intent: its file pages within its folder (in that folder's saved order); anything else is a single item.
    private suspend fun open(uri: Uri): Browse {
        val type = args.type ?: resolver?.let { r -> runCatching { r.getType(uri) }.getOrNull() }
        val path = resolver?.let { r -> withContext(Dispatchers.IO) { resolveViewPath(uri, r, roots()) } }
            ?: return Browse.Single(uri, type)
        return try {
            folderOf(path) ?: Browse.Single(uri, type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "couldn't place $path in its folder", e)
            Browse.Single(uri, type)
        }
    }

    private suspend fun folderOf(path: String): Browse.Grid? {
        val folder = File(path).parent ?: return null
        val prefs = dao.observePrefs(folder).first()
        val (sort, descending) = FolderText.folderSort(prefs?.sort, prefs?.descending)
        val filter = MediaFilter(sort = sort, descending = descending, groupBy = GroupBy.NONE)
        indexIfNew(dao, path, roots())
        val start = saved.get<Int>(KEY_PAGE) ?: dao.indexOf(filter, folder, path) ?: return null
        return Browse.Grid(filter, folder, start)
    }

    companion object {
        private const val KEY_PAGE = "viewerPage"
        private const val KEY_ASKED = "locationAsked"
        private const val TAG = "ViewerViewModel"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GalleryApp
                val graph = app.graph
                val saved = createSavedStateHandle()
                ViewerViewModel(
                    ViewerArgs.from(saved), graph.db.mediaDao(), graph.db.pathDao(), graph.settings, saved, graph.fileOps,
                    graph.recycleBin, roots = { StorageLayout.roots(app) }, resolver = app.contentResolver,
                    toasts = graph.toasts, opScope = graph.appScope,
                )
            }
        }
    }
}
