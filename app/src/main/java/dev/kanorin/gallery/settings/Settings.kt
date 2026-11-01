package dev.kanorin.gallery.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What a remembered "delete" tap does without asking again. */
enum class DeleteChoice { BIN, PERMANENT }

/** App settings in DataStore Preferences. Defaults live here and nowhere else. */
class Settings(private val dataStore: DataStore<Preferences>) {

    val skipSeconds: Flow<Int> = dataStore.data.map { it[SKIP_SECONDS] ?: DEFAULT_SKIP_SECONDS }
    val holdSpeed: Flow<Float> = dataStore.data.map { it[HOLD_SPEED] ?: DEFAULT_HOLD_SPEED }
    val rememberedDeleteChoice: Flow<DeleteChoice?> = dataStore.data.map { prefs ->
        prefs[DELETE_CHOICE]?.let { name -> DeleteChoice.entries.firstOrNull { it.name == name } }
    }
    val gridAll: Flow<Int> = dataStore.data.map { it[GRID_ALL] ?: DEFAULT_GRID_ALL }
    val gridFolders: Flow<Int> = dataStore.data.map { it[GRID_FOLDERS] ?: DEFAULT_GRID_FOLDERS }
    val gridInside: Flow<Int> = dataStore.data.map { it[GRID_INSIDE] ?: DEFAULT_GRID_INSIDE }
    val showHidden: Flow<Boolean> = dataStore.data.map { it[SHOW_HIDDEN] ?: DEFAULT_SHOW_HIDDEN }

    /** True once the user taps "Not now" on the optional media-management step. */
    val manageMediaPromptDismissed: Flow<Boolean> =
        dataStore.data.map { it[MANAGE_MEDIA_DISMISSED] ?: DEFAULT_MANAGE_MEDIA_DISMISSED }

    suspend fun setSkipSeconds(value: Int) {
        require(value in SKIP_SECONDS_OPTIONS) { "skipSeconds must be one of $SKIP_SECONDS_OPTIONS" }
        dataStore.edit { it[SKIP_SECONDS] = value }
    }

    suspend fun setHoldSpeed(value: Float) {
        require(value in HOLD_SPEED_OPTIONS) { "holdSpeed must be one of $HOLD_SPEED_OPTIONS" }
        dataStore.edit { it[HOLD_SPEED] = value }
    }

    /** `null` forgets the choice, so the delete dialog asks again. */
    suspend fun setRememberedDeleteChoice(value: DeleteChoice?) {
        dataStore.edit { if (value == null) it.remove(DELETE_CHOICE) else it[DELETE_CHOICE] = value.name }
    }

    suspend fun setGridAll(value: Int) {
        require(value in GRID_ALL_RANGE) { "gridAll must be in $GRID_ALL_RANGE" }
        dataStore.edit { it[GRID_ALL] = value }
    }

    suspend fun setGridFolders(value: Int) {
        require(value in GRID_FOLDERS_RANGE) { "gridFolders must be in $GRID_FOLDERS_RANGE" }
        dataStore.edit { it[GRID_FOLDERS] = value }
    }

    suspend fun setGridInside(value: Int) {
        require(value in GRID_INSIDE_RANGE) { "gridInside must be in $GRID_INSIDE_RANGE" }
        dataStore.edit { it[GRID_INSIDE] = value }
    }

    suspend fun setShowHidden(value: Boolean) { dataStore.edit { it[SHOW_HIDDEN] = value } }
    suspend fun setManageMediaPromptDismissed(value: Boolean) {
        dataStore.edit { it[MANAGE_MEDIA_DISMISSED] = value }
    }

    companion object {
        const val DEFAULT_SKIP_SECONDS = 10
        const val DEFAULT_HOLD_SPEED = 2f
        const val DEFAULT_GRID_ALL = 4
        const val DEFAULT_GRID_FOLDERS = 3
        const val DEFAULT_GRID_INSIDE = 3
        const val DEFAULT_SHOW_HIDDEN = false
        const val DEFAULT_MANAGE_MEDIA_DISMISSED = false

        val SKIP_SECONDS_OPTIONS = listOf(5, 10)
        val HOLD_SPEED_OPTIONS = listOf(1.5f, 2f, 3f)
        /** Columns per row a pinch can reach (spec 5.2, 5.3, 5.6). */
        val GRID_ALL_RANGE = 2..6
        val GRID_FOLDERS_RANGE = 2..4
        val GRID_INSIDE_RANGE = 2..6

        private val SKIP_SECONDS = intPreferencesKey("skip_seconds")
        private val HOLD_SPEED = floatPreferencesKey("hold_speed")
        private val DELETE_CHOICE = stringPreferencesKey("remembered_delete_choice")
        private val GRID_ALL = intPreferencesKey("grid_all")
        private val GRID_FOLDERS = intPreferencesKey("grid_folders")
        private val GRID_INSIDE = intPreferencesKey("grid_inside")
        private val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        private val MANAGE_MEDIA_DISMISSED = booleanPreferencesKey("manage_media_prompt_dismissed")
    }
}
