package dev.kanorin.gallery.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.logic.BinPolicy
import dev.kanorin.gallery.logic.DeleteText
import dev.kanorin.gallery.settings.DeleteChoice
import dev.kanorin.gallery.ui.theme.GalleryColors

const val REMEMBER_TAG = "delete-remember"

private val OptionShape = RoundedCornerShape(14.dp)
private val BoxShape = RoundedCornerShape(4.dp)
private val OptionTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
private val OptionHint = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp)
private val RememberStyle = TextStyle(color = GalleryColors.RememberText, fontSize = 13.sp)

/**
 * Spec 5.5, mockup phone 5: "Delete N items?", Recycle bin or permanently, and "Remember my choice".
 * [onChoice] gets the pick and whether the box was ticked; the caller persists it only then.
 */
@Composable
fun DeleteDialog(photos: Int, videos: Int, onChoice: (DeleteChoice, remember: Boolean) -> Unit, onCancel: () -> Unit) {
    var remember by rememberSaveable { mutableStateOf(false) }
    GalleryDialog(DeleteText.title(photos + videos), DeleteText.subline(photos, videos), onDismiss = onCancel) {
        Choice(
            GalleryIcons.Trash, "Move to Recycle bin", "Restore any time within ${BinPolicy.RETENTION_DAYS} days",
            background = GalleryColors.OptionPrimary, titleColor = GalleryColors.Accent,
        ) { onChoice(DeleteChoice.BIN, remember) }
        Choice(
            GalleryIcons.Flame, "Delete permanently", "Gone from the phone. Can't be undone",
            background = GalleryColors.Surface2, titleColor = GalleryColors.Danger,
        ) { onChoice(DeleteChoice.PERMANENT, remember) }
        Row(
            Modifier
                .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)
                .clip(BoxShape)
                .toggleable(remember, role = Role.Checkbox) { remember = it }
                .testTag(REMEMBER_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .background(if (remember) GalleryColors.Accent else Color.Transparent, BoxShape)
                    .border(2.dp, if (remember) GalleryColors.Accent else GalleryColors.CheckboxBorder, BoxShape),
            )
            Text("Remember my choice", style = RememberStyle)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 2.dp), horizontalArrangement = Arrangement.End) {
            DialogTextButton("Cancel", onCancel)
        }
    }
}

/** Mockup .dopt: icon, bold title, muted hint. */
@Composable
private fun Choice(icon: ImageVector, title: String, hint: String, background: Color, titleColor: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(OptionShape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = GalleryColors.Text, modifier = Modifier.size(20.dp))
        Column {
            Text(title, style = OptionTitle.copy(color = titleColor))
            Text(hint, style = OptionHint)
        }
    }
}
