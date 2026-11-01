package dev.kanorin.gallery

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.data.GalleryDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestTeardownTest {
    // Final review item 5, the flake forced: a ViewModel-like scope's query still running as the test closes the db.
    @Test fun aDrainedScopeHasNoQueryLeftWhenTheDbCloses() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val inQuery = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    db.runInTransaction {
                        inQuery.countDown()
                        Thread.sleep(300)
                    }
                    db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media").close()
                } catch (e: Throwable) {
                    failure.set(e)
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(inQuery.await(5, TimeUnit.SECONDS))
        cancelAndDrain(scope)
        db.close()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
    }
}
