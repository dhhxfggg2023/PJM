package com.dhhxfggg.pjm.domain.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.dhhxfggg.pjm.domain.util.AudioSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台音频播放服务（Media3 官方 MediaSessionService）。
 *
 * 睡眠助眠场景的关键：锁屏/退到后台后音频必须继续响，并且要有系统媒体通知
 * （锁屏能暂停/继续/调音量）。用官方的 MediaSessionService + MediaSession 实现，
 * 自己在页面里只拿 MediaController 控制，不再让播放器绑在 Composable 生命周期上。
 */
class AudioPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    /** 播放中同步「应用内音量」的协程（ExoPlayer 只能在主线程访问） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var volumeSyncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // 系统均衡器挂在全局输出上（session 0），这里初始化一次；失败会自己记日志
        AudioSettings.applyEqualizer(this)
        player =
            ExoPlayer.Builder(this).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                // 拔耳机/断蓝牙自动暂停，避免突然外放
                setHandleAudioBecomingNoisy(true)
            }
        player.volume = AudioSettings.appVolume(this)
        startVolumeSync()
        // 拔耳机/断蓝牙自动暂停（成熟播放器都这么做，避免声音突然外放）
        // 点通知/锁屏回到 App
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionBuilder = MediaSession.Builder(this, player)
        if (launchIntent != null) {
            sessionBuilder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        mediaSession = sessionBuilder.build()
    }

    /**
     * 播放中同步「应用内音量」。
     *
     * 核心修复：`player.volume` 原先只在 onCreate 读一次 —— 用户在设置页播放中调音量
     * 要等 MediaSessionService 被重新创建才生效。AudioSettings 只提供同步读取接口
     * （本轮改造范围不含 domain/util），因此只在【播放中】按 500ms 轮询：
     * 读的是进程内 SharedPreferences 内存值，暂停/停止时完全空转，代价可忽略。
     */
    private fun startVolumeSync() {
        if (volumeSyncJob != null) return
        volumeSyncJob =
            scope.launch {
                var applied = player.volume
                while (isActive) {
                    delay(VOLUME_SYNC_INTERVAL_MS)
                    if (!player.isPlaying) continue
                    val target = AudioSettings.appVolume(this@AudioPlaybackService)
                    if (target != applied) {
                        player.volume = target
                        applied = target
                    }
                }
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户把任务划掉时，如果没在播就收掉服务；正在播（助眠/后台听）则继续
        if (mediaSession?.player?.isPlaying != true) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        volumeSyncJob?.cancel()
        volumeSyncJob = null
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** 播放中检查「应用内音量」的间隔：够快让音量条听起来即时，又不至于空转 */
        private const val VOLUME_SYNC_INTERVAL_MS = 500L
    }
}
