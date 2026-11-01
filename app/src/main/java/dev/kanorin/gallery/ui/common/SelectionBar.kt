package dev.kanorin.gallery.ui.common

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.ui.theme.GalleryColors
import java.util.Locale

/** The pill row's height. The selection bar takes the same slot, so nothing below moves (spec 5.2, section 4). */
val TopBarHeight = 60.dp

const val MOVE_TO_FOLDER_TAG = "selection-move-to-folder"
const val COPY_TO_FOLDER_TAG = "selection-copy-to-folder"

private val CountStyle = TextStyle(color = GalleryColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
private val SizeStyle = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp)

/** `✕ · "N selected" + total size · select all · share · delete · ⋮` (spec 5.2); ⋮ = Move / Copy to folder. [totalBytes] is bytes. */
@Composable
fun SelectionBar(
    count: Int,
    totalBytes: Long,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: () -> Unit,
    onCopyToFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(TopBarHeight).padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BarButton(GalleryIcons.Close, "Close", onClose)
        Column(Modifier.weight(1f)) {
            Text(String.format(Locale.ENGLISH, "%,d selected", count), style = CountStyle, maxLines = 1)
            Text(Formatter.formatShortFileSize(context, totalBytes), style = SizeStyle, maxLines = 1)
        }
        BarButton(GalleryIcons.SelectAll, "Select all", onSelectAll)
        BarButton(GalleryIcons.Share, "Share", onShare)
        BarButton(GalleryIcons.Trash, "Delete", onDelete)
        // The menu shares the button's Box, so it drops from the ⋮.
        Box {
            BarButton(GalleryIcons.Dots, "More", onClick = { menu = true })
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = GalleryColors.Surface2) {
                DropdownMenuItem(
                    text = { Text("Move to folder") }, onClick = { menu = false; onMoveToFolder() },
                    modifier = Modifier.testTag(MOVE_TO_FOLDER_TAG),
                )
                DropdownMenuItem(
                    text = { Text("Copy to folder") }, onClick = { menu = false; onCopyToFolder() },
                    modifier = Modifier.testTag(COPY_TO_FOLDER_TAG),
                )
            }
        }
    }
}

/** The one round icon button of every top bar: 36 dp with a 20 dp icon, or [BigBarButton]'s 44 / 24 dp. */
@Composable
internal fun BarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier.size(size).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = GalleryColors.Text, modifier = Modifier.size(iconSize))
    }
}

/** The title bars' 44 dp button with a 24 dp icon, 6 dp in from the edge (bin, hidden folders, picker, settings). */
@Composable
internal fun BigBarButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    BarButton(icon, label, onClick, modifier.padding(start = 6.dp), size = 44.dp, iconSize = 24.dp)
