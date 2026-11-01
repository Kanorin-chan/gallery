package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoomMathTest {
    // A 1000 x 2000 px view holding a 4:3 photo: fitted at 1000 x 750.
    private val frame = Frame.fit(1000f, 2000f, 4000, 3000)
    private val eps = 0.01f

    @Test fun fitsTheImageInsideTheView() {
        assertEquals(1000f, frame.fitW, eps)
        assertEquals(750f, frame.fitH, eps)
        val tall = Frame.fit(1000f, 2000f, 1000, 4000)
        assertEquals(500f, tall.fitW, eps)
        assertEquals(2000f, tall.fitH, eps)
    }

    @Test fun anUnknownSizeFillsTheView() {
        val f = Frame.fit(1000f, 2000f, 0, 0)
        assertEquals(1000f, f.fitW, eps)
        assertEquals(2000f, f.fitH, eps)
    }

    @Test fun panRoomGrowsWithScale() {
        assertEquals(0f, ZoomMath.maxX(1f, frame), eps)
        assertEquals(0f, ZoomMath.maxY(1f, frame), eps)
        assertEquals(750f, ZoomMath.maxX(2.5f, frame), eps) // (2500 - 1000) / 2
        assertEquals(0f, ZoomMath.maxY(2.5f, frame), eps) // 1875 tall still fits in 2000
        assertEquals(2000f, ZoomMath.maxY(8f, frame), eps) // (750 * 8 - 2000) / 2
    }

    @Test fun zoomingKeepsThePointUnderTheFingersStill() {
        val fx = 800f
        val fy = 1100f
        val before = Zoom()
        val after = ZoomMath.zoomBy(before, 2.5f, fx, fy, frame)
        assertEquals(2.5f, after.scale, eps)
        // The content point under the focus: (focus - centre - offset) / scale, the same before and after.
        assertEquals((fx - 500f - before.x) / before.scale, (fx - 500f - after.x) / after.scale, eps)
        assertEquals(-450f, after.x, eps)
        // Vertically there's no room yet (1875 < 2000), so it stays centred.
        assertEquals(0f, after.y, eps)
    }

    @Test fun scaleStaysBetweenOneAndEight() {
        val big = ZoomMath.zoomBy(Zoom(), 20f, 500f, 1000f, frame)
        assertEquals(ZoomMath.MAX_SCALE, big.scale, eps)
        val small = ZoomMath.zoomBy(Zoom(3f, 100f, 0f), 0.1f, 500f, 1000f, frame)
        assertEquals(1f, small.scale, eps)
        assertEquals(0f, small.x, eps)
    }

    @Test fun panIsClampedToTheImageEdges() {
        val z = Zoom(2.5f, 0f, 0f)
        assertEquals(-750f, ZoomMath.panBy(z, -5000f, 0f, frame).x, eps)
        assertEquals(750f, ZoomMath.panBy(z, 5000f, 0f, frame).x, eps)
        assertEquals(0f, ZoomMath.panBy(z, 0f, 400f, frame).y, eps)
        assertEquals(-120f, ZoomMath.panBy(z, -120f, 0f, frame).x, eps)
    }

    @Test fun doubleTapZoomsTo2_5AroundTheTapAndBackToFit() {
        val z = ZoomMath.doubleTap(Zoom(), 700f, 1000f, frame)
        assertEquals(ZoomMath.DOUBLE_TAP_SCALE, z.scale, eps)
        assertEquals(-300f, z.x, eps) // (700 - 500) * (1 - 2.5)
        assertEquals(Zoom(), ZoomMath.doubleTap(z, 700f, 1000f, frame))
        // A tap near the edge can't push the photo off its edge.
        assertEquals(-750f, ZoomMath.doubleTap(Zoom(), 1000f, 1000f, frame).x, eps)
    }

    @Test fun atFitSidewaysIsThePagersAndUpDownIsTheScreens() {
        assertEquals(DragOwner.PAGER, ZoomMath.owner(Zoom(), frame, -30f, 4f))
        assertEquals(DragOwner.PAGER, ZoomMath.owner(Zoom(), frame, 30f, -4f))
        assertEquals(DragOwner.VERTICAL, ZoomMath.owner(Zoom(), frame, 4f, -30f))
        assertEquals(DragOwner.VERTICAL, ZoomMath.owner(Zoom(), frame, 4f, 30f))
    }

    @Test fun zoomedTheImagePansUntilItsEdgeThenThePagerTakesOver() {
        val centred = Zoom(2.5f, 0f, 0f)
        assertEquals(DragOwner.PAN, ZoomMath.owner(centred, frame, -30f, 0f))
        assertEquals(DragOwner.PAN, ZoomMath.owner(centred, frame, 30f, 0f))
        assertEquals(DragOwner.PAN, ZoomMath.owner(centred, frame, 0f, 30f))
        // Panned all the way left: its right edge is on screen, so a leftward drag goes to the next page.
        val rightEdge = Zoom(2.5f, -750f, 0f)
        assertEquals(DragOwner.PAGER, ZoomMath.owner(rightEdge, frame, -30f, 0f))
        assertEquals(DragOwner.PAN, ZoomMath.owner(rightEdge, frame, 30f, 0f))
        val leftEdge = Zoom(2.5f, 750f, 0f)
        assertEquals(DragOwner.PAGER, ZoomMath.owner(leftEdge, frame, 30f, 0f))
        // A tall photo zoomed a little still fits sideways: nothing to pan, so the pager.
        val tall = Frame.fit(1000f, 2000f, 1000, 4000)
        assertEquals(DragOwner.PAGER, ZoomMath.owner(Zoom(1.5f, 0f, 0f), tall, -30f, 0f))
    }

    @Test fun hiResIsAPowerOfTwoStepNeverOver4096() {
        assertEquals(4000 to 3000, ZoomMath.hiResSize(4000, 3000))
        assertEquals(4096 to 4096, ZoomMath.hiResSize(4096, 4096))
        assertEquals(4080 to 3060, ZoomMath.hiResSize(16320, 12240)) // 200 MP: a quarter each side
        assertEquals(2048 to 5, ZoomMath.hiResSize(4097, 10))
        assertEquals(50 to 4096, ZoomMath.hiResSize(100, 8192))
        assertNull(ZoomMath.hiResSize(0, 3000))
    }
}
