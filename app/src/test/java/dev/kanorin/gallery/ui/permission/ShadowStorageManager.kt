package dev.kanorin.gallery.ui.permission

import android.os.Environment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

/** Robolectric's Environment.isExternalStorageManager crashes; everything else stays Robolectric's ShadowEnvironment. */
@Implements(Environment::class)
class ShadowStorageManager : ShadowEnvironment() {
    companion object {
        var granted = false

        @Implementation @JvmStatic
        fun isExternalStorageManager(): Boolean = granted
    }
}
