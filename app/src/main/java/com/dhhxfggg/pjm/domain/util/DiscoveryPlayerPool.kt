package com.dhhxfggg.pjm.domain.util

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.ArrayDeque

/**
 * 发现页 ExoPlayer 复用池。
 *
 * 背景：LazyColumn 中每个视频若各自 new ExoPlayer（含 MediaCodec 硬解），
 * 滑动时反复创建/销毁播放器，卡顿且耗电。池化后最多保持 [POOL_SIZE]
 * 个实例，滚动时借还复用 —— 避免 codec 反复初始化。
 *
 * 同一时刻仅当前激活项使用播放器（其余显示缩略图），池内实例数 = 激活上限。
 */
object DiscoveryPlayerPool {
    private const val POOL_SIZE = 2

    private val pool = ArrayDeque<ExoPlayer>()
    private val inUse = mutableSetOf<ExoPlayer>()

    /**
     * 借出一个播放器。池中有空闲则复用，否则新建。
     */
    @Synchronized
    fun acquire(context: Context): ExoPlayer {
        val player =
            pool.pollFirst()
                ?: ExoPlayer.Builder(context).build().apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true,
                    )
                }
        inUse.add(player)
        return player
    }

    /**
     * 归还播放器。池未满则复用，否则释放。
     */
    @Synchronized
    fun release(player: ExoPlayer) {
        if (!inUse.remove(player)) return
        try {
            player.stop()
            player.clearMediaItems()
            player.seekTo(0)
        } catch (_: Exception) {
        }
        if (pool.size < POOL_SIZE) {
            pool.addLast(player)
        } else {
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 应用级清理（一般无需调用，进程结束时自动释放）。
     */
    @Synchronized
    fun shutdown() {
        pool.forEach { runCatching { it.release() } }
        pool.clear()
        inUse.forEach { runCatching { it.release() } }
        inUse.clear()
    }
}
