package dev.kanorin.gallery.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.ShowChip
import dev.kanorin.gallery.logic.SortField
import dev.kanorin.gallery.logic.SortText
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.theme.GalleryColors

fun sortTag(field: SortField) = "sheet-sort-${field.name}"
const val SORT_ORDER_TAG = "sheet-sort-order"
fun chipTag(chip: ShowChip) = "sheet-chip-${chip.name}"
fun groupTag(by: GroupBy) = "sheet-group-${by.name}"
const val BIN_ROW_TAG = "sheet-row-bin"
const val HIDDEN_ROW_TAG = "sheet-row-hidden"
const val SETTINGS_ROW_TAG = "sheet-row-settings"

private val HeadingStyle = TextStyle(color = GalleryColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
private val OptionStyle = TextStyle(color = GalleryColors.Text, fontSize = 14.sp)
private val SideStyle = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp)
private val ChipStyle = TextStyle(fontSize = 12.sp)
private val SegStyle = TextStyle(fontSize = 13.sp)
private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
private val SortFields = listOf(SortField.DATE_TAKEN, SortField.DATE_MODIFIED, SortField.NAME, SortField.SIZE, SortField.DURATION)
private val GroupLabels = listOf(GroupBy.DAY to "Day", GroupBy.MONTH to "Month", GroupBy.YEAR to "Year", GroupBy.NONE to "None")
private val ChipLabels = listOf(
    ShowChip.PHOTOS to "Photos", ShowChip.VIDEOS to "Videos", ShowChip.GIFS to "GIFs",
    ShowChip.SCREENSHOTS to "Screenshots", ShowChip.FAVOURITES to "Favourites",
)

/** The sheet behind the pill's menu button (spec 5.4). A count that is null has not been answered yet. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MenuSheet(
    filter: MediaFilter,
    binCount: Int?,
    hiddenCount: Int?,
    onSort: (SortField) -> Unit,
    onFlipOrder: () -> Unit,
    onChip: (ShowChip, Boolean) -> Unit,
    onGroup: (GroupBy) -> Unit,
    onBin: () -> Unit,
    onHiddenFolders: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = GalleryColors.Surface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            Box(Modifier.padding(vertical = 10.dp).size(width = 38.dp, height = 4.dp).clip(CircleShape).background(GalleryColors.SheetGrab))
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
            Heading("Sort by", first = true)
            SortFields.forEach { field ->
                val on = filter.sort == field
                Row(
                    Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton, onClick = { onSort(field) }).padding(vertical = 7.dp)
                        .testTag(sortTag(field)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RadioDot(on)
                    Text(SortText.title(field), style = OptionStyle)
                    Spacer(Modifier.weight(1f))
                    // Every row keeps the order's room (unchosen: invisible), so picking another sort moves nothing.
                    Text(
                        SortText.order(field, if (on) filter.descending else SortText.defaultDescending(field)), style = SideStyle,
                        modifier = if (on) {
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onFlipOrder)
                                .padding(horizontal = 6.dp, vertical = 4.dp).testTag(SORT_ORDER_TAG)
                        } else {
                            Modifier.alpha(0f).clearAndSetSemantics {}.padding(horizontal = 6.dp, vertical = 4.dp)
                        },
                    )
                }
            }
            Heading("Show")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipLabels.forEach { (chip, label) ->
                    ShowChipView(label, filter.isOn(chip), chipTag(chip), enabled = !filter.isLastKindOn(chip)) { onChip(chip, it) }
                }
            }
            Heading("Group by")
            // A name, size or length sort has no date groups: None shows as chosen and the rest can't be picked.
            val groupable = filter.sort == SortField.DATE_TAKEN || filter.sort == SortField.DATE_MODIFIED
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GalleryColors.Surface2).padding(3.dp)) {
                GroupLabels.forEach { (by, label) ->
                    val on = filter.effectiveGroupBy == by
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                            .background(if (on) GalleryColors.Surface3 else Color.Transparent)
                            .alpha(if (groupable) 1f else 0.4f)
                            .selectable(on, enabled = groupable, role = Role.RadioButton, onClick = { onGroup(by) })
                            .padding(vertical = 7.dp)
                            .testTag(groupTag(by)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = SegStyle.copy(color = if (on) GalleryColors.Text else GalleryColors.Muted))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            MenuRow(GalleryIcons.Trash, "Recycle bin", binCount?.let { "$it ${if (it == 1) "item" else "items"} ›" } ?: "›", BIN_ROW_TAG, onBin)
            MenuRow(GalleryIcons.EyeOff, "Hidden folders", hiddenCount?.let { "$it ›" } ?: "›", HIDDEN_ROW_TAG, onHiddenFolders)
            MenuRow(GalleryIcons.Gear, "Settings", "›", SETTINGS_ROW_TAG, onSettings)
        }
    }
}

@Composable
private fun Heading(text: String, first: Boolean = false) {
    Text(text.uppercase(), style = HeadingStyle, modifier = Modifier.padding(top = if (first) 4.dp else 12.dp, bottom = 8.dp))
}

// Accent ring with an accent dot inside; off is a bare grey ring (mockup .radio).
@Composable
private fun RadioDot(on: Boolean) {
    Box(
        Modifier.size(18.dp).border(2.dp, if (on) GalleryColors.Accent else GalleryColors.CheckboxBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(GalleryColors.Accent))
    }
}

@Composable
private fun ShowChipView(label: String, on: Boolean, tag: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (on) GalleryColors.ChipOn else GalleryColors.Surface2)
            .alpha(if (enabled) 1f else 0.4f)
            .toggleable(on, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 11.dp, vertical = 6.dp)
            .testTag(tag),
    ) {
        val style = ChipStyle.copy(color = if (on) GalleryColors.Accent else GalleryColors.ChipText)
        Row {
            // The tick's room is always there (off: invisible), so a chip keeps its size; the on state is read, not the tick.
            Text("✓ ", style = style, modifier = Modifier.alpha(if (on) 1f else 0f).clearAndSetSemantics {})
            Text(label, style = style)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, side: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).testTag(tag)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(GalleryColors.RowDivider))
        Row(Modifier.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = GalleryColors.Text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = OptionStyle)
            Spacer(Modifier.weight(1f))
            Text(side, style = SideStyle)
        }
    }
}
