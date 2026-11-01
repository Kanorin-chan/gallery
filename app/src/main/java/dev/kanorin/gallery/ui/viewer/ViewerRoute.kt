package dev.kanorin.gallery.ui.viewer

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.logic.FilterCodec
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.ui.player.playerRoute

const val ARG_FILTER = "filter"
const val ARG_FOLDER = "folder"
const val ARG_INDEX = "index"
const val ARG_URI = "uri"
const val ARG_TYPE = "type"
const val ARG_PATH = "path"
const val ARG_BIN = "bin"

/** The viewer: a grid's query + start index, a VIEW intent's URI, or a Recycle bin item. Every argument is optional. */
const val VIEWER_ROUTE =
    "viewer?$ARG_FILTER={$ARG_FILTER}&$ARG_FOLDER={$ARG_FOLDER}&$ARG_INDEX={$ARG_INDEX}&$ARG_PATH={$ARG_PATH}" +
        "&$ARG_URI={$ARG_URI}&$ARG_TYPE={$ARG_TYPE}&$ARG_BIN={$ARG_BIN}"

val VIEWER_ARGUMENTS = listOf(
    navArgument(ARG_FILTER) { type = NavType.StringType; nullable = true; defaultValue = null },
    navArgument(ARG_FOLDER) { type = NavType.StringType; nullable = true; defaultValue = null },
    navArgument(ARG_INDEX) { type = NavType.IntType; defaultValue = 0 },
    navArgument(ARG_PATH) { type = NavType.StringType; nullable = true; defaultValue = null },
    navArgument(ARG_URI) { type = NavType.StringType; nullable = true; defaultValue = null },
    navArgument(ARG_TYPE) { type = NavType.StringType; nullable = true; defaultValue = null },
    navArgument(ARG_BIN) { type = NavType.StringType; nullable = true; defaultValue = null },
)

/**
 * The tapped item ([path], at [index] when tapped) of the grid showing [filter] in [folder] (null = All,
 * FAVORITES_FOLDER = Favourites). The viewer starts at [path]'s place then, [index] only if it's gone.
 */
fun viewerRoute(filter: MediaFilter, folder: String?, index: Int, path: String? = null): String =
    "viewer?$ARG_FILTER=${Uri.encode(FilterCodec.encode(filter))}&$ARG_INDEX=$index" +
        (folder?.let { "&$ARG_FOLDER=${Uri.encode(it)}" } ?: "") +
        (path?.let { "&$ARG_PATH=${Uri.encode(it)}" } ?: "")

/** A VIEW intent's [uri] (of MIME [type], if known): its folder when it's one of ours, else just that item. */
fun viewerRoute(uri: Uri, type: String? = null): String =
    "viewer?$ARG_URI=${Uri.encode(uri.toString())}" + (type?.let { "&$ARG_TYPE=${Uri.encode(it)}" } ?: "")

/** A Recycle bin tile's tap: [binPath] alone, read-only, with Restore and Delete forever. */
fun binViewerRoute(binPath: String, type: String?): String =
    "viewer?$ARG_BIN=${Uri.encode(binPath)}" + (type?.let { "&$ARG_TYPE=${Uri.encode(it)}" } ?: "")

/** A grid tile's tap (spec 5.7 + ruling D): a video opens the player, a photo the viewer on the grid's own query. */
fun tileRoute(item: MediaEntity, filter: MediaFilter, folder: String?, index: Int): String =
    if (item.isVideo) playerRoute(item.path) else viewerRoute(filter, folder, index, item.path)

/** The viewer's nav arguments, as they arrive (already decoded once by Navigation). */
data class ViewerArgs(
    val filter: MediaFilter,
    val folder: String?,
    val index: Int,
    val uri: Uri?,
    val type: String? = null,
    /** The tapped item; its place in the query now beats [index], which may have moved by the first load. */
    val path: String? = null,
    /** A Recycle bin item's file, shown alone. */
    val binPath: String? = null,
) {
    /** Opened by another app's VIEW intent: back returns to that app. */
    val fromIntent: Boolean get() = uri != null

    companion object {
        fun from(saved: SavedStateHandle): ViewerArgs = ViewerArgs(
            filter = saved.get<String>(ARG_FILTER)?.let(FilterCodec::decode) ?: MediaFilter(),
            folder = saved.get<String>(ARG_FOLDER),
            index = saved.get<Int>(ARG_INDEX) ?: 0,
            uri = saved.get<String>(ARG_URI)?.let(Uri::parse),
            type = saved.get<String>(ARG_TYPE),
            path = saved.get<String>(ARG_PATH),
            binPath = saved.get<String>(ARG_BIN),
        )
    }
}
