package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 5.8: pinch 1-5× on the playing video, the point under the fingers stays put, pans stop at the edges. */
class VideoZoomTest {
    private val w = 310f
    private val h = 680f
    private val eps = 0.01f

    // Where content point (cx, cy) (px from the view's top-left at 1×) lands on the screen.
    private fun VideoZoom.screen(cx: Float, cy: Float) = (tx + scale * cx) to (ty + scale * cy)

    @Test fun zoomingKeepsTheFocusPointStill() {
        val z = VideoZoom().zoomAt(2f, 100f, 250f, w, h)
        assertEquals(2f, z.scale, eps)
        val (x, y) = z.screen(100f, 250f)
        assertEquals(100f, x, eps)
        assertEquals(250f, y, eps)
        assertEquals(-100f, z.tx, eps)
        assertEquals(-250f, z.ty, eps)
    }

    @Test fun aSecondZoomKeepsItsOwnFocusStill() {
        val first = VideoZoom().zoomAt(2f, 100f, 250f, w, h)
        // The content point under (200, 400) now: ((200 + 100) / 2, (400 + 250) / 2).
        val second = first.zoomAt(3f, 200f, 400f, w, h)
        val (x, y) = second.screen(150f, 325f)
        assertEquals(200f, x, eps)
        assertEquals(400f, y, eps)
    }

    @Test fun theScaleStaysBetweenOneAndFive() {
        assertEquals(5f, VideoZoom().zoomAt(6f, 155f, 340f, w, h).scale, eps)
        val out = VideoZoom(2f, -100f, -250f).zoomAt(0.5f, 155f, 340f, w, h)
        assertEquals(1f, out.scale, eps)
        assertEquals(0f, out.tx, eps)
        assertEquals(0f, out.ty, eps)
    }

    @Test fun aZoomNearTheEdgeIsClampedToTheView() {
        // Focus at the bottom-right corner: the picture's edge can't come inside the view.
        val z = VideoZoom().zoomAt(2f, w, h, w, h)
        assertEquals(w - 2f * w, z.tx, eps)
        assertEquals(h - 2f * h, z.ty, eps)
    }

    @Test fun aPanStopsAtTheEdges() {
        val z = VideoZoom(2f, -100f, -250f)
        val right = z.pan(500f, 0f, w, h)
        assertEquals(0f, right.tx, eps)
        assertEquals(-250f, right.ty, eps)
        val left = z.pan(-1_000f, -1_000f, w, h)
        assertEquals(w - 2f * w, left.tx, eps)
        assertEquals(h - 2f * h, left.ty, eps)
        val inside = z.pan(30f, -40f, w, h)
        assertEquals(-70f, inside.tx, eps)
        assertEquals(-290f, inside.ty, eps)
        assertEquals("unzoomed, nothing moves", VideoZoom(), VideoZoom().pan(50f, 50f, w, h))
    }

    @Test fun resetIsTheIdentity() {
        assertEquals(VideoZoom(), VideoZoom(3f, -40f, -90f).reset())
        assertEquals(1f, VideoZoom().scale)
        assertEquals(0f, VideoZoom().tx)
        assertEquals(0f, VideoZoom().ty)
    }

    @Test fun zoomedMeansMoreThanAHair() {
        assertFalse(VideoZoom().zoomed)
        assertFalse(VideoZoom(1.005f).zoomed)
        assertTrue(VideoZoom(1.02f).zoomed)
    }

    @Test fun thePillShowsOneDecimal() {
        assertEquals("2.3×", VideoZoom(2.3f).label)
        assertEquals("2.0×", VideoZoom(1.96f).label)
        assertEquals("5.0×", VideoZoom(5f).label)
    }
}
