package dev.kanorin.gallery.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenamePolicyTest {
    private val root = "/storage/emulated/0"

    private fun can(rel: String) = RenamePolicy.canRename("$root/$rel", root)

    @Test fun cameraAndScreenshotsAreProtected() {
        assertFalse(can("DCIM/Camera"))
        assertFalse(can("Pictures/Screenshots"))
        assertFalse(can("DCIM/Screenshots"))
    }

    @Test fun messengerAppsAreProtectedAtAnyDepth() {
        assertFalse(can("WhatsApp/Media/WhatsApp Images"))
        assertFalse(can("Pictures/Telegram"))
        assertFalse(can("Download/instagram saves"))
        assertFalse(can("Movies/TikTok"))
    }

    @Test fun androidMediaIsProtected() {
        assertFalse(can("Android/media/com.x"))
        assertFalse(can("Android/media/com.x/files/pics"))
    }

    @Test fun lineMatchesExactlyOnly() {
        assertFalse(can("LINE"))
        assertFalse(can("Pictures/LINE/stickers"))
        assertTrue(can("Pictures/Timeline"))
        assertTrue(can("Pictures/line"))
    }

    @Test fun ordinaryFoldersCanBeRenamed() {
        assertTrue(can("Pictures/Trip KL"))
        assertTrue(can("Pictures/旅行 2026 🌸"))
        assertTrue(can("DCIM/Other"))
    }

    @Test fun theStorageRootItselfCannotBeRenamed() {
        assertFalse(RenamePolicy.canRename(root, root))
    }

    @Test fun sdCardFoldersFollowTheSameRules() {
        assertTrue(RenamePolicy.canRename("/storage/ABCD-1234/Trips", root))
        assertFalse(RenamePolicy.canRename("/storage/ABCD-1234/DCIM/Camera", root))
    }

    // Final review: an SD card's root is a storage root too; the OS refuses it, so it's never offered.
    @Test fun noStorageRootCanBeRenamed() {
        val roots = listOf(root, "/storage/ABCD-1234")
        assertFalse(RenamePolicy.canRename("/storage/ABCD-1234", root, roots))
        assertFalse(RenamePolicy.canRename("/storage/ABCD-1234/", root, roots))
        assertFalse(RenamePolicy.canRename(root, root, roots))
        assertTrue(RenamePolicy.canRename("/storage/ABCD-1234/Trips", root, roots))
        assertFalse("rules apply below the card's root", RenamePolicy.canRename("/storage/ABCD-1234/Screenshots", root, roots))
    }
}
