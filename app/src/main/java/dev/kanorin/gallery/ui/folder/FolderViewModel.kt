package dev.kanorin.gallery.ui.folder

import android.os.Environment
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
import dev.kanorin.gallery.data.FolderPrefsEntity
import dev.kanorin.gallery.data.FolderStats
import dev.kanorin.gallery.data.MediaDao
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.data.MediaQuery
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.logic.FAVORITES_FOLDER
import dev.kanorin.gallery.logic.FolderOrdering
import dev.kanorin.gallery.logic.FolderText
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.SearchParser
import dev.kanorin.gallery.logic.Section
import dev.kanorin.gallery.logic.SortField
import dev.kanorin.gallery.logic.SortText
import dev.kanorin.gallery.logic.TimelineGrouping
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.SelectionActions
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.common.launchGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId

const val FOLDER_ROUTE = "folder/{encodedPath}"
const val FAVOURITES_ROUTE = "favorites"
const val FOLDER_ARG = "encodedPath"

/** The inside-folder grid's size range: the stepper, the pill and the setter all read this one constant (spec 5.6). */
val INSIDE_COLUMN_RANGE: IntRange = Settings.GRID_INSIDE_RANGE

/** The type chip's cycle. */
enum class TypeChip(val label: String) {
    ALL("All types"), PHOTOS("Photos"), VIDEOS("Videos");

    fun next() = entries[(ordinal + 1) % entries.size]
}

/** A folder's saved order. */
data class SortChoice(val field: SortField, val descending: Boolean)

/**
 * One folder's screen state (spec 5.6): its rows, the chips, its saved grid size and sort, its totals.
 * Selection, delete and Move / Copy are [actions], the same code the All view runs.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class FolderViewModel(
    initialPath: String,
    private val dao: MediaDao,
    settings: Settings,
    private val saved: SavedStateHandle,
    private val ops: FileOps,
    bin: RecycleBin,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    val storageRoot: String = StorageLayout.primaryRoot().path,
    private val toasts: ToastHostState = ToastHostState(),
    opScope: CoroutineScope? = null,
    /** [dev.kanorin.gallery.index.MediaIndex.firstSync]: an empty folder then means "not yet", so it says nothing. */
    val firstSync: StateFlow<Boolean> = MutableStateFlow(false),
) : ViewModel() {
    // A rename moves the screen to the new path, and the saved value keeps it there after process death.
    private val pathState = MutableStateFlow(saved.get<String>(KEY_PATH) ?: initialPath)
    val path: StateFlow<String> = pathState.asStateFlow()
    val isFavourites: Boolean get() = path.value == FAVORITES_FOLDER

    val title: StateFlow<String> = path.map(::nameOf).stateIn(viewModelScope, SharingStarted.Eagerly, nameOf(pathState.value))

    private val prefs: Flow<FolderPrefsEntity?> = path.flatMapLatest { dao.observePrefs(it) }

    /** This folder's order; null until Room answers. First open: date taken, newest first. */
    val sort: StateFlow<SortChoice?> = prefs.map { p ->
        val (field, descending) = FolderText.folderSort(p?.sort, p?.descending)
        SortChoice(field, descending)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** This folder's grid size, else the Settings default; null until both answer. */
    val columns: StateFlow<Int?> = combine(prefs, settings.gridInside) { p, default ->
        p?.gridColumns?.takeIf { it in INSIDE_COLUMN_RANGE } ?: default
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The type and favourites chips and the search are for this visit only: kept over a rotation, not remembered.
    val type: StateFlow<TypeChip> = saved.getStateFlow(KEY_TYPE, TypeChip.ALL.name).map { TypeChip.valueOf(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TypeChip.valueOf(saved.get<String>(KEY_TYPE) ?: TypeChip.ALL.name))
    val favouritesOnly: StateFlow<Boolean> = saved.getStateFlow(KEY_FAV, false)
    val query: StateFlow<String> = saved.getStateFlow(KEY_QUERY, "")

    /** Everything the grid shows, as one filter; no date headers inside a folder (ruling AF). */
    val filter: StateFlow<MediaFilter?> = combine(
        sort.filterNotNull(),
        type,
        favouritesOnly,
        query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }.map { SearchParser.parse(it, clock(), zone()).takeUnless { s -> s.isEmpty } },
    ) { s, t, fav, search ->
        MediaFilter(
            photos = t != TypeChip.VIDEOS,
            videos = t != TypeChip.PHOTOS,
            gifs = t != TypeChip.VIDEOS,
            favouritesOnly = fav,
            sort = s.field,
            descending = s.descending,
            groupBy = GroupBy.NONE,
            search = search,
        )
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val request: Flow<Pair<String, MediaFilter>> = combine(path, filter.filterNotNull()) { p, f -> p to f }.distinctUntilChanged()

    val items: Flow<PagingData<MediaEntity>> = request.flatMapLatest { (p, f) ->
        Pager(MediaQuery.PAGING) { dao.pagingItems(f, p) }.flow
    }.cachedIn(viewModelScope)

    /** The current request's groups; null until Room has answered for it (a new request goes back to null). */
    val sections: StateFlow<List<Section>?> = request.flatMapLatest { (p, f) ->
        val answered: Flow<List<Section>?> = dao.sections(f, p, TimelineGrouping.fixedZone(zone(), clock()))
        answered.onStart { emit(null) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The app bar's count and size, and the Hide / Unhide choice; null until Room answers. */
    val stats: StateFlow<FolderStats?> = path.flatMapLatest { if (it == FAVORITES_FOLDER) dao.favouritesStats() else dao.folderStats(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Selection, delete (dialog, remembered choice, UNDO), Move / Copy, share: shared with the All view. */
    val actions = SelectionActions(
        dao, settings, saved, ops, bin, toasts, viewModelScope, opScope ?: viewModelScope,
        pathsInOrder = { dao.orderedPaths(filter.value ?: MediaFilter(groupBy = GroupBy.NONE), path.value) },
        newFolderParent = File(storageRoot, Environment.DIRECTORY_PICTURES),
    )

    /** Offered only where [FileOps.canRename] allows it (every mounted root refused); never for Favourites. Read off Main. */
    val canRename: StateFlow<Boolean> = path.map { p -> p != FAVORITES_FOLDER && ops.canRename(p) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** A pinch or a pill tap; anything outside [INSIDE_COLUMN_RANGE] is dropped, not saved. */
    fun setColumns(n: Int) {
        if (n !in INSIDE_COLUMN_RANGE) return
        val p = path.value
        toasts.launchGuarded(viewModelScope, "Couldn't save the grid size") { dao.setFolderGrid(p, n) }
    }

    /** The sort chip's tap: the next field, at that field's usual order. */
    fun cycleSort() {
        val cur = sort.value ?: return
        val next = SortField.entries[(cur.field.ordinal + 1) % SortField.entries.size]
        save(SortChoice(next, SortText.defaultDescending(next)))
    }

    /** The sort chip's long-press: the same field, the other way round. */
    fun flipOrder() {
        val cur = sort.value ?: return
        save(cur.copy(descending = !cur.descending))
    }

    private fun save(choice: SortChoice) {
        val p = path.value
        toasts.launchGuarded(viewModelScope, "Couldn't save the sort") { dao.setFolderSort(p, choice.field, choice.descending) }
    }

    fun cycleType() {
        saved[KEY_TYPE] = type.value.next().name
    }

    fun setFavouritesOnly(on: Boolean) {
        saved[KEY_FAV] = on
    }

    fun setQuery(value: String) {
        saved[KEY_QUERY] = value
    }

    /** Hide, or Unhide when the folder's rows are flagged hidden. The folder stays open either way. */
    fun toggleHidden() {
        val p = path.value
        val hide = stats.value?.hidden != true
        toasts.launchGuarded(viewModelScope, if (hide) "Couldn't hide the folder" else "Couldn't unhide the folder") {
            dao.setHiddenOverride(p, hide)
        }
    }

    suspend fun renameSiblings(): Set<String> = actions.renameSiblings(path.value)

    /** Renames the folder on disk; on success this screen follows it to the new path. */
    fun rename(name: String) {
        actions.renameFolder(path.value, name) { new ->
            viewModelScope.launch {
                saved[KEY_PATH] = new
                pathState.value = new
            }
        }
    }

    private fun nameOf(path: String) = if (path == FAVORITES_FOLDER) FolderOrdering.FAVOURITES_NAME else File(path).name

    companion object {
        private const val KEY_PATH = "path"
        private const val KEY_TYPE = "type"
        private const val KEY_FAV = "favouritesOnly"
        private const val KEY_QUERY = "query"
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val graph = (this[APPLICATION_KEY] as GalleryApp).graph
                val saved = createSavedStateHandle()
                val arg = saved.get<String>(FOLDER_ARG)
                FolderViewModel(
                    if (arg == null) FAVORITES_FOLDER else pathFromArg(arg),
                    graph.db.mediaDao(), graph.settings, saved, graph.fileOps, graph.recycleBin,
                    toasts = graph.toasts, opScope = graph.appScope, firstSync = graph.mediaIndex.firstSync,
                )
            }
        }
    }
}

/** The path as the NavHost hands it over. FolderRouteTest pins what that is. */
internal fun pathFromArg(arg: String): String = arg
