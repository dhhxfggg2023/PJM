package com.dhhxfggg.pjm.domain.service

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.dhhxfggg.pjm.domain.util.AudioSettings

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

    override fun onCreate() {
        super.onCreate()
        // 系统均衡器挂在全局输出上（session 0），这里初始化一次；失败会自己记日志
        AudioSettings.applyEqualizer(this)
        player =
            ExoPlayer.Builder(this).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            }
        player.volume = AudioSettings.appVolume(this)
        mediaSession = MediaSession.Builder(this, player).build()
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
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
