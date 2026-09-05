package com.dhhxfggg.pjm.domain.util

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.dhhxfggg.pjm.data.model.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "app_settings")

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_THEME = stringPreferencesKey(Settings.KEY_THEME)
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey(Settings.KEY_DYNAMIC_COLOR)
        val KEY_CUSTOM_BACKGROUND_ENABLED = booleanPreferencesKey(Settings.KEY_CUSTOM_BACKGROUND_ENABLED)
        val KEY_CUSTOM_BACKGROUND_URI = stringPreferencesKey(Settings.KEY_CUSTOM_BACKGROUND_URI)
        val KEY_BACKGROUND_OPACITY = floatPreferencesKey(Settings.KEY_BACKGROUND_OPACITY)
        val KEY_GLOBAL_UI_SCALE = floatPreferencesKey(Settings.KEY_GLOBAL_UI_SCALE)
        
        // 性能优化：标记迁移是否完成
        val KEY_MIGRATION_DONE = booleanPreferencesKey("migration_done")

        // 加密容器命名规范化迁移 v2（旧版毫秒时间戳 → 可读时间戳，单卷补 .1）：
        // 独立于 KEY_MIGRATION_DONE，保证即使在旧迁移已置位的情况下本次也会执行一次
        val KEY_PJM_NAMING_MIGRATION_V2 = booleanPreferencesKey("pjm_naming_migration_v2_done")

        val KEY_FILE_VIEW_MODE = stringPreferencesKey(Settings.KEY_FILE_VIEW_MODE)
        val KEY_GRID_SPAN_COUNT = intPreferencesKey(Settings.KEY_GRID_SPAN_COUNT)
        val KEY_EXPORT_SPLIT_SIZE = intPreferencesKey(Settings.KEY_EXPORT_SPLIT_SIZE)
        val KEY_AUTO_DELETE_ORIGINAL = booleanPreferencesKey(Settings.KEY_AUTO_DELETE_ORIGINAL)
        val KEY_ARCHIVE_AUTO_EXTRACTION = booleanPreferencesKey(Settings.KEY_ARCHIVE_AUTO_EXTRACTION)
        val KEY_BILI_ROOT_URI = stringPreferencesKey(Settings.KEY_BILI_ROOT_URI)
        val KEY_BILI_AUTO_DELETE = booleanPreferencesKey(Settings.KEY_BILI_AUTO_DELETE)
        val KEY_BILI_MERGED_AUTO_DELETE = booleanPreferencesKey(Settings.KEY_BILI_MERGED_AUTO_DELETE)

        fun getSettingsFlow(context: Context): Flow<Settings.AppSettings> = context.dataStore.data.map { preferences ->
            Settings.AppSettings(
                theme = preferences[KEY_THEME] ?: "system",
                isDynamicColorEnabled = preferences[KEY_DYNAMIC_COLOR] ?: true,
                isCustomBackgroundEnabled = preferences[KEY_CUSTOM_BACKGROUND_ENABLED] ?: false,
                customBackgroundUri = preferences[KEY_CUSTOM_BACKGROUND_URI],
                backgroundOpacity = preferences[KEY_BACKGROUND_OPACITY] ?: 1.0f,
                globalUiScale = preferences[KEY_GLOBAL_UI_SCALE] ?: 1.0f,
                fileViewMode = preferences[KEY_FILE_VIEW_MODE] ?: "grid",
                gridSpanCount = preferences[KEY_GRID_SPAN_COUNT] ?: 2,
                exportSplitSize = preferences[KEY_EXPORT_SPLIT_SIZE] ?: 1024,
                autoDeleteOriginal = preferences[KEY_AUTO_DELETE_ORIGINAL] ?: false,
                isArchiveAutoExtractionEnabled = preferences[KEY_ARCHIVE_AUTO_EXTRACTION] ?: true,
                isMigrationDone = preferences[KEY_MIGRATION_DONE] ?: false,
                biliRootUri = preferences[KEY_BILI_ROOT_URI],
                biliAutoDelete = preferences[KEY_BILI_AUTO_DELETE] ?: false,
                biliMergedAutoDelete = preferences[KEY_BILI_MERGED_AUTO_DELETE] ?: false
            )
        }
    }

    val settings: Flow<Settings.AppSettings> = getSettingsFlow(context)

    suspend fun updateMigrationDone(done: Boolean) {
        context.dataStore.edit { preferences -> preferences[KEY_MIGRATION_DONE] = done }
    }

    /** 命名规范化迁移 v2 是否已完成（独立标志，见 KEY_PJM_NAMING_MIGRATION_V2） */
    suspend fun isPjmNamingMigrationDone(): Boolean =
        context.dataStore.data.first()[KEY_PJM_NAMING_MIGRATION_V2] ?: false

    suspend fun setPjmNamingMigrationDone(done: Boolean) {
        context.dataStore.edit { preferences -> preferences[KEY_PJM_NAMING_MIGRATION_V2] = done }
    }

    suspend fun updateBooleanSetting(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { preferences -> preferences[key] = value }
    }

    suspend fun updateIntSetting(key: Preferences.Key<Int>, value: Int) {
        context.dataStore.edit { preferences -> preferences[key] = value }
    }

    suspend fun updateStringSetting(key: Preferences.Key<String>, value: String?) {
        context.dataStore.edit { preferences -> 
            if (value == null) preferences.remove(key) 
            else preferences[key] = value 
        }
    }

    suspend fun updateFloatSetting(key: Preferences.Key<Float>, value: Float) {
        context.dataStore.edit { preferences -> preferences[key] = value }
    }

    suspend fun resetAllSettings() {
        context.dataStore.edit { preferences -> preferences.clear() }
    }
}
