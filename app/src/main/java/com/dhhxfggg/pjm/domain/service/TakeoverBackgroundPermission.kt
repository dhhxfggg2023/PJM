package com.dhhxfggg.pjm.domain.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.dhhxfggg.pjm.domain.util.PjmLogger

/**
 * 后台运行的唯一开关：系统「电池优化」白名单。
 *
 * 为什么必须有它：PJM 靠前台服务观察相册变化，而国产 ROM（这台是 vivo）会在锁屏后
 * 冻结不在白名单里的进程 —— 前台服务记录还在、通知还挂着，但协程一行都不跑，
 * 表现就是「只有打开 App 才移动文件」。系统白名单是应用唯一能自己申请到的豁免。
 *
 * vivo 还有一层自己的「后台高耗电 / 自启动」开关，系统弹窗不一定覆盖，
 * 所以额外提供一条深链；都打不开就退回应用详情页，用户至少能自己翻。
 */
object TakeoverBackgroundPermission {
    private const val TAG = "TakeoverBgPermission"

    /** 两次系统弹窗之间的最小间隔：用户明确拒绝后不要每次开屏都弹 */
    private const val ASK_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** vivo 自启动管理 */
    private const val AUTO_START_ACTION = "com.iqoo.secure.BGSTARTUPMANAGER"

    fun isExempt(context: Context): Boolean =
        runCatching {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)

    /**
     * 调起系统「允许后台运行」弹窗。
     *
     * @param force 用户主动点击「允许后台运行」时传 true，跳过 6 小时限流
     * @return true 表示弹窗/设置页已经打开（不代表用户点了允许）
     */
    fun request(
        context: Context,
        force: Boolean = false,
    ): Boolean {
        if (isExempt(context)) return false
        val now = System.currentTimeMillis()
        if (!force && now - TakeoverPrefs.lastBatteryAskedAt(context) < ASK_INTERVAL_MS) return false
        TakeoverPrefs.markBatteryAsked(context, now)
        val direct =
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.packageName),
            )
        return launch(context, direct) || launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /**
     * 打开系统「电池」页。
     *
     * 为什么不直接开「后台耗电管理」：那一页被 vivo 用 signatureOrSystem 权限
     * （com.iqoo.powersaving.permission.action.openhpactivity）保护，
     * 第三方应用无论用 action 还是组件直连都会被 SecurityException 挡回来
     * （实测 shell 的 am start 能开，是因为 shell 有这个权限）。所以这里把用户送到电池页，
     * 它第一项就是「后台耗电管理」的入口。
     */
    fun openBatteryPage(context: Context): Boolean {
        val candidates =
            listOf(
                Intent().setComponent(
                    ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"),
                ),
                Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        return launchFirst(context, candidates)
    }

    /** 打开「自启动管理」：允许自启动，避免被清理后不再拉起 */
    fun openAutoStartPage(context: Context): Boolean {
        val candidates =
            listOf(
                Intent().setComponent(
                    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ),
                Intent(AUTO_START_ACTION),
            )
        return launchFirst(context, candidates)
    }

    private fun launchFirst(
        context: Context,
        intents: List<Intent>,
    ): Boolean {
        for (intent in intents) {
            if (launch(context, intent)) return true
        }
        return launch(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)),
        )
    }

    private fun launch(
        context: Context,
        intent: Intent,
    ): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.onFailure { e ->
            PjmLogger.w(TAG, "打开设置失败: " + intent.component + " / " + e.javaClass.simpleName)
        }.getOrDefault(false)
    }
}
