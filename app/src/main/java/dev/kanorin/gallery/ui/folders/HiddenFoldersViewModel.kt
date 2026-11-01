package dev.kanorin.gallery.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kanorin.gallery.GalleryApp
import dev.kanorin.gallery.data.MediaDao
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.logic.FolderCard
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.common.launchGuarded
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HiddenFoldersViewModel(
    private val dao: MediaDao,
    settings: Settings,
    storageRoot: String,
    private val toasts: ToastHostState = ToastHostState(),
) : ViewModel() {
    /** null until Room answers. */
    val hidden: StateFlow<List<FolderCard>?> = dao.foldersModel(settings, storageRoot)
        .map { it.arranged.hidden }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The override beats the rule, so a `.nomedia` folder shows in Folders from now on. */
    fun unhide(card: FolderCard) {
        toasts.launchGuarded(viewModelScope, "Couldn't unhide the folder") { dao.setHiddenOverride(card.path, false) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val graph = (this[APPLICATION_KEY] as GalleryApp).graph
                HiddenFoldersViewModel(graph.db.mediaDao(), graph.settings, StorageLayout.primaryRoot().path, graph.toasts)
            }
        }
    }
}
