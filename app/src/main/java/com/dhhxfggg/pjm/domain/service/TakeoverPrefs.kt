package com.dhhxfggg.pjm.domain.service

import android.content.Context
import androidx.core.content.edit

/**
 * 「应用接管」的少量持久状态。
 *
 * 只存两样东西：
 * 1. 已接管应用数量 —— 前台服务在 onStartCommand 里就要把通知显示出来，
 *    那一刻不能查 Room（主线程查询会抛异常），先读上次写入的值，
 *    这样即使进程是被系统重新拉起的，通知也不会退回到「等待选择应用」。
 * 2. 上次申请「后台运行」白名单的时间 —— 用户拒绝后不能每次开屏都弹系统框。
 */
object TakeoverPrefs {
    private const val FILE = "takeover_prefs"
    private const val KEY_APP_COUNT = "managed_app_count"
    private const val KEY_BATTERY_ASKED_AT = "battery_asked_at"
    private const val KEY_LAST_SCAN = "last_scan_at"
    private const val KEY_LAST_TICK = "last_tick_at"
    private const val KEY_LAST_TICK_GAP = "last_tick_gap"
    private const val KEY_GAP_COUNT = "gap_count"
    private const val KEY_LAST_GAP_AT = "last_gap_at"
    private const val KEY_LAST_GAP_MS = "last_gap_ms"
    private const val KEY_ENABLED = "enabled"

    /** 0 才表示「还没有接管任何应用」 */
    fun managedCount(context: Context): Int = prefs(context).getInt(KEY_APP_COUNT, 0)

    fun setManagedCount(
        context: Context,
        count: Int,
    ) {
        prefs(context).edit { putInt(KEY_APP_COUNT, count) }
    }

    /**
     * 接管总开关。
     *
     * 默认关闭：新装用户从没授予过后台权限，不该一上来就常驻后台。
     * 但升级上来的老用户列表里已经有应用（之前就在接管），第一次读时沿用为开启，
     * 避免更新后接管突然停掉。
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = prefs(context)
        return if (prefs.contains(KEY_ENABLED)) {
            prefs.getBoolean(KEY_ENABLED, false)
        } else {
            prefs.getInt(KEY_APP_COUNT, 0) > 0
        }
    }

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun lastBatteryAskedAt(context: Context): Long = prefs(context).getLong(KEY_BATTERY_ASKED_AT, 0L)

    /** 最近一次扫描（任何来源）的时间戳：界面上用它判断后台到底有没有在跑 */
    fun lastScanAt(context: Context): Long = prefs(context).getLong(KEY_LAST_SCAN, 0L)

    fun markScan(context: Context) {
        prefs(context).edit { putLong(KEY_LAST_SCAN, System.currentTimeMillis()) }
    }

    /** 定时兜底上一次真正触发的时间：两次心跳间隔过大就说明进程被冻结/停止过 */
    fun lastTickAt(context: Context): Long = prefs(context).getLong(KEY_LAST_TICK, 0L)

    /**
     * 上一次 tick 当时声明的周期。
     *
     * 兜底周期是自适应的（空闲会拉长到 15 分钟），空档判定不能再用固定阈值 ——
     * 否则「空闲拉长」本身会被误报成「后台被系统冻结」。
     */
    fun lastTickGap(context: Context): Long = prefs(context).getLong(KEY_LAST_TICK_GAP, 0L)

    fun markTick(
        context: Context,
        at: Long = System.currentTimeMillis(),
        expectedGapMs: Long = 0L,
    ) {
        prefs(context).edit {
            putLong(KEY_LAST_TICK, at)
            if (expectedGapMs > 0L) putLong(KEY_LAST_TICK_GAP, expectedGapMs)
        }
    }

    /** 记录一次「本该扫描却没有扫描」的空档（冻结或被杀） */
    fun recordBackgroundGap(
        context: Context,
        gapMs: Long,
    ) {
        val count = backgroundGapCount(context) + 1
        prefs(context).edit {
            putInt(KEY_GAP_COUNT, count)
            putLong(KEY_LAST_GAP_AT, System.currentTimeMillis())
            putLong(KEY_LAST_GAP_MS, gapMs.coerceAtLeast(0L))
        }
    }

    fun backgroundGapCount(context: Context): Int = prefs(context).getInt(KEY_GAP_COUNT, 0)

    fun lastBackgroundGapAt(context: Context): Long = prefs(context).getLong(KEY_LAST_GAP_AT, 0L)

    fun lastBackgroundGapMs(context: Context): Long = prefs(context).getLong(KEY_LAST_GAP_MS, 0L)

    fun markBatteryAsked(
        context: Context,
        at: Long = System.currentTimeMillis(),
    ) {
        prefs(context).edit { putLong(KEY_BATTERY_ASKED_AT, at) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
