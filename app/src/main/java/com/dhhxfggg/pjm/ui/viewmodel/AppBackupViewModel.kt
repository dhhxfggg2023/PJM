package com.dhhxfggg.pjm.ui.viewmodel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.domain.util.ApkExporter
import com.dhhxfggg.pjm.domain.util.PjmLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 可备份的已安装应用（列表用）。
 *
 * 刻意不持有图标：一百多个应用的 Icon 一次性加载会白占几十 MB 堆，
 * 由列表行按可见性懒加载（见 AppBackupScreen 的 AppIconBox）。
 *
 * @property apkBytes 主 APK + 全部分片的体积之和，用于「大概要占多少空间」的提示
 * （只备份单个 APK，没有分片概念）
 */
data class BackupAppInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val apkBytes: Long,
    val isSystem: Boolean,
) {
    /** 小写化的搜索键：按应用名或包名都能过滤 */
    val searchKey: String = (label + " " + packageName).lowercase()
}

/**
 * 备份应用筛选规则（纯函数，方便单测钉死）：只保留用户安装的应用。
 * 更新过的系统应用仍然带 [ApplicationInfo.FLAG_SYSTEM]，同样排除。
 */
internal fun isBackupableApp(
    info: ApplicationInfo,
    selfPackage: String,
): Boolean = info.packageName != selfPackage && (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0

/** 备份过程中的进度：字节级细读由 [ApkExporter] 提供，这里只保留界面要显示的数字。 */
data class AppBackupProgress(
    val currentIndex: Int,
    val totalApps: Int,
    val currentLabel: String,
    val loadedBytes: Long,
    val totalBytes: Long,
)

/**
 * 「备份已安装应用」页面的状态与动作。
 *
 * 单独一个 ViewModel 而不塞进 SettingsViewModel：设置页那个 StateFlow 已经用 combine
 * 拼了太多东西，再加一份应用列表会让每次勾选都重算整张设置状态。
 */
@HiltViewModel
class AppBackupViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val fileDao: FileDao,
    ) : ViewModel() {
        private val _apps = MutableStateFlow<List<BackupAppInfo>>(emptyList())
        val apps: StateFlow<List<BackupAppInfo>> = _apps.asStateFlow()

        private val _isLoading = MutableStateFlow(true)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        /** 搜索关键词（已做 300ms 防抖，见 [onQueryChange]） */
        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _selected = MutableStateFlow<Set<String>>(emptySet())
        val selected: StateFlow<Set<String>> = _selected.asStateFlow()

        private val _progress = MutableStateFlow<AppBackupProgress?>(null)
        val progress: StateFlow<AppBackupProgress?> = _progress.asStateFlow()

        private val _outcome = MutableStateFlow<ApkExporter.BackupOutcome?>(null)
        val outcome: StateFlow<ApkExporter.BackupOutcome?> = _outcome.asStateFlow()

        private var queryJob: kotlinx.coroutines.Job? = null

        init {
            loadApps()
        }

        /**
         * 搜索防抖 300ms：输入框每敲一个字都过滤一次，一百多个应用的列表会抖得看不清结果，
         * 也会让每帧都重排列表。
         */
        fun onQueryChange(raw: String) {
            queryJob?.cancel()
            queryJob =
                viewModelScope.launch {
                    kotlinx.coroutines.delay(QUERY_DEBOUNCE_MS)
                    _query.value = raw.trim()
                }
        }

        fun toggleSelection(packageName: String) {
            _selected.update { current ->
                if (current.contains(packageName)) current - packageName else current + packageName
            }
        }

        fun clearSelection() {
            _selected.value = emptySet()
        }

        fun dismissOutcome() {
            _outcome.value = null
        }

        fun reload() = loadApps()

        /**
         * 读取已安装应用。
         *
         * 过滤规则：去掉本应用自身和全部系统应用。
         * [ApplicationInfo.FLAG_SYSTEM] 标记预装系统应用；[ApplicationInfo.FLAG_UPDATED_SYSTEM_APP]
         * 标记被商店更新过的系统应用 —— 它仍是系统应用，只是版本号更新，同样不备份。
         * 只保留用户自己安装的应用。
         */
        private fun loadApps() {
            viewModelScope.launch {
                _isLoading.value = true
                _apps.value = withContext(Dispatchers.IO) { readInstalledApps() }
                _isLoading.value = false
            }
        }

        private fun readInstalledApps(): List<BackupAppInfo> {
            val packageManager = context.packageManager
            val installed =
                runCatching { packageManager.getInstalledApplications(PackageManager.GET_META_DATA) }
                    .getOrElse {
                        PjmLogger.e(TAG, "读取已安装应用失败", it)
                        return emptyList()
                    }
            return installed
                .asSequence()
                .filter { isBackupableApp(it, context.packageName) }
                .mapNotNull { info -> info.toBackupApp(packageManager) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .toList()
        }

        private fun ApplicationInfo.toBackupApp(packageManager: PackageManager): BackupAppInfo? {
            val packageInfo =
                runCatching { packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA) }
                    .getOrNull()
                    ?: return null
            val appInfo = packageInfo.applicationInfo ?: this
            val label =
                runCatching { packageManager.getApplicationLabel(appInfo).toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: packageName
            val sourceDir = appInfo.sourceDir?.trim().orEmpty()
            if (sourceDir.isEmpty()) return null
            val bytes =
                java.io
                    .File(sourceDir)
                    .length()
                    .coerceAtLeast(0L)
            return BackupAppInfo(
                packageName = packageName,
                label = label,
                versionName = packageInfo.versionName,
                versionCode = versionCodeCompat(packageInfo),
                apkBytes = bytes,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }

        @Suppress("DEPRECATION")
        private fun versionCodeCompat(packageInfo: android.content.pm.PackageInfo): Long =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            }

        /** 正在备份：防止连点导致同一批应用被提取两次（会得到两份重复的 APK） */
        private var running = false

        /** 开始备份当前选中的应用；重复点击会被 [running] 挡住。 */
        fun startBackup() {
            val targets = _selected.value.toList()
            if (targets.isEmpty() || running) return
            running = true
            _outcome.value = null
            viewModelScope.launch {
                try {
                    val sizeByPackage = _apps.value.associate { it.packageName to it.apkBytes }
                    val sizes = targets.map { sizeByPackage[it] ?: 0L }
                    val totalBytes = sizes.sum().coerceAtLeast(1L)
                    val result =
                        ApkExporter.exportApps(context, targets, fileDao) { index, total, label, loaded, appTotal ->
                            // 导出器只报「当前应用」的字节数：前面已完成的应用体积要在这里补上，
                            // 否则进度条每换一个应用就归零重来，用户会以为备份卡住/重头开始。
                            val completedBytes = sizes.take((index - 1).coerceIn(0, sizes.size)).sum()
                            _progress.value =
                                AppBackupProgress(
                                    currentIndex = index,
                                    totalApps = total,
                                    currentLabel = label,
                                    loadedBytes = completedBytes + loaded.coerceAtMost(appTotal),
                                    totalBytes = totalBytes,
                                )
                        }
                    _progress.value = null
                    _outcome.value = result
                    if (result.successCount > 0) _selected.value = emptySet()
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    _progress.value = null
                    throw cancel
                } finally {
                    running = false
                }
            }
        }

        companion object {
            private const val TAG = "AppBackupViewModel"
            private const val QUERY_DEBOUNCE_MS = 300L
        }
    }
