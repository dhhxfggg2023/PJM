package com.dhhxfggg.pjm.domain.util

import android.content.Context
import com.dhhxfggg.pjm.MainApplication
import com.dhhxfggg.pjm.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用内更新的全局状态机（检查 / 下载 / 安装）。
 *
 * 核心修复：下载流程原来写在 UI 的 `LaunchedEffect` / `rememberCoroutineScope()` 里，
 * 而承载它的组件随时可能离开组合 ——
 *  - `UpdateNoticeBanner` 在 MainActivity 中是 `if (opTasks.isEmpty())` **条件组合**的：
 *    只要出现任何后台任务进度，横幅就被移除，正在进行的下载协程被**静默取消**；
 *  - 旋转屏幕会让 `remember` 里的进度与「已开始下载」标志一起丢失，同样中断下载；
 *  - 设置页的下载绑在 `rememberCoroutineScope()` 上，离开设置页即取消。
 * 三种情况下用户都看不到任何提示，安装流程凭空消失。
 *
 * 现在状态与协程都上移到进程级作用域（[MainApplication.applicationScope]），
 * UI 只负责观察与展示 —— 离开组合不再影响下载。
 */
object UpdateController {
    private val _available = MutableStateFlow<UpdateChecker.CheckResult.UpdateAvailable?>(null)

    /** 待展示的可用更新；null 表示无 */
    val available: StateFlow<UpdateChecker.CheckResult.UpdateAvailable?> = _available.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float?>(null)

    /** 下载进度：null = 未开始；0f..1f = 进行中 */
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _dismissed = MutableStateFlow(false)

    /** 用户是否已关闭横幅 */
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** 一次性提示文案，由 UI 消费后调用 [consumeMessage] 清空 */
    val message: StateFlow<String?> = _message.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /**
     * 供设置页「检查更新」把结果同步给全局状态（横幅与设置页共用同一条数据）。
     */
    fun publishAvailable(update: UpdateChecker.CheckResult.UpdateAvailable) {
        _available.value = update
        _dismissed.value = false
    }

    /**
     * 冷启动后静默检查一次。幂等：已有检查在跑、已发现更新、或正在下载时直接返回。
     */
    fun checkOnce(
        context: Context,
        delayMs: Long = 2500,
    ) {
        if (checkJob?.isActive == true || _available.value != null || downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        checkJob =
            MainApplication.applicationScope.launch {
                // 让首屏先稳定，稍后检查，避免抢占冷启动资源
                delay(delayMs)
                val result = UpdateChecker.checkForUpdate(appContext)
                if (result is UpdateChecker.CheckResult.UpdateAvailable) {
                    _available.value = result
                    _dismissed.value = false
                }
            }
    }

    /**
     * 开始下载并安装。重复点击无效（已有下载在跑时直接返回）。
     */
    fun startDownload(context: Context) {
        val update = _available.value ?: return
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        _downloadProgress.value = 0f
        downloadJob =
            MainApplication.applicationScope.launch {
                val result = UpdateChecker.downloadApk(appContext, update.apkUrl) { p -> _downloadProgress.value = p }
                _downloadProgress.value = null
                when (result) {
                    is UpdateChecker.DownloadResult.Success -> {
                        val ok = UpdateChecker.installApk(appContext, result.apkFile)
                        _dismissed.value = true
                        if (!ok) _message.value = appContext.getString(R.string.msg_update_install_fallback)
                    }
                    is UpdateChecker.DownloadResult.Error -> {
                        _dismissed.value = true
                        _message.value = result.message
                    }
                }
            }
    }

    /** 用户关闭横幅 */
    fun dismiss() {
        _dismissed.value = true
    }

    fun consumeMessage() {
        _message.value = null
    }
}
