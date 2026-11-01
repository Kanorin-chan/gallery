package dev.kanorin.gallery.ui.folder

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The folder route through a real NavHost: what arrives must be the path that was sent. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FolderRouteTest {
    @get:Rule val compose = createComposeRule()

    private val paths = listOf(
        "/storage/emulated/0/Pictures/Trip KL",
        "/storage/emulated/0/Pictures/Trip #1",
        "/storage/emulated/0/Pictures/100% done",
        "/storage/emulated/0/Pictures/a%41b%2Fc",
        "/storage/emulated/0/Pictures/日本語のフォルダ",
        "/storage/emulated/0/Pictures/what? a+b & c=d",
        "/storage/1234-ABCD/DCIM/Camera",
    )

    @Test fun everyAwkwardPathArrivesIntact() {
        var nav: NavHostController? = null
        var arrived: String? = null
        compose.setContent {
            val controller = rememberNavController().also { nav = it }
            NavHost(controller, startDestination = "start") {
                composable("start") { Text("start") }
                composable(FOLDER_ROUTE, arguments = listOf(navArgument(FOLDER_ARG) { type = NavType.StringType })) { entry ->
                    val arg = entry.arguments?.getString(FOLDER_ARG)
                    LaunchedEffect(arg) { arrived = arg?.let(::pathFromArg) }
                    Text("folder")
                }
            }
        }
        for (path in paths) {
            arrived = null
            compose.runOnUiThread { nav!!.navigate("folder/${Uri.encode(path)}") }
            compose.waitUntil(5_000) { arrived != null }
            assertEquals(path, arrived)
            compose.runOnUiThread { nav!!.popBackStack() }
            compose.waitForIdle()
        }
    }

    @Test fun favouritesHasNoArgument() {
        var arg: String? = "unset"
        compose.setContent {
            val controller = rememberNavController()
            NavHost(controller, startDestination = FAVOURITES_ROUTE) {
                composable(FAVOURITES_ROUTE) { entry ->
                    arg = entry.arguments?.getString(FOLDER_ARG)
                    Text("fav")
                }
            }
        }
        compose.waitForIdle()
        assertNull(arg)
    }
}
