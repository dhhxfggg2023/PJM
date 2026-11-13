package com.dhhxfggg.pjm.domain.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dhhxfggg.pjm.MainApplication
import com.dhhxfggg.pjm.data.db.ManagedAppDao
import com.dhhxfggg.pjm.domain.util.PjmLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * 接管服务的启动/停止开关。
 *
 * 为什么要一个独立的启动器：服务需要在三个地方被拉起 ——
 * Application 冷启动、开机广播、设置页增删应用；而这三个地方都没有 ViewModel，
 * 只能靠 Hilt EntryPoint 自己拿 DAO 判断「有没有被接管的应用」。
 *
 * 判断规则：列表为空就不启动（否则会常驻一条毫无意义的通知）。
 */
object TakeoverStarter {
    private const val TAG = "TakeoverStarter"

    /** JobScheduler 任务 id（固定值，只用于「有没有登记过」判断） */
    private const val JOB_ID = 4201
    private const val JOB_INTERVAL_MS = 15L * 60 * 1000
    private const val JOB_FLEX_MS = 5L * 60 * 1000

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TakeoverEntryPoint {
        fun managedAppDao(): ManagedAppDao
    }

    /**
     * 无条件启动（调用方已确认列表非空）。
     *
     * @param knownCount 调用方刚查到的应用数量，写进缓存供前台服务的通知文案使用；
     *   -1 表示调用方不知道（例如开机广播），沿用上次缓存
     */
    fun start(
        context: Context,
        knownCount: Int = -1,
    ) {
        if (knownCount >= 0) TakeoverPrefs.setManagedCount(context, knownCount)
        if (!TakeoverPrefs.isEnabled(context)) {
            PjmLogger.i(TAG, "接管总开关已关闭，不启动服务")
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, TakeoverService::class.java))
            PjmLogger.i(TAG, "请求启动接管服务（应用数=" + TakeoverPrefs.managedCount(context) + "）")
            // 同时挂一个 15 分钟的 JobScheduler 兜底：国产 ROM 会冻结后台进程，
            // 只靠前台服务的话「切后台就不再移动文件」—— 定时任务会带临时白名单唤醒进程。
            scheduleBackgroundJob(context)
        } catch (t: Throwable) {
            // Android 12+ 后台限制不允许启动前台服务时会抛这个 —— 不崩溃，等用户下次打开 App 再拉起
            PjmLogger.w(TAG, "接管服务启动失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 停止（用户移除全部应用时调用） */
    fun stop(context: Context) {
        try {
            context.stopService(Intent(context, TakeoverService::class.java))
            cancelBackgroundJob(context)
        } catch (t: Throwable) {
            PjmLogger.w(TAG, "接管服务停止失败: ${t.message}")
        }
    }

    /**
     * 15 分钟一次的兜底任务（系统允许的最小周期）。
     * 已存在则不重复登记；persisted=true 让它跨重启保留（RECEIVE_BOOT_COMPLETED 已声明）。
     */
    private fun scheduleBackgroundJob(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job =
            JobInfo
                .Builder(JOB_ID, ComponentName(context, TakeoverJobService::class.java))
                .setPeriodic(JOB_INTERVAL_MS, JOB_FLEX_MS)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
        runCatching { scheduler.schedule(job) }
            .onFailure { e -> PjmLogger.w(TAG, "兜底任务登记失败: ${e.message}") }
    }

    private fun cancelBackgroundJob(context: Context) {
        runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
    }

    /**
     * 按当前列表决定是否启动（Application 冷启动 / 开机广播）。
     * 查询放到后台线程，避免拖慢冷启动。
     */
    fun startIfEnabled(context: Context) {
        val appContext = context.applicationContext
        MainApplication.applicationScope.launch {
            runCatching {
                val dao =
                    EntryPointAccessors
                        .fromApplication(appContext, TakeoverEntryPoint::class.java)
                        .managedAppDao()
                val count = dao.count()
                if (count > 0 && TakeoverPrefs.isEnabled(appContext)) start(appContext, count)
            }.onFailure { e -> PjmLogger.w(TAG, "读取被接管应用列表失败: ${e.message}") }
        }
    }
}
