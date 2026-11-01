package dev.kanorin.gallery.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.theme.GalleryColors
import kotlinx.coroutines.launch

@Composable
fun PermissionScreen(
    state: PermissionState,
    onGrantAllFiles: () -> Unit,
    onGrantManageMedia: () -> Unit,
    onSkipManageMedia: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!state.allFiles) {
            PermissionCard(
                text = "Gallery needs access to all files so it can show every photo and video on your phone.",
                button = "Allow all files access",
                onClick = onGrantAllFiles,
            )
        } else if (!state.manageMedia) {
            PermissionCard(
                text = "Allow media management so deleting and moving doesn't ask every time.",
                button = "Allow media management",
                onClick = onGrantManageMedia,
                secondary = "Not now",
                onSecondary = onSkipManageMedia,
            )
        }
    }
}

/** Shows [content] once all-files is granted and media management is granted or skipped. */
@Composable
fun PermissionGate(
    state: PermissionState,
    settings: Settings,
    onGrantAllFiles: () -> Unit,
    onGrantManageMedia: () -> Unit,
    content: @Composable () -> Unit,
) {
    // null = not loaded yet; avoids flashing the optional step for someone who already skipped it.
    val dismissed by settings.manageMediaPromptDismissed.collectAsState<Boolean, Boolean?>(initial = null)
    val scope = rememberCoroutineScope()
    when {
        state.allFiles && (state.manageMedia || dismissed == true) -> content()
        state.allFiles && dismissed == null -> Unit
        else -> PermissionScreen(
            state = state,
            onGrantAllFiles = onGrantAllFiles,
            onGrantManageMedia = onGrantManageMedia,
            onSkipManageMedia = { scope.launch { settings.setManageMediaPromptDismissed(true) } },
        )
    }
}

@Composable
internal fun PermissionCard(
    text: String,
    button: String,
    onClick: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    buttonTag: String? = null,
) {
    Surface(color = GalleryColors.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text, color = GalleryColors.Text, textAlign = TextAlign.Center)
            Button(
                onClick = onClick,
                modifier = if (buttonTag != null) Modifier.testTag(buttonTag) else Modifier,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GalleryColors.Accent,
                    contentColor = GalleryColors.Bg,
                ),
            ) { Text(button) }
            if (secondary != null) {
                TextButton(onClick = onSecondary) { Text(secondary, color = GalleryColors.Muted) }
            }
        }
    }
}
