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

    private const val TAG = "DiscoveryPlayerPool"

    private val pool = ArrayDeque<ExoPlayer>()
    private val inUse = mutableSetOf<ExoPlayer>()

    /**
     * 借出一个播放器。池中有空闲则复用，否则新建。
     *
     * 核心修复：一律使用 `applicationContext` 构建播放器。
     * 调用方传进来的是 `LocalContext.current`（Activity），而 ExoPlayer 的
     * `DefaultRenderersFactory` → `MediaCodecVideoRenderer` 会长期持有该 Context；
     * 池是进程级单例且从不销毁 → 每次 Activity 重建（旋转/深色模式切换）都会
     * 永久泄漏一个 Activity。换成 Application Context 后与 Activity 生命周期彻底解耦。
     */
    @Synchronized
    fun acquire(context: Context): ExoPlayer {
        val appContext = context.applicationContext ?: context
        val player =
            pool.pollFirst()
                ?: ExoPlayer.Builder(appContext).build().apply {
                    volume = AudioSettings.appVolume(appContext)
                    // 发现页要"播完自动连播下一项"，必须关闭单曲循环，否则永远不会回调 STATE_ENDED
                    repeatMode = Player.REPEAT_MODE_OFF
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true,
                    )
                }
        // 池里借出来的旧实例不会重新走新建分支，音量必须在公共返回路径上按当前设置刷新，
        // 否则用户在设置里调过音量后，发现页复用池里的播放器一直是旧音量。
        player.volume = AudioSettings.appVolume(appContext)
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
     * 内存压力回收（由 MainApplication 在 onTrimMemory 里调用）。
     *
     * 背景：池是进程级单例，最多常驻 [POOL_SIZE] 个 ExoPlayer，每个都握着 MediaCodec
     * 硬解实例，却从不随系统内存压力收缩 —— 后台时容易被判定为内存大户直接杀进程。
     *
     * 语义（刻意如此，不改 acquire/release 契约）：
     *  - **空闲**实例（在 pool 里）直接 release，下次 acquire 重新创建；
     *  - **正在使用**（inUse）的实例只 stop、不 release —— 调用方手里还握着这个引用，
     *    release 之后再用会抛异常。stop 后续播会在下次 prepare 时恢复。
     */
    @Synchronized
    fun trimMemory() {
        val idle = pool.size
        pool.forEach { runCatching { it.release() } }
        pool.clear()
        inUse.forEach { runCatching { it.stop() } }
        if (idle > 0) PjmLogger.d(TAG, "内存压力回收：释放空闲播放器 " + idle + " 个，停止在用播放器 " + inUse.size + " 个")
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
