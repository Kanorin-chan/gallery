package dev.kanorin.gallery.index

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Virtual time throughout: no wall-clock waits. */
@OptIn(ExperimentalCoroutinesApi::class)
class IndexSchedulerTest {
    private val calls = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun TestScope.scheduler(
        changeFlow: Flow<Unit> = changes,
        store: suspend () -> Unit = { calls += "store" },
        scan: suspend () -> Unit = { calls += "scan" },
        purge: suspend (Boolean) -> Unit = {},
    ) = IndexScheduler(
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        changes = changeFlow,
        syncMediaStore = store,
        fullScan = scan,
        clock = { testScheduler.currentTime },
        onError = { message, _ -> errors += message },
        purge = purge,
    )

    private fun TestScope.burst(n: Int = 1) {
        repeat(n) { assertTrue(changes.tryEmit(Unit)) }
        runCurrent()
    }

    @Test fun coldStartReadsMediaStoreThenScans() = runTest {
        val s = scheduler()
        assertFalse(s.isStarted)
        s.start()
        assertTrue(s.isStarted)
        runCurrent()
        assertEquals(listOf("store", "scan"), calls)
    }

    @Test fun startTwiceStartsOnce() = runTest {
        val s = scheduler()
        s.start()
        s.start()
        runCurrent()
        assertEquals(listOf("store", "scan"), calls)
    }

    @Test fun changeBurstSyncsMediaStoreOnceAfterDebounceAndNeverScans() = runTest {
        scheduler().start()
        runCurrent()
        calls.clear()

        burst(5)
        advanceTimeBy(IndexScheduler.DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(emptyList<String>(), calls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("store"), calls)
        advanceTimeBy(10 * minute)
        assertEquals(listOf("store"), calls)
    }

    @Test fun changesDuringASyncCollapseIntoOneMore() = runTest {
        var gate: CompletableDeferred<Unit>? = null
        scheduler(store = { calls += "store"; gate?.await() }).start()
        runCurrent()
        calls.clear()

        gate = CompletableDeferred()
        burst()
        advanceTimeBy(IndexScheduler.DEBOUNCE_MS + 1)
        assertEquals(listOf("store"), calls) // running, held at the gate
        burst(3)
        advanceTimeBy(IndexScheduler.DEBOUNCE_MS + 1)
        burst(2)
        advanceTimeBy(IndexScheduler.DEBOUNCE_MS + 1)
        val held = gate
        gate = null
        held.complete(Unit)
        advanceTimeBy(minute)
        assertEquals(listOf("store", "store"), calls)
    }

    @Test fun mediaStoreSyncRunsWhileAScanIsInProgress() = runTest {
        val scanGate = CompletableDeferred<Unit>()
        scheduler(scan = { calls += "scan"; scanGate.await() }).start()
        runCurrent()
        assertEquals(listOf("store", "scan"), calls)

        burst()
        advanceTimeBy(IndexScheduler.DEBOUNCE_MS + 1)
        assertEquals(listOf("store", "scan", "store"), calls) // the photo didn't wait for the walk
        scanGate.complete(Unit)
    }

    @Test fun observerFailureResubscribesAndCatchesUp() = runTest {
        var subscriptions = 0
        val flaky = flow {
            if (subscriptions++ == 0) throw IllegalStateException("observer died")
            emitAll(changes)
        }
        scheduler(changeFlow = flaky).start()
        runCurrent()
        assertEquals(listOf("store", "scan"), calls)
        assertEquals(1, errors.size)

        advanceTimeBy(IndexScheduler.RESUBSCRIBE_MIN_MS + IndexScheduler.DEBOUNCE_MS + 1)
        assertEquals(2, subscriptions)
        assertEquals(listOf("store", "scan", "store"), calls) // catch-up for anything missed while down

        burst()
        advanceTimeBy(IndexScheduler.DEBOUNCE_MS + 1)
        assertEquals(listOf("store", "scan", "store", "store"), calls)
    }

    // Round 2 (J): Android delays a cached app's ContentObserver ~10 s, so coming back must read MediaStore itself.
    @Test fun resumeAlwaysSyncsMediaStoreButTheWalkKeepsItsThrottle() = runTest {
        val s = scheduler()
        s.start()
        runCurrent()
        assertEquals(listOf("store", "scan"), calls)
        assertEquals("the cold start succeeded", emptyList<String>(), errors)
        calls.clear()

        s.onAppResumed()
        runCurrent()
        assertEquals(listOf("store"), calls)
        s.onAppResumed()
        runCurrent()
        assertEquals("no walk within 5 min", listOf("store", "store"), calls)
    }

    @Test fun resumeScansAtMostOncePerFiveMinutes() = runTest {
        val s = scheduler()
        s.start()
        runCurrent()
        calls.clear()

        s.onAppResumed()
        runCurrent()
        assertEquals(listOf("store"), calls)

        advanceTimeBy(5 * minute)
        s.onAppResumed()
        runCurrent()
        s.onAppResumed()
        runCurrent()
        assertEquals(listOf("store", "store", "scan", "store"), calls)
    }

    @Test fun resumeDuringALongScanDoesNotStartAnother() = runTest {
        val scanGate = CompletableDeferred<Unit>()
        val s = scheduler(scan = { calls += "scan"; scanGate.await() })
        s.start()
        runCurrent()
        advanceTimeBy(6 * minute)
        s.onAppResumed()
        runCurrent()
        assertEquals(listOf("store", "scan", "store"), calls)
        scanGate.complete(Unit)
    }

    @Test fun resumeRetriesAFailedMediaStoreSync() = runTest {
        var fail = true
        val s = scheduler(store = {
            calls += "store"
            if (fail) { fail = false; error("MediaStore down") }
        })
        s.start()
        runCurrent()
        assertEquals(listOf("store", "scan"), calls)
        assertEquals(1, errors.size)

        s.onAppResumed()
        runCurrent()
        assertEquals(listOf("store", "scan", "store"), calls)
        assertEquals("the retry succeeded", 1, errors.size)
    }

    @Test fun resumeRetriesAFailedScanEvenWithinFiveMinutes() = runTest {
        var fail = true
        val s = scheduler(scan = {
            calls += "scan"
            if (fail) { fail = false; error("walk failed") }
        })
        s.start()
        runCurrent()
        s.onAppResumed()
        runCurrent()
        assertEquals(listOf("store", "scan", "store", "scan"), calls)
    }

    @Test fun scansEverySixHours() = runTest {
        scheduler().start()
        runCurrent()
        calls.clear()
        advanceTimeBy(6 * hour - 1)
        runCurrent()
        assertEquals(emptyList<String>(), calls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("scan"), calls)
    }

    @Test fun nothingRunsBeforeStart() = runTest {
        val s = scheduler()
        s.onAppResumed()
        burst()
        advanceTimeBy(hour)
        assertEquals(emptyList<String>(), calls)
    }

    // ---- Recycle bin purge (Ruling AA): every cold start; the resume's daily gate is the bin's own (BinPolicy.purgeDue).

    @Test fun coldStartPurgesTheBinAndEveryResumeAsksIt() = runTest {
        val purges = mutableListOf<Boolean>()
        val s = scheduler(purge = { purges += it })
        s.start()
        runCurrent()
        assertEquals(listOf(true), purges)
        s.onAppResumed()
        runCurrent()
        assertEquals(listOf(true, false), purges)
        s.start()
        runCurrent()
        assertEquals("one cold start", listOf(true, false), purges)
    }

    @Test fun aFailedPurgeIsLoggedAndTheIndexCarriesOn() = runTest {
        scheduler(purge = { error("disk full") }).start()
        runCurrent()
        assertEquals(listOf("store", "scan"), calls)
        assertEquals(listOf("bin purge failed"), errors)
    }

    @Test fun noPurgeBeforeStart() = runTest {
        val purges = mutableListOf<Boolean>()
        scheduler(purge = { purges += it }).onAppResumed()
        runCurrent()
        assertEquals(emptyList<Boolean>(), purges)
    }
}
