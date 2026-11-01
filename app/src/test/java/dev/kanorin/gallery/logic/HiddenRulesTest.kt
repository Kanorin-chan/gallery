package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenRulesTest {
    private val root = "/s/0"
    private val noNomedia: (String) -> Boolean = { false }

    @Test fun dotFolderIsHidden() {
        assertTrue(HiddenRules.isHidden("/s/0/.Statuses/a", root, noNomedia))
        assertTrue(HiddenRules.isHidden("/s/0/WhatsApp/Media/.Statuses", root, noNomedia))
    }

    @Test fun nomediaAncestorIsHidden() {
        val withNomedia = setOf("/s/0/Telegram")
        assertTrue(HiddenRules.isHidden("/s/0/Telegram/Telegram Images", root) { it in withNomedia })
        assertTrue(HiddenRules.isHidden("/s/0/Telegram", root) { it in withNomedia })
    }

    @Test fun normalFolderVisible() {
        val asked = mutableListOf<String>()
        assertFalse(HiddenRules.isHidden("/s/0/DCIM/Camera", root) { asked += it; false })
        // Every folder between the item and the root is checked, the root is not.
        assertEquals(listOf("/s/0/DCIM/Camera", "/s/0/DCIM"), asked)
    }

    @Test fun rootItselfIgnored() {
        assertFalse(HiddenRules.isHidden("/s/0", root) { true })
        assertFalse(HiddenRules.isHidden("/s/0/DCIM", "/s/0/") { it == "/s/0" })
        // A dot in the root's own path doesn't hide what's under it.
        assertFalse(HiddenRules.isHidden("/s/.0/DCIM", "/s/.0", noNomedia))
    }

    @Test fun siblingWithSharedPrefixIsNotUnderRoot() {
        // "/s/01" is not inside "/s/0": its own folders are all checked.
        assertTrue(HiddenRules.isHidden("/s/01/DCIM", root) { it == "/s/01" })
    }

    @Test fun unicodeFolderNamesWork() {
        assertFalse(HiddenRules.isHidden("/s/0/Pictures/旅行 2026 🌸", root, noNomedia))
        assertTrue(HiddenRules.isHidden("/s/0/Pictures/.旅行", root, noNomedia))
    }
}
