package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 5.8 / mockup phone 7: hold = 2×, slide up = 0.5×, slide down + release = locked 2×, the next hold unlocks. */
class SpeedGestureTest {
    // Mockup values: ±40 px slides, an 8 px slop, 350 ms hold.
    private fun gesture(locked: Boolean = false) = SpeedGesture(holdSpeed = 2f, slidePx = 40f, touchSlopPx = 8f, locked = locked)

    @Test fun aStillFingerHoldsAtThreeHundredFiftyMs() {
        val g = gesture()
        g.down(100f, 300f, 0, zoomed = false)
        g.tick(349)
        assertEquals(SpeedMode.NORMAL, g.mode)
        assertEquals(1.5f, g.rate(1.5f))
        g.tick(350)
        assertEquals(SpeedMode.HOLD, g.mode)
        assertEquals(2f, g.rate(1f))
        assertTrue("a hold is no tap", g.consumed)
    }

    @Test fun slidingWhileHeldGoesSlowThenOffersTheLockAndReleaseLocks() {
        val g = gesture()
        g.down(100f, 300f, 0, zoomed = false)
        g.tick(350)
        g.move(100f, 250f, 400) // dy -50
        assertEquals(SpeedMode.SLOW, g.mode)
        assertEquals(0.5f, g.rate(1f))
        g.move(100f, 330f, 450) // dy +30: back inside the slides
        assertEquals(SpeedMode.HOLD, g.mode)
        g.move(100f, 350f, 500) // dy +50
        assertEquals(SpeedMode.PENDING_LOCK, g.mode)
        assertEquals(2f, g.rate(1f))
        g.up(550)
        assertEquals(SpeedMode.LOCKED, g.mode)
        assertEquals(2f, g.rate(1.25f))
    }

    // Kano 2026-10-31: "if on tap: just show the progress bar and thing. dont cancle. only cancel is hold once more"
    @Test fun aTapKeepsTheLockAndOnlyAHoldEndsIt() {
        val g = gesture(locked = true)
        g.down(200f, 200f, 2_000, zoomed = false)
        assertEquals(SpeedMode.LOCKED, g.mode)
        assertFalse("a tap on the locked video is a tap", g.consumed)
        g.up(2_100)
        assertEquals("the tap left the lock on", SpeedMode.LOCKED, g.mode)
        assertEquals(2f, g.rate(1f))

        g.down(200f, 200f, 3_000, zoomed = false)
        g.tick(3_350)
        assertEquals("the hold takes over at the same speed", SpeedMode.HOLD, g.mode)
        assertTrue("a hold is no tap", g.consumed)
        assertEquals(2f, g.rate(1f))
        g.up(3_500)
        assertEquals(SpeedMode.NORMAL, g.mode)
        assertEquals(1.5f, g.rate(1.5f))
    }

    @Test fun lockedADoubleTapAPanAndAPinchKeepTheLock() {
        val g = gesture(locked = true)
        g.down(200f, 200f, 0, zoomed = false)
        g.tapped()
        g.tick(500)
        g.up(600)
        assertEquals("a double-tap's held second press", SpeedMode.LOCKED, g.mode)
        g.down(200f, 200f, 1_000, zoomed = true)
        g.move(260f, 200f, 1_050)
        assertTrue(g.panning)
        g.up(1_500)
        assertEquals(SpeedMode.LOCKED, g.mode)
        g.down(200f, 200f, 2_000, zoomed = false)
        g.pinch()
        g.up(2_500)
        assertEquals(SpeedMode.LOCKED, g.mode)
    }

    @Test fun lockedAHoldCanSlideDownToLockAgain() {
        val g = gesture(locked = true)
        g.down(100f, 300f, 0, zoomed = false)
        g.tick(350)
        g.move(100f, 350f, 400)
        assertEquals(SpeedMode.PENDING_LOCK, g.mode)
        g.up(450)
        assertEquals(SpeedMode.LOCKED, g.mode)
    }

    @Test fun releasingAHoldOrASlowGoesBackToNormal() {
        val g = gesture()
        g.down(0f, 300f, 0, zoomed = false)
        g.tick(350)
        g.up(500)
        assertEquals(SpeedMode.NORMAL, g.mode)
        g.down(0f, 300f, 1_000, zoomed = false)
        g.tick(1_350)
        g.move(0f, 200f, 1_400)
        g.up(1_500)
        assertEquals(SpeedMode.NORMAL, g.mode)
        assertEquals(1f, g.rate(1f))
    }

    @Test fun zoomedAMoveBeforeTheHoldPans() {
        val g = gesture()
        g.down(100f, 300f, 0, zoomed = true)
        g.move(120f, 300f, 100) // 20 px at 100 ms
        assertTrue(g.panning)
        assertTrue("a pan is no tap", g.consumed)
        g.tick(400)
        assertEquals(SpeedMode.NORMAL, g.mode)
        g.up(500)
        assertFalse(g.panning)
        assertEquals(SpeedMode.NORMAL, g.mode)
    }

    @Test fun notZoomedAMoveBeforeTheHoldCancelsItAndIsLeftAlone() {
        val g = gesture()
        g.down(100f, 300f, 0, zoomed = false)
        g.move(120f, 300f, 100)
        assertFalse("nothing to pan", g.panning)
        assertFalse("left for whoever wants it", g.consumed)
        g.tick(400)
        assertEquals(SpeedMode.NORMAL, g.mode)
    }

    @Test fun aMoveInsideTheSlopStillHolds() {
        val g = gesture()
        g.down(100f, 300f, 0, zoomed = true)
        g.move(105f, 303f, 100)
        assertFalse(g.panning)
        g.tick(350)
        assertEquals(SpeedMode.HOLD, g.mode)
        // Held, the same finger slides; it never pans.
        g.move(160f, 300f, 400)
        assertFalse(g.panning)
        assertEquals(SpeedMode.HOLD, g.mode)
    }

    @Test fun aLateEventStillCountsTheHoldFirst() {
        val g = gesture()
        g.down(100f, 300f, 0, zoomed = false)
        g.move(100f, 250f, 400) // no tick came: the finger was still until now
        assertEquals(SpeedMode.SLOW, g.mode)
    }

    @Test fun aQuickTapIsATap() {
        val g = gesture()
        g.down(100f, 300f, 0, zoomed = true)
        g.up(120)
        assertFalse(g.consumed)
        assertEquals(SpeedMode.NORMAL, g.mode)
    }

    @Test fun aSecondFingerEndsTheHold() {
        val g = gesture()
        g.down(100f, 300f, 0, zoomed = false)
        g.tick(350)
        g.move(100f, 350f, 400)
        assertEquals(SpeedMode.PENDING_LOCK, g.mode)
        g.pinch()
        assertEquals(SpeedMode.NORMAL, g.mode)
        g.up(600)
        assertEquals("a pinch never locks", SpeedMode.NORMAL, g.mode)
        g.down(100f, 300f, 1_000, zoomed = false)
        g.pinch()
        g.tick(1_400)
        assertEquals("nor holds", SpeedMode.NORMAL, g.mode)
        assertTrue(g.consumed)
    }

    @Test fun theHoldSpeedComesFromTheSetting() {
        val g = SpeedGesture(holdSpeed = 3f, slidePx = 40f, touchSlopPx = 8f)
        g.down(0f, 0f, 0, zoomed = false)
        g.tick(350)
        assertEquals(3f, g.rate(1f))
        g.holdSpeed = 1.5f
        assertEquals(1.5f, g.rate(1f))
    }

    @Test fun aLockedGestureStartsLocked() {
        val g = gesture(locked = true)
        assertEquals(SpeedMode.LOCKED, g.mode)
        assertEquals(2f, g.rate(1f))
    }

    @Test fun labelsReadAsTheMockupWritesThem() {
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f), PlaybackSpeed.OPTIONS)
        assertEquals(
            listOf("0.25×", "0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "1.75×", "2×"),
            PlaybackSpeed.OPTIONS.map(PlaybackSpeed::label),
        )
        assertEquals("3×", PlaybackSpeed.label(3f))
    }

    @Test fun pillsSayWhatTheFingerDoes() {
        assertNull(PlaybackSpeed.pill(SpeedMode.NORMAL, 2f))
        assertEquals("2× speed", PlaybackSpeed.pill(SpeedMode.HOLD, 2f))
        assertEquals("0.5× slow", PlaybackSpeed.pill(SpeedMode.SLOW, 2f))
        assertEquals("Release to lock 2×", PlaybackSpeed.pill(SpeedMode.PENDING_LOCK, 2f))
        assertEquals("2× locked · hold to unlock", PlaybackSpeed.pill(SpeedMode.LOCKED, 2f))
        assertEquals("1.5× speed", PlaybackSpeed.pill(SpeedMode.HOLD, 1.5f))
    }

    // Final review (Task 15): a double-tap's held second press is the tap detector's, never a hold.
    @Test fun aPressTheTapDetectorTookNeverHolds() {
        val g = SpeedGesture(2f, slidePx = 100f, touchSlopPx = 8f)
        g.down(10f, 10f, 0, zoomed = false)
        g.tapped()
        g.tick(1_000)
        assertEquals(SpeedMode.NORMAL, g.mode)
        assertFalse(g.holdPending)
        assertFalse("still a tap for the detector", g.consumed)
        g.up(1_200)
        g.down(10f, 10f, 2_000, zoomed = false)
        g.tick(2_400)
        assertEquals("the next press holds again", SpeedMode.HOLD, g.mode)
    }
}
