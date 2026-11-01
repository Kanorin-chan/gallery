package dev.kanorin.gallery.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.kanorin.gallery.BuildConfig
import dev.kanorin.gallery.settings.DeleteChoice
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.BigBarButton
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.folder.INSIDE_COLUMN_RANGE
import dev.kanorin.gallery.ui.folders.FOLDER_COLUMN_RANGE
import dev.kanorin.gallery.ui.permission.PermissionCard
import dev.kanorin.gallery.ui.permission.PermissionState
import dev.kanorin.gallery.ui.permission.launchAllFilesAccess
import dev.kanorin.gallery.ui.permission.launchManageMedia
import dev.kanorin.gallery.ui.permission.readPermissionState
import dev.kanorin.gallery.ui.theme.GalleryColors
import kotlinx.coroutines.launch

const val SETTINGS_ROUTE = "settings"
const val PERMISSIONS_ROUTE = "settings/permissions"

const val SETTINGS_BACK_TAG = "settings-back"
const val DELETE_ASKS_TAG = "settings-delete-asks"
const val SHOW_HIDDEN_TAG = "settings-show-hidden"
const val PERMISSIONS_ROW_TAG = "settings-permissions"
const val GRID_ALL_TAG = "settings-grid-all"
const val GRID_FOLDERS_TAG = "settings-grid-folders"
const val GRID_INSIDE_TAG = "settings-grid-inside"
const val ALL_FILES_BUTTON_TAG = "permissions-all-files"
const val MANAGE_MEDIA_BUTTON_TAG = "permissions-manage-media"
fun skipTag(seconds: Int) = "settings-skip-$seconds"
fun holdTag(speed: Float) = "settings-hold-$speed"
fun plusTag(stepper: String) = "$stepper-plus"
fun minusTag(stepper: String) = "$stepper-minus"
fun valueTag(stepper: String) = "$stepper-value"

private val TitleStyle = TextStyle(color = GalleryColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
private val SectionStyle = TextStyle(color = GalleryColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
private val RowTitleStyle = TextStyle(color = GalleryColors.Text, fontSize = 15.sp)
private val SubStyle = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp)
private val ChoiceStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
private val ValueStyle = TextStyle(color = GalleryColors.Text, fontSize = 15.sp, textAlign = TextAlign.Center)
private val ChoiceShape = RoundedCornerShape(16.dp)

@Composable
fun SettingsScreen(settings: Settings, onBack: () -> Unit, onPermissions: () -> Unit) {
    val scope = rememberCoroutineScope()
    val skip by settings.skipSeconds.collectAsState(Settings.DEFAULT_SKIP_SECONDS)
    val hold by settings.holdSpeed.collectAsState(Settings.DEFAULT_HOLD_SPEED)
    val choice by settings.rememberedDeleteChoice.collectAsState(null)
    val gridAll by settings.gridAll.collectAsState(Settings.DEFAULT_GRID_ALL)
    val gridFolders by settings.gridFolders.collectAsState(Settings.DEFAULT_GRID_FOLDERS)
    val gridInside by settings.gridInside.collectAsState(Settings.DEFAULT_GRID_INSIDE)
    val showHidden by settings.showHidden.collectAsState(Settings.DEFAULT_SHOW_HIDDEN)

    SettingsFrame("Settings", onBack) {
        Section("Video")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Skip on double-tap", style = RowTitleStyle, modifier = Modifier.weight(1f))
            Choices(Settings.SKIP_SECONDS_OPTIONS, skip, { "$it s" }, { skipTag(it) }) { scope.launch { settings.setSkipSeconds(it) } }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Hold speed", style = RowTitleStyle, modifier = Modifier.weight(1f))
            Choices(Settings.HOLD_SPEED_OPTIONS, hold, ::speedLabel, { holdTag(it) }) { scope.launch { settings.setHoldSpeed(it) } }
        }

        Section("Delete")
        val remembered = choice
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Delete asks every time", style = RowTitleStyle)
                when (remembered) {
                    DeleteChoice.BIN -> Text("Always moves to Recycle bin", style = SubStyle)
                    DeleteChoice.PERMANENT -> Text("Always deletes permanently", style = SubStyle)
                    null -> Unit
                }
            }
            // On with no remembered choice has nothing to turn off; the dialog's tick-box sets one.
            GallerySwitch(
                checked = remembered == null, enabled = remembered != null, tag = DELETE_ASKS_TAG,
            ) { if (it) scope.launch { settings.setRememberedDeleteChoice(null) } }
        }

        Section("Grid")
        Text("Default grid size", style = RowTitleStyle, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Text(
            "Folders with their own saved size keep it.", style = SubStyle,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
        Stepper("All photos", GRID_ALL_TAG, gridAll, Settings.GRID_ALL_RANGE) { scope.launch { settings.setGridAll(it) } }
        Stepper("Folders", GRID_FOLDERS_TAG, gridFolders, FOLDER_COLUMN_RANGE) { scope.launch { settings.setGridFolders(it) } }
        Stepper("Inside a folder", GRID_INSIDE_TAG, gridInside, INSIDE_COLUMN_RANGE) { scope.launch { settings.setGridInside(it) } }

        Section("Folders")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Show hidden folders", style = RowTitleStyle, modifier = Modifier.weight(1f))
            GallerySwitch(checked = showHidden, enabled = true, tag = SHOW_HIDDEN_TAG) { scope.launch { settings.setShowHidden(it) } }
        }

        Section("Permissions")
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onPermissions)
                .padding(horizontal = 16.dp, vertical = 14.dp).testTag(PERMISSIONS_ROW_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Permissions", style = RowTitleStyle, modifier = Modifier.weight(1f))
            Text("›", style = RowTitleStyle.copy(color = GalleryColors.Muted, fontSize = 20.sp))
        }

        Text(
            "Gallery ${BuildConfig.VERSION_NAME}", style = SubStyle,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center,
        )
    }
}

/** What "All files access" and "Media management" are now, each with a button that reopens its system page. */
@Composable
fun PermissionsSettingsScreen(
    onBack: () -> Unit,
    readState: (() -> PermissionState)? = null,
    onOpenAllFiles: (() -> Unit)? = null,
    onOpenManageMedia: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val read = readState ?: { readPermissionState(context) }
    val openAllFiles = onOpenAllFiles ?: { launchAllFilesAccess(context) }
    val openManageMedia = onOpenManageMedia ?: { launchManageMedia(context) }
    var state by remember { mutableStateOf(read()) }
    // The user comes back from the system page.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = read() }
    // The Not-now flag only gates the first-run step; here the button is always offered.
    SettingsFrame("Permissions", onBack) {
        Box(Modifier.padding(16.dp)) {
            PermissionCard(
                text = "All files access: ${if (state.allFiles) "Allowed" else "Not allowed"}",
                button = "Open settings", onClick = openAllFiles, buttonTag = ALL_FILES_BUTTON_TAG,
            )
        }
        Box(Modifier.padding(horizontal = 16.dp)) {
            PermissionCard(
                text = "Media management: ${if (state.manageMedia) "Allowed" else "Not allowed"}",
                button = "Open settings", onClick = openManageMedia, buttonTag = MANAGE_MEDIA_BUTTON_TAG,
            )
        }
    }
}

@Composable
private fun SettingsFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(GalleryColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BigBarButton(GalleryIcons.Back, "Back", onBack, Modifier.testTag(SETTINGS_BACK_TAG))
            Text(title, style = TitleStyle, modifier = Modifier.padding(start = 6.dp))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun Section(label: String) {
    Text(label, style = SectionStyle, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
}

/** A segmented control: one chip per option, the current one lit. */
@Composable
private fun <T> Choices(options: List<T>, current: T, label: (T) -> String, tag: (T) -> String, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val on = option == current
            Box(
                Modifier.clip(ChoiceShape)
                    .background(if (on) GalleryColors.ChipOn else GalleryColors.Surface2)
                    .selectable(on, role = Role.RadioButton, onClick = { onPick(option) })
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .testTag(tag(option)),
            ) {
                Text(label(option), style = ChoiceStyle.copy(color = if (on) GalleryColors.Accent else GalleryColors.ChipText))
            }
        }
    }
}

@Composable
private fun GallerySwitch(checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag(tag),
        colors = SwitchDefaults.colors(
            checkedThumbColor = GalleryColors.OnAccent, checkedTrackColor = GalleryColors.Accent,
            uncheckedThumbColor = GalleryColors.Muted, uncheckedTrackColor = GalleryColors.Surface3,
            uncheckedBorderColor = GalleryColors.CheckboxBorder,
            disabledCheckedThumbColor = GalleryColors.OnAccent, disabledCheckedTrackColor = GalleryColors.ChipOn,
        ),
    )
}

@Composable
private fun Stepper(label: String, tag: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = RowTitleStyle.copy(color = GalleryColors.ChipText), modifier = Modifier.weight(1f))
        StepButton("−", enabled = value > range.first, tag = minusTag(tag)) { onChange(value - 1) }
        Text(value.toString(), style = ValueStyle, modifier = Modifier.widthIn(min = 36.dp).testTag(valueTag(tag)))
        StepButton("+", enabled = value < range.last, tag = plusTag(tag)) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(GalleryColors.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = RowTitleStyle.copy(color = if (enabled) GalleryColors.Text else GalleryColors.EmptyGlyph, fontSize = 20.sp))
    }
}

// 1.5× / 2× / 3×: whole speeds drop the ".0".
private fun speedLabel(speed: Float) = if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×"
