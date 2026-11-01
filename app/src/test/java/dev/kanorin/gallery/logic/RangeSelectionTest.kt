package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class RangeSelectionTest {
    private val keys = (0 until 20).map { "/p/$it.jpg" }
    private val keyAt: (Int) -> String? = { keys.getOrNull(it) }
    private fun k(vararg i: Int) = i.map { keys[it] }.toSet()

    @Test fun anchorOnAnUnselectedItemSelectsUpToTheFinger() {
        val r = RangeSelection(anchor = 0, base = emptySet(), select = true)
        assertEquals(k(0, 1, 2, 3, 4, 5), r.at(5, keyAt))
    }

    @Test fun movingBackSelectsOnlyTheShorterRangePlusTheBase() {
        val base = k(9, 12)
        val r = RangeSelection(anchor = 0, base = base, select = true)
        r.at(5, keyAt)
        assertEquals(k(0, 1, 2) + base, r.at(2, keyAt))
    }

    @Test fun anchorOnASelectedItemRemovesTheRangeFromTheBase() {
        val base = k(0, 1, 2, 3, 4, 7)
        val r = RangeSelection(anchor = 0, base = base, select = !base.contains(keyAt(0)))
        assertEquals(k(4, 7), r.at(3, keyAt))
    }

    @Test fun aRangeRunsBothWaysFromTheAnchor() {
        val r = RangeSelection(anchor = 6, base = emptySet(), select = true)
        assertEquals(k(3, 4, 5, 6), r.at(3, keyAt))
    }

    @Test fun unknownKeysAreSkippedNotSelected() {
        val r = RangeSelection(anchor = 0, base = emptySet(), select = true)
        assertEquals(k(0, 1, 3), r.at(3) { if (it == 2) null else keyAt(it) })
    }

    @Test fun stepChangesAppliedToTheBaseAlwaysEqualAt() {
        val rnd = Random(11)
        repeat(200) {
            val base = keys.filter { rnd.nextBoolean() }.toSet()
            val anchor = rnd.nextInt(keys.size)
            val r = RangeSelection(anchor, base, select = keys[anchor] !in base)
            val shown = base.toMutableSet()
            repeat(8) {
                val current = rnd.nextInt(keys.size)
                val change = r.step(current, keyAt)
                shown += change.on
                shown -= change.off
                assertEquals("anchor $anchor current $current", r.at(current, keyAt), shown)
            }
        }
    }

    @Test fun aKeyThatTurnsUpLaterJoinsTheRangeOnTheNextStep() {
        val r = RangeSelection(anchor = 0, base = emptySet(), select = true)
        var loaded = false
        val lazyKeys: (Int) -> String? = { if (it == 2 && !loaded) null else keyAt(it) }
        assertEquals(k(0, 1, 3), r.step(3, lazyKeys).on)
        loaded = true
        assertEquals(k(0, 1, 2, 3), r.step(3, lazyKeys).on)
    }
}
