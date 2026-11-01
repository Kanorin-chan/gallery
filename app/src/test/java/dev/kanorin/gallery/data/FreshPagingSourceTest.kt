package dev.kanorin.gallery.data

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** The source's lifetime rules, with the table watcher and Room's source replaced by fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreshPagingSourceTest {
    private val scope = CoroutineScope(Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
    }

    // Reports the load it is in, then never finishes.
    private class HangingRoom : PagingSource<Int, Int>() {
        val started = CompletableDeferred<Unit>()
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
            started.complete(Unit)
            awaitCancellation()
        }

        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
    }

    private fun waitFor(ms: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (!done()) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(10)
        }
        return true
    }

    private val refresh = PagingSource.LoadParams.Refresh<Int>(null, 10, false)

    // The subscription's own emission, then quiet.
    private val subscribedOnly: Flow<Unit> = flow { emit(Unit); awaitCancellation() }

    @Test fun aLoadCancelledMidReadInvalidatesTheSourceAndEndsItsWatcher() = runBlocking {
        val room = HangingRoom()
        val source = FreshPagingSource(room, subscribedOnly)
        val load = scope.launch { source.load(refresh) }
        withTimeout(5_000) { room.started.await() }

        load.cancelAndJoin()

        assertTrue("source invalid", waitFor(2_000) { source.invalid })
        assertTrue("watcher cancelled", waitFor(2_000) { source.watcher.isCancelled })
        assertTrue("room's source told too", room.invalid)
    }

    @Test fun aLoadCancelledBeforeTheWatcherSubscribedDoesTheSame() = runBlocking {
        val room = HangingRoom()
        val source = FreshPagingSource(room, flow<Unit> { awaitCancellation() })
        val load = scope.launch { source.load(refresh) }
        Thread.sleep(100) // parked on the subscription

        load.cancelAndJoin()

        assertTrue("source invalid", waitFor(2_000) { source.invalid })
        assertTrue("watcher cancelled", waitFor(2_000) { source.watcher.isCancelled })
    }

    @Test fun aWatcherFailureAfterTheBaselineIsLogged() {
        ShadowLog.clear()
        val boom = IllegalStateException("tracker gone")
        val source = FreshPagingSource(HangingRoom(), flow<Unit> { emit(Unit); throw boom })
        assertTrue("watcher ended", waitFor(2_000) { source.watcher.isCompleted })
        val warned = ShadowLog.getLogsForTag("FreshPagingSource").filter { it.type == Log.WARN }
        assertEquals(1, warned.size)
        assertEquals(boom, warned.single().throwable)
        source.invalidate()
    }

    @Test fun aWatcherFailureBeforeTheBaselineSurfacesAsALoadError() = runBlocking {
        ShadowLog.clear()
        val boom = IllegalStateException("no tracker")
        val source = FreshPagingSource(HangingRoom(), flow<Unit> { throw boom })
        val result = withTimeout(5_000) { source.load(refresh) }
        // Coroutines may hand back a copy of the exception.
        assertEquals(boom.message, (result as PagingSource.LoadResult.Error).throwable.message)
        assertTrue("not double-reported", ShadowLog.getLogsForTag("FreshPagingSource").none { it.type == Log.WARN })
        source.invalidate()
    }
}
