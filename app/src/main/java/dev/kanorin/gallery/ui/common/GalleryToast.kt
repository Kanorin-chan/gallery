package dev.kanorin.gallery.ui.common

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.fileops.OpResult
import dev.kanorin.gallery.ui.theme.GalleryColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

const val TOAST_TAG = "gallery-toast"
const val TOAST_ACTION_TAG = "gallery-toast-action"
const val TOAST_MS = 3_500L

private val ToastShape = RoundedCornerShape(14.dp)
private val MessageStyle = TextStyle(color = GalleryColors.ToastText, fontSize = 13.sp)
private val ActionStyle = TextStyle(color = GalleryColors.ToastAction, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)

/**
 * The app's toasts (one at a time) and a progress line for a long file op, shown instead of the toast until cleared.
 * Plain StateFlows: file ops post from IO threads, and the state outlives any one screen.
 */
class ToastHostState {
    /** Identity matters: a repeat of the same text is a new toast with a fresh 3.5 s. */
    class Toast internal constructor(
        val id: Long,
        val message: String,
        val action: String?,
        val onAction: (() -> Unit)?,
        private val onGone: (() -> Unit)?,
    ) {
        private val left = AtomicBoolean(false)

        internal fun leave(acted: Boolean) {
            if (left.compareAndSet(false, true) && !acted) onGone?.invoke()
        }
    }

    private val ids = AtomicLong()
    private val current = MutableStateFlow<Toast?>(null)
    private val line = MutableStateFlow<String?>(null)

    val toast: StateFlow<Toast?> = current.asStateFlow()
    val progress: StateFlow<String?> = line.asStateFlow()

    /**
     * Replaces whatever toast is showing. [onGone] runs once if this toast leaves without its action (timed out or
     * replaced): a held op finishes then.
     */
    fun show(message: String, action: String? = null, onGone: (() -> Unit)? = null, onAction: (() -> Unit)? = null) {
        val next = Toast(ids.incrementAndGet(), message, action, onAction, onGone)
        while (true) {
            val old = current.value
            if (current.compareAndSet(old, next)) {
                old?.leave(acted = false)
                return
            }
        }
    }

    /** The action, once, and only while [toast] is still the one showing: a late tap never undoes a finished op. */
    fun act(toast: Toast) {
        if (!current.compareAndSet(toast, null)) return
        toast.leave(acted = true)
        toast.onAction?.invoke()
    }

    /** "Moving 120 of 3,400…" while an op runs; null when it's done. */
    fun setProgress(line: String?) {
        this.line.value = line
    }

    fun dismiss(toast: Toast) {
        if (current.compareAndSet(toast, null)) toast.leave(acted = false)
    }
}

/**
 * Runs a file op with its progress line ([line] of current, total) in the toast slot. The ops report per file;
 * anything that still escapes fails every path instead of taking the app's scope down.
 */
suspend fun ToastHostState.runOp(
    paths: List<String>,
    line: (Int, Int) -> String,
    op: suspend (onProgress: (Int, Int) -> Unit) -> OpResult,
): OpResult = try {
    op { i, n -> setProgress(line(i, n)) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w("ToastHostState", "file op failed", e)
    OpResult(emptyList(), paths.map { it to (e.message ?: "failed") })
} finally {
    setProgress(null)
}

/** [block] on [scope] for something the user just did; a failure is logged and shown as [failure], never a crash. */
fun ToastHostState.launchGuarded(scope: CoroutineScope, failure: String, block: suspend CoroutineScope.() -> Unit): Job =
    scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ToastHostState", failure, e)
            show(failure)
        }
    }

/** Where any screen posts a toast; [ToastHost] provides the app's. */
val LocalToasts = staticCompositionLocalOf { ToastHostState() }

/** Draws [content] with [state]'s toast or progress line at the bottom, above the nav bar. */
@Composable
fun ToastHost(state: ToastHostState, content: @Composable () -> Unit) {
    val toast by state.toast.collectAsState()
    val progress by state.progress.collectAsState()
    LaunchedEffect(toast) {
        val shown = toast ?: return@LaunchedEffect
        delay(TOAST_MS)
        state.dismiss(shown)
    }
    CompositionLocalProvider(LocalToasts provides state) {
        Box(Modifier.fillMaxSize()) {
            content()
            val place = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 14.dp, end = 14.dp, bottom = 20.dp)
            val line = progress
            val t = toast
            when {
                line != null -> key("progress") { GalleryToast(line, null, null, place, check = false) }
                t != null -> key(t.id) {
                    GalleryToast(t.message, t.action, t.onAction?.let { { state.act(t) } }, place)
                }
            }
        }
    }
}

/** Light card at the bottom (mockup .toast): check, message, optional action. [check] = false for a progress line. */
@Composable
fun GalleryToast(
    message: String,
    action: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    check: Boolean = true,
) {
    // The mockup's pop: 0.6 -> 1 scale and fade in, 0.2 s.
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(200)) }
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = 0.6f + 0.4f * pop.value
                scaleX = s
                scaleY = s
                alpha = pop.value
            }
            .shadow(8.dp, ToastShape)
            .background(GalleryColors.ToastBg, ToastShape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(TOAST_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (check) Icon(GalleryIcons.Check, contentDescription = null, tint = GalleryColors.ToastText, modifier = Modifier.size(18.dp))
        Text(message, style = MessageStyle, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action, style = ActionStyle, maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = onAction != null, role = Role.Button) { onAction?.invoke() }
                    .padding(4.dp)
                    .testTag(TOAST_ACTION_TAG),
            )
        }
    }
}
