package dev.kanorin.gallery.index

import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageLayoutTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun rootsAreEveryMountedVolume() {
        val internal = tmp.newFolder("storage", "emulated", "0")
        val sd = tmp.newFolder("storage", "1234-ABCD")
        val pulled = tmp.newFolder("storage", "5678-EF01")
        val storage = shadowOf(context.getSystemService(StorageManager::class.java))
        storage.addStorageVolume(volume("primary", internal, Environment.MEDIA_MOUNTED, primary = true))
        storage.addStorageVolume(volume("sd", sd, Environment.MEDIA_MOUNTED))
        storage.addStorageVolume(volume("pulled", pulled, Environment.MEDIA_UNMOUNTED))
        assertEquals(listOf(internal, sd), StorageLayout.roots(context))
    }

    @Test fun volumeNamesMatchMediaStore() {
        assertEquals("external_primary", StorageLayout.volumeNameOf(File("/storage/emulated/0")))
        assertEquals("1234-abcd", StorageLayout.volumeNameOf(File("/storage/1234-ABCD")))
    }

    @Test fun robolectricEnvironmentHelpersStillWorkUnderTheAppShadow() {
        val dir = tmp.newFolder("ext")
        ShadowEnvironment.setExternalStorageDirectory(dir.toPath())
        assertEquals(dir, StorageLayout.primaryRoot())
    }

    private fun volume(id: String, dir: File, state: String, primary: Boolean = false) =
        StorageVolumeBuilder(id, dir, id, Process.myUserHandle(), state)
            .setIsPrimary(primary)
            .setIsRemovable(!primary)
            .setIsEmulated(primary)
            .build()
}
