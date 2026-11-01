package dev.kanorin.gallery.ui.viewer

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.logic.DetailsText
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.theme.GalleryColors
import java.time.ZoneId

private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
private val TitleStyle = TextStyle(color = GalleryColors.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
private val SubStyle = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp)
private val CloseDrag = 40.dp

/**
 * Swipe up on a photo (spec 5.7, mockup phone 6): when it was taken and modified, name / size / resolution / MP,
 * folder and full path, camera settings, location. Location: the place and the coordinates, the coordinates alone
 * when there's no place, "Location hidden by Android" when the permission is denied, nothing when there are none.
 * No Tags row in v1 (spec 3). Dragging the grab bar down closes it.
 */
@Composable
fun DetailsSheet(
    details: PhotoDetails,
    folderLabel: String,
    folderPath: String,
    locationHidden: Boolean,
    zone: ZoneId,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val closeAt = with(LocalDensity.current) { CloseDrag.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    Column(
        modifier
            .clip(SheetShape)
            .background(GalleryColors.Surface)
            .padding(horizontal = 16.dp)
            .testTag(DETAILS_SHEET_TAG),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .draggable(
                    rememberDraggableState { dragged += it },
                    Orientation.Vertical,
                    onDragStarted = { dragged = 0f },
                    onDragStopped = { if (dragged > closeAt) onClose() },
                )
                .padding(top = 10.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(38.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(GalleryColors.SheetGrab))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            InfoRow(GalleryIcons.Calendar, DetailsText.dateLine(details.dateTaken, zone), DetailsText.modifiedLine(details.dateTaken, details.dateModified, zone))
            InfoRow(
                GalleryIcons.Image, details.name,
                DetailsText.sizeLine(Formatter.formatShortFileSize(context, details.size), details.width, details.height),
            )
            InfoRow(GalleryIcons.Folder, folderLabel, folderPath)
            DetailsText.camera(details.aperture, details.shutter, details.iso, details.focalMm, details.flash)?.let {
                InfoRow(GalleryIcons.Camera, "Camera", it)
            }
            val lat = details.lat
            val lon = details.lon
            when {
                lat != null && lon != null -> {
                    val coords = DetailsText.coordinates(lat, lon)
                    InfoRow(GalleryIcons.Pin, details.place ?: coords, if (details.place != null) coords else null)
                }
                locationHidden -> InfoRow(GalleryIcons.Pin, "Location hidden by Android", null)
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, sub: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, tint = GalleryColors.Muted, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Column {
            Text(title, style = TitleStyle)
            if (sub != null) Text(sub, style = SubStyle)
        }
    }
}
