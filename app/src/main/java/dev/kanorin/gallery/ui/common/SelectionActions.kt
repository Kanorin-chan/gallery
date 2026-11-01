package dev.kanorin.gallery.ui.common

import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import dev.kanorin.gallery.data.MediaDao
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.logic.DeleteText
import dev.kanorin.gallery.logic.Transfer
import dev.kanorin.gallery.logic.TransferText
import dev.kanorin.gallery.settings.DeleteChoice
import dev.kanorin.gallery.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The delete dialog: the files it asks about, and its subline's counts. */
data class DeleteAsk(val paths: List<String>, val photos: Int, val videos: Int)

/**
 * Everything a screen with a photo grid does with its selection: the selection itself, delete (dialog, remembered
 * choice, UNDO), Move / Copy, folder rename. Shared by the All view and the inside-folder screen, so there is one
 * code path. [uiScope] is the ViewModel's; [opScope] outlives the screen (a rotation never stops a file op).
 * The selection and the pending Move / Copy choice live in [saved].
 */
@OptIn(ExperimentalCoroutinesApi::class, SavedStateHandleSaveableApi::class)
class SelectionActions(
    private val dao: MediaDao,
    private val settings: Settings,
    private val saved: SavedStateHandle,
    private val ops: FileOps,
    private val bin: RecycleBin,
    private val toasts: ToastHostState,
    private val uiScope: CoroutineScope,
    private val opScope: CoroutineScope,
    /** Every path of the grid in order, unloaded pages included. */
    val pathsInOrder: suspend () -> List<String>,
    /** Where the picker's "+ New folder" makes its folder. */
    private val newFolderParent: File,
    /** Runs when the selection ends, for the host's own state (ADD PHOTOS). */
    private val onEnd: () -> Unit = {},
    /** Runs as a delete starts (dialog pick or remembered choice): the player closes then. */
    private val onDelete: () -> Unit = {},
) {
    /** Held here, so a rotation keeps a selection of any size; the Saver covers process death. */
    val selection: SelectionState = saved.saveable(KEY_SELECTION, saver = SelectionState.Saver) { SelectionState() }

    private val asking = MutableStateFlow<DeleteAsk?>(null)

    /** The delete dialog's counts while it's open; null = closed. */
    val deleteAsk: StateFlow<DeleteAsk?> = asking.asStateFlow()

    /** The bar's ⋮ choice, waiting for the folder picker's answer ([transferTo]). */
    val pendingTransfer: StateFlow<Transfer?> = saved.getStateFlow<Transfer?>(KEY_PENDING, null)

    /** Bytes selected, summed in Room: selected tiles can sit on pages never loaded. */
    val selectedBytes: StateFlow<Long> = snapshotFlow { selection.selected.toSet() }
        .mapLatest { dao.sumSizes(it) }
        .stateIn(uiScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0), 0L)

    /** The bar's select all: everything, or nothing when everything was selected. */
    fun toggleAll() {
        toasts.launchGuarded(uiScope, "Couldn't select everything") { selection.toggleAll(pathsInOrder()) }
    }

    suspend fun rows(paths: Collection<String>): List<MediaEntity> = dao.byPaths(paths)

    /** ✕ and back: selection (and the host's own mode) end together. */
    fun endSelection() {
        onEnd()
        selection.clear()
    }

    /** Move to folder / Copy to folder from the bar's ⋮; the host opens the picker next. */
    fun beginTransfer(kind: Transfer) {
        saved[KEY_PENDING] = kind
    }

    /** The same for exactly [paths] (the viewer's one photo): they become the selection the picker's answer moves. */
    fun beginTransfer(kind: Transfer, paths: Collection<String>) {
        selection.clear()
        selection.setAll(paths, on = true)
        beginTransfer(kind)
    }

    /** The picker's answer. */
    fun transferTo(folder: String) {
        val kind = pendingTransfer.value ?: return
        saved[KEY_PENDING] = null
        transfer(kind, folder)
    }

    /** Moves or copies the selection into [folder] off the main thread: a progress line meanwhile, then a result toast. */
    fun transfer(kind: Transfer, folder: String) {
        val picked = selection.selected.toList()
        endSelection()
        if (picked.isEmpty()) return
        opScope.launch { runTransfer(kind, picked, File(folder)) }
    }

    /** The picker's "+ New folder": make it, then move or copy the selection into it. Once per choice. */
    fun createFolderForTransfer(name: String) {
        val kind = pendingTransfer.value ?: return
        saved[KEY_PENDING] = null
        opScope.launch {
            val dir = ops.createFolder(newFolderParent, name).getOrElse {
                toasts.show(failure(it, "Couldn't create the folder"))
                return@launch
            }
            // Selection is Main-thread state, kept until the folder really exists.
            val picked = withContext(Dispatchers.Main.immediate) { selection.selected.toList().also { endSelection() } }
            if (picked.isEmpty()) toasts.show("Folder “${dir.name}” created") else runTransfer(kind, picked, dir)
        }
    }

    private suspend fun runTransfer(kind: Transfer, picked: List<String>, dest: File) {
        val result = toasts.runOp(picked, { i, n -> TransferText.progress(kind, i, n) }) { line ->
            // Off Main from the lookup on, as the op itself runs: nothing waits on the main thread in between.
            withContext(Dispatchers.IO) {
                val live = indexed(picked)
                if (kind == Transfer.MOVE) ops.move(live, dest, line) else ops.copy(live, dest, line)
            }
        }
        toasts.show(TransferText.result(kind, result.done.size, result.failed.size, result.indexLag))
    }

    /** [paths] still in the index, in order: a file deleted or moved elsewhere since it was selected drops out. */
    private suspend fun indexed(paths: List<String>): List<String> {
        val known = dao.byPaths(paths).mapTo(HashSet()) { it.path }
        return paths.filter { it in known }
    }

    /** The bar's delete (spec 5.5): the dialog for exactly these files, or straight to the remembered choice. */
    fun askDelete() = askDelete(selection.selected.toList())

    /** Delete for exactly [picked] (the viewer's one photo), through the same dialog, remembered choice and UNDO. */
    fun askDelete(picked: List<String>) {
        if (picked.isEmpty()) return
        toasts.launchGuarded(uiScope, "Couldn't start the delete") {
            val live = indexed(picked)
            if (live.isEmpty()) return@launchGuarded
            val remembered = settings.rememberedDeleteChoice.first()
            if (remembered != null) return@launchGuarded run(remembered, live, remembered = true)
            val videos = dao.countVideos(live)
            asking.value = DeleteAsk(live, live.size - videos, videos)
        }
    }

    fun cancelDelete() {
        asking.value = null
    }

    /** The dialog's pick ([remember] = the box was ticked), for the files it counted. */
    fun delete(choice: DeleteChoice, remember: Boolean) {
        val ask = asking.value ?: return
        asking.value = null
        if (remember) opScope.launch { settings.setRememberedDeleteChoice(choice) }
        run(choice, ask.paths, remembered = false)
    }

    /** Selection ends at once; the op runs off the main thread. */
    private fun run(choice: DeleteChoice, picked: List<String>, remembered: Boolean) {
        endSelection()
        onDelete()
        opScope.launch {
            when {
                choice == DeleteChoice.BIN -> {
                    val result = toasts.runOp(picked, DeleteText::progress) { bin.moveToBin(picked, onProgress = it) }
                    val text = DeleteText.binned(result.done.size, result.failed.size)
                    if (result.done.isEmpty()) toasts.show(text) else toasts.show(text, UNDO) { undo(result.landed) }
                }
                // Ruling AD: nobody confirmed this one, so it's held in the bin until its UNDO runs out.
                remembered -> {
                    val result = toasts.runOp(picked, DeleteText::progress) { bin.moveToBin(picked, pendingErase = true, onProgress = it) }
                    val text = DeleteText.deleted(result.done.size, result.failed.size)
                    if (result.done.isEmpty()) {
                        toasts.show(text)
                    } else {
                        toasts.show(text, UNDO, onGone = { erase(result.landed) }) { undo(result.landed) }
                    }
                }
                else -> {
                    val result = toasts.runOp(picked, DeleteText::progress) { bin.deletePermanently(picked, it) }
                    toasts.show(DeleteText.deleted(result.done.size, result.failed.size, result.indexLag))
                }
            }
        }
    }

    /** A held delete whose UNDO ran out; anything left over is erased by the next purge. */
    private fun erase(binPaths: List<String>) {
        opScope.launch {
            try {
                bin.deleteForever(binPaths)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "held delete left for the next purge", e)
            }
        }
    }

    /** The toast's UNDO: exactly the files that op binned go back; quiet unless some can't. */
    private fun undo(binPaths: List<String>) {
        opScope.launch {
            val result = toasts.runOp(binPaths, DeleteText::restoring) { line ->
                // First: a held delete the user took back is never erased, even if the restore below fails.
                try {
                    bin.unhold(binPaths)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't release held deletes before the restore", e)
                }
                bin.restore(binPaths, line)
            }
            if (result.failed.isNotEmpty()) toasts.show(DeleteText.undoFailed(result.failed.size))
        }
    }

    /** Names a folder rename must not clash with. */
    suspend fun renameSiblings(path: String): Set<String> = File(path).let { ops.siblingNames(it.parentFile ?: it, except = it) }

    /** Renames the folder on disk; a failure is a toast, success hands the new path to [onRenamed]. */
    fun renameFolder(path: String, name: String, onRenamed: (String) -> Unit = {}) {
        opScope.launch {
            ops.renameFolder(path, name)
                .onSuccess { onRenamed(it.path) }
                .onFailure { toasts.show(failure(it, "Couldn't rename the folder")) }
        }
    }

    companion object {
        const val UNDO = "UNDO"
        private const val KEY_SELECTION = "selection"
        private const val KEY_PENDING = "pendingTransfer"
        private const val TAG = "SelectionActions"
        private const val STOP_TIMEOUT_MS = 5_000L

        // A clash the op itself found (another app was quicker) reads like the dialog's own error; a stuck rename says where it is.
        fun failure(e: Throwable, fallback: String) = when (e) {
            is FileOps.NameException -> e.error.message
            is FileOps.RenameStuck -> e.message ?: fallback
            else -> fallback
        }
    }
}
