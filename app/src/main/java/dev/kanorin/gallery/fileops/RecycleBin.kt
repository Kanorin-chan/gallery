package dev.kanorin.gallery.fileops

import android.os.Environment
import android.util.Log
import androidx.room.withTransaction
import dev.kanorin.gallery.data.BinEntity
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MetaEntity
import dev.kanorin.gallery.index.FsWalker
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.index.WalkerRows
import dev.kanorin.gallery.logic.BinPolicy
import dev.kanorin.gallery.logic.FileNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The Recycle bin (spec 5.5): a `.gallery-bin` holding a `.nomedia` at each storage root, so binning is always a
 * same-volume rename. A binned file always has its row: the row goes in before the file does and leaves only after
 * the file has. Each also has a `<uuid>.json` sidecar (original path, time binned), so the bin outlives the app's
 * database. [purgeExpired] settles rows whose file is gone and adopts files with no row. Runs under [FileOps]' lock.
 */
class RecycleBin(
    private val db: GalleryDb,
    private val ops: FileOps,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deleter: (File) -> Boolean = File::delete,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    // Test seams: each per-file index write, and each rename into or out of a bin.
    internal var beforeCommit: suspend () -> Unit = {}
    internal var renamer: (File, File) -> Boolean = { from, to -> !to.exists() && from.renameTo(to) }

    private class Failure(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** [at] = where the file now is (null once deleted); [lagged] = its rows couldn't follow yet. */
    private class Done(val at: String?, val changed: List<String>, val lagged: Boolean = false)

    /** UNKNOWN = not a bin file, its card is out or its bin can't be listed: such a row is never acted on. */
    private enum class Where { HERE, GONE, UNKNOWN }

    private val bins = db.binDao()
    private val media = db.mediaDao()
    private val pathDao = db.pathDao()
    private val meta = db.metaDao()

    /** The bin screen's items, newest first. A row whose file is gone from a mounted card is never a broken tile. */
    fun items(): Flow<List<BinEntity>> = bins.items().map { rows ->
        val roots = ops.roots()
        // A card that's out keeps its rows on screen: Restore says why it can't.
        rows.filter { StorageLayout.rootOf(it.binPath, roots) == null || (StorageLayout.isBinFile(it.binPath, roots) && File(it.binPath).isFile) }
    }.flowOn(io)

    /** Each file into its own volume's bin; [OpResult.landed] holds the bin paths, for UNDO. [pendingErase] = held, not shown. */
    suspend fun moveToBin(paths: List<String>, pendingErase: Boolean = false, onProgress: (Int, Int) -> Unit = FileOps.NO_PROGRESS): OpResult =
        each(paths, onProgress) { path, roots -> bin(File(path), roots, pendingErase) }

    /** UNDO's first step: held deletes become ordinary bin items with a fresh 30 days, so a failed restore can't be erased. */
    suspend fun unhold(binPaths: List<String>) {
        ops.exclusive {
            val now = clock()
            commit { binPaths.chunked(MAX_ARGS).forEach { bins.unhold(it, now) } }
        }
    }

    /** Back where each came from, with a " (n)" suffix on a clash and its folder made again if it's gone. */
    suspend fun restore(binPaths: List<String>, onProgress: (Int, Int) -> Unit = FileOps.NO_PROGRESS): OpResult =
        each(binPaths, onProgress, ::unbin)

    /** Binned files gone for good, with their rows; a row whose file is already gone is settled instead. */
    suspend fun deleteForever(binPaths: List<String>, onProgress: (Int, Int) -> Unit = FileOps.NO_PROGRESS): OpResult =
        each(binPaths, onProgress, ::erase)

    /** Straight off the phone, not via the bin. */
    suspend fun deletePermanently(paths: List<String>, onProgress: (Int, Int) -> Unit = FileOps.NO_PROGRESS): OpResult =
        each(paths, onProgress) { path, roots -> eraseFile(File(path), roots) }

    /** The index's schedule: every cold start; on resume once [BinPolicy.PURGE_GAP_MS] has passed. */
    suspend fun scheduledPurge(coldStart: Boolean) {
        val now = clock()
        // A resume can land inside a held delete's UNDO window: only a cold start erases held rows.
        if (coldStart || BinPolicy.purgeDue(meta.get(LAST_PURGE_KEY)?.toLongOrNull(), now)) purgeExpired(now, eraseHeld = coldStart)
    }

    /** Rows whose file is gone, settled; nothing else (the bin screen runs this as it opens). */
    suspend fun settleGhosts() {
        ops.exclusive {
            val roots = ops.roots()
            val listable = listable()
            for (entry in bins.all()) {
                currentCoroutineContext().ensureActive()
                if (where(entry, roots, listable) == Where.GONE) housekeeping(entry.binPath) { settle(entry) }
            }
        }
    }

    /**
     * Adopts bin files with no row, settles rows whose file is gone, deletes what has been binned too long (and, with
     * [eraseHeld], held "Delete permanently" ones whatever their age), then sidecars left alone. A card that's out is left as it is.
     */
    suspend fun purgeExpired(now: Long, eraseHeld: Boolean = true) {
        ops.exclusive {
            val roots = ops.roots()
            adopt(roots, now)
            val listable = listable()
            val changed = ArrayList<String>()
            for (entry in bins.all()) {
                currentCoroutineContext().ensureActive()
                when (where(entry, roots, listable)) {
                    Where.GONE -> housekeeping(entry.binPath) { settle(entry) }
                    Where.HERE -> if ((eraseHeld && entry.pendingErase) || BinPolicy.isExpired(entry.deletedAt, now)) {
                        housekeeping(entry.binPath) { changed += erase(entry.binPath, roots).changed }
                    }
                    Where.UNKNOWN -> Unit
                }
            }
            dropLoneSidecars(roots)
            meta.put(listOf(MetaEntity(LAST_PURGE_KEY, now.toString())))
            ops.scan(changed)
        }
    }

    private suspend fun bin(src: File, roots: List<File>, pendingErase: Boolean): Done {
        if (FileOps.blocked(src)) throw Failure(FileOps.BLOCKED)
        if (!src.isFile) throw Failure(NOT_FOUND)
        if (StorageLayout.underBin(src.path)) throw Failure("Already in the Recycle bin")
        val root = StorageLayout.rootOf(src.path, roots) ?: throw Failure("Not on a storage volume the app knows")
        val ext = FileNames.extension(src.name)
        // Its bin name would be its own sidecar's.
        if (ext.equals(SIDECAR_EXT, ignoreCase = true)) throw Failure("Not a photo or video")
        val target = File(binDir(root), UUID.randomUUID().toString() + if (ext.isEmpty()) "" else ".$ext")
        val row = media.byPath(src.path)
        val mime = row?.mimeType ?: FsWalker.mimeTypeOf(src.name) ?: UNKNOWN_MIME
        val entry = BinEntity(
            target.path, src.path, clock(), src.length(), mime, row?.isVideo ?: mime.startsWith("video/"), row?.durationMs ?: 0,
            pendingErase,
        )
        val note = sidecarOf(target)
        // Before anything moves: if the database is ever lost, the bin still knows where each file came from.
        writeSidecar(note, entry)
        // Row first: a crash before the rename leaves a row without a file (purge settles it), never a lost file.
        try {
            commit {
                bins.insert(entry)
                media.deleteByPaths(listOf(src.path))
                pathDao.moveFavorite(src.path, target.path)
                pathDao.moveVideoProgress(src.path, target.path)
            }
        } catch (e: Failure) {
            note.delete()
            throw e
        }
        if (moved(src, target)) return Done(target.path, listOf(src.path, target.path))
        try {
            commit {
                bins.delete(target.path)
                row?.let {
                    media.upsert(listOf(it))
                    media.applyOverrides(listOf(it.folderPath))
                }
                pathDao.moveFavorite(target.path, src.path)
                pathDao.moveVideoProgress(target.path, src.path)
            }
        } catch (e: Failure) {
            Log.w(TAG, "${src.path} stayed put; its rows wait for the next purge", e)
        }
        note.delete()
        throw Failure("Couldn't move it to the Recycle bin")
    }

    private suspend fun unbin(binPath: String, roots: List<File>): Done {
        val entry = bins.byBinPath(binPath) ?: throw Failure(NOT_IN_BIN)
        val binned = File(entry.binPath)
        val origin = File(entry.originalPath)
        // Its card is out: the binned file and the way back are both on it.
        if (StorageLayout.rootOf(origin.path, roots) == null || StorageLayout.rootOf(binned.path, roots) == null) throw Failure(NO_CARD)
        if (!StorageLayout.isBinFile(binned.path, roots)) throw Failure(NOT_IN_BIN)
        if (FileOps.blocked(origin)) throw Failure(FileOps.BLOCKED)
        if (!binned.isFile) throw Failure(NOT_FOUND)
        val parent = origin.parentFile ?: throw Failure(NOT_FOUND)
        if (!parent.isDirectory && !parent.mkdirs()) throw Failure("Couldn't make its folder again")
        val target = ops.freeTarget(parent, origin.name)
        if (!moved(binned, target)) throw Failure("Couldn't restore it")
        // In the grid at once, with the facts the walk would read; MediaStore's own row replaces it after a sync.
        val row = WalkerRows.build(target, roots)
        try {
            commit {
                bins.delete(entry.binPath)
                pathDao.moveFavorite(entry.binPath, target.path)
                pathDao.moveVideoProgress(entry.binPath, target.path)
                if (row != null) {
                    media.upsert(listOf(row))
                    media.applyOverrides(listOf(row.folderPath))
                }
            }
        } catch (e: Failure) {
            // Back to where its row still says it is.
            if (!binned.exists() && moved(target, binned)) throw e
            Log.w(TAG, "${target.path} is out of the bin but its row stays until the next purge", e)
            return Done(target.path, listOf(binned.path, target.path), lagged = true)
        }
        sidecarOf(binned).delete()
        return Done(target.path, listOf(binned.path, target.path))
    }

    private suspend fun erase(binPath: String, roots: List<File>): Done {
        val entry = bins.byBinPath(binPath) ?: throw Failure(NOT_IN_BIN)
        val file = File(entry.binPath)
        if (StorageLayout.rootOf(file.path, roots) == null) throw Failure(NO_CARD)
        // Only ever a file directly in a mounted root's bin.
        if (!StorageLayout.isBinFile(file.path, roots)) throw Failure(NOT_IN_BIN)
        if (!file.exists()) {
            // A missing file is trusted only from a bin folder that can be listed.
            if (file.parentFile?.list() == null) throw Failure("Couldn't read the Recycle bin")
            // Already gone: its favourite goes back with the file, not into the void.
            settle(entry)
            return Done(null, emptyList())
        }
        delete(file)
        sidecarOf(file).delete()
        return Done(null, listOf(file.path), lagged = !forget(file.path) { bins.delete(entry.binPath) })
    }

    private suspend fun eraseFile(src: File, roots: List<File>): Done {
        if (FileOps.blocked(src)) throw Failure(FileOps.BLOCKED)
        // Binned files go through deleteForever, which keeps the bin's rows in step.
        if (StorageLayout.underBin(src.path)) throw Failure("In the Recycle bin")
        // Missing from a card that's out isn't gone: it's still on the card.
        if (!src.exists() && StorageLayout.rootOf(src.path, roots) == null) throw Failure(NO_CARD)
        delete(src)
        return Done(null, listOf(src.path), lagged = !forget(src.path) { media.deleteByPaths(listOf(src.path)) })
    }

    /** Deletes [file] if it's there; never a folder. */
    private fun delete(file: File) {
        if (!file.exists()) return
        if (!file.isFile) throw Failure("Not a file")
        // false also means "already gone".
        if (!deleter(file) && file.exists()) throw Failure("Couldn't delete it")
    }

    /** A deleted file's rows; false = they stay until the index catches up. */
    private suspend fun forget(path: String, alsoDrop: suspend () -> Unit): Boolean = try {
        commit {
            alsoDrop()
            pathDao.deleteFavorite(path)
            pathDao.deleteVideoProgress(path)
        }
        true
    } catch (e: Failure) {
        Log.w(TAG, "rows of deleted $path linger", e)
        false
    }

    // The row went in but the file never did (a crash before the rename), or left without it (a failed restore write).
    private suspend fun settle(entry: BinEntity) {
        val back = File(entry.originalPath).exists()
        commit {
            bins.delete(entry.binPath)
            if (back) {
                pathDao.moveFavorite(entry.binPath, entry.originalPath)
                pathDao.moveVideoProgress(entry.binPath, entry.originalPath)
            } else {
                pathDao.deleteFavorite(entry.binPath)
                pathDao.deleteVideoProgress(entry.binPath)
            }
        }
        sidecarOf(File(entry.binPath)).delete()
    }

    // Files in a bin with no row (the app's data was wiped): back on screen, never deleted unseen.
    private suspend fun adopt(roots: List<File>, now: Long) {
        val known = bins.all().mapTo(HashSet()) { it.binPath }
        for (root in roots) {
            val dir = File(root, StorageLayout.BIN_DIR_NAME)
            for (name in dir.list() ?: continue) {
                if (name == NOMEDIA || isSidecar(name)) continue
                val file = File(dir, name)
                if (file.path in known || !file.isFile) continue
                val note = readSidecar(sidecarOf(file))
                val mime = FsWalker.mimeTypeOf(name) ?: UNKNOWN_MIME
                // No sidecar: a fresh 30 days from now, never the file's mtime (a rename keeps the photo's own).
                val original = note?.first ?: File(File(File(root, Environment.DIRECTORY_PICTURES), RESTORED_DIR), name).path
                val entry = BinEntity(file.path, original, note?.second ?: now, file.length(), mime, mime.startsWith("video/"), 0)
                housekeeping(file.path) {
                    if (note == null) writeSidecar(sidecarOf(file), entry)
                    commit { bins.insert(entry) }
                }
            }
        }
    }

    private fun dropLoneSidecars(roots: List<File>) {
        for (root in roots) {
            val dir = File(root, StorageLayout.BIN_DIR_NAME)
            val names = dir.list() ?: continue
            val held = names.filterNot { it == NOMEDIA || isSidecar(it) }.mapTo(HashSet()) { File(it).nameWithoutExtension }
            names.filter { isSidecar(it) && File(it).nameWithoutExtension !in held }.forEach { File(dir, it).delete() }
        }
    }

    private fun where(entry: BinEntity, roots: List<File>, listable: (File) -> Boolean): Where {
        if (!StorageLayout.isBinFile(entry.binPath, roots)) return Where.UNKNOWN
        val file = File(entry.binPath)
        if (!listable(file.parentFile!!)) return Where.UNKNOWN
        return if (file.exists()) Where.HERE else Where.GONE
    }

    // A bin folder that can't be listed says nothing about its files; one listing per folder per pass.
    private fun listable(): (File) -> Boolean {
        val seen = HashMap<File, Boolean>()
        return { dir -> seen.getOrPut(dir) { dir.list() != null } }
    }

    /** Every item under the lock; cancellation lands between files only, and what changed is scanned either way. */
    private suspend fun each(items: List<String>, onProgress: (Int, Int) -> Unit, body: suspend (String, List<File>) -> Done): OpResult =
        ops.exclusive {
            val roots = ops.roots()
            val done = ArrayList<String>(items.size)
            val landed = ArrayList<String>(items.size)
            val failed = ArrayList<Pair<String, String>>()
            val changed = ArrayList<String>()
            var lagged = 0
            try {
                for ((i, item) in items.withIndex()) {
                    onProgress(i + 1, items.size)
                    currentCoroutineContext().ensureActive()
                    try {
                        val d = withContext(NonCancellable) { body(item, roots) }
                        done += item
                        d.at?.let { landed += it }
                        changed += d.changed
                        if (d.lagged) lagged++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += item to (e.message ?: e.javaClass.simpleName)
                    }
                }
            } finally {
                ops.scan(changed)
            }
            OpResult(done, failed, lagged, landed)
        }

    /** One purge / settle step; a failure is logged and the pass goes on. */
    private suspend fun housekeeping(what: String, block: suspend () -> Unit) = withContext(NonCancellable) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "bin housekeeping skipped $what", e)
        }
    }

    // A false from rename can still mean it moved: trust the files, not the return value.
    private fun moved(from: File, to: File): Boolean = renamer(from, to) || (to.exists() && !from.exists())

    private fun binDir(root: File): File {
        val dir = File(root, StorageLayout.BIN_DIR_NAME)
        val nomedia = File(dir, NOMEDIA)
        // Other apps skip a .nomedia folder, so a binned file leaves every gallery, not just this one.
        if ((!dir.isDirectory && !dir.mkdirs()) || (!nomedia.exists() && !nomedia.createNewFile())) {
            throw Failure("Couldn't open the Recycle bin")
        }
        return dir
    }

    private fun sidecarOf(binned: File) = File(binned.parentFile, binned.nameWithoutExtension + "." + SIDECAR_EXT)

    private fun isSidecar(name: String) = FileNames.extension(name).equals(SIDECAR_EXT, ignoreCase = true)

    private fun writeSidecar(note: File, entry: BinEntity) {
        try {
            note.writeText(JSONObject().put(KEY_ORIGINAL, entry.originalPath).put(KEY_DELETED_AT, entry.deletedAt).toString())
        } catch (e: Exception) {
            note.delete()
            throw Failure("Couldn't open the Recycle bin", e)
        }
    }

    /** (original path, deletedAt ms), or null when it's missing or unreadable. */
    private fun readSidecar(note: File): Pair<String, Long>? = try {
        JSONObject(note.readText()).let { it.getString(KEY_ORIGINAL) to it.getLong(KEY_DELETED_AT) }
            .takeIf { File(it.first).isAbsolute }
    } catch (e: Exception) {
        null
    }

    private suspend fun commit(block: suspend () -> Unit) {
        try {
            beforeCommit()
            db.withTransaction { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Failure(FileOps.INDEX_FAILED, e)
        }
    }

    companion object {
        /** A restore's reason when the file's card isn't mounted. */
        const val NO_CARD = "The SD card isn't available"

        /** Every failure of [result] was its SD card being out. */
        fun cardOut(result: OpResult): Boolean = result.failed.all { it.second == NO_CARD }

        private const val NOT_FOUND = "File not found"
        private const val NOT_IN_BIN = "Not in the Recycle bin"
        private const val NOMEDIA = ".nomedia"
        private const val SIDECAR_EXT = "json"
        private const val KEY_ORIGINAL = "originalPath"
        private const val KEY_DELETED_AT = "deletedAt"
        /** An adopted file with no sidecar goes back to `<root>/Pictures/Restored/`. */
        private const val RESTORED_DIR = "Restored"
        private const val UNKNOWN_MIME = "application/octet-stream"
        /** Under SQLite's 999 bind-variable limit. */
        private const val MAX_ARGS = 500
        private const val LAST_PURGE_KEY = "bin.lastPurge"
        private const val TAG = "RecycleBin"
    }
}
