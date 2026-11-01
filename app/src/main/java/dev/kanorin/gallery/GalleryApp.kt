package dev.kanorin.gallery

import android.app.Application
import coil3.SingletonImageLoader
import dev.kanorin.gallery.ui.permission.readPermissionState

class GalleryApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        SingletonImageLoader.setSafe { graph.imageLoader }
        // Without all-files access there's nothing to index; MainActivity starts it once granted.
        if (readPermissionState(this).allFiles) graph.mediaIndex.start()
    }
}
