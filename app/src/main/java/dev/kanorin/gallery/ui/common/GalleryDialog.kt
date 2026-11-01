package dev.kanorin.gallery.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kanorin.gallery.ui.theme.GalleryColors

private val DialogShape = RoundedCornerShape(24.dp)
private val TitleStyle = TextStyle(color = GalleryColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
private val SublineStyle = TextStyle(color = GalleryColors.Muted, fontSize = 13.sp)
private val TextButtonStyle = TextStyle(color = GalleryColors.Accent, fontSize = 14.sp)

/** The mockup's .dialog: a rounded card 18 dp from the sides, title, optional subline, then [content]. */
@Composable
fun GalleryDialog(title: String, subline: String?, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 18.dp)
                .fillMaxWidth()
                .background(GalleryColors.Surface, DialogShape)
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        ) {
            Text(title, style = TitleStyle, modifier = Modifier.padding(horizontal = 4.dp))
            if (subline != null) Text(subline, style = SublineStyle, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp))
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** An accent text button (mockup .nf-btns / .dcancel); [color] = Danger for a destructive confirm. */
@Composable
fun DialogTextButton(
    text: String,
    onClick: () -> Unit,
    bold: Boolean = false,
    modifier: Modifier = Modifier,
    color: Color = GalleryColors.Accent,
) {
    Text(
        text,
        style = TextButtonStyle.copy(color = color, fontWeight = if (bold) FontWeight.SemiBold else null),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}
