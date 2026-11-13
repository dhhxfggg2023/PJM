package com.dhhxfggg.pjm.domain.service

import android.app.job.JobParameters
import android.app.job.JobService
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.db.ManagedAppDao
import com.dhhxfggg.pjm.data.db.TakeoverJobDao
import com.dhhxfggg.pjm.domain.util.PjmLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 后台兜底任务（JobScheduler，15 分钟一次）。
 *
 * 关键设计：**自己跑扫描**，不依赖「能不能拉起前台服务」。
 * 国产 ROM 冻结后台进程时，从任务里启动前台服务也可能被拒；那样兜底就形同虚设。
 * 这里直接调用 [TakeoverScanEngine]，在本进程里完成一轮「媒体库 + 目录」扫描。
 *
 * 任务时限是十分钟，扫描本身按单轮 50 个文件 + 目录水位推进，足够在时限内收尾。
 */
@AndroidEntryPoint
class TakeoverJobService : JobService() {
    @Inject
    lateinit var managedAppDao: ManagedAppDao

    @Inject
    lateinit var takeoverJobDao: TakeoverJobDao

    @Inject
    lateinit var fileDao: FileDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!TakeoverPrefs.isEnabled(this)) {
            PjmLogger.i(TAG, "接管总开关已关闭，跳过定时扫描")
            jobFinished(params, false)
            return false
        }
        PjmLogger.i(TAG, "定时任务触发，直接执行一轮接管扫描")
        TakeoverNotifications.ensureChannels(this)
        scope.launch {
            try {
                TakeoverScanEngine.scanOnce(
                    context = this@TakeoverJobService,
                    managedAppDao = managedAppDao,
                    takeoverJobDao = takeoverJobDao,
                    fileDao = fileDao,
                    doSweep = true,
                )
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                PjmLogger.e(TAG, "兜底扫描失败", e as? Exception)
            } finally {
                jobFinished(params, false)
            }
        }
        // 返回 true：工作仍在继续，完成后再调 jobFinished
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TakeoverJobService"
    }
}
