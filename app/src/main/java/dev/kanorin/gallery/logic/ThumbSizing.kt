package dev.kanorin.gallery.logic

/** Largest power of two that keeps both decoded sides >= [targetPx]; 1 when unknown or already small. */
fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    val shortSide = minOf(width, height)
    var sample = 1
    // Compare with a shift-safe bound: sample never exceeds shortSide.
    while (shortSide / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/** Box asked of MediaStore: its thumbnail fits inside, so a 4:3 photo needs 4/3 x the tile to cover it. */
fun thumbBoxPx(sizePx: Int): Int = (sizePx * 4 + 2) / 3

/** A thumbnail covers a square tile of [targetPx] when its short side does. */
fun reachesTarget(width: Int, height: Int, targetPx: Int): Boolean = minOf(width, height) >= targetPx

/** The index stores the stored size and the rotation apart: width x height as the picture is seen. */
fun upright(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
    if (orientation % 180 != 0) height to width else width to height

/** [width] x [height] scaled to cover a [boxW] x [boxH] box (aspect kept, never above the source); null when unknown. */
fun coverSize(width: Int, height: Int, boxW: Int, boxH: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0 || boxW <= 0 || boxH <= 0) return null
    // Integer maths: the deciding side lands exactly on the box, the other rounds up.
    val (w, h) = if (boxW.toLong() * height >= boxH.toLong() * width) {
        boxW to ceilDiv(height.toLong() * boxW, width.toLong())
    } else {
        ceilDiv(width.toLong() * boxH, height.toLong()) to boxH
    }
    return if (w > width || h > height) width to height else w to h
}

private fun ceilDiv(a: Long, b: Long): Int = ((a + b - 1) / b).toInt()
