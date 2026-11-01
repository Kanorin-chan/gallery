package dev.kanorin.gallery

import android.app.Application
import android.content.Context
import android.media.MediaScannerConnection
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.fileops.fsyncDir
import dev.kanorin.gallery.index.AndroidMediaStoreSource
import dev.kanorin.gallery.index.FsWalker
import dev.kanorin.gallery.index.MediaIndex
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.thumbs.buildImageLoader
import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Hand-wired dependencies, one instance per process (see [GalleryApp]). */
class AppGraph(app: Application) {
    /** Lives as long as the process; background work that outlives a screen runs here. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = Settings(app.settingsDataStore)

    val db: GalleryDb by lazy { GalleryDb.build(app) }

    val mediaIndex: MediaIndex by lazy {
        MediaIndex(
            db, AndroidMediaStoreSource(app), FsWalker { StorageLayout.roots(app) }, appScope,
            purge = { coldStart -> recycleBin.scheduledPurge(coldStart) },
        )
    }

    /** New folder, Move, Copy, Rename. MediaScanner hears of every changed path; the index's own sync follows. */
    val fileOps: FileOps by lazy {
        FileOps(
            db,
            scanner = { paths -> MediaScannerConnection.scanFile(app, paths.toTypedArray(), null, null) },
            roots = { StorageLayout.roots(app) },
            storageRoot = StorageLayout.primaryRoot().path,
            syncDir = ::fsyncDir,
        )
    }

    /** One `.gallery-bin` per storage root; shares FileOps' lock and roots. */
    val recycleBin: RecycleBin by lazy { RecycleBin(db, fileOps) }

    /** The app's toasts; MainActivity draws them over every screen, so they outlive a rotation. */
    val toasts = ToastHostState()

    /** Coil singleton for every grid tile (installed in [GalleryApp]). */
    val imageLoader: ImageLoader by lazy { buildImageLoader(app) }

    /** One per player screen (a var: tests swap in a fake). Pauses for a call or unplugged headphones. */
    var newPlayer: (Context) -> Player = { context ->
        ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}
