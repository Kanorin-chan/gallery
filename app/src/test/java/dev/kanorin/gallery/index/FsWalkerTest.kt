package dev.kanorin.gallery.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FsWalkerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File

    @Before fun setUp() {
        root = tmp.newFolder("storage", "emulated", "0")
    }

    private fun put(relative: String): File =
        File(root, relative).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }

    private fun walked(): Set<String> =
        FsWalker(listOf(root)).walk().map { it.relativeTo(root).invariantSeparatorsPath }.toSet()

    @Test fun yieldsMediaByExtensionAnyCase() {
        put("DCIM/Camera/IMG_1.JPG")
        put("DCIM/Camera/clip.mp4")
        put("Download/a.heic")
        put("Download/movie.MKV")
        put("Download/notes.txt")
        put("Download/song.mp3")
        put("Download/noext")
        assertEquals(
            setOf("DCIM/Camera/IMG_1.JPG", "DCIM/Camera/clip.mp4", "Download/a.heic", "Download/movie.MKV"),
            walked(),
        )
    }

    @Test fun everyListedExtensionIsMedia() {
        val exts = "jpg jpeg png gif webp heic heif avif bmp dng mp4 mkv webm 3gp mov m4v avi ts".split(' ')
        exts.forEach { put("All/f.$it") }
        assertEquals(exts.map { "All/f.$it" }.toSet(), walked())
    }

    @Test fun skipsAndroidDataObbAndBinButWalksAndroidMedia() {
        put("Android/data/com.x/files/a.jpg")
        put("Android/obb/com.y/b.mp4")
        put("android/DATA/com.z/c.jpg") // case-insensitive storage
        put("${StorageLayout.BIN_DIR_NAME}/d.jpg")
        put("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/e.jpg")
        assertEquals(setOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/e.jpg"), walked())
    }

    @Test fun walksIntoHiddenFolders() {
        put("WhatsApp/Media/.Statuses/s.jpg")
        put("Pictures/Stickers/.nomedia")
        put("Pictures/Stickers/st.webp")
        assertEquals(setOf("WhatsApp/Media/.Statuses/s.jpg", "Pictures/Stickers/st.webp"), walked())
    }

    @Test fun skipsAndroidTrashAndPendingFilesAndTheAppsCopiesInFlight() {
        put("DCIM/Camera/.trashed-1790000000-IMG_2.jpg")
        put("DCIM/Camera/.pending-1790000000-IMG_3.jpg")
        put("DCIM/Camera/.gallery-tmp-1a2b3c4d.jpg")
        put("DCIM/Camera/IMG_4.jpg")
        assertEquals(setOf("DCIM/Camera/IMG_4.jpg"), walked())
    }

    @Test fun walksEveryRoot() {
        val sd = tmp.newFolder("storage", "1234-ABCD")
        put("DCIM/a.jpg")
        File(sd, "DCIM/b.jpg").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(setOf("a.jpg", "b.jpg"), FsWalker(listOf(root, sd)).walk().map { it.name }.toSet())
    }

    @Test fun symlinkLoopIsWalkedOnce() {
        put("DCIM/Camera/a.jpg")
        val loop = File(root, "DCIM/Camera/loop").toPath()
        try {
            Files.createSymbolicLink(loop, File(root, "DCIM").toPath())
        } catch (e: Exception) {
            assumeNoException("this host can't make symlinks", e)
        }
        assertEquals(listOf("DCIM/Camera/a.jpg"), FsWalker(listOf(root)).walk().map { it.relativeTo(root).invariantSeparatorsPath }.toList())
    }

    @Test fun rootsAreReadOnEveryWalk() {
        val sd = tmp.newFolder("storage", "1234-ABCD")
        File(sd, "b.jpg").writeText("x")
        put("a.jpg")
        var mounted = listOf(root)
        val walker = FsWalker { mounted }
        assertEquals(setOf("a.jpg"), walker.walk().map { it.name }.toSet())
        mounted = listOf(root, sd) // card inserted
        assertEquals(setOf("a.jpg", "b.jpg"), walker.walk().map { it.name }.toSet())
        assertEquals(listOf(root, sd), walker.roots)
    }

    @Test fun mimeTypesComeFromTheExtension() {
        assertEquals("image/jpeg", FsWalker.mimeTypeOf("IMG.JPG"))
        assertEquals("image/png", FsWalker.mimeTypeOf("旅行 🌸.png"))
        assertEquals("video/mp4", FsWalker.mimeTypeOf("clip.mp4"))
        assertEquals("video/quicktime", FsWalker.mimeTypeOf("a.MOV"))
        assertNull(FsWalker.mimeTypeOf("notes.txt"))
        assertNull(FsWalker.mimeTypeOf("jpg"))
    }
}
