package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {
    @Test fun theSuffixGoesBeforeTheExtension() {
        assertEquals("a (1).jpg", FileNames.withSuffix("a.jpg", 1))
        assertEquals("IMG 2026 (12).HEIC", FileNames.withSuffix("IMG 2026.HEIC", 12))
        assertEquals("clip.tar (2).gz", FileNames.withSuffix("clip.tar.gz", 2))
    }

    @Test fun noExtensionOrADotFileGetsItAtTheEnd() {
        assertEquals("README (1)", FileNames.withSuffix("README", 1))
        assertEquals(".nomedia (1)", FileNames.withSuffix(".nomedia", 1))
        assertEquals(".hidden (1).jpg", FileNames.withSuffix(".hidden.jpg", 1))
    }

    @Test fun theExtensionIsAfterTheLastDotOfANonDotName() {
        assertEquals("jpg", FileNames.extension("a.b.jpg"))
        assertEquals("", FileNames.extension("README"))
        assertEquals("", FileNames.extension(".nomedia"))
        assertEquals("jpg", FileNames.extension(".hidden.jpg"))
    }

    @Test fun freePicksTheFirstNameNotTaken() {
        assertEquals("a.jpg", FileNames.free("a.jpg") { false })
        val taken = setOf("a.jpg", "a (1).jpg")
        assertEquals("a (2).jpg", FileNames.free("a.jpg") { it in taken })
    }

    @Test fun unicodeNamesKeepTheirCharacters() =
        assertEquals("写真 🌸 (1).jpg", FileNames.free("写真 🌸.jpg") { it == "写真 🌸.jpg" })

    @Test(expected = IllegalStateException::class)
    fun freeGivesUpInsteadOfLoopingForever() {
        FileNames.free("a.jpg") { true }
    }
}
