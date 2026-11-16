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
class SettingsManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
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
            val KEY_PREDICTIVE_BACK = booleanPreferencesKey(Settings.KEY_PREDICTIVE_BACK)
            val KEY_DISCOVERY_DEFAULT_MODE = stringPreferencesKey(Settings.KEY_DISCOVERY_DEFAULT_MODE)
            val KEY_COVER_ENABLED = booleanPreferencesKey(Settings.KEY_COVER_ENABLED)
            val KEY_COVER_REFRESH = stringPreferencesKey(Settings.KEY_COVER_REFRESH)

            fun getSettingsFlow(context: Context): Flow<Settings.AppSettings> =
                context.dataStore.data.map { preferences ->
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
                        biliMergedAutoDelete = preferences[KEY_BILI_MERGED_AUTO_DELETE] ?: false,
                        predictiveBackEnabled = preferences[KEY_PREDICTIVE_BACK] ?: true,
                        discoveryDefaultMode = preferences[KEY_DISCOVERY_DEFAULT_MODE] ?: "bili_videos",
                        coverEnabled = preferences[KEY_COVER_ENABLED] ?: true,
                        coverRefresh = preferences[KEY_COVER_REFRESH] ?: "each",
                    )
                }
        }

        val settings: Flow<Settings.AppSettings> = getSettingsFlow(context)

        suspend fun updateMigrationDone(done: Boolean) {
            context.dataStore.edit { preferences -> preferences[KEY_MIGRATION_DONE] = done }
        }

        /** 命名规范化迁移 v2 是否已完成（独立标志，见 KEY_PJM_NAMING_MIGRATION_V2） */
        suspend fun isPjmNamingMigrationDone(): Boolean = context.dataStore.data.first()[KEY_PJM_NAMING_MIGRATION_V2] ?: false

        suspend fun setPjmNamingMigrationDone(done: Boolean) {
            context.dataStore.edit { preferences -> preferences[KEY_PJM_NAMING_MIGRATION_V2] = done }
        }

        suspend fun updateBooleanSetting(
            key: Preferences.Key<Boolean>,
            value: Boolean,
        ) {
            context.dataStore.edit { preferences -> preferences[key] = value }
        }

        suspend fun updateIntSetting(
            key: Preferences.Key<Int>,
            value: Int,
        ) {
            context.dataStore.edit { preferences -> preferences[key] = value }
        }

        suspend fun updateStringSetting(
            key: Preferences.Key<String>,
            value: String?,
        ) {
            context.dataStore.edit { preferences ->
                if (value == null) {
                    preferences.remove(key)
                } else {
                    preferences[key] = value
                }
            }
        }

        suspend fun updateFloatSetting(
            key: Preferences.Key<Float>,
            value: Float,
        ) {
            context.dataStore.edit { preferences -> preferences[key] = value }
        }

        suspend fun resetAllSettings() {
            // 核心修复：迁移完成标志不能随「恢复出厂设置」一起被清掉，
            // 否则下次冷启动会重跑一次性命名迁移（虽有幂等保护，但属非预期行为）。
            // 先读出这两个标志，清空后再写回。
            val namingDone = isPjmNamingMigrationDone()
            val migrationDone = context.dataStore.data.first()[KEY_MIGRATION_DONE] ?: false
            context.dataStore.edit { preferences ->
                preferences.clear()
                if (migrationDone) preferences[KEY_MIGRATION_DONE] = true
                if (namingDone) preferences[KEY_PJM_NAMING_MIGRATION_V2] = true
            }
            // 核心修复：SharedPreferences 实例在进程内缓存了 mMap，直接删 shared_prefs/*.xml
            // 并不会让已持有的实例重载 —— 用户「恢复出厂设置」后读到的仍是旧值，
            // 直到杀进程才生效。改按文件名逐个 edit().clear().commit()：同步清掉内存态
            // 与磁盘态，且无需硬编码名单，今后新增的 prefs 文件也会一并覆盖。
            runCatching {
                val spDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                spDir
                    .listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".xml") }
                    ?.forEach { file ->
                        val name = file.name.removeSuffix(".xml")
                        context
                            .getSharedPreferences(name, Context.MODE_PRIVATE)
                            .edit()
                            .clear()
                            .commit()
                    }
            }
        }
    }
