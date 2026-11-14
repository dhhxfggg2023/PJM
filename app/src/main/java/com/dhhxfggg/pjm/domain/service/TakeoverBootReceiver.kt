package com.dhhxfggg.pjm.domain.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dhhxfggg.pjm.domain.util.PjmLogger

/**
 * 开机自启：让「保存即入库」在重启后依然生效。
 *
 * 只在 BOOT_COMPLETED 时动作；真正的判断（列表是否为空、能否启动前台服务）
 * 全部交给 [TakeoverStarter]，本类只做转发，不持有任何状态。
 *
 * 注意：部分 ROM 的自启动管理会拦截开机广播，
 * 此时服务会在用户下次打开 App 时由 Application 拉起 —— 功能不丢，只是晚一点。
 */
class TakeoverBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        PjmLogger.i("TakeoverBootReceiver", "开机完成，尝试恢复应用接管")
        TakeoverStarter.startIfEnabled(context)
    }
}
