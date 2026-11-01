package dev.kanorin.gallery.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.logic.FolderNameValidator
import dev.kanorin.gallery.logic.NameError
import dev.kanorin.gallery.ui.theme.GalleryColors

const val NAME_FIELD_TAG = "folder-name-field"

/** The mockup's input maxlength. */
private const val MAX_NAME = 60
private val FieldShape = RoundedCornerShape(12.dp)
private val FieldStyle = TextStyle(color = GalleryColors.Text, fontSize = 15.sp)
private val ErrorStyle = TextStyle(color = GalleryColors.Danger, fontSize = 12.sp)

/**
 * New folder (spec 5.3, mockup phone 2), also Rename with its own [title] / [confirm] / [initial]. The name is checked
 * on [confirm] against [siblings] (null while they're still being listed: the button waits); [onConfirm] gets it trimmed.
 */
@Composable
fun NewFolderDialog(
    siblings: Collection<String>?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "New folder",
    subline: String? = "Created in Pictures/ on your phone",
    confirm: String = "Create",
    initial: String = "",
) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var error by rememberSaveable { mutableStateOf<NameError?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun submit() {
        val names = siblings ?: return
        val problem = FolderNameValidator.validate(text.text, names)
        if (problem != null) {
            error = problem
        } else {
            onDismiss()
            onConfirm(text.text.trim())
        }
    }

    GalleryDialog(title, subline, onDismiss) {
        BasicTextField(
            value = text,
            onValueChange = {
                if (it.text.length <= MAX_NAME) {
                    if (it.text != text.text) error = null
                    text = it
                }
            },
            singleLine = true,
            textStyle = FieldStyle,
            cursorBrush = SolidColor(GalleryColors.Accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .testTag(NAME_FIELD_TAG),
            decorationBox = { field ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(GalleryColors.Surface2, FieldShape)
                        .border(1.5.dp, if (focused) GalleryColors.Accent else GalleryColors.FieldBorder, FieldShape)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    if (text.text.isEmpty()) Text("Folder name", style = FieldStyle.copy(color = GalleryColors.Muted), maxLines = 1)
                    field()
                }
            },
        )
        Text(
            error?.message.orEmpty(), style = ErrorStyle,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp).heightIn(min = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.End),
        ) {
            DialogTextButton("Cancel", onDismiss)
            DialogTextButton(confirm, ::submit, bold = true)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
