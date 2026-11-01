package dev.kanorin.gallery.ui.player

import android.content.Context
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.ToastHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File

/** Room in memory, one storage root, real FileOps / bin, and a [FakePlayer] per ViewModel. */
@UnstableApi
class PlayerKit(val root: File) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: GalleryDb = Room.inMemoryDatabaseBuilder(context, GalleryDb::class.java).allowMainThreadQueries().build()
    val ops = FileOps(db, {}, roots = { listOf(root) }, storageRoot = root.path)
    val bin = RecycleBin(db, ops)
    val settings = Settings(MemoryStore())
    val toasts = ToastHostState()
    val opScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val players = mutableListOf<FakePlayer>()

    /** A video file on disk with its index row. */
    fun video(name: String, durationMs: Long, width: Int = 1920, height: Int = 1080): File {
        val f = File(root, "Movies/$name").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(2048)) }
        runBlocking {
            db.mediaDao().upsert(
                listOf(
                    MediaEntity(
                        path = f.path, folderPath = f.parent!!, name = f.name, mimeType = "video/mp4", isVideo = true,
                        dateTaken = 1_000, dateModified = 1_000, size = f.length(), width = width, height = height,
                        durationMs = durationMs, orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
                    ),
                ),
            )
        }
        return f
    }

    fun progress(path: String, positionMs: Long) = runBlocking {
        db.videoProgressDao().save(path, positionMs, 1)
    }

    fun savedProgress(path: String): Long? = runBlocking { db.videoProgressDao().positionOf(path) }

    fun vm(
        path: String? = null,
        uri: Uri? = null,
        type: String? = null,
        saved: SavedStateHandle = SavedStateHandle(),
        durationMs: Long = 61 * 60_000L,
    ): PlayerViewModel = PlayerViewModel(
        PlayerArgs(path, uri, type), db.mediaDao(), db.videoProgressDao(), db.pathDao(), settings, saved, ops, bin,
        roots = { listOf(root) }, resolver = context.contentResolver,
        newPlayer = { FakePlayer(durationMs).also { players += it } },
        toasts = toasts, opScope = opScope, storageRoot = root.path,
    )

    fun close() {
        opScope.cancel()
        db.close()
    }
}

/** The node's px bounds through every layer's scale (ui-test's bounds helpers add only the offset). */
fun SemanticsNodeInteraction.frameInRoot(): Rect {
    val c = fetchSemanticsNode().layoutInfo.coordinates
    return Rect(c.localToRoot(Offset.Zero), c.localToRoot(Offset(c.size.width.toFloat(), c.size.height.toFloat())))
}
