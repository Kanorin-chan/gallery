package dev.kanorin.gallery.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateSet
import dev.kanorin.gallery.logic.RangeSelection

/**
 * Which files are selected, by absolute path (spec 5.2). Selection is [active] while anything is selected,
 * and also mid hold-drag at zero: like the mockup, it leaves only when the finger lifts.
 */
class SelectionState(initial: Collection<String> = emptyList()) {
    val selected: SnapshotStateSet<String> = mutableStateSetOf<String>().apply { addAll(initial) }

    private var activeState by mutableStateOf(initial.isNotEmpty())
    val active: Boolean get() = activeState

    var dragging: Boolean = false
        private set

    // ADD PHOTOS: selecting with nothing picked yet; only clear() ends it.
    private var held by mutableStateOf(false)

    fun toggle(path: String) {
        if (!selected.remove(path)) selected.add(path)
        settle()
    }

    fun setAll(paths: Collection<String>, on: Boolean) {
        // A Set argument: AbstractSet.removeAll calls contains on the argument, O(n) per call on a List.
        if (on) selected.addAll(paths) else selected.removeAll(paths.toSet())
        settle()
    }

    /** Header circle and select all: selects the missing ones, or clears the group when all were selected. */
    fun toggleAll(paths: Collection<String>) = setAll(paths, on = !selected.containsAll(paths))

    fun clear() {
        selected.clear()
        dragging = false
        held = false
        activeState = false
    }

    /** Selection mode with nothing selected (ADD PHOTOS); stays active at 0 until [clear]. */
    fun hold() {
        held = true
        settle()
    }

    fun beginDrag() {
        dragging = true
        activeState = true
    }

    fun apply(change: RangeSelection.Change) {
        selected.addAll(change.on)
        selected.removeAll(change.off)
        settle()
    }

    fun endDrag() {
        dragging = false
        settle()
    }

    private fun settle() {
        activeState = held || dragging || selected.isNotEmpty()
    }

    companion object {
        /** Paths kept for process death; Parcel strings are UTF-16, so a long path costs ~250 bytes. */
        const val SAVE_LIMIT = 200

        val Saver: Saver<SelectionState, Any> = listSaver(
            save = { if (it.selected.size <= SAVE_LIMIT) it.selected.toList() else emptyList() },
            restore = { SelectionState(it) },
        )
    }
}
