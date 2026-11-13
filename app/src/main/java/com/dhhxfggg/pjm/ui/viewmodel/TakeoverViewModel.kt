package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.db.ManagedAppDao
import com.dhhxfggg.pjm.data.db.TakeoverJobDao
import com.dhhxfggg.pjm.data.model.ManagedAppEntity
import com.dhhxfggg.pjm.data.model.TakeoverStatus
import com.dhhxfggg.pjm.domain.service.TakeoverBackgroundPermission
import com.dhhxfggg.pjm.domain.service.TakeoverPrefs
import com.dhhxfggg.pjm.domain.service.TakeoverStarter
import com.dhhxfggg.pjm.domain.util.PjmLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 可被接管的应用（只用于选择列表，不落库）。
 *
 * 图标不放在这里 —— 一百多个应用的图标一次性全解码会白占几十 MB 内存，
 * 由 UI 按行懒加载（见 TakeoverSettings 的 AppIcon）。
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * 「应用接管」页面的状态与动作。
 *
 * 与 [SettingsViewModel] 分开：接管有自己的一张表、一个常驻服务、两种失败计数，
 * 塞进设置页的大 StateFlow 里只会让 combine 越堆越长。
 */
@HiltViewModel
class TakeoverViewModel
    @Inject
    constructor(
        private val app: Application,
        private val managedAppDao: ManagedAppDao,
        private val takeoverJobDao: TakeoverJobDao,
    ) : AndroidViewModel(app) {
        /** 总开关状态：关闭时不驻留后台、不申请电池/自启动权限 */
        private val _enabled = MutableStateFlow(TakeoverPrefs.isEnabled(app))
        val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

        /** 已接管的应用（有列表 ≠ 正在接管，还要总开关打开） */
        val managedApps: StateFlow<List<ManagedAppEntity>> =
            managedAppDao
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        /** 已入库但原文件没删掉 —— 必须让用户看到，并去通知里重试 */
        val deleteFailures: StateFlow<Int> =
            takeoverJobDao
                .observeCountByStatus(TakeoverStatus.DELETE_FAILED)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        /** 入库失败（原文件仍在相册，没有数据丢失） */
        val importFailures: StateFlow<Int> =
            takeoverJobDao
                .observeCountByStatus(TakeoverStatus.IMPORT_FAILED)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        /**
         * 读出可添加的应用：桌面能启动、非系统应用（被更新过的系统应用如 Chrome 允许）。
         *
         * 放在 suspend 里由 UI 用 produceState/LaunchedEffect 触发 ——
         * 组合期直接查 PackageManager 会在进入页面时卡住一帧，应用多了更明显。
         */
        suspend fun loadInstalledUserApps(): List<InstalledApp> =
            withContext(Dispatchers.IO) {
                val packageManager = app.packageManager
                val intent =
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                runCatching {
                    packageManager
                        .queryIntentActivities(intent, 0)
                        .mapNotNull { resolved ->
                            val info: ApplicationInfo = resolved.activityInfo?.applicationInfo ?: return@mapNotNull null
                            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                            if (isSystem && !isUpdatedSystem) return@mapNotNull null
                            if (info.packageName == app.packageName) return@mapNotNull null
                            InstalledApp(
                                packageName = info.packageName,
                                label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(info.packageName),
                            )
                        }.distinctBy { it.packageName }
                        .sortedBy { it.label.lowercase() }
                }.getOrElse { e ->
                    PjmLogger.w("TakeoverViewModel", "读取已安装应用失败: ${e.message}")
                    emptyList()
                }
            }

        fun setEnabled(value: Boolean) {
            if (_enabled.value == value) return
            TakeoverPrefs.setEnabled(app, value)
            _enabled.value = value
            PjmLogger.i("TakeoverViewModel", if (value) "接管总开关：开启" else "接管总开关：关闭")
            viewModelScope.launch {
                if (value) {
                    val count = managedAppDao.count()
                    if (count > 0) TakeoverStarter.start(app, count)
                } else {
                    TakeoverStarter.stop(app)
                }
            }
        }

        fun addApp(
            packageName: String,
            displayName: String,
        ) {
            viewModelScope.launch {
                managedAppDao.upsert(ManagedAppEntity(packageName = packageName, displayName = displayName))
                val count = managedAppDao.count()
                // 第一个应用加入时把观察者服务拉起来（已在跑则 start 是幂等的）
                TakeoverStarter.start(app, count)
                // 用户刚点完「添加」，正是能弹系统框的时机：接管一旦启用默认就要「后台运行」白名单
                if (count == 1 && _enabled.value) TakeoverBackgroundPermission.request(app, force = true)
                PjmLogger.i("TakeoverViewModel", "开始接管: $packageName")
            }
        }

        fun removeApp(packageName: String) {
            viewModelScope.launch {
                managedAppDao.delete(packageName)
                val remaining = managedAppDao.count()
                TakeoverPrefs.setManagedCount(app, remaining)
                if (remaining == 0) {
                    // 列表空了就没有观察的意义 —— 常驻通知也随之消失
                    TakeoverStarter.stop(app)
                }
                PjmLogger.i("TakeoverViewModel", "取消接管: $packageName")
            }
        }

        /** 后台状态快照：让界面能显示「到底有没有在跑」，而不是让用户猜开关有没有生效 */
        data class BackgroundStatus(
            val lastScanAt: Long,
            val gapCount: Int,
            val lastGapAt: Long,
            val lastGapMs: Long,
        )

        fun backgroundStatus(): BackgroundStatus =
            BackgroundStatus(
                lastScanAt = TakeoverPrefs.lastScanAt(app),
                gapCount = TakeoverPrefs.backgroundGapCount(app),
                lastGapAt = TakeoverPrefs.lastBackgroundGapAt(app),
                lastGapMs = TakeoverPrefs.lastBackgroundGapMs(app),
            )

        fun clearFailureRecords() {
            viewModelScope.launch {
                takeoverJobDao.clearByStatus(
                    listOf(TakeoverStatus.DELETE_FAILED, TakeoverStatus.IMPORT_FAILED),
                )
            }
        }
    }
