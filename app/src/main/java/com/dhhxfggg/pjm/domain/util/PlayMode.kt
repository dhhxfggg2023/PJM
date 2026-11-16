package com.dhhxfggg.pjm.domain.util

/**
 * 与 ExoPlayer.Player.REPEAT_MODE_* 保持同一组常量值；领域层不依赖 media3。
 * 放在文件顶层而不是枚举 companion 里：枚举常量初始化时 companion 尚未就绪。
 */
object PlayerRepeatMode {
    const val OFF = 0
    const val ALL = 1
    const val ONE = 2
}

/**
 * 音频播放模式的单一四态定义（纯 Kotlin，无 Android/Compose 依赖）。
 *
 * 为什么抽到领域层：ExoPlayer 的 shuffle / repeat 两个开关自由组合会出现
 * 「随机 + 单曲循环」这种自相矛盾的状态，UI 只暴露这四个语义明确的模式。
 * 轮换顺序和反推规则是纯逻辑，放到这里才能在 JVM 单测里钉住，不必起 Compose。
 */
enum class PlayMode(
    val shuffle: Boolean,
    val repeat: Int,
) {
    /** 顺序播放：一首接一首，放完最后一首就停 */
    SEQUENTIAL(false, PlayerRepeatMode.OFF),

    /** 列表循环：放完最后一首回第一首 */
    REPEAT_ALL(false, PlayerRepeatMode.ALL),

    /** 单曲循环：一直重复当前这一首 */
    REPEAT_ONE(false, PlayerRepeatMode.ONE),

    /** 随机播放：打乱顺序且一轮放完再重新洗牌 */
    SHUFFLE(true, PlayerRepeatMode.ALL),
    ;

    /** 下一个模式：顺序 → 列表循环 → 单曲循环 → 随机 → 顺序 */
    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * 把播放器上成对的两个开关还原成一个模式（锁屏 / 通知栏切换后也能对上）。
         *
         * 随机优先于 repeat：四个模式里只有 SHUFFLE 会打开 shuffle。
         */
        fun fromPlayerFlags(
            shuffleEnabled: Boolean,
            repeatMode: Int,
        ): PlayMode =
            when {
                shuffleEnabled -> SHUFFLE
                repeatMode == PlayerRepeatMode.ONE -> REPEAT_ONE
                repeatMode == PlayerRepeatMode.ALL -> REPEAT_ALL
                else -> SEQUENTIAL
            }
    }
}
