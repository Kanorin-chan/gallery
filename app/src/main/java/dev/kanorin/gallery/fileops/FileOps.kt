package dev.kanorin.gallery.fileops

import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.room.withTransaction
import dev.kanorin.gallery.data.CreatedFolderEntity
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.logic.FileNames
import dev.kanorin.gallery.logic.FolderNameValidator
import dev.kanorin.gallery.logic.HiddenRules
import dev.kanorin.gallery.logic.NameError
import dev.kanorin.gallery.logic.RenamePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/**
 * [done] = source paths that made it; [failed] = source path to the reason.
 * [indexLag] = done files whose index row didn't commit: shown late, once the next sync re-learns them.
 * [landed] = where the bin's moves put each [done] file, same order (UNDO restores exactly these).
 */
data class OpResult(
    val done: List<String>,
    val failed: List<Pair<String, String>>,
    val indexLag: Int = 0,
    val landed: List<String> = emptyList(),
)

/**
 * The user's own New folder, Move, Copy and Rename (spec 5.2, 5.3): the change on disk first, then every table that
 * stores a path in one transaction per batch, then [scanner] on the old and new paths. Favourites and video progress
 * (all the index can't re-learn) follow each file the moment it lands. Moved rows drop their MediaStore id; the next
 * sync upserts MediaStore's row on the same path. A source is deleted only after its copy is verified, and nothing
 * existing is ever overwritten.
 */
class FileOps(
    private val db: GalleryDb,
    private val scanner: (List<String>) -> Unit,
    internal val roots: () -> List<File> = { emptyList() },
    /** [RenamePolicy]'s root: internal storage. */
    private val storageRoot: String = "",
    private val copier: (from: File, to: File) -> Unit = ::copyBytes,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val deleteSource: (File) -> Boolean = File::delete,
    /** Bytes free on the volume holding a folder; null = can't tell. */
    private val freeSpace: (File) -> Long? = ::freeBytes,
    /** Makes a folder's new entries durable before a source is deleted; production passes [fsyncDir]. */
    private val syncDir: (File) -> Unit = {},
) {
    // Test seams: a batch's (or a rename's) index write, one file's favourite write and the op's lookups, just before they run.
    internal var beforeBatchCommit: suspend () -> Unit = {}
    internal var beforeFileCommit: suspend () -> Unit = {}
    internal var beforeQuery: suspend (name: String) -> Unit = {}
    internal var dirRenamer: (File, File) -> Boolean = { from, to -> from.renameTo(to) }

    class NameException(val error: NameError) : IOException(error.message)

    /** A case-only rename that could go neither forward nor back: the folder now sits at [at]. */
    class RenameStuck(val at: File) : IOException("Rename got stuck — the folder is at ${at.name}")

    /** [lagging] = the file failed, but its favourite / progress still names the copy. */
    private class OpFailure(override val message: String, val lagging: Boolean = false) : IOException(message)

    /** [renamed] = a same-volume rename (undoable); [carried] = its favourite / progress already follow it. */
    private class Placed(val from: String, val to: File, val size: Long, val modified: Long, val renamed: Boolean, val carried: Boolean)

    private class Recorded(val undone: List<String>, val lagged: List<String>)

    private inner class Destination(
        val dir: File,
        val known: List<File>,
        val root: File?,
        val hidden: Boolean,
        private val taken: MutableSet<String>,
    ) {
        val volume: String? = root?.let(StorageLayout::volumeNameOf)

        fun isTaken(name: String) = isTaken(dir, name, taken)

        fun claim(file: File) {
            taken += file.path.lowercase()
        }
    }

    private val media = db.mediaDao()
    private val pathDao = db.pathDao()

    // One op at a time: the names one batch picks can't race another's.
    private val lock = Mutex()

    /** The bin's ops run under this lock too: no name they pick can race a move, copy or rename. */
    internal suspend fun <T> exclusive(block: suspend () -> T): T = withContext(io) { lock.withLock { block() } }

    /** [name] in [dir], or its first free " (n)" variant, by the move's rule. */
    internal suspend fun freeTarget(dir: File, name: String): File {
        val taken = pathDao.mediaPathsIn(dir.path).mapTo(HashSet()) { it.lowercase() }
        return File(dir, FileNames.free(name) { isTaken(dir, it, taken) })
    }

    // A stale row (file gone, index behind) counts too, so its favourite can't land on a new file.
    private fun isTaken(dir: File, name: String, taken: Set<String>) = File(dir, name).let { it.exists() || it.path.lowercase() in taken }

    /** [RenamePolicy] over every mounted root ([mounted], read once by a caller asking for many folders). */
    fun canRename(path: String, mounted: List<File> = roots()): Boolean = RenamePolicy.canRename(path, storageRoot, mounted.map { it.path })

    /** Names in [parent] on disk plus folders this app made there (still on disk), without [except]. */
    suspend fun siblingNames(parent: File, except: File? = null): Set<String> = withContext(io) {
        val created = media.liveCreatedFolders(pathDao.createdFolderPaths()).map(::File).filter { it.parentFile == parent }.map { it.name }
        (parent.list().orEmpty().asList() + created).filterNot { it == except?.name }.toSet()
    }

    /** A real, empty directory, recorded so the Folders view shows it before it holds anything. */
    suspend fun createFolder(parent: File, name: String): Result<File> = withContext(io) {
        lock.withLock {
            attempt {
                if (blocked(parent)) throw OpFailure(BLOCKED)
                val clean = name.trim()
                FolderNameValidator.validate(clean, siblingNames(parent))?.let { throw NameException(it) }
                val dir = File(parent, clean)
                if (!dir.mkdirs()) throw OpFailure("Couldn't create the folder")
                withContext(NonCancellable) {
                    try {
                        pathDao.addCreatedFolder(CreatedFolderEntity(dir.path))
                    } catch (e: Exception) {
                        dir.delete()
                        throw e
                    }
                }
                dir
            }
        }
    }

    /** Same volume: a rename. Otherwise (or if the rename fails) a verified copy, then the source goes. */
    suspend fun move(paths: List<String>, dest: File, onProgress: (Int, Int) -> Unit = NO_PROGRESS): OpResult =
        transfer(paths, dest, move = true, onProgress)

    suspend fun copy(paths: List<String>, dest: File, onProgress: (Int, Int) -> Unit = NO_PROGRESS): OpResult =
        transfer(paths, dest, move = false, onProgress)

    /** Renames the directory itself; every path under it moves in every table. Only where [RenamePolicy] allows. */
    suspend fun renameFolder(path: String, newName: String): Result<File> = withContext(io) {
        lock.withLock {
            attempt {
                val dir = File(path)
                val parent = dir.parentFile
                if (parent == null || blocked(dir) || !canRename(path)) throw OpFailure("This folder can't be renamed")
                if (!dir.isDirectory) throw OpFailure("Folder not found")
                val name = newName.trim()
                FolderNameValidator.validate(name, siblingNames(parent, except = dir))?.let { throw NameException(it) }
                if (name == dir.name) return@attempt dir
                val target = File(parent, name)
                val caseOnly = name.equals(dir.name, ignoreCase = true)
                if (!caseOnly && target.exists()) throw NameException(NameError.DUPLICATE)
                val folders = pathDao.foldersUnder(dir.path, dir.path + File.separator)
                // Read before the rename: a prune between the rename and the rewrite can't lose them (put back after).
                val created = pathDao.createdFolderPaths().filter { it == dir.path || it.startsWith(dir.path + File.separator) }
                withContext(NonCancellable) {
                    try {
                        if (!renameDir(dir, target, caseOnly)) throw OpFailure("Couldn't rename the folder")
                    } catch (e: RenameStuck) {
                        follow(dir, e.at, folders, created)
                        throw e
                    }
                    try {
                        rewriteFolder(dir, target, folders, created)
                    } catch (e: Exception) {
                        // Back to the old name, so disk and index still agree.
                        val back = try {
                            renameDir(target, dir, caseOnly)
                        } catch (stuck: RenameStuck) {
                            follow(dir, stuck.at, folders, created)
                            throw stuck
                        }
                        if (!back) {
                            // It stays under the new name: the index goes there instead.
                            follow(dir, target, folders, created)
                            throw RenameStuck(target)
                        }
                        throw e
                    }
                    scan(listOf(dir.path, target.path))
                }
                target
            }
        }
    }

    private suspend fun transfer(sources: List<String>, dest: File, move: Boolean, onProgress: (Int, Int) -> Unit): OpResult =
        withContext(io) {
            lock.withLock {
                fun refused(why: String) = OpResult(emptyList(), sources.map { it to why })
                if (blocked(dest)) return@withLock refused(BLOCKED)
                if (!dest.isDirectory) return@withLock refused("Folder not found")
                // Nothing moved yet: any failure here refuses the whole op, never crashes the app's scope.
                val to = try {
                    recoverTemps(dest)
                    val known = roots()
                    val root = StorageLayout.rootOf(dest.path, known)
                    beforeQuery("mediaPathsIn")
                    val taken = pathDao.mediaPathsIn(dest.path).mapTo(HashSet()) { it.lowercase() }
                    Destination(dest, known, root, ruleHidden(dest.path, root), taken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't start a file op into ${dest.path}", e)
                    return@withLock refused(if (move) "Couldn't start the move" else "Couldn't start the copy")
                }
                val done = LinkedHashSet<String>(sources.size)
                val failed = ArrayList<Pair<String, String>>()
                val lagging = HashSet<String>()
                var laggedFailures = 0
                var n = 0
                val batch = ArrayList<Placed>(BATCH)
                suspend fun commit() {
                    val r = record(batch.drain(), to, move)
                    r.undone.forEach {
                        done -= it
                        failed += it to INDEX_FAILED
                    }
                    lagging += r.lagged
                }
                try {
                    for (chunk in sources.chunked(BATCH)) {
                        val tracked = if (move) trackedIn(chunk) else emptySet()
                        // Lookup failed: their favourites ride only on the batch commit.
                        if (tracked == null) lagging += chunk
                        for (path in chunk) {
                            onProgress(++n, sources.size)
                            ensureActive() // between files only: a file is never left half done
                            try {
                                batch += place(File(path), to, move, tracked?.contains(path) == true)
                                done += path
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed += path to (e.message ?: e.javaClass.simpleName)
                                if ((e as? OpFailure)?.lagging == true) laggedFailures++
                            }
                        }
                        commit()
                    }
                } finally {
                    commit() // a cancelled op still records what it already moved
                }
                OpResult(done.toList(), failed, done.count { it in lagging } + laggedFailures)
            }
        }

    /** Which of [chunk] have a favourite or progress; null if the lookup failed. */
    private suspend fun trackedIn(chunk: List<String>): Set<String>? = try {
        beforeQuery("trackedPaths")
        pathDao.trackedPaths(chunk).toHashSet()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "favourite lookup failed; these files' favourites follow with the batch", e)
        null
    }

    /** Puts [src] into [to]; throws with the source, its favourite and the destination unchanged. */
    private suspend fun place(src: File, to: Destination, move: Boolean, tracked: Boolean): Placed {
        if (blocked(src)) throw OpFailure(BLOCKED)
        if (!src.isFile) throw OpFailure("File not found")
        if (move && src.parentFile == to.dir) return placed(src.path, src, renamed = false, carried = false)
        val target = File(to.dir, FileNames.free(src.name, to::isTaken))
        // rename(2) replaces an existing target, hence the exists check.
        if (move && sameVolume(src, to) && !target.exists() && src.renameTo(target)) {
            to.claim(target)
            if (!tracked || carry(src.path, target.path)) return placed(src.path, target, renamed = true, carried = tracked)
            // Its favourite couldn't follow: the file goes back to it.
            if (!src.exists() && target.renameTo(src)) throw OpFailure(INDEX_FAILED)
            Log.w(TAG, "${target.path} stays moved; the batch moves its favourite")
            return placed(src.path, target, renamed = true, carried = false)
        }
        val size = safeCopy(src, target)
        to.claim(target)
        if (!move) return placed(src.path, target, renamed = false, carried = false)
        // Before the source goes, so a crash from here leaves the favourite on a file that exists.
        if (tracked && !carry(src.path, target.path)) {
            // The copy goes only while the original provably exists; otherwise it's the only one.
            if (src.exists()) {
                target.delete()
                throw OpFailure(INDEX_FAILED)
            }
            return placed(src.path, target, renamed = false, carried = false)
        }
        syncDir(to.dir)
        // Right before the source goes: the copy must still be the one just verified.
        if (!target.isFile || target.length() != size) {
            val back = !tracked || carry(target.path, src.path)
            if (!back) Log.w(TAG, "favourite of ${src.path} stays on the changed copy ${target.path}")
            throw OpFailure("The copy changed before the original could go", lagging = !back)
        }
        // false also means "already gone": another app deleted it mid-copy, and the copy is now the only one.
        if (!deleteSource(src) && src.exists()) {
            val back = !tracked || carry(target.path, src.path)
            if (src.exists()) {
                // The original is still there: drop the copy so nothing is doubled (kept if its favourite couldn't go back).
                if (back) target.delete()
                throw OpFailure("Couldn't remove the original")
            }
            // It went while its favourite was going back: the copy is the only one, so the favourite returns to it.
            return placed(src.path, target, renamed = false, carried = tracked && (!back || carry(src.path, target.path)))
        }
        return placed(src.path, target, renamed = false, carried = tracked)
    }

    private fun placed(from: String, to: File, renamed: Boolean, carried: Boolean) =
        Placed(from, to, to.length(), to.lastModified(), renamed, carried)

    /** Favourite + video progress follow one file right away: the only rows the index can't re-learn. */
    private suspend fun carry(from: String, to: String): Boolean = withContext(NonCancellable) {
        try {
            beforeFileCommit()
            db.withTransaction {
                pathDao.moveFavorite(from, to)
                pathDao.moveVideoProgress(from, to)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "favourite / progress couldn't follow $from", e)
            false
        }
    }

    // Unknown roots: try the rename anyway; across mounts it fails (EXDEV) and the copy runs.
    private fun sameVolume(src: File, to: Destination): Boolean {
        val from = StorageLayout.rootOf(src.path, to.known)
        return from == null || to.root == null || from == to.root
    }

    /**
     * Copy to a temp name, check the size, then name it [target]; returns the verified size (bytes).
     * On failure the temp is gone and [target] was never made.
     */
    private fun safeCopy(src: File, target: File): Long {
        val dir = target.parentFile!!
        val size = src.length()
        // Leave the phone room to breathe: a gallery that fills storage breaks the camera and the database.
        val free = freeSpace(dir)
        if (free != null && free - size < FREE_SPACE_MARGIN) throw OpFailure(NO_SPACE)
        val tmp = tempIn(dir, FileNames.extension(target.name))
        try {
            copier(src, tmp)
            if (!tmp.isFile || tmp.length() != size) throw OpFailure("The copy didn't match the original")
            tmp.setLastModified(src.lastModified())
            if (target.exists() || !tmp.renameTo(target)) throw OpFailure("Couldn't name the copy")
            return size
        } catch (e: Exception) {
            // The source went mid-copy (another app; the open fd kept reading): the temp may be the only copy.
            // It stays, and the next op into this folder makes it a visible "Recovered-" file (Ruling Z).
            if (!src.exists() && tmp.exists()) {
                Log.w(TAG, "kept ${tmp.path}: its original vanished mid-copy", e)
                throw OpFailure("Couldn't finish — a copy was kept as ${tmp.name}")
            }
            tmp.delete()
            throw e as? OpFailure ?: OpFailure(e.message ?: "Copy failed")
        }
    }

    /** One batch's rows: one transaction, then the scanner. The files are already in place. */
    private suspend fun record(batch: List<Placed>, to: Destination, move: Boolean): Recorded {
        val changed = batch.filter { it.from != it.to.path }
        if (changed.isEmpty()) return Recorded(emptyList(), emptyList())
        return withContext(NonCancellable) {
            val recorded = try {
                beforeBatchCommit()
                db.withTransaction {
                    val old = media.byPaths(changed.map { it.from }).associateBy { it.path }
                    if (move) media.deleteByPaths(changed.map { it.from })
                    media.upsert(changed.mapNotNull { p -> old[p.from]?.let { relocated(it, p, to) } })
                    // Carried ones already moved (a no-op now); this catches a favourite added mid-op.
                    if (move) {
                        changed.forEach {
                            pathDao.moveFavorite(it.from, it.to.path)
                            pathDao.moveVideoProgress(it.from, it.to.path)
                        }
                    }
                    // The rule went in with the rows; the user's Hide / Unhide of the destination wins.
                    media.applyOverrides(listOf(to.dir.path))
                }
                Recorded(emptyList(), emptyList())
            } catch (e: Exception) {
                Log.w(TAG, "index update after a file op failed", e)
                // Ruling Y: a renamed file with nothing carried goes back to where its row still is; the rest lag.
                val undone = if (move) changed.filter(::undoRename).map { it.from } else emptyList()
                Recorded(undone, changed.map { it.from } - undone.toSet())
            }
            scan(if (move) changed.flatMap { listOf(it.from, it.to.path) } else changed.map { it.to.path })
            recorded
        }
    }

    private fun undoRename(p: Placed): Boolean {
        val from = File(p.from)
        return p.renamed && !p.carried && !from.exists() && p.to.isFile && p.to.renameTo(from)
    }

    // Ruling Z: an hour-old temp is a crash's leftover that may be the only copy (the dir fsync is best effort),
    // so it becomes a visible "Recovered-" file, never deleted. Files only: a stuck rename's temp is a folder.
    private fun recoverTemps(dir: File) {
        val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        val recovered = dir.list().orEmpty().filter { it.startsWith(StorageLayout.TEMP_PREFIX) }.map { File(dir, it) }
            .filter { it.isFile && it.lastModified() < cutoff }
            .mapNotNull { tmp ->
                val name = RECOVERED_PREFIX + tmp.name.removePrefix(StorageLayout.TEMP_PREFIX)
                val visible = File(dir, FileNames.free(name) { File(dir, it).exists() })
                if (tmp.renameTo(visible)) visible.path else null.also { Log.w(TAG, "couldn't recover ${tmp.path}") }
            }
        if (recovered.isNotEmpty()) scan(recovered)
    }

    // Short and fixed, extension kept: a name near the 255-byte limit still fits, and a recovered one still opens.
    private fun tempIn(dir: File, extension: String): File {
        val ext = if (extension.length in 1..MAX_TEMP_EXT) ".$extension" else ""
        while (true) {
            val f = File(dir, StorageLayout.TEMP_PREFIX + Random.nextInt().toUInt().toString(16).padStart(8, '0') + ext)
            if (!f.exists()) return f
        }
    }

    // size + mtime as the walk reads them, so it never re-probes the file.
    private fun relocated(row: MediaEntity, p: Placed, to: Destination) = row.copy(
        path = p.to.path, folderPath = to.dir.path, name = p.to.name, mediaStoreId = null, hidden = to.hidden,
        volume = to.volume ?: row.volume, size = p.size, dateModified = p.modified,
    )

    private suspend fun rewriteFolder(old: File, new: File, oldFolders: List<String>, created: List<String>) {
        val oldPrefix = old.path + File.separator
        val known = roots()
        // Every folder under the new name gets its rule again: a ".Name" hides them all.
        val hidden = (oldFolders.map { new.path + it.substring(old.path.length) } + new.path).distinct()
            .associateWith { ruleHidden(it, StorageLayout.rootOf(it, known)) }
        beforeBatchCommit()
        db.withTransaction {
            pathDao.renameMedia(old.path, oldPrefix, new.path)
            pathDao.renameFavorites(old.path, oldPrefix, new.path)
            pathDao.renameVideoProgress(old.path, oldPrefix, new.path)
            pathDao.renameFolderPrefs(old.path, oldPrefix, new.path)
            pathDao.renameCreatedFolders(old.path, oldPrefix, new.path)
            created.forEach { pathDao.addCreatedFolder(CreatedFolderEntity(new.path + it.substring(old.path.length))) }
            pathDao.renameBinOrigins(old.path, oldPrefix, new.path)
            hidden.forEach { (folder, rule) -> media.setFolderHidden(folder, rule) }
            media.applyOverrides(hidden.keys.toList())
        }
    }

    private fun renameDir(from: File, to: File, caseOnly: Boolean): Boolean {
        if (!caseOnly) return !to.exists() && dirRenamer(from, to)
        // A case-insensitive volume sees the new name as taken: go through a temp name.
        val parent = from.parentFile ?: return false
        val tmp = tempIn(parent, "")
        if (!dirRenamer(from, tmp)) return false
        if (dirRenamer(tmp, to)) return true
        if (dirRenamer(tmp, from)) return false
        // Neither name takes it back: a visible name the user can find, else the temp itself.
        val visible = File(parent, FileNames.free("${from.name} (recovering)") { File(parent, it).exists() })
        val at = if (dirRenamer(tmp, visible)) visible else tmp
        Log.e(TAG, "case-only rename stuck: ${from.path} is now ${at.path}")
        throw RenameStuck(at)
    }

    // Best effort: the index follows a folder that ended up somewhere unplanned.
    private suspend fun follow(old: File, at: File, folders: List<String>, created: List<String>) {
        try {
            rewriteFolder(old, at, folders, created)
        } catch (e: Exception) {
            Log.w(TAG, "index couldn't follow ${old.path} to ${at.path}", e)
        }
        scan(listOf(old.path, at.path))
    }

    // Same rule as the index: dot-folder or .nomedia between the folder and its root; the user's override is applied in SQL.
    private fun ruleHidden(folder: String, root: File?): Boolean =
        HiddenRules.isHidden(folder, root?.path.orEmpty()) { File(it, NOMEDIA).exists() }

    internal fun scan(changed: List<String>) {
        try {
            scanner(changed)
        } catch (e: Exception) {
            Log.w(TAG, "scan request failed", e)
        }
    }

    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun <T> ArrayList<T>.drain(): List<T> = toList().also { clear() }

    companion object {
        private const val RECOVERED_PREFIX = "Recovered-"
        private const val TEMP_MAX_AGE_MS = 3_600_000L
        private const val MAX_TEMP_EXT = 16
        private const val TAG = "FileOps"
        private const val BATCH = 200
        private const val NOMEDIA = ".nomedia"
        internal const val BLOCKED = "Android/data and Android/obb can't be changed"
        private const val NO_SPACE = "Not enough space"
        internal const val INDEX_FAILED = "Couldn't update the index"
        /** A copy must leave this much free on its volume (bytes). */
        const val FREE_SPACE_MARGIN = 200L * 1024 * 1024
        internal val NO_PROGRESS: (Int, Int) -> Unit = { _, _ -> }

        // Constraint: never read or write Android/data or Android/obb.
        internal fun blocked(f: File): Boolean = f.path.split('/', File.separatorChar).zipWithNext().any { (a, b) ->
            a.equals("Android", ignoreCase = true) && (b.equals("data", ignoreCase = true) || b.equals("obb", ignoreCase = true))
        }
    }
}

/** The default copier: [from] into a NEW file [to], synced to disk before it returns. */
fun copyBytes(from: File, to: File) {
    FileChannel.open(to.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
        from.inputStream().use { it.copyTo(Channels.newOutputStream(out), 1 shl 16) }
        out.force(true)
    }
}

/** Best effort: FUSE may refuse a directory fsync, and a move must not fail for it. */
fun fsyncDir(dir: File) {
    try {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    } catch (e: Exception) {
        Log.d("FileOps", "fsync of ${dir.path} skipped: $e")
    }
}

/** Bytes free on [dir]'s volume, or null when it can't be read (0 free of 0 total): then a copy goes ahead. */
fun freeBytes(dir: File): Long? = dir.usableSpace.takeUnless { it == 0L && dir.totalSpace == 0L }
