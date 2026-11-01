package dev.kanorin.gallery.data

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Room's paging source ignores an invalidation that lands before its first load returns (room-paging 2.8.5),
 * so a write in that window left the grid stale until the next write. This one watches the query's tables from before
 * that load and, if a change came in during it, invalidates as soon as it returns. [changes] = those tables' invalidations.
 */
internal class FreshPagingSource<V : Any>(
    private val room: PagingSource<Int, V>,
    /** Emits once on subscribing, then on every change of the watched tables. */
    changes: Flow<*>,
) : PagingSource<Int, V>() {
    constructor(room: PagingSource<Int, V>, tracker: InvalidationTracker, tables: Array<String>) :
        this(room, tracker.createFlow(*tables))

    private val firstLoad = AtomicInteger(WAITING)
    private val watching = CompletableDeferred<Unit>()

    // Lives until this source is invalidated, as Room's own observer does.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The change watcher; cancelled with the source. */
    internal val watcher: Job = scope.launch {
        try {
            // The first emission is the subscription itself; every commit after it is seen.
            changes.collect { if (!watching.complete(Unit)) onChange() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Before the baseline the first load reports it; after it nobody would know, so say so.
            if (!watching.completeExceptionally(e)) Log.w(TAG, "table watcher failed; this source may go stale", e)
        }
    }

    init {
        room.registerInvalidatedCallback(::invalidate)
        registerInvalidatedCallback {
            scope.cancel()
            watching.cancel()
            room.invalidate()
        }
    }

    override val jumpingSupported: Boolean get() = room.jumpingSupported
    override val keyReuseSupported: Boolean get() = room.keyReuseSupported

    override fun getRefreshKey(state: PagingState<Int, V>): Int? = room.getRefreshKey(state)

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, V> {
        if (firstLoad.get() == DONE) return room.load(params)
        try {
            try {
                watching.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                return LoadResult.Invalid()
            } catch (e: Exception) {
                return LoadResult.Error(e)
            }
            firstLoad.set(LOADING)
            val result = room.load(params)
            // The page is still shown (no blank grid mid-burst); the next generation replaces it.
            if (firstLoad.getAndSet(DONE) == MISSED) invalidate()
            return result
        } catch (e: CancellationException) {
            // A Pager dropped before its first load ends never invalidates us; this ends the watcher.
            invalidate()
            throw e
        }
    }

    // Before the first load: it will read the change. During it: defer to its end. After it: now.
    private fun onChange() {
        if (firstLoad.compareAndSet(LOADING, MISSED)) return
        if (firstLoad.get() == DONE) invalidate()
    }

    private companion object {
        const val TAG = "FreshPagingSource"
        const val WAITING = 0
        const val LOADING = 1
        const val MISSED = 2
        const val DONE = 3
    }
}
