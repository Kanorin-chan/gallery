package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollerVisibilityTest {
    private val v = ScrollerVisibility()

    @Test fun hiddenAtStart() {
        assertFalse(v.visible(0))
        assertFalse(v.pressable(0))
    }

    @Test fun showsOnScrollAndHides1500MsAfter() {
        v.onScroll(0)
        assertTrue(v.visible(0))
        assertTrue(v.visible(1499))
        assertFalse(v.visible(1501))
    }

    @Test fun eachScrollRestartsTheWait() {
        v.onScroll(0)
        v.onScroll(1000)
        assertTrue(v.visible(2400))
        assertFalse(v.visible(2501))
    }

    @Test fun staysWhileDraggingThenHides1500MsAfterRelease() {
        v.onScroll(0)
        v.onDragStart()
        assertTrue(v.visible(10_000))
        v.onDragEnd(10_000)
        assertTrue(v.visible(11_499))
        assertFalse(v.visible(11_501))
    }

    @Test fun pressableOnlyWhileVisible() {
        v.onScroll(0)
        assertTrue(v.pressable(1499))
        assertFalse(v.pressable(1501))
    }

    @Test fun reportsTheWaitUntilItHides() {
        assertNull(v.msUntilHidden(0))
        v.onScroll(100)
        assertEquals(1500L, v.msUntilHidden(100))
        assertEquals(400L, v.msUntilHidden(1200))
        assertNull(v.msUntilHidden(1600))
        v.onDragStart()
        assertNull(v.msUntilHidden(1600))
    }
}
