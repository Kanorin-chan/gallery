package dev.kanorin.gallery.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

private const val TAG = "SystemUi"

/**
 * Hides the status and nav bars with ours (immersive); a swipe from the edge shows them for a moment.
 * Screens asking for hidden bars are counted per activity: they come back only when none asks (a cross-fade
 * disposes the screen below after the one on top hid them).
 */
@Composable
internal fun SystemBars(visible: Boolean) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() } ?: return
    val controller = remember(activity, view) { WindowCompat.getInsetsController(activity.window, view) }
    DisposableEffect(activity, controller, visible) {
        val hiding = hidingBars.getOrPut(activity) { HidingBars() }
        if (!visible) {
            hiding.screens++
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else if (hiding.screens == 0) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (!visible && --hiding.screens == 0) {
                hidingBars.remove(activity)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private class HidingBars {
    var screens = 0
}

// Main thread only (composition effects); the same pattern as the player's held orientations.
private val hidingBars = WeakHashMap<Activity, HidingBars>()

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Starts what [make] builds; nothing to start or a failed start is the [failure] toast, never a crash. */
internal fun launch(context: Context, toast: (String) -> Unit, failure: String, make: () -> Intent?) {
    try {
        val intent = make() ?: return toast(failure)
        context.startActivity(intent)
    } catch (e: RuntimeException) {
        Log.w(TAG, failure, e)
        toast(failure)
    }
}
