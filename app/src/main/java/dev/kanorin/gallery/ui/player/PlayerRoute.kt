package dev.kanorin.gallery.ui.player

import android.net.Uri
import androidx.navigation.NavType
import androidx.navigation.navArgument

/** The player's path argument; Navigation hands it over decoded. */
const val PLAYER_ARG = "encodedPath"
const val PLAYER_ARG_URI = "uri"
const val PLAYER_ARG_TYPE = "type"

/** The video player on one of our files (a grid tile, the viewer's play button). */
const val PLAYER_ROUTE = "player/{$PLAYER_ARG}"

/** The video player for a VIEW intent: the URI plays as one of our files when it resolves to one. */
const val PLAYER_INTENT_ROUTE = "player?$PLAYER_ARG_URI={$PLAYER_ARG_URI}&$PLAYER_ARG_TYPE={$PLAYER_ARG_TYPE}"

val PLAYER_INTENT_ARGUMENTS = listOf(
    navArgument(PLAYER_ARG_URI) { type = NavType.StringType; nullable = true; defaultValue = null },
    navArgument(PLAYER_ARG_TYPE) { type = NavType.StringType; nullable = true; defaultValue = null },
)

fun playerRoute(path: String): String = "player/${Uri.encode(path)}"

/** A VIEW intent's video [uri] (of MIME [type], if known). */
fun playerRoute(uri: Uri, type: String?): String =
    "player?$PLAYER_ARG_URI=${Uri.encode(uri.toString())}" + (type?.let { "&$PLAYER_ARG_TYPE=${Uri.encode(it)}" } ?: "")
