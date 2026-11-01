package dev.kanorin.gallery.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palette from the approved mockup (gallery-v11.html). */
object GalleryColors {
    val Bg = Color(0xFF0D0E11)
    val Surface = Color(0xFF15171C)
    val Surface2 = Color(0xFF1F2229)
    val Surface3 = Color(0xFF2A2E37)
    val Text = Color(0xFFECEEF2)
    val Muted = Color(0xFF8D929C)
    val Accent = Color(0xFF7CC4FF)
    val AccentPressed = Color(0xFFA9D8FF)
    val OnAccent = Color(0xFF04121F)
    val Danger = Color(0xFFFF6B6B)
    val PillDivider = Color(0xFF3A3E48)
    val SelectRing = Color(0xFF4A4F5A)
    /** Tile check circle while selecting: white ring over a 25% black scrim (mockup .ck). */
    val CheckRing = Color(0xFFFFFFFF)
    val CheckScrim = Color(0x40000000)
    /** Folders (mockup .folder): heart badge disc and heart, empty-cover glyph, the "+ New folder" dashed border. */
    val BadgeScrim = Color(0x8C000000)
    val Heart = Color(0xFFFF8FA3)
    val EmptyGlyph = Color(0xFF5A606B)
    val DashedBorder = Color(0xFF4A505C)
    /** GalleryToast (mockup .toast): light card, dark text, blue action. */
    val ToastBg = Color(0xFFE9ECF1)
    val ToastText = Color(0xFF101318)
    val ToastAction = Color(0xFF0A6BB8)
    /** Dialog text field border (mockup .nf-in); accent while focused. */
    val FieldBorder = Color(0xFF3A3F4A)
    /** Highlighted dialog option (mockup .dopt.primary). */
    val OptionPrimary = Color(0xFF1E3346)
    /** The delete dialog's "Remember my choice" (mockup .remember): label, and the box's border while off. */
    val RememberText = Color(0xFFC9CCD3)
    val CheckboxBorder = Color(0xFF555B66)
    /** The ⋮ sheet (mockup .sheet): grab bar, chip text, an on chip, the rows' top divider. */
    val SheetGrab = Color(0xFF3D424C)
    val ChipText = Color(0xFFCFD3DA)
    val ChipOn = Color(0xFF23384D)
    val RowDivider = Color(0xFF23262D)
    /** The viewer (mockup phone 6): black behind the photo, the bars' scrim (.vtop #000a), the action labels. */
    val ViewerBg = Color(0xFF000000)
    val ViewerScrim = Color(0xAA000000)
    val ActionText = RememberText
    /** The player (mockup phone 7): times, the bar's layers, the play disc, a lit button, pills, ripples, preview, scrims. */
    val PlayerText = Color(0xFFDFE2E8)
    val TrackBg = Color(0x30FFFFFF)
    val TrackBuffered = Color(0x55FFFFFF)
    val KnobRing = Color(0x337CC4FF)
    val PlayDisc = Color(0x1FFFFFFF)
    val ButtonOn = Color(0x227CC4FF)
    val PlayerPill = Color(0xD914161B)
    val Ripple = Color(0x33FFFFFF)
    val PreviewBorder = Color(0xFFFFFFFF)
    val PreviewBg = Color(0xFF222222)
    val PlayerScrimTop = Color(0xBB000000)
    val PlayerScrimBottom = Color(0xDD000000)
    /** The speed list (mockup .spdmenu, rgba(24,26,32,.97)). */
    val SpeedMenuBg = Color(0xF7181A20)
}

private val GalleryColorScheme = darkColorScheme(
    primary = GalleryColors.Accent,
    onPrimary = GalleryColors.Bg,
    background = GalleryColors.Bg,
    onBackground = GalleryColors.Text,
    surface = GalleryColors.Surface,
    onSurface = GalleryColors.Text,
    surfaceVariant = GalleryColors.Surface2,
    onSurfaceVariant = GalleryColors.Muted,
    surfaceContainer = GalleryColors.Surface,
    surfaceContainerHigh = GalleryColors.Surface2,
    surfaceContainerHighest = GalleryColors.Surface3,
    outline = GalleryColors.Muted,
    error = GalleryColors.Danger,
    onError = GalleryColors.Bg,
)

/** Dark only (spec 5.9). */
@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GalleryColorScheme, content = content)
}
