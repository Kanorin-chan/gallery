package dev.kanorin.gallery.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleCountTest {
    @Test fun nothingArmsUntilBothSidesHaveLoaded() {
        val c = StaleCount()
        assertFalse("sections not answered", c.armed(expected = null, count = 3, settled = true))
        assertFalse("paging still loading", c.armed(expected = 5, count = 3, settled = false))
    }

    @Test fun aMismatchArmsAndAgreementDoesNot() {
        val c = StaleCount()
        assertTrue(c.armed(expected = 5, count = 3, settled = true))
        assertFalse(c.armed(expected = 5, count = 5, settled = true))
    }

    @Test fun aMismatchThatSurvivesItsRefreshIsLeftAlone() {
        val c = StaleCount()
        assertTrue(c.armed(5, 3, true))
        c.refreshed(5)
        assertFalse("same total, same count", c.armed(5, 3, true))
        assertFalse("same total, other count: still no loop", c.armed(5, 4, true))
    }

    @Test fun aNewTotalOrAnAgreementReArmsIt() {
        val c = StaleCount()
        c.refreshed(5)
        assertTrue("a new sections total", c.armed(6, 3, true))
        c.refreshed(6)
        assertFalse(c.armed(6, 6, true))
        assertTrue("short again after agreeing", c.armed(6, 3, true))
    }
}
