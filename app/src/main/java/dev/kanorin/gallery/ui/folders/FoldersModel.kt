package dev.kanorin.gallery.ui.folders

import android.net.Uri
import dev.kanorin.gallery.data.MediaDao
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.logic.Arranged
import dev.kanorin.gallery.logic.FolderCard
import dev.kanorin.gallery.logic.FolderOrdering
import dev.kanorin.gallery.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File

/** The arranged cards plus the cover rows the thumbnails need (the cover path alone can't make a thumbnail request). */
data class FoldersModel(val arranged: Arranged, val covers: Map<String, MediaEntity>)

/** Pinch range of the Folders grid: the stepper, the zoom pill and the saved value all read this one constant. */
val FOLDER_COLUMN_RANGE: IntRange = Settings.GRID_FOLDERS_RANGE

/** Created folders are re-checked on disk at every change (filtered only: their rows go on FileOps' write paths). */
fun MediaDao.foldersModel(settings: Settings, storageRoot: String): Flow<FoldersModel> =
    combine(folders(), folderPrefs(), createdFolders(), favouritesSummary(), settings.showHidden) { f, p, c, fav, show ->
        FolderOrdering.arrange(f, p, c.filter { File(it).isDirectory }, storageRoot, show, fav)
    }.map { arranged ->
        val covers = (arranged.pinned + arranged.others + arranged.hidden).mapNotNull { it.coverPath }.toSet()
        FoldersModel(arranged, byPaths(covers).associateBy { it.path })
    }.flowOn(Dispatchers.IO)

/** Where a tap on [card] goes. Favourites has its own route; the others carry the encoded path. */
fun folderRoute(card: FolderCard): String =
    if (card.isFavourites) "favorites" else "folder/${Uri.encode(card.path)}"
