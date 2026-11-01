package dev.kanorin.gallery.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The mockup's (gallery-v11.html) 24-unit line glyphs; tint them with Icon. */
object GalleryIcons {
    val Search = stroked("Search", circle(11f, 11f, 7f), "M20,20l-3.6,-3.6")
    val Grid = stroked(
        "Grid",
        roundRect(3.5f, 3.5f, 7f, 7f, 1.6f), roundRect(13.5f, 3.5f, 7f, 7f, 1.6f),
        roundRect(3.5f, 13.5f, 7f, 7f, 1.6f), roundRect(13.5f, 13.5f, 7f, 7f, 1.6f),
    )
    val Folder = stroked(
        "Folder",
        "M3,7.5A2.5,2.5 0 0 1 5.5,5H9l2,2.2h7.5A2.5,2.5 0 0 1 21,9.7v7.8a2.5,2.5 0 0 1 -2.5,2.5h-13A2.5,2.5 0 0 1 3,17.5z",
    )
    val Dots = filled("Dots", circle(12f, 5f, 1.7f), circle(12f, 12f, 1.7f), circle(12f, 19f, 1.7f))
    val PlaySmall = filled("PlaySmall", "M7,5v14l11,-7z")
    val Close = stroked("Close", "M6,6l12,12M18,6L6,18")
    val SelectAll = stroked("SelectAll", roundRect(3.5f, 3.5f, 17f, 17f, 3f), "M8,12.3l3,3l5.5,-6")
    val Share = stroked(
        "Share",
        circle(6f, 12f, 2.4f), circle(17.5f, 6f, 2.4f), circle(17.5f, 18f, 2.4f), "M8.2,10.9l7.2,-3.7M8.2,13.1l7.2,3.7",
    )
    val Trash = stroked("Trash", "M4,7h16M9.5,7V4.5h5V7M6.5,7l0.9,12.5h9.2L17.5,7M10,11v5M14,11v5")
    val Flame = stroked(
        "Flame",
        "M12,21c-3.9,0 -6.5,-2.6 -6.5,-6.2 0,-3.4 2.6,-5.6 3.6,-8.8 1.3,1.4 2,2.8 2.1,4.3 1,-1 1.7,-2.5 1.8,-4.3 " +
            "2.7,2 5.5,5.4 5.5,8.8 0,3.6 -2.6,6.2 -6.5,6.2z",
    )
    val Heart = filled("Heart", "M12,20s-7.5,-4.6 -7.5,-10.2A4.3,4.3 0 0 1 12,7a4.3,4.3 0 0 1 7.5,2.8C19.5,15.4 12,20 12,20z")
    val EyeOff = stroked(
        "EyeOff",
        "M3,3l18,18M10.6,6.2A9.6,9.6 0 0 1 12,6c5,0 8.5,4.5 9.5,6 -0.5,0.8 -1.6,2.2 -3,3.5M6.6,7.7C4.6,9 3.2,10.9 2.5,12c1,1.5 4.5,6 9.5,6 1.6,0 3,-0.4 4.3,-1.1M9.9,10a3,3 0 0 0 4.2,4.2",
    )
    val Gear = stroked(
        "Gear",
        circle(12f, 12f, 3f),
        "M12,2.8v2.4M12,18.8v2.4M2.8,12h2.4M18.8,12h2.4M5.5,5.5l1.7,1.7M16.8,16.8l1.7,1.7M5.5,18.5l1.7,-1.7M16.8,7.2l1.7,-1.7",
    )
    val Plus = stroked("Plus", "M12,5v14M5,12h14")
    val Back = stroked("Back", "M15,5l-7,7l7,7")
    /** The tile check: the mockup draws it at 11 px with a 4-unit stroke. */
    val Check = stroked("Check", "M5,12.5l4.5,4.5L19,7.5")
    val CheckHeavy = stroked("CheckHeavy", "M5,12.5l4.5,4.5L19,7.5", width = 4f)
    /** The viewer (mockup phone 6): the heart's outline when off, edit, the details rows' glyphs, the poster's play. */
    val HeartOutline = stroked("HeartOutline", "M12,20s-7.5,-4.6 -7.5,-10.2A4.3,4.3 0 0 1 12,7a4.3,4.3 0 0 1 7.5,2.8C19.5,15.4 12,20 12,20z")
    val Edit = stroked("Edit", "M4,20h4L19,9l-4,-4L4,16z", "M13.5,6.5l4,4")
    val Calendar = stroked("Calendar", roundRect(3.5f, 5f, 17f, 15.5f, 2.5f), "M3.5,10h17M8,3v4M16,3v4")
    val Image = stroked("Image", roundRect(3.5f, 4.5f, 17f, 15f, 2.5f), circle(9f, 10f, 1.8f), "M20.5,16l-5,-5l-9,8.5")
    val Camera = stroked(
        "Camera",
        "M4,8.5A2,2 0 0 1 6,6.5h2l1.6,-2h4.8l1.6,2h2a2,2 0 0 1 2,2V18a2,2 0 0 1 -2,2H6a2,2 0 0 1 -2,-2z",
        circle(12f, 13f, 3.6f),
    )
    val Pin = stroked("Pin", "M12,21s-6.5,-6 -6.5,-11a6.5,6.5 0 0 1 13,0c0,5 -6.5,11 -6.5,11z", circle(12f, 10f, 2.3f))
    val Play = filled("Play", "M7.5,5v14l11.5,-7z")
    /** The player (mockup phone 7). */
    val Pause = filled("Pause", roundRect(6.5f, 5f, 3.8f, 14f, 1f), roundRect(13.7f, 5f, 3.8f, 14f, 1f))
    val Rewind = filled("Rewind", "M20,6v12l-8,-6zM12,6v12l-8,-6z")
    val FastForward = filled("FastForward", "M4,6v12l8,-6zM12,6v12l8,-6z")
    val RotationLock = stroked(
        "RotationLock",
        "M20,12a8,8 0 0 1 -13.7,5.6M4,12a8,8 0 0 1 13.7,-5.6",
        "M17.7,3v3.6h-3.6M6.3,21v-3.6h3.6",
        roundRect(9f, 11f, 6f, 4.6f, 1f),
        "M10.3,11V9.8a1.7,1.7 0 0 1 3.4,0V11",
    )
    val Rotate = stroked("Rotate", "M20,12a8,8 0 1 1 -2.4,-5.7", "M20,4v4.5h-4.5")
    /** The speed pill: slow (a play glyph behind a bar) and the lock. */
    val Slow = builder("Slow").apply {
        addPath(pathData = PathParser().parsePathString("M9,6v12l8,-6z").toNodes(), fill = SolidColor(Color.Black))
        addPath(
            pathData = PathParser().parsePathString("M5,6v12").toNodes(), stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
    val Lock = stroked("Lock", roundRect(5f, 10.5f, 14f, 10f, 2.2f), "M8,10.5V7.8a4,4 0 0 1 8,0v2.7")
}

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1 0 ${2 * r},0a$r,$r 0 1 0 ${-2 * r},0z"

private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float): String {
    val hw = w - 2 * r
    val vh = h - 2 * r
    return "M${x + r},${y}h${hw}a$r,$r 0 0 1 $r,${r}v${vh}a$r,$r 0 0 1 ${-r},${r}h${-hw}" +
        "a$r,$r 0 0 1 ${-r},${-r}v${-vh}a$r,$r 0 0 1 $r,${-r}z"
}

private fun builder(name: String) = ImageVector.Builder(
    name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
)

private fun stroked(name: String, vararg paths: String, width: Float = 1.9f): ImageVector = builder(name).apply {
    paths.forEach {
        addPath(
            pathData = PathParser().parsePathString(it).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}.build()

private fun filled(name: String, vararg paths: String): ImageVector = builder(name).apply {
    paths.forEach { addPath(pathData = PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black)) }
}.build()
