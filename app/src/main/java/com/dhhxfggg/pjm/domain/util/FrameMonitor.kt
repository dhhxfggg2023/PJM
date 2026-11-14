package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.os.SystemClock
import android.view.Choreographer

/**
 * 调试版慢帧统计。
 *
 * 遇到"滑动/动画卡"时，光看代码猜不准是哪一屏、哪类操作在掉帧。
 * 这里用 Choreographer 逐帧量间隔，每 5 秒最多写一条日志（带当前页面路由），
 * 这样拿到日志就能直接定位到具体页面，而不是继续瞎调。
 *
 * 每 5 秒最多写一条日志，正常流畅时不写。
 */
object FrameMonitor {
    /** 超过这个间隔才算慢帧（120Hz 一帧 8.3ms，24ms 已经明显掉帧） */
    private const val JANK_THRESHOLD_MS = 24L

    /** 间隔超过这个值说明界面本来就是静止的（没有新帧），不能算慢帧 */
    private const val IDLE_RESET_MS = 300L

    private const val REPORT_WINDOW_MS = 5000L

    /** 当前页面路由，由导航层更新 */
    @Volatile
    var currentScreen: String = ""

    private var lastFrameNanos = 0L
    private var windowStartMs = 0L
    private var slowFrameCount = 0
    private var worstFrameMs = 0L
    private var started = false

    private val callback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!started) return
                val gapMs = (frameTimeNanos - lastFrameNanos) / 1_000_000
                val idle = lastFrameNanos == 0L || gapMs > IDLE_RESET_MS
                lastFrameNanos = frameTimeNanos
                if (!idle && gapMs > JANK_THRESHOLD_MS) {
                    slowFrameCount++
                    if (gapMs > worstFrameMs) worstFrameMs = gapMs
                }

                val now = SystemClock.elapsedRealtime()
                if (windowStartMs == 0L) windowStartMs = now
                if (now - windowStartMs >= REPORT_WINDOW_MS) {
                    if (slowFrameCount > 0) {
                        PjmLogger.w(
                            "Frame",
                            "慢帧 " + currentScreen + ": " + slowFrameCount + " 次, 最慢 " + worstFrameMs + "ms",
                        )
                    }
                    slowFrameCount = 0
                    worstFrameMs = 0
                    windowStartMs = now
                }
                if (started) Choreographer.getInstance().postFrameCallback(this)
            }
        }

    fun start(context: Context) {
        if (started) return
        // 不开 debug 包的判断：日常装的是 release 构建，掉帧数据也要能拿到（每 5 秒最多一行日志）
        started = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        started = false
    }
}
