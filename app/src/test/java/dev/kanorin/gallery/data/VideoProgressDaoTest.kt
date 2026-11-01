package dev.kanorin.gallery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoProgressDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GalleryDb::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.videoProgressDao()

    @After fun tearDown() = db.close()

    private fun row(path: String) = MediaEntity(
        path = path, folderPath = path.substringBeforeLast('/'), name = path.substringAfterLast('/'), mimeType = "video/mp4",
        isVideo = true, dateTaken = 1, dateModified = 1, size = 1, width = 0, height = 0, durationMs = 3_600_000,
        orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
    )

    @Test fun aSaveReplacesTheLastOne() = runBlocking {
        db.mediaDao().upsert(listOf(row("/s/Movies/a.mp4")))
        dao.save("/s/Movies/a.mp4", 60_000, 1)
        dao.save("/s/Movies/a.mp4", 90_000, 2)
        assertEquals(90_000L, dao.positionOf("/s/Movies/a.mp4"))
    }

    // A save that lands after a delete or a move must not bring the old path back.
    @Test fun aFileTheIndexNoLongerHasGetsNoRow() = runBlocking {
        dao.save("/s/Movies/gone.mp4", 60_000, 1)
        assertNull(dao.positionOf("/s/Movies/gone.mp4"))
    }

    @Test fun theEndClearsIt() = runBlocking {
        db.mediaDao().upsert(listOf(row("/s/Movies/a.mp4")))
        dao.save("/s/Movies/a.mp4", 60_000, 1)
        db.pathDao().deleteVideoProgress("/s/Movies/a.mp4")
        assertNull(dao.positionOf("/s/Movies/a.mp4"))
    }
}
