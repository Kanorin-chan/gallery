package dev.kanorin.gallery.index

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * When the index runs which half. MediaStore changes trigger only the cheap MediaStore sync;
 * the storage walk runs at cold start, on resume (at most every 5 min) and every 6 h. Every resume syncs MediaStore.
 * [purge] (the Recycle bin's) runs on its own at cold start (true) and on every resume (false).
 */
internal class IndexScheduler(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val changes: Flow<Unit>,
    private val syncMediaStore: suspend () -> Unit,
    private val fullScan: suspend () -> Unit,
    private val clock: () -> Long,
    private val onError: (String, Throwable) -> Unit,
    private val purge: suspend (coldStart: Boolean) -> Unit = {},
) {
    private val started = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    @Volatile private var scanFailed = false
    @Volatile private var lastScanStart = 0L

    val isStarted: Boolean get() = started.get()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        lastScanStart = clock() // the cold-start scan counts toward the resume throttle
        scope.launch(dispatcher) { observe() }
        scope.launch(dispatcher) { attempt("bin purge failed") { purge(true) } }
        scope.launch(dispatcher) {
            runStore() // MediaStore first: the grid fills before the walk starts
            runScan()
            while (true) {
                delay(FULL_SCAN_PERIOD_MS)
                runScan()
            }
        }
    }

    fun onAppResumed() {
        if (!started.get()) return
        scope.launch(dispatcher) { attempt("bin purge failed") { purge(false) } }
        scope.launch(dispatcher) {
            // Always: Android holds a cached app's ContentObserver call ~10 s, and the new photo must already be there.
            runStore()
            if (scanFailed || clock() - lastScanStart >= RESUME_SCAN_GAP_MS) runScan()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun observe() {
        changes
            .retryWhen { cause, attempt ->
                onError("MediaStore observer failed; resubscribing", cause)
                delay((RESUBSCRIBE_MIN_MS shl attempt.coerceAtMost(6).toInt()).coerceAtMost(RESUBSCRIBE_MAX_MS))
                emit(Unit) // catch up on whatever changed while nobody listened
                true
            }
            .debounce(DEBOUNCE_MS)
            .conflate() // changes during a sync collapse into one follow-up
            .collect { runStore() }
    }

    private suspend fun runStore() {
        attempt("MediaStore sync failed") { syncMediaStore() }
    }

    private suspend fun runScan() {
        if (!scanning.compareAndSet(false, true)) return
        try {
            lastScanStart = clock()
            scanFailed = !attempt("storage scan failed") { fullScan() }
        } finally {
            scanning.set(false)
        }
    }

    private suspend fun attempt(what: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(what, e)
        false
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        const val RESUBSCRIBE_MIN_MS = 1_000L
        const val RESUBSCRIBE_MAX_MS = 60_000L
        const val RESUME_SCAN_GAP_MS = 5 * 60_000L
        const val FULL_SCAN_PERIOD_MS = 6 * 60 * 60_000L
    }
}
