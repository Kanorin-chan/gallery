package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeScrollTest {
    // A grid 0..600 dp tall: the edge zones are 0..50 and 550..600.
    private fun step(y: Float) = EdgeScroll.step(y, top = 0f, bottom = 600f)

    @Test fun theMiddleDoesNotScroll() {
        assertEquals(0f, step(300f))
        assertEquals(0f, step(50f))
        assertEquals(0f, step(550f))
    }

    @Test fun nearTheTopScrollsUpByAThirdOfTheDepth() {
        assertEquals(-10f, step(20f), 1e-4f)
        assertEquals(-50f / 3, step(0f), 1e-4f)
    }

    @Test fun nearTheBottomScrollsDownByAThirdOfTheDepth() {
        assertEquals(10f, step(580f), 1e-4f)
    }

    @Test fun pastTheEdgeIsCappedAt25() {
        assertEquals(-25f, step(-40f))
        assertEquals(-25f, step(-500f))
        assertEquals(25f, step(700f))
    }

    @Test fun zonesAreMeasuredFromTheGridsOwnEdges() {
        assertEquals(-10f, EdgeScroll.step(120f, top = 100f, bottom = 700f), 1e-4f)
        assertEquals(0f, EdgeScroll.step(120f, top = 0f, bottom = 700f))
    }

    // Final review (Task 7): the mockup's speeds are per 16.7 ms, so a 120 or 165 Hz screen must not scroll faster.
    @Test fun aSecondOfFramesCoversTheSameDistanceAt60120And165Hz() {
        val distances = listOf(60, 120, 165).map { hz ->
            val frameNs = 1_000_000_000L / hz
            var last: Long? = null
            var total = 0f
            for (i in 1..hz) {
                val now = i * frameNs
                total += EdgeScroll.step(580f, 0f, 600f, EdgeScroll.frameMs(last, now))
                last = now
            }
            total
        }
        distances.forEach { assertEquals(600f, it, 600f * 0.02f) }
    }

    @Test fun theCapIsPer16ms() {
        assertEquals(12.5f, EdgeScroll.step(700f, 0f, 600f, EdgeScroll.FRAME_MS / 2), 1e-3f)
        assertEquals(25f, EdgeScroll.step(700f, 0f, 600f, EdgeScroll.FRAME_MS), 1e-3f)
    }

    @Test fun theFirstFrameCountsAsOneAndAStallDoesNotLeap() {
        assertEquals(EdgeScroll.FRAME_MS, EdgeScroll.frameMs(null, 5_000_000_000L), 1e-4f)
        assertEquals(8f, EdgeScroll.frameMs(1_000_000_000L, 1_008_000_000L), 1e-4f)
        assertEquals(EdgeScroll.MAX_FRAME_MS, EdgeScroll.frameMs(1_000_000_000L, 1_500_000_000L), 1e-4f)
    }
}
