package dev.kanorin.gallery.index

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.data.MetaEntity
import dev.kanorin.gallery.logic.DateTaken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the `media` table in step with the phone. Two halves with separate locks, so a new
 * photo never waits for a walk: [syncMediaStore] (cheap, on every change) and [fullScan]
 * (the storage walk, for files MediaStore misses). The UI only ever reads Room.
 */
class MediaIndex(
    private val db: GalleryDb,
    private val source: MediaStoreSource,
    private val walker: FsWalker,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    clock: () -> Long = SystemClock::elapsedRealtime,
    /** The Recycle bin's purge, on the index's schedule (true = cold start). */
    purge: suspend (coldStart: Boolean) -> Unit = {},
) {
    private val media = db.mediaDao()
    private val meta = db.metaDao()
    private val storeLock = Mutex()
    private val scanLock = Mutex()
    private var running = 0 // guarded by `this`
    private var storeEnded = false // guarded by `this`
    private var scanEnded = false // guarded by `this`
    // Test seams: run between a batch's read and its write.
    internal var beforeStoreCommit: suspend () -> Unit = {}
    internal var beforeFolderRefresh: suspend () -> Unit = {}

    private val _syncing = MutableStateFlow(false)
    private val _firstSync = MutableStateFlow(false)

    private val scheduler = IndexScheduler(
        scope = scope,
        dispatcher = io,
        changes = source.changes(),
        syncMediaStore = ::syncMediaStore,
        fullScan = ::fullScan,
        clock = clock,
        onError = { message, e -> Log.w(TAG, message, e) },
        purge = purge,
    )

    /** True while either half is running. */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** True from [start] until this process's first MediaStore read and first walk have both ended (done or failed). */
    val firstSync: StateFlow<Boolean> = _firstSync.asStateFlow()

    val started: Boolean get() = scheduler.isStarted

    /** Cold start: MediaStore, then one walk; afterwards changes sync MediaStore only. Safe to call again. */
    fun start() {
        synchronized(this) { if (!scheduler.isStarted && !(storeEnded && scanEnded)) _firstSync.value = true }
        scheduler.start()
    }

    /** Syncs MediaStore, walks again if the last walk is 5+ min old (or failed), and lets the bin purge if a day has passed. */
    fun onAppResumed() = scheduler.onAppResumed()

    /** Both halves, in order. */
    suspend fun sync() {
        syncMediaStore()
        fullScan()
    }

    suspend fun syncMediaStore(): Unit = withContext(io) { storeLock.withLock { tracked(store = true) { readMediaStore() } } }

    suspend fun fullScan(): Unit = withContext(io) { scanLock.withLock { tracked(store = false) { scanStorage() } } }

    private suspend fun readMediaStore() {
        val roots = walker.roots
        val hasNomedia = nomediaLookup()
        // Generations before rows: anything changing mid-read is read again next time.
        val snapshot = source.currentGenerations()
        for (volume in snapshot) {
            for (batch in source.changedSince(volume.volume, startGeneration(volume)).chunked(BATCH)) {
                // One transaction per batch, so the grid fills while this runs.
                val rows = batch.filterNot { inBin(it.path) || it.name.startsWith(StorageLayout.TEMP_PREFIX) }
                    .map { finishStoreRow(it, roots, hasNomedia) }
                beforeStoreCommit()
                media.replaceStoreRows(rows)
            }
        }
        // Rows whose id left MediaStore. No File.exists here: 100k stats per sync is seconds.
        val live = source.allIds()
        media.deleteByStoreIds(media.storeIds().filterNot { it in live })
        // A card pulled (or a db rebuilt) mid-read gets no generation, so it's read in full when it's back.
        val now = source.currentGenerations().associateBy { it.volume }
        commitGenerations(snapshot.filter { now[it.volume]?.version == it.version })
    }

    private suspend fun scanStorage() {
        val roots = walker.roots
        val hasNomedia = nomediaLookup()
        // Snapshots before the walk; rows MediaStore adds meanwhile are protected by the walker-only writes.
        val storePaths = media.storePaths().toHashSet()
        val known = media.walkerFacts().associateBy { it.path }
        val seen = HashSet<String>(known.size * 2)
        val fresh = ArrayList<MediaEntity>(BATCH)
        val changed = ArrayList<MediaEntity>(BATCH)
        for (file in walker.walk()) {
            val path = file.path
            if (path in storePaths) continue
            val old = known[path]
            if (old != null) {
                seen += path
                if (file.length() == old.size && file.lastModified() == old.dateModified) continue
            }
            val row = walkerRow(file, roots, hasNomedia) ?: continue
            if (old == null) fresh += row else changed += row
            if (fresh.size == BATCH) media.insertWalkerRows(fresh.drain())
            if (changed.size == BATCH) media.replaceWalkerRows(changed.drain())
        }
        if (fresh.isNotEmpty()) media.insertWalkerRows(fresh.drain())
        if (changed.isNotEmpty()) media.replaceWalkerRows(changed.drain())
        // Gone = not seen by this walk and not on disk: a folder the walk couldn't list keeps its rows. Checks the unseen only.
        val listed = HashMap<String, Set<String>?>() // one listing per parent, this pass only
        media.deleteWalkerRows(known.keys.filter { it !in seen && !onDisk(it, listed) })
        // A .nomedia or dot-folder added or removed anywhere since a row was written (or during this walk: a fresh lookup).
        val nomediaNow = nomediaLookup()
        for ((folder, rows) in media.folderHiddenFlags().groupBy { it.folderPath }) {
            val hidden = isHidden(folder, roots, nomediaNow)
            beforeFolderRefresh()
            // The DAO keeps a Hide / Unhide the user made meanwhile; rows may differ from the rule only through one.
            if (rows.any { it.hidden != hidden }) media.refreshFolderHidden(folder, hidden)
        }
    }

    // Shared storage ignores case, so exists() is true for a case-renamed old name: its exact name must be listed.
    private fun onDisk(path: String, listed: MutableMap<String, Set<String>?>): Boolean {
        val file = File(path)
        val parent = file.parent ?: return file.exists()
        val names = listed.getOrPut(parent) { File(parent).list()?.toHashSet() }
        return names?.contains(file.name) ?: file.exists()
    }

    private suspend fun commitGenerations(volumes: List<VolumeGeneration>) = db.withTransaction {
        meta.deleteWithPrefix(META_PREFIX)
        meta.put(volumes.flatMap {
            listOf(MetaEntity(versionKey(it.volume), it.version), MetaEntity(generationKey(it.volume), it.generation.toString()))
        })
    }

    private suspend fun startGeneration(volume: VolumeGeneration): Long {
        val version = meta.get(versionKey(volume.volume))
        val stored = meta.get(generationKey(volume.volume))?.toLongOrNull()
        // A new version means MediaStore rebuilt its db: generations and ids restarted.
        return if (version != volume.version || stored == null || stored > volume.generation) 0 else stored
    }

    private fun finishStoreRow(
        row: MediaEntity,
        roots: List<File>,
        hasNomedia: (String) -> Boolean,
    ) = row.copy(
        dateTaken = DateTaken.resolve(row.dateTaken, { MediaProbe.exifDateTaken(row.path, row.mimeType) }, row.dateModified),
        hidden = isHidden(row.folderPath, roots, hasNomedia),
    )

    private fun walkerRow(file: File, roots: List<File>, hasNomedia: (String) -> Boolean): MediaEntity? =
        WalkerRows.build(file, roots, hasNomedia)

    // Rule only: the DAO applies the user's Hide / Unhide inside each write transaction.
    private fun isHidden(folder: String, roots: List<File>, hasNomedia: (String) -> Boolean): Boolean =
        WalkerRows.isHidden(folder, roots, hasNomedia)

    private fun nomediaLookup(): (String) -> Boolean = WalkerRows.nomediaLookup()

    // The walker never enters the bin; MediaStore may still list it.
    private fun inBin(path: String) = StorageLayout.underBin(path)

    private suspend fun tracked(store: Boolean, block: suspend () -> Unit) {
        setRunning(+1, store)
        try {
            block()
        } finally {
            setRunning(-1, store)
        }
    }

    private fun setRunning(delta: Int, store: Boolean) = synchronized(this) {
        running += delta
        if (delta < 0) {
            if (store) storeEnded = true else scanEnded = true
            if (storeEnded && scanEnded) _firstSync.value = false
        }
        _syncing.value = running > 0
    }

    private fun <T> ArrayList<T>.drain(): List<T> = toList().also { clear() }

    companion object {
        private const val BATCH = 500
        private const val TAG = "MediaIndex"
        private const val META_PREFIX = "mediastore."

        internal fun generationKey(volume: String) = "${META_PREFIX}generation.$volume"
        internal fun versionKey(volume: String) = "${META_PREFIX}version.$volume"
    }
}
