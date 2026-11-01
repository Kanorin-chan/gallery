package dev.kanorin.gallery.ui.permission

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings

data class PermissionState(val allFiles: Boolean, val manageMedia: Boolean)

fun readPermissionState(context: Context): PermissionState = PermissionState(
    allFiles = Environment.isExternalStorageManager(),
    // Below API 31 there is no media-management grant; treat as satisfied so no card shows.
    manageMedia = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || MediaStore.canManageMedia(context),
)

private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

private fun Context.startSettings(intent: Intent) {
    // Non-activity contexts need NEW_TASK; harmless to skip from an Activity.
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

fun launchAllFilesAccess(context: Context) {
    try {
        context.startSettings(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(context)))
    } catch (_: ActivityNotFoundException) {
        // Some OEM builds lack the per-app page; the general list still works.
        try {
            context.startSettings(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            // Nothing left to open; stay on the screen.
        }
    }
}

fun launchManageMedia(context: Context) {
    try {
        context.startSettings(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, packageUri(context)))
    } catch (_: ActivityNotFoundException) {
        // Optional permission; no page to open is not an error.
    }
}
