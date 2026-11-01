package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderNameValidatorTest {
    private fun v(name: String, siblings: Collection<String> = emptyList()) = FolderNameValidator.validate(name, siblings)

    @Test fun emptyAndBlankNeedAName() {
        assertEquals(NameError.EMPTY, v(""))
        assertEquals(NameError.EMPTY, v("  "))
    }

    @Test fun aSlashIsIllegal() = assertEquals(NameError.ILLEGAL, v("a/b"))

    @Test fun everyCharacterTheSpecListsIsIllegal() {
        for (c in "\\/:*?\"<>|") assertEquals("'$c'", NameError.ILLEGAL, v("a${c}b"))
    }

    // Not in the message, but File(parent, "..") is the parent's parent; a NUL ends the name early.
    @Test fun dotNamesAndControlCharactersAreIllegal() {
        assertEquals(NameError.ILLEGAL, v("."))
        assertEquals(NameError.ILLEGAL, v(".."))
        assertEquals(NameError.ILLEGAL, v(" .. "))
        assertEquals(NameError.ILLEGAL, v("a\u0000b"))
        assertEquals(NameError.ILLEGAL, v("a\nb"))
    }

    @Test fun duplicatesCompareIgnoringCase() = assertEquals(NameError.DUPLICATE, v("camera", listOf("Camera")))

    @Test fun theNameIsTrimmedBeforeTheDuplicateCheck() = assertEquals(NameError.DUPLICATE, v("  Trip ", listOf("trip")))

    @Test fun cjkSpacesAndEmojiAreFine() {
        assertNull(v("旅行 2026 🌸"))
        assertEquals(NameError.DUPLICATE, v("旅行 2026 🌸", listOf("旅行 2026 🌸")))
        assertNull(v("旅行 2026 🌸", listOf("旅行 2026")))
    }

    @Test fun dotFoldersAndOtherPunctuationAreAllowed() {
        assertNull(v(".Private"))
        assertNull(v("Trip (KL) #1 & co."))
    }

    @Test fun theMessagesAreTheSpecsCopy() {
        assertEquals("Give it a name", NameError.EMPTY.message)
        assertEquals("Can't use \\ / : * ? \" < > |", NameError.ILLEGAL.message)
        assertEquals("A folder with that name already exists", NameError.DUPLICATE.message)
    }
}
