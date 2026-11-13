package com.dhhxfggg.pjm.domain.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dhhxfggg.pjm.MainActivity
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.util.PjmLogger

/**
 * 应用接管的通知（渠道登记 + 两类提醒），前台服务与兜底任务共用。
 *
 * 抽出来的原因：现在有两条执行路径（常驻前台服务 / JobScheduler 兜底），
 * 两条都要能发「原文件删不掉」「读不到相册索引」的提醒，通知代码只能有一份。
 */
object TakeoverNotifications {
    private const val TAG = "TakeoverNotifications"

    const val CHANNEL_SERVICE = "takeover_service"
    const val CHANNEL_FAILURE = "takeover_failure"

    const val NOTIFICATION_ID_SERVICE = 1002
    private const val NOTIFICATION_ID_FAILURE = 1003
    private const val NOTIFICATION_ID_QUERY_FAILED = 1004

    /** 失败通知携带的 uri 列表（点击后回到「删除原件」确认弹窗） */
    const val EXTRA_FAILED_URIS = "extra_takeover_failed_uris"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val serviceChannel =
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.takeover_channel_service),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.takeover_channel_service_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        val failureChannel =
            NotificationChannel(
                CHANNEL_FAILURE,
                context.getString(R.string.takeover_channel_failure),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.takeover_channel_failure_desc)
            }
        manager.createNotificationChannels(listOf(serviceChannel, failureChannel))
    }

    /**
     * 删除失败通知：文件已经安全入库，只是原文件还在相册。
     * 点击回到 App 的「删除原件」确认弹窗，用户走系统删除流程重试。
     */
    fun notifyDeleteFailures(
        context: Context,
        uris: List<Uri>,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_FAILED_URIS, uris.take(20).map { it.toString() }.toTypedArray())
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_FAILURE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.takeover_failure_title))
                .setContentText(context.getString(R.string.takeover_failure_text, uris.size))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(context.getString(R.string.takeover_failure_bigtext)),
                ).setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        runCatching { manager.notify(NOTIFICATION_ID_FAILURE, notification) }
    }

    /** 连续读不到相册索引时提醒：不是「没有新文件」，而是接管暂时失效 */
    fun notifyQueryFailure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                3,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_FAILURE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.takeover_query_failed_title))
                .setContentText(context.getString(R.string.takeover_query_failed_text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        runCatching { manager.notify(NOTIFICATION_ID_QUERY_FAILED, notification) }
        PjmLogger.w(TAG, "已发送「接管暂时失效」通知")
    }
}
