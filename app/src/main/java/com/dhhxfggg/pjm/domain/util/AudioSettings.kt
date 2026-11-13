package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.media.audiofx.Equalizer

/**
 * 全局音频设置：5 段均衡器 + 应用内音量。
 *
 * 为什么存在 SharedPreferences 而不是 DataStore：播放发生在 [AudioPlaybackService] 与
 * 各个 ExoPlayer 里，有些调用点不是协程上下文，同步读取更省事；这里只有几个数字。
 *
 * 均衡器挂在「全局输出」（audio session 0）：它作用于整个媒体输出，和 ExoPlayer 的
 * audio session 解耦，后台助眠、视频、发现页都吃同一份设置。
 */
object AudioSettings {
    private const val TAG = "AudioSettings"
    private const val FILE = "pjm_audio"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_VOLUME = "app_volume"
    private const val BAND_COUNT = 5

    /** UI 上展示的 5 个频段中心频率（Hz），实际应用时映射到设备最近的分带 */
    val BAND_FREQS = intArrayOf(60, 230, 910, 3600, 14000)
    val BAND_LABELS = arrayOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")

    private var equalizer: Equalizer? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEqualizerEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEqualizerEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        applyEqualizer(context)
    }

    /** 应用内音量 0..1，不修改系统音量 */
    fun appVolume(context: Context): Float = prefs(context).getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f)

    fun setAppVolume(
        context: Context,
        volume: Float,
    ) {
        prefs(context).edit().putFloat(KEY_VOLUME, volume.coerceIn(0f, 1f)).apply()
    }

    /** 每段增益，单位 dB（-12..12） */
    fun bandGainDb(
        context: Context,
        index: Int,
    ): Float = prefs(context).getFloat("band_" + index, 0f).coerceIn(-12f, 12f)

    fun setBandGainDb(
        context: Context,
        index: Int,
        db: Float,
    ) {
        prefs(context).edit().putFloat("band_" + index, db.coerceIn(-12f, 12f)).apply()
        applyEqualizer(context)
    }

    /**
     * 把设置应用到系统均衡器。失败（设备不支持/被占用）只记日志，不影响播放。
     */
    @Synchronized
    fun applyEqualizer(context: Context) {
        runCatching {
            if (!isEqualizerEnabled(context)) {
                equalizer?.release()
                equalizer = null
                return
            }
            val effect = equalizer ?: Equalizer(0, 0).also { equalizer = it }
            effect.enabled = true
            val levels = effect.bandLevelRange // [min, max] in millibels
            val bandCount = effect.numberOfBands.toInt()
            for (uiBand in 0 until BAND_COUNT) {
                val targetFreq = BAND_FREQS[uiBand] * 1000
                var bestBand = 0
                var bestDistance = Long.MAX_VALUE
                for (band in 0 until bandCount) {
                    val range = effect.getBandFreqRange(band.toShort())
                    if (range != null && range.size >= 2) {
                        val center = (range[0].toLong() + range[1]) / 2
                        val distance = kotlin.math.abs(center - targetFreq)
                        if (distance < bestDistance) {
                            bestDistance = distance
                            bestBand = band
                        }
                    }
                }
                val millibels = (bandGainDb(context, uiBand) * 100).toInt().toShort()
                effect.setBandLevel(bestBand.toShort(), millibels.coerceIn(levels[0], levels[1]))
            }
        }.onFailure { e ->
            PjmLogger.w(TAG, "应用均衡器失败: " + e.message)
        }
    }

    /** 播放器创建时把「应用内音量」用上 */
    fun applyVolume(
        context: Context,
        setVolume: (Float) -> Unit,
    ) {
        setVolume(appVolume(context))
    }
}
