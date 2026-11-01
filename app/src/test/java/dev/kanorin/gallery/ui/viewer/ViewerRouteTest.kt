package dev.kanorin.gallery.ui.viewer

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kanorin.gallery.logic.FAVORITES_FOLDER
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.SearchQuery
import dev.kanorin.gallery.logic.SortField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The viewer route through a real NavHost: the grid's query, folder and index arrive exactly as sent. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ViewerRouteTest {
    @get:Rule val compose = createComposeRule()

    private var nav: NavHostController? = null
    private var arrived: ViewerArgs? = null

    private fun host() {
        compose.setContent {
            val controller = rememberNavController().also { nav = it }
            NavHost(controller, startDestination = "start") {
                composable("start") { Text("start") }
                composable(VIEWER_ROUTE, arguments = VIEWER_ARGUMENTS) { entry ->
                    val args = ViewerArgs.from(entry.savedStateHandle)
                    LaunchedEffect(args) { arrived = args }
                    Text("viewer")
                }
            }
        }
    }

    private fun go(route: String): ViewerArgs {
        arrived = null
        compose.runOnUiThread { nav!!.navigate(route) }
        compose.waitUntil(5_000) { arrived != null }
        val got = arrived!!
        compose.runOnUiThread { nav!!.popBackStack() }
        compose.waitForIdle()
        return got
    }

    @Test fun aGridsQueryArrivesIntact() {
        host()
        val filters = listOf(
            MediaFilter(),
            MediaFilter(photos = false, sort = SortField.NAME, descending = false, groupBy = GroupBy.NONE),
            MediaFilter(search = SearchQuery("a&b=c?d#e%41 100% 日本 🌸.x", 1_790_000_000_000L, 1_790_086_400_000L)),
        )
        val folders = listOf(null, FAVORITES_FOLDER, "/storage/emulated/0/Pictures/日本 #1 100%", "/storage/1234-ABCD/a%2Fb?c&d")
        for (f in filters) for (folder in folders) {
            assertEquals(ViewerArgs(f, folder, 1234, null), go(viewerRoute(f, folder, 1234)))
        }
        val path = "/storage/emulated/0/Pictures/日本 #1 100%/a&b=c?%41.jpg"
        assertEquals(ViewerArgs(filters[2], folders[2], 7, null, path = path), go(viewerRoute(filters[2], folders[2], 7, path)))
    }

    @Test fun aViewIntentsUriArrivesIntact() {
        host()
        val uris = listOf(
            "content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCamera%2Fa%20b.jpg",
            "file:///storage/emulated/0/Pictures/%E6%97%A5%23%25.jpg",
            "content://media/external/images/media/42?x=1&y=2",
        ).map(Uri::parse)
        for (u in uris) {
            val got = go(viewerRoute(u))
            assertEquals(u, got.uri)
            assertEquals(null, got.type)
            assertEquals(true, got.fromIntent)
        }
        assertEquals("image/heic", go(viewerRoute(uris[0], "image/heic")).type)
    }

    // Final review M9: a Recycle bin tile opens its file alone; back stays in the app.
    @Test fun aBinItemsPathArrivesIntact() {
        host()
        val path = "/storage/emulated/0/.gallery-bin/日本 #1 100%/a&b=c?%41.jpg"
        val got = go(binViewerRoute(path, "image/jpeg"))
        assertEquals(path, got.binPath)
        assertEquals("image/jpeg", got.type)
        assertEquals(false, got.fromIntent)
    }
}
