package dev.kanorin.gallery.logic

import kotlin.math.abs

/** A photo's zoom: [scale] from 1 (fitted) to [ZoomMath.MAX_SCALE]; [x], [y] its offset from centred, in px. */
data class Zoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val zoomed: Boolean get() = scale > 1f + EPSILON

    private companion object {
        const val EPSILON = 0.001f
    }
}

/** The view, and the photo fitted inside it at 1x, in px. */
data class Frame(val viewW: Float, val viewH: Float, val fitW: Float, val fitH: Float) {
    companion object {
        /** [imageW] x [imageH] fitted into the view; an unknown size (0) fills it. */
        fun fit(viewW: Float, viewH: Float, imageW: Int, imageH: Int): Frame {
            if (imageW <= 0 || imageH <= 0 || viewW <= 0f || viewH <= 0f) return Frame(viewW, viewH, viewW, viewH)
            val s = minOf(viewW / imageW, viewH / imageH)
            return Frame(viewW, viewH, imageW * s, imageH * s)
        }
    }
}

/** Who a one-finger drag belongs to, decided once it passes the touch slop. */
enum class DragOwner { PAN, PAGER, VERTICAL }

/**
 * Viewer zoom maths (spec 5.7). Focus points are px from the view's top-left. The photo is scaled about the
 * view's centre, then offset: a content point p (px from centre at 1x) shows at centre + offset + scale * p.
 */
object ZoomMath {
    const val MAX_SCALE = 8f
    const val DOUBLE_TAP_SCALE = 2.5f
    const val HI_RES_EDGE = 4096

    // Under half a px from the edge counts as at it.
    private const val EDGE_PX = 0.5f

    /** How far the photo can move sideways either way at [scale]: 0 while it fits. */
    fun maxX(scale: Float, f: Frame): Float = ((f.fitW * scale - f.viewW) / 2f).coerceAtLeast(0f)

    fun maxY(scale: Float, f: Frame): Float = ((f.fitH * scale - f.viewH) / 2f).coerceAtLeast(0f)

    fun clamp(z: Zoom, f: Frame): Zoom {
        val s = z.scale.coerceIn(1f, MAX_SCALE)
        val mx = maxX(s, f)
        val my = maxY(s, f)
        return Zoom(s, z.x.coerceIn(-mx, mx), z.y.coerceIn(-my, my))
    }

    /** Scales by [factor] keeping the content under the focus where it is, then clamps. */
    fun zoomBy(z: Zoom, factor: Float, focusX: Float, focusY: Float, f: Frame): Zoom {
        val s = (z.scale * factor).coerceIn(1f, MAX_SCALE)
        val k = s / z.scale
        val fx = focusX - f.viewW / 2f
        val fy = focusY - f.viewH / 2f
        return clamp(Zoom(s, fx * (1f - k) + k * z.x, fy * (1f - k) + k * z.y), f)
    }

    fun panBy(z: Zoom, dx: Float, dy: Float, f: Frame): Zoom = clamp(z.copy(x = z.x + dx, y = z.y + dy), f)

    /** Fitted -> 2.5x around the tap; zoomed at all -> fitted. */
    fun doubleTap(z: Zoom, focusX: Float, focusY: Float, f: Frame): Zoom =
        if (z.zoomed) Zoom() else zoomBy(Zoom(), DOUBLE_TAP_SCALE, focusX, focusY, f)

    /**
     * A drag of ([dx], [dy]) at the slop: mostly vertical -> the image when zoomed, else the screen (details);
     * sideways -> the image while it can still move that way, else the pager.
     */
    fun owner(z: Zoom, f: Frame, dx: Float, dy: Float): DragOwner {
        if (abs(dy) > abs(dx)) return if (z.zoomed) DragOwner.PAN else DragOwner.VERTICAL
        if (!z.zoomed) return DragOwner.PAGER
        val mx = maxX(z.scale, f)
        val room = if (dx > 0) mx - z.x else z.x + mx
        return if (room > EDGE_PX) DragOwner.PAN else DragOwner.PAGER
    }

    /**
     * The zoomed decode's target: the size halved until the long edge is within [edge]. A power-of-two step lets
     * the decoder subsample straight to it, never holding a bigger bitmap first. Null when the size is unknown.
     */
    fun hiResSize(width: Int, height: Int, edge: Int = HI_RES_EDGE): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val long = maxOf(width, height).toLong()
        var step = 1L
        while (long > edge * step) step *= 2
        return (width / step).toInt().coerceAtLeast(1) to (height / step).toInt().coerceAtLeast(1)
    }
}
