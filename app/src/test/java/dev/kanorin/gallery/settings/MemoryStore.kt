package dev.kanorin.gallery.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** In-memory store: the real file store's atomic rename fails on Windows when the file is open. */
class MemoryStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        state.updateAndGet { runBlockingTransform(transform, it) }

    private fun runBlockingTransform(transform: suspend (Preferences) -> Preferences, p: Preferences) =
        kotlinx.coroutines.runBlocking { transform(p) }
}
