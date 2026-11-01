package dev.kanorin.gallery

import android.R
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import dev.kanorin.gallery.ui.permission.ShadowStorageManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowStorageManager::class])
class SmokeTest {
    @Test
    fun mainActivityLaunchesWithRootView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.content)
                assertNotNull(root)
                assertTrue(root.childCount > 0)
            }
        }
    }

    @Test
    fun applicationExposesGraphWithSettings() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        assertNotNull(app.graph.settings)
    }
}
