package dev.kanorin.gallery.ui.permission

import android.content.Context
import android.provider.MediaStore
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Lets tests choose what MediaStore.canManageMedia returns. */
@Implements(MediaStore::class)
class ShadowMediaStore {
    companion object {
        var canManage = false

        @Implementation @JvmStatic
        fun canManageMedia(context: Context): Boolean = canManage
    }
}
