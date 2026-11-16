package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.util.PlayMode
import com.dhhxfggg.pjm.domain.util.PlayMode.Companion.fromPlayerFlags
import com.dhhxfggg.pjm.domain.util.PlayerRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音频播放四态的纯逻辑测试：轮换顺序 + 从 shuffle/repeat 开关反推。
 */
class PlayModeTest {
    @Test
    fun nextRotatesSequentialToRepeatAll() {
        assertEquals(PlayMode.REPEAT_ALL, PlayMode.SEQUENTIAL.next())
    }

    @Test
    fun nextRotatesRepeatAllToRepeatOne() {
        assertEquals(PlayMode.REPEAT_ONE, PlayMode.REPEAT_ALL.next())
    }

    @Test
    fun nextRotatesRepeatOneToShuffle() {
        assertEquals(PlayMode.SHUFFLE, PlayMode.REPEAT_ONE.next())
    }

    @Test
    fun nextRotatesShuffleBackToSequential() {
        assertEquals(PlayMode.SEQUENTIAL, PlayMode.SHUFFLE.next())
    }

    @Test
    fun nextIsAFourCycle() {
        var mode = PlayMode.SEQUENTIAL
        repeat(4) { mode = mode.next() }
        assertEquals(PlayMode.SEQUENTIAL, mode)
    }

    @Test
    fun fromPlayerFlagsDetectsShuffleRegardlessOfRepeat() {
        // 随机优先：即便 repeat=one（外部设置出的矛盾态），UI 也只显示随机
        assertEquals(
            PlayMode.SHUFFLE,
            fromPlayerFlags(shuffleEnabled = true, repeatMode = PlayerRepeatMode.ONE),
        )
        assertEquals(
            PlayMode.SHUFFLE,
            fromPlayerFlags(shuffleEnabled = true, repeatMode = PlayerRepeatMode.ALL),
        )
    }

    @Test
    fun fromPlayerFlagsDetectsRepeatOne() {
        assertEquals(
            PlayMode.REPEAT_ONE,
            fromPlayerFlags(shuffleEnabled = false, repeatMode = PlayerRepeatMode.ONE),
        )
    }

    @Test
    fun fromPlayerFlagsDetectsRepeatAll() {
        assertEquals(
            PlayMode.REPEAT_ALL,
            fromPlayerFlags(shuffleEnabled = false, repeatMode = PlayerRepeatMode.ALL),
        )
    }

    @Test
    fun fromPlayerFlagsFallsBackToSequential() {
        assertEquals(
            PlayMode.SEQUENTIAL,
            fromPlayerFlags(shuffleEnabled = false, repeatMode = PlayerRepeatMode.OFF),
        )
        // 未知 repeat 值也按顺序处理，不能崩
        assertEquals(PlayMode.SEQUENTIAL, fromPlayerFlags(shuffleEnabled = false, repeatMode = 99))
    }

    @Test
    fun playerFlagsMatchModeSemantics() {
        // 写回播放器的开关与反推一一对应（SHUFFLE 固定带 repeat all）
        PlayMode.entries.forEach { mode ->
            assertEquals(mode, fromPlayerFlags(mode.shuffle, mode.repeat))
        }
    }
}
