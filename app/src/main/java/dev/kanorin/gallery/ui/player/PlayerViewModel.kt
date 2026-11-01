package dev.kanorin.gallery.ui.player

import android.content.ContentResolver
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import dev.kanorin.gallery.GalleryApp
import dev.kanorin.gallery.data.MediaDao
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.data.PathDao
import dev.kanorin.gallery.data.VideoProgressDao
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.logic.ResumePolicy
import dev.kanorin.gallery.logic.upright
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.SelectionActions
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.common.indexIfNew
import dev.kanorin.gallery.ui.common.resolveViewPath
import dev.kanorin.gallery.ui.folder.pathFromArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the player plays: one of our files (resume, Delete), or another app's content (neither). */
sealed interface PlayerSource {
    data class Path(val path: String) : PlayerSource

    data class Content(val uri: Uri) : PlayerSource
}

/** The route's arguments: a path (grids, the viewer) or a VIEW intent's URI. */
data class PlayerArgs(val path: String?, val uri: Uri?, val type: String? = null) {
    /** Opened by another app: back returns there. */
    val fromIntent: Boolean get() = uri != null

    companion object {
        fun from(saved: SavedStateHandle) = PlayerArgs(
            path = saved.get<String>(PLAYER_ARG)?.let(::pathFromArg),
            uri = saved.get<String>(PLAYER_ARG_URI)?.let(Uri::parse),
            type = saved.get<String>(PLAYER_ARG_TYPE),
        )
    }
}

/** The screen's view of the player; times in ms. */
data class Playback(
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val durationMs: Long = 0,
    /** The button offers "play": paused, ended, not started, or held back (a call took the audio focus). */
    val paused: Boolean = true,
    val failed: Boolean = false,
)

/** The rotate button's choice; it beats the lock until the lock is touched again. */
enum class Forced { PORTRAIT, LANDSCAPE }

/** The ⋮ Info dialog. [sizeBytes] null = unknown; [location] is the path, or the URI for another app's content. */
data class VideoInfo(
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val codecMime: String?,
    val sizeBytes: Long?,
    val location: String,
)

/**
 * The video player's state (spec 5.8). It owns one [Player] for the screen's whole life: a rotation keeps it
 * (and so the position and play / pause); process death comes back through [saved]. Resume points follow
 * [ResumePolicy] and are saved on pause, on stop and every 5 s while playing.
 */
class PlayerViewModel(
    val args: PlayerArgs,
    private val dao: MediaDao,
    private val progress: VideoProgressDao,
    private val pathDao: PathDao,
    settings: Settings,
    private val saved: SavedStateHandle,
    ops: FileOps,
    bin: RecycleBin,
    private val roots: () -> List<File>,
    /** Resolves VIEW URIs and reads their name / size; null in tests that need neither. */
    private val resolver: ContentResolver?,
    newPlayer: () -> Player,
    toasts: ToastHostState = ToastHostState(),
    opScope: CoroutineScope? = null,
    storageRoot: String = StorageLayout.primaryRoot().path,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val opScope: CoroutineScope = opScope ?: viewModelScope

    val player: Player = newPlayer()

    private val sourceState = MutableStateFlow<PlayerSource?>(null)

    /** null while a VIEW intent's URI is being resolved. */
    val source: StateFlow<PlayerSource?> = sourceState.asStateFlow()

    private val rowState = MutableStateFlow<MediaEntity?>(null)

    /** The index row of a [PlayerSource.Path]; null for another app's content. */
    val item: StateFlow<MediaEntity?> = rowState.asStateFlow()

    private val titleState = MutableStateFlow(args.path?.let { File(it).name } ?: args.uri?.lastPathSegment.orEmpty())
    val title: StateFlow<String> = titleState.asStateFlow()
    @Volatile private var contentSize: Long? = null

    private val playbackState = MutableStateFlow(Playback())
    val playback: StateFlow<Playback> = playbackState.asStateFlow()
    private val playing = MutableStateFlow(false)

    val skipSeconds: StateFlow<Int> = settings.skipSeconds.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT_SKIP_SECONDS)
    val holdSpeed: StateFlow<Float> = settings.holdSpeed.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT_HOLD_SPEED)

    /** The speed list's choice (spec 5.8); a hold plays at its own rate, then comes back to this. */
    val speed: StateFlow<Float> = saved.getStateFlow(KEY_SPEED, 1f)
    val rotationLocked: StateFlow<Boolean> = saved.getStateFlow(KEY_LOCKED, false)
    val forced: StateFlow<Forced?> = saved.getStateFlow<Forced?>(KEY_FORCED, null)

    private val closing = MutableStateFlow(false)

    /** True once a delete has started: the screen closes. */
    val closed: StateFlow<Boolean> = closing.asStateFlow()

    /** Delete: the grids' dialog, remembered choice and UNDO, for the one file. */
    val actions = SelectionActions(
        dao, settings, saved, ops, bin, toasts, viewModelScope, this.opScope,
        pathsInOrder = { emptyList() },
        newFolderParent = File(storageRoot, Environment.DIRECTORY_PICTURES),
        onDelete = ::onDeleteStarted,
    )

    private var started = false
    private var lateResume: Long? = null

    // One writer, in order: a save never lands after the end's clear.
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing.value = isPlaying
            if (!isPlaying) save()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) applyLateResume()
            if (state == Player.STATE_ENDED) clearProgress()
        }
    }

    init {
        player.addListener(listener)
        this.opScope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't store the resume point", e)
                }
            }
        }
        viewModelScope.launch { open() }
        // The bar moves while playing; paused, nothing changes on its own.
        viewModelScope.launch {
            playing.collectLatest { on ->
                refresh()
                while (on) {
                    delay(TICK_MS)
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            playing.collectLatest { on ->
                while (on) {
                    delay(SAVE_EVERY_MS)
                    save()
                }
            }
        }
    }

    private suspend fun open() {
        val src = resolve()
        sourceState.value = src
        val restored = saved.get<Long>(KEY_POSITION)
        var start = restored ?: 0L
        when (src) {
            is PlayerSource.Path -> {
                titleState.value = File(src.path).name
                val row = load(src.path)
                rowState.value = row
                if (restored == null) {
                    val stored = storedPosition(src.path)
                    val duration = row?.durationMs ?: 0
                    if (duration > 0) start = ResumePolicy.startPosition(duration, stored) else lateResume = stored
                }
            }
            is PlayerSource.Content -> readContentFacts(src.uri)
        }
        player.setMediaItem(MediaItem.fromUri(src.uri()), start)
        player.playWhenReady = saved.get<Boolean>(KEY_PLAYING) ?: true
        player.prepare()
        started = true
        refresh()
    }

    private suspend fun resolve(): PlayerSource {
        args.path?.let { return PlayerSource.Path(it) }
        val uri = args.uri ?: return PlayerSource.Content(Uri.EMPTY)
        val path = resolver?.let { r ->
            try {
                withContext(Dispatchers.IO) { resolveViewPath(uri, r, roots()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "couldn't resolve $uri", e)
                null
            }
        }
        return if (path != null) PlayerSource.Path(path) else PlayerSource.Content(uri)
    }

    // A file from a VIEW intent may be new to the index; it gets its row like the viewer's.
    private suspend fun load(path: String): MediaEntity? = try {
        if (args.fromIntent) indexIfNew(dao, path, roots())
        dao.byPath(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "couldn't read the row of $path", e)
        null
    }

    private suspend fun storedPosition(path: String): Long? = try {
        progress.positionOf(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "couldn't read the resume point of $path", e)
        null
    }

    private suspend fun readContentFacts(uri: Uri) {
        val r = resolver ?: return
        try {
            withContext(Dispatchers.IO) {
                r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.takeIf { it.isNotBlank() }?.let { titleState.value = it }
                        if (!c.isNull(1)) contentSize = c.getLong(1)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "no name / size for $uri: ${e.javaClass.simpleName}")
        }
    }

    // The index had no duration: decide once the player knows it, unless the user has moved already.
    private fun applyLateResume() {
        val stored = lateResume ?: return
        lateResume = null
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val start = ResumePolicy.startPosition(duration, stored)
        if (start > 0 && player.currentPosition < LATE_RESUME_SLACK_MS) player.seekTo(start)
    }

    @OptIn(UnstableApi::class)
    private fun refresh() {
        val p = player
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: rowState.value?.durationMs ?: 0
        playbackState.value = Playback(
            positionMs = p.currentPosition,
            bufferedMs = p.bufferedPosition,
            durationMs = duration,
            // Media3's rule: a suppressed player (transient focus loss) shows "play" too.
            paused = Util.shouldShowPlayButton(p),
            failed = p.playerError != null,
        )
    }

    /** The button: play (from the start when ended, taking the focus back when held back) or pause. */
    @OptIn(UnstableApi::class)
    fun playPause() {
        Util.handlePlayPauseButtonAction(player)
        refresh()
    }

    /** A pick from the speed list. */
    fun setSpeed(speed: Float) {
        saved[KEY_SPEED] = speed
    }

    /** What the player plays at now: [speed], or a hold's rate. */
    fun playAt(rate: Float) {
        if (player.playbackParameters.speed != rate) player.setPlaybackSpeed(rate)
    }

    /** [positionMs] within the video. */
    fun seekTo(positionMs: Long) {
        val duration = playbackState.value.durationMs
        player.seekTo(if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0))
        refresh()
    }

    /** Back (negative) or forward by [deltaMs] from where it is now. */
    fun skipBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    /** Lock on / off (and the rotate button's choice dropped); returns the new state. */
    fun toggleRotationLock(): Boolean {
        val on = !rotationLocked.value
        saved[KEY_FORCED] = null
        saved[KEY_LOCKED] = on
        return on
    }

    /** The rotate button: the opposite of what the screen is now. */
    fun rotate(landscapeNow: Boolean) {
        saved[KEY_FORCED] = if (landscapeNow) Forced.PORTRAIT else Forced.LANDSCAPE
    }

    /** The activity's orientation from before the player; the first value seen is kept (rotations, process death). */
    fun previousOrientation(current: Int): Int = saved.get<Int>(KEY_PREVIOUS) ?: current.also { saved[KEY_PREVIOUS] = it }

    /** The screen stopped. Leaving the app pauses; a rotation (a configuration change) doesn't. */
    fun onStop(changingConfigurations: Boolean) {
        if (!changingConfigurations) {
            player.pause()
            // Not opened yet: it opens paused, not playing in the background.
            if (!started) saved[KEY_PLAYING] = false
        }
        save()
    }

    fun delete() {
        val src = sourceState.value as? PlayerSource.Path ?: return
        actions.askDelete(listOf(src.path))
    }

    /** What ⋮ Info shows: the index row first, the player's own facts where the row has none. */
    fun info(): VideoInfo {
        val row = rowState.value
        val video = player.videoSize
        val (w, h) = row?.let { upright(it.width, it.height, it.orientation) }?.takeIf { it.first > 0 } ?: (video.width to video.height)
        val format = player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }
        val src = sourceState.value
        return VideoInfo(
            name = titleState.value,
            durationMs = playbackState.value.durationMs,
            width = w,
            height = h,
            codecMime = format?.sampleMimeType,
            sizeBytes = row?.size ?: contentSize,
            location = when (src) {
                is PlayerSource.Path -> src.path
                is PlayerSource.Content -> src.uri.toString()
                null -> args.uri?.toString().orEmpty()
            },
        )
    }

    // The delete runs on the app's scope; the file's resume point moves with it, so nothing is written after.
    private fun onDeleteStarted() {
        closing.value = true
        player.pause()
    }

    private fun save() {
        // Not positioned yet (opening, or a resume waiting for the duration): 0 would wipe the resume point.
        if (!started || lateResume != null) return
        val position = player.currentPosition
        saved[KEY_POSITION] = position
        saved[KEY_PLAYING] = player.playWhenReady
        val src = sourceState.value as? PlayerSource.Path ?: return
        if (closing.value || player.playbackState == Player.STATE_ENDED) return
        val now = clock()
        writes.trySend { progress.save(src.path, position, now) }
    }

    // Spec 5.8: the end clears the resume point.
    private fun clearProgress() {
        val src = sourceState.value as? PlayerSource.Path ?: return
        writes.trySend { pathDao.deleteVideoProgress(src.path) }
    }

    override fun onCleared() {
        save()
        writes.close()
        player.removeListener(listener)
        player.release()
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val KEY_POSITION = "playerPosition"
        private const val KEY_PLAYING = "playerPlaying"
        private const val KEY_LOCKED = "rotationLocked"
        private const val KEY_FORCED = "rotationForced"
        private const val KEY_PREVIOUS = "previousOrientation"
        private const val KEY_SPEED = "playerSpeed"
        const val TICK_MS = 100L
        const val SAVE_EVERY_MS = 5_000L
        private const val LATE_RESUME_SLACK_MS = 1_000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GalleryApp
                val graph = app.graph
                val saved = createSavedStateHandle()
                PlayerViewModel(
                    PlayerArgs.from(saved), graph.db.mediaDao(), graph.db.videoProgressDao(), graph.db.pathDao(), graph.settings,
                    saved, graph.fileOps, graph.recycleBin, roots = { StorageLayout.roots(app) }, resolver = app.contentResolver,
                    newPlayer = { graph.newPlayer(app) }, toasts = graph.toasts, opScope = graph.appScope,
                )
            }
        }
    }
}

/** The activity orientation for the lock and the rotate button (spec 5.8); FULL_SENSOR ignores the system's auto-rotate. */
fun orientationFor(locked: Boolean, forced: Forced?): Int = when (forced) {
    Forced.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    Forced.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    null -> if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
}

private fun PlayerSource.uri(): Uri = when (this) {
    is PlayerSource.Path -> Uri.fromFile(File(path))
    is PlayerSource.Content -> uri
}
