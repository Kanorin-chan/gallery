package dev.kanorin.gallery.logic

import java.util.Locale

/**
 * The playing video's zoom (spec 5.8): content point p shows at t + scale * p (px, origin top-left), scale 1-5.
 * [ZoomMath]'s maths on a frame that is the whole view (the letterbox zooms too): t stays in [w - scale * w, 0].
 */
data class VideoZoom(val scale: Float = 1f, val tx: Float = 0f, val ty: Float = 0f) {
    /** Past a hair over 1x (the mockup's 1.01): the pill shows and a moving finger pans. */
    val zoomed: Boolean get() = scale > ZOOMED_ABOVE

    /** The pill's "2.3×". */
    val label: String get() = "%.1f×".format(Locale.ROOT, scale)

    /** [newScale] (clamped to 1-5) with the content under ([fx], [fy]) kept there, then clamped to the view. */
    fun zoomAt(newScale: Float, fx: Float, fy: Float, w: Float, h: Float): VideoZoom {
        val s = newScale.coerceIn(1f, MAX_SCALE)
        return of(ZoomMath.zoomBy(centred(w, h), s / scale, fx, fy, Frame(w, h, w, h)), w, h)
    }

    fun pan(dx: Float, dy: Float, w: Float, h: Float): VideoZoom = of(ZoomMath.panBy(centred(w, h), dx, dy, Frame(w, h, w, h)), w, h)

    fun reset(): VideoZoom = VideoZoom()

    // ZoomMath offsets from centred: the same picture is t + (scale - 1) * size / 2 from there.
    private fun centred(w: Float, h: Float) = Zoom(scale, tx + (scale - 1f) * w / 2f, ty + (scale - 1f) * h / 2f)

    companion object {
        const val MAX_SCALE = 5f
        const val ZOOMED_ABOVE = 1.01f

        private fun of(z: Zoom, w: Float, h: Float) = VideoZoom(z.scale, z.x - (z.scale - 1f) * w / 2f, z.y - (z.scale - 1f) * h / 2f)
    }
}
