package dev.kanorin.gallery

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import org.robolectric.Shadows.shadowOf

/** Cancels [scope] and waits for its children, pumping Main (a blocking join there can deadlock). Call before `db.close()`. */
fun cancelAndDrain(scope: CoroutineScope, timeoutMs: Long = 10_000) {
    val job = scope.coroutineContext.job
    job.cancel()
    val end = System.currentTimeMillis() + timeoutMs
    while (!job.isCompleted) {
        check(System.currentTimeMillis() < end) { "scope still running $timeoutMs ms after cancel" }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
    shadowOf(Looper.getMainLooper()).idle()
}
