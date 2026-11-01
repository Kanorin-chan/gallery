package dev.kanorin.gallery.ui.common

import android.os.Looper
import androidx.compose.runtime.saveable.SaverScope
import dev.kanorin.gallery.logic.RangeSelection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectionStateTest {
    private val a = "/p/a.jpg"
    private val b = "/p/b.jpg"
    private val c = "/p/c.jpg"

    // These writes hit Compose's global snapshot outside a composition, and its observer posts to the main looper.
    // Left queued, later suites in this JVM never see paged rows (bisected: MainWiring -> this -> ZoomPill -> MainScreen).
    @After fun drainMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun togglingInAndOutLeavesSelectionAtZero() {
        val s = SelectionState()
        assertFalse(s.active)
        s.toggle(a)
        assertTrue(s.active)
        assertEquals(setOf(a), s.selected.toSet())
        s.toggle(a)
        assertFalse(s.active)
        assertTrue(s.selected.isEmpty())
    }

    @Test fun setAllAddsOrRemovesEveryPath() {
        val s = SelectionState()
        s.setAll(listOf(a, b, c), on = true)
        s.setAll(listOf(b), on = false)
        assertEquals(setOf(a, c), s.selected.toSet())
        s.setAll(listOf(a, c), on = false)
        assertFalse(s.active)
    }

    @Test fun toggleAllSelectsTheRestOrClearsAFullGroup() {
        val s = SelectionState(listOf(a))
        s.toggleAll(listOf(a, b))
        assertEquals(setOf(a, b), s.selected.toSet())
        s.toggleAll(listOf(a, b))
        assertTrue(s.selected.isEmpty())
        assertFalse(s.active)
        s.toggleAll(emptyList())
        assertFalse(s.active)
    }

    @Test fun clearEndsSelection() {
        val s = SelectionState(listOf(a, b))
        assertTrue(s.active)
        s.clear()
        assertFalse(s.active)
        assertTrue(s.selected.isEmpty())
    }

    @Test fun aDragKeepsSelectionOnAtZeroUntilTheFingerLifts() {
        val s = SelectionState(listOf(a))
        s.beginDrag()
        s.apply(RangeSelection.Change(on = emptySet(), off = setOf(a)))
        assertTrue("still selecting mid-drag", s.active)
        assertTrue(s.dragging)
        s.endDrag()
        assertFalse(s.active)
        assertFalse(s.dragging)
    }

    @Test fun clearStopsADrag() {
        val s = SelectionState()
        s.beginDrag()
        s.apply(RangeSelection.Change(on = setOf(a), off = emptySet()))
        s.clear()
        assertFalse(s.dragging)
        assertFalse(s.active)
    }

    @Test fun theSaverRoundTripsAndDropsHugeSelections() {
        val scope = SaverScope { true }
        val saved = with(SelectionState.Saver) { scope.save(SelectionState(listOf(a, b))) }!!
        val back = SelectionState.Saver.restore(saved)!!
        assertEquals(setOf(a, b), back.selected.toSet())
        assertTrue(back.active)

        // Past the limit the Bundle would overflow; nothing is saved rather than crashing on stop.
        val huge = SelectionState((0..SelectionState.SAVE_LIMIT).map { "/p/$it.jpg" })
        assertNull(with(SelectionState.Saver) { scope.save(huge) })
    }
}
