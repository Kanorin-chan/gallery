package dev.kanorin.gallery.ui.common

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import dev.kanorin.gallery.logic.StaleCount
import kotlinx.coroutines.delay

private const val TAG = "RefreshWhenStale"

/**
 * Refreshes [items] once if its count still differs from [expected] (the sections' item total; null = not
 * answered yet) [StaleCount.STALE_MS] after both have loaded. Each change of either restarts the wait.
 */
@Composable
fun RefreshWhenStale(expected: Int?, items: LazyPagingItems<*>) {
    val check = remember { StaleCount() }
    val count = items.itemCount
    val settled = items.loadState.refresh is LoadState.NotLoading
    LaunchedEffect(expected, count, settled) {
        if (!check.armed(expected, count, settled)) return@LaunchedEffect
        delay(StaleCount.STALE_MS)
        check.refreshed(expected ?: return@LaunchedEffect)
        Log.w(TAG, "paged count $count != sections total $expected: refreshing (a page missed a write)")
        items.refresh()
    }
}
