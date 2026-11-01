package dev.kanorin.gallery.ui.main

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
import dev.kanorin.gallery.data.MediaDao
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.data.MediaQuery
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.logic.FolderCard
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.SearchParser
import dev.kanorin.gallery.logic.ShowChip
import dev.kanorin.gallery.logic.SortField
import dev.kanorin.gallery.logic.SortText
import dev.kanorin.gallery.logic.Section
import dev.kanorin.gallery.logic.TimelineGrouping
import dev.kanorin.gallery.logic.Transfer
import dev.kanorin.gallery.settings.DeleteChoice
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.MainMode
import dev.kanorin.gallery.ui.common.DeleteAsk
import dev.kanorin.gallery.ui.common.SelectionActions
import dev.kanorin.gallery.ui.common.SelectionState
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.common.launchGuarded
import dev.kanorin.gallery.ui.folders.FOLDER_COLUMN_RANGE
import dev.kanorin.gallery.ui.folders.FoldersModel
import dev.kanorin.gallery.ui.folders.foldersModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

/** The main screen's state: mode and query survive process death; the timeline reads only Room. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(
    private val dao: MediaDao,
    private val settings: Settings,
    private val saved: SavedStateHandle,
    private val ops: FileOps,
    private val bin: RecycleBin,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** Camera / Screenshots pinning counts from here (spec 7: internal storage). */
    val storageRoot: String = StorageLayout.primaryRoot().path,
    /** App-wide toasts: an op's result lands there even when this screen is gone. */
    private val toasts: ToastHostState = ToastHostState(),
    /** Where file ops run (the app's scope in production), so a rotation or leaving the screen never stops one; null = this VM. */
    opScope: CoroutineScope? = null,
    /** Visible Recycle bin items (BinDao.count); the sheet shows nothing until it answers. */
    binCounts: Flow<Int> = emptyFlow(),
    /** [dev.kanorin.gallery.index.MediaIndex.firstSync]: an empty grid then means "not yet", so it says nothing. */
    val firstSync: StateFlow<Boolean> = MutableStateFlow(false),
) : ViewModel() {
    private val opScope: CoroutineScope = opScope ?: viewModelScope

    val mode: StateFlow<MainMode> = saved.getStateFlow(KEY_MODE, MainMode.ALL)
    val query: StateFlow<String> = saved.getStateFlow(KEY_QUERY, "")

    // The All view's whole filter: the ⋮ sheet's choices (kept in SavedStateHandle, not across restarts) plus the applied search.
    private val filterState = MutableStateFlow(restoreFilter())
    val filter: StateFlow<MediaFilter> = filterState.asStateFlow()

    /** The sheet's Recycle bin row; null until Room answers. */
    val binCount: StateFlow<Int?> = binCounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** null until DataStore answers, so a user's saved size never flashes the default first. */
    val columns: StateFlow<Int?> = settings.gridAll.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Folders grid size; null until DataStore answers. */
    val folderColumns: StateFlow<Int?> = settings.gridFolders.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The Folders view's cards, hidden rules and prefs applied; null until Room answers. */
    val folders: StateFlow<FoldersModel?> = dao.foldersModel(settings, storageRoot)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The sheet's Hidden folders row; null until Room answers. */
    val hiddenFolderCount: StateFlow<Int?> = folders.map { it?.arranged?.hidden?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The current filter's groups, labelled; null until Room has answered for it (a new filter goes back to null). */
    val sections: StateFlow<List<Section>?> = filterState.flatMapLatest { f ->
        val fixed = TimelineGrouping.fixedZone(zone(), clock())
        val by = f.effectiveGroupBy
        val labelled: Flow<List<Section>?> = combine(dao.sections(f, null, fixed), dayTicks(fixed)) { list, now ->
            list.map {
                it.copy(
                    label = TimelineGrouping.label(it.key, by, now, fixed),
                    scrollLabel = scrollLabel(it.key, by, now, fixed),
                )
            }
        }
        labelled.onStart { emit(null) }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val items: Flow<PagingData<MediaEntity>> = filterState.flatMapLatest { f ->
        Pager(MediaQuery.PAGING) { dao.pagingItems(f, null) }.flow
    }.cachedIn(viewModelScope)

    /** ADD PHOTOS: the folder the All view is filling (spec 5.3). Saved, so the flow survives rotation and process death. */
    val addTarget: StateFlow<String?> = saved.getStateFlow(KEY_ADD_TARGET, null)

    /** New folder goes in internal storage's Pictures/ (spec 5.3). */
    val newFolderParent: File get() = File(storageRoot, Environment.DIRECTORY_PICTURES)

    /** Selection, delete, Move / Copy: the code the inside-folder screen shares. */
    val actions = SelectionActions(
        dao, settings, saved, ops, bin, toasts, viewModelScope, this.opScope,
        pathsInOrder = { dao.orderedPaths(filterState.value, null) },
        newFolderParent = newFolderParent,
        onEnd = { saved[KEY_ADD_TARGET] = null },
    )

    /** The All view's selection (held in [actions], so a rotation keeps it). */
    val selection: SelectionState = actions.selection

    /** The delete dialog's counts while it's open; null = closed. */
    val deleteAsk: StateFlow<DeleteAsk?> = actions.deleteAsk

    /** The bar's ⋮ choice, waiting for the folder picker's answer ([transferTo]). */
    val pendingTransfer: StateFlow<Transfer?> = actions.pendingTransfer

    init {
        if (addTarget.value != null) selection.hold()
        // Typing in All mode filters the All view after a pause; in Folders mode the query only filters cards.
        viewModelScope.launch {
            combine(mode, query) { m, q -> if (m == MainMode.ALL) q else null }
                .filterNotNull()
                .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
                .collect { applySearch(it) }
        }
    }

    /** Bytes selected, summed in Room: selected tiles can sit on pages never loaded. */
    val selectedBytes: StateFlow<Long> get() = actions.selectedBytes

    /** Every path in grid order, unloaded pages included. */
    suspend fun pathsInOrder(): List<String> = actions.pathsInOrder()

    /** The header circle: selects the group, or clears it when it was all selected. */
    fun toggleSection(section: Section) {
        toasts.launchGuarded(viewModelScope, "Couldn't select the group") {
            selection.toggleAll(dao.orderedPaths(filterState.value, null, section.firstItemIndex, section.count))
        }
    }

    /** The bar's select all: everything, or nothing when everything was selected. */
    fun toggleAll() = actions.toggleAll()

    suspend fun rows(paths: Collection<String>): List<MediaEntity> = actions.rows(paths)

    /** ✕ and back: selection and ADD PHOTOS end together. */
    fun endSelection() = actions.endSelection()

    /** The toast's ADD PHOTOS: the All view, selecting from nothing, with "Add to <name>" at the bottom. */
    fun startAdding(folder: String) {
        saved[KEY_MODE] = MainMode.ALL
        saved[KEY_ADD_TARGET] = folder
        selection.clear()
        selection.hold()
    }

    /** Move to folder / Copy to folder from the bar's ⋮; the host opens the picker next. */
    fun beginTransfer(kind: Transfer) = actions.beginTransfer(kind)

    /** The picker's answer. */
    fun transferTo(folder: String) = actions.transferTo(folder)

    fun transfer(kind: Transfer, folder: String) = actions.transfer(kind, folder)

    fun askDelete() = actions.askDelete()

    fun cancelDelete() = actions.cancelDelete()

    fun delete(choice: DeleteChoice, remember: Boolean) = actions.delete(choice, remember)

    suspend fun newFolderSiblings(): Set<String> = ops.siblingNames(newFolderParent)

    /** The Folders view's Rename check: mounted roots read off Main whenever the list changes; none offered until then. */
    val renameCheck: StateFlow<(String) -> Boolean> = folders.filterNotNull()
        .map { ops.roots().let { mounted -> { path: String -> ops.canRename(path, mounted) } } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), { _: String -> false })

    suspend fun renameSiblings(path: String): Set<String> = actions.renameSiblings(path)

    /** The Folders view's "+ New folder": the toast offers ADD PHOTOS. */
    fun createFolder(name: String) {
        opScope.launch {
            ops.createFolder(newFolderParent, name)
                .onSuccess { dir -> toasts.show("Folder “${dir.name}” created", ADD_PHOTOS) { startAdding(dir.path) } }
                .onFailure { toasts.show(SelectionActions.failure(it, "Couldn't create the folder")) }
        }
    }

    /** The picker's "+ New folder": make it, then move or copy the selection into it. Once per choice. */
    fun createFolderForTransfer(name: String) = actions.createFolderForTransfer(name)

    fun renameFolder(path: String, name: String) = actions.renameFolder(path, name)

    fun toggleMode() {
        saved[KEY_MODE] = if (mode.value == MainMode.ALL) MainMode.FOLDERS else MainMode.ALL
    }

    /** A pinch's new All-grid size; anything outside [Settings.GRID_ALL_RANGE] is dropped, not saved. */
    fun setColumns(n: Int) {
        if (n !in Settings.GRID_ALL_RANGE) return
        viewModelScope.launch { settings.setGridAll(n) }
    }

    /** A pinch's new Folders size; the same range the grid's stepper uses, so no ask can be refused. */
    fun setFolderColumns(n: Int) {
        if (n !in FOLDER_COLUMN_RANGE) return
        viewModelScope.launch { settings.setGridFolders(n) }
    }

    fun togglePin(card: FolderCard) {
        toasts.launchGuarded(viewModelScope, if (card.pinned) "Couldn't unpin the folder" else "Couldn't pin the folder") {
            dao.setPinned(card.path, !card.pinned)
        }
    }

    /** Hide, or Unhide on a card shown while show-hidden is on. */
    fun toggleFolderHidden(card: FolderCard) {
        toasts.launchGuarded(viewModelScope, if (card.hidden) "Couldn't unhide the folder" else "Couldn't hide the folder") {
            dao.setHiddenOverride(card.path, !card.hidden)
        }
    }

    fun setQuery(value: String) {
        saved[KEY_QUERY] = value
    }

    /** Picking a different sort starts at that sort's usual order; picking the current one changes nothing. */
    fun setSort(field: SortField) = changeFilter {
        if (it.sort == field) it else it.copy(sort = field, descending = SortText.defaultDescending(field))
    }

    fun flipOrder() = changeFilter { it.copy(descending = !it.descending) }

    fun setGroupBy(by: GroupBy) = changeFilter { it.copy(groupBy = by) }

    fun setChip(chip: ShowChip, on: Boolean) = changeFilter { it.withChip(chip, on) }

    private fun changeFilter(change: (MediaFilter) -> MediaFilter) {
        val f = filterState.updateAndGet(change)
        saved[KEY_SORT] = f.sort
        saved[KEY_DESC] = f.descending
        saved[KEY_GROUP] = f.groupBy
        saved[KEY_CHIPS] = ShowChip.entries.filter(f::isOn).map { it.name }.toCollection(ArrayList())
    }

    private fun applySearch(raw: String) {
        val parsed = SearchParser.parse(raw, clock(), zone()).takeUnless { it.isEmpty }
        filterState.update { it.copy(search = parsed) }
    }

    private fun restoreFilter(): MediaFilter {
        val chips = saved.get<List<String>>(KEY_CHIPS)
        var f = MediaFilter(
            sort = saved.get<SortField>(KEY_SORT) ?: SortField.DATE_TAKEN,
            descending = saved.get<Boolean>(KEY_DESC) ?: true,
            groupBy = saved.get<GroupBy>(KEY_GROUP) ?: GroupBy.DAY,
        )
        if (chips != null) ShowChip.entries.forEach { f = f.withChip(it, it.name in chips) }
        val q = saved.get<String>(KEY_QUERY).orEmpty()
        if (q.isNotBlank() && saved.get<MainMode>(KEY_MODE) != MainMode.FOLDERS) {
            f = f.copy(search = SearchParser.parse(q, clock(), zone()).takeUnless { it.isEmpty })
        }
        return f
    }

    /** Fast-scroll bubble: the month even for day groups, the year for year groups, nothing when ungrouped. */
    private fun scrollLabel(key: Long, by: GroupBy, now: Long, zone: ZoneOffset): String = when (by) {
        GroupBy.DAY, GroupBy.MONTH -> TimelineGrouping.label(key, GroupBy.MONTH, now, zone)
        GroupBy.YEAR -> TimelineGrouping.label(key, GroupBy.YEAR, now, zone)
        GroupBy.NONE -> ""
    }

    /** Now, then again at each local midnight, so "Today" becomes "Yesterday" with the app open. */
    private fun dayTicks(zone: ZoneOffset): Flow<Long> = flow {
        while (true) {
            val now = clock()
            emit(now)
            delay(TimelineGrouping.groupKey(now, GroupBy.DAY, zone) + DAY_MS - now)
        }
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_QUERY = "query"
        private const val KEY_SORT = "sort"
        private const val KEY_DESC = "sortDescending"
        private const val KEY_GROUP = "groupBy"
        private const val KEY_CHIPS = "showChips"
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val KEY_ADD_TARGET = "addTarget"
        const val ADD_PHOTOS = "ADD PHOTOS"
        const val UNDO = SelectionActions.UNDO
        private const val DAY_MS = 86_400_000L
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val graph = (this[APPLICATION_KEY] as GalleryApp).graph
                MainViewModel(
                    graph.db.mediaDao(), graph.settings, createSavedStateHandle(), graph.fileOps, graph.recycleBin,
                    toasts = graph.toasts, opScope = graph.appScope, binCounts = graph.db.binDao().count(),
                    firstSync = graph.mediaIndex.firstSync,
                )
            }
        }
    }
}
