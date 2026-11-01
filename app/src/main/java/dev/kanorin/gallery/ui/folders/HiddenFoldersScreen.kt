package dev.kanorin.gallery.ui.folders

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kanorin.gallery.logic.FolderCard
import dev.kanorin.gallery.ui.common.BigBarButton
import dev.kanorin.gallery.ui.common.GalleryIcons
import dev.kanorin.gallery.ui.theme.GalleryColors

const val HIDDEN_FOLDERS_ROUTE = "hidden-folders"
fun hiddenRowTag(path: String) = "hidden-row-$path"
fun unhideTag(path: String) = "unhide-$path"

private val TitleStyle = TextStyle(color = GalleryColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
private val NameStyle = TextStyle(color = GalleryColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
private val PathStyle = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp)
private val ActionStyle = TextStyle(color = GalleryColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)

/** Every hidden folder, by rule or by choice, each with Unhide (spec 7: one tap unhides). */
@Composable
fun HiddenFoldersScreen(viewModel: HiddenFoldersViewModel, onBack: () -> Unit) {
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    HiddenFoldersContent(hidden, onBack, viewModel::unhide)
}

@Composable
internal fun HiddenFoldersContent(hidden: List<FolderCard>?, onBack: () -> Unit, onUnhide: (FolderCard) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(GalleryColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BigBarButton(GalleryIcons.Back, "Back", onBack)
            Text("Hidden folders", style = TitleStyle, modifier = Modifier.padding(start = 6.dp))
        }
        when {
            hidden == null -> Unit
            hidden.isEmpty() -> Text("No hidden folders", style = PathStyle, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(hidden, key = { it.path }) { card -> HiddenRow(card) { onUnhide(card) } }
            }
        }
    }
}

@Composable
private fun HiddenRow(card: FolderCard, onUnhide: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(hiddenRowTag(card.path)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(GalleryIcons.Folder, contentDescription = null, tint = GalleryColors.Muted, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, style = NameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(card.path, style = PathStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "Unhide", style = ActionStyle,
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onUnhide)
                .padding(horizontal = 12.dp, vertical = 10.dp).testTag(unhideTag(card.path)),
        )
    }
}
