package dev.kanorin.gallery.ui.bin

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kanorin.gallery.GalleryApp
import dev.kanorin.gallery.data.BinEntity
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.logic.DeleteText
import dev.kanorin.gallery.ui.common.SelectionState
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.common.runOp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Recycle bin screen (spec 5.5): its items, a selection of bin paths, Restore / Delete forever / Empty bin. */
class BinViewModel(
    private val bin: RecycleBin,
    saved: SavedStateHandle,
    private val toasts: ToastHostState = ToastHostState(),
    /** Where ops run (the app's scope in production), so leaving the screen never stops one; null = this VM. */
    opScope: CoroutineScope? = null,
    /** Wall clock (epoch ms) for each tile's days left. */
    val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val opScope: CoroutineScope = opScope ?: viewModelScope

    /** Newest first; null until Room answers. */
    val items: StateFlow<List<BinEntity>?> = bin.items().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    @OptIn(SavedStateHandleSaveableApi::class)
    val selection: SelectionState = saved.saveable(KEY_SELECTION, saver = SelectionState.Saver) { SelectionState() }

    init {
        // Ruling AB: rows whose file is gone are settled as the screen opens (their favourites go back).
        this.opScope.launch {
            try {
                bin.settleGhosts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "bin settle pass failed", e)
            }
        }
    }

    fun endSelection() = selection.clear()

    fun toggleAll() = selection.toggleAll(items.value.orEmpty().map { it.binPath })

    fun restoreSelected() {
        val picked = take()
        if (picked.isEmpty()) return
        opScope.launch { toasts.restoreFromBin(bin, picked) }
    }

    fun deleteSelected() {
        val picked = take()
        if (picked.isEmpty()) return
        opScope.launch { toasts.deleteFromBinForever(bin, picked) }
    }

    /** "Empty bin": exactly the items on screen when it's confirmed. */
    fun emptyBin() {
        val shown = items.value.orEmpty().map { it.binPath }
        selection.clear()
        if (shown.isEmpty()) return
        opScope.launch { toasts.deleteFromBinForever(bin, shown) }
    }

    // The selection ends as the op starts, so no stale bin path stays selected.
    private fun take(): List<String> = selection.selected.toList().also { selection.clear() }

    companion object {
        private const val KEY_SELECTION = "selection"
        private const val TAG = "BinViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val graph = (this[APPLICATION_KEY] as GalleryApp).graph
                BinViewModel(graph.recycleBin, createSavedStateHandle(), graph.toasts, graph.appScope)
            }
        }
    }
}

/** Restores [binPaths] with the progress line, then the result toast: the bin screen and the viewer's bin item. */
internal suspend fun ToastHostState.restoreFromBin(bin: RecycleBin, binPaths: List<String>) {
    val result = runOp(binPaths, DeleteText::restoring) { bin.restore(binPaths, it) }
    show(DeleteText.restored(result.done.size, result.failed.size, RecycleBin.cardOut(result)))
}

/** Deletes [binPaths] for good with the progress line, then the result toast. */
internal suspend fun ToastHostState.deleteFromBinForever(bin: RecycleBin, binPaths: List<String>) {
    val result = runOp(binPaths, DeleteText::progress) { bin.deleteForever(binPaths, it) }
    show(DeleteText.deleted(result.done.size, result.failed.size, result.indexLag))
}
