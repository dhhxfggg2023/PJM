package com.dhhxfggg.pjm.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.composables.icons.lucide.FastForward
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Minimize
import com.composables.icons.lucide.Rewind
import com.dhhxfggg.pjm.R
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/** 进度轮询间隔：位置文本一秒变两次，肉眼已经连续，再快只是白白唤醒主线程 */
private const val PROGRESS_POLL_MS = 500L

/** 时长还没拿到时的轮询间隔（进度条和快进手势都依赖它，先催紧一点） */
private const val PROGRESS_WARMUP_MS = 250L

/**
 * 播放进度状态：发现页（视频/音频）和查看器共用同一份轮询实现。
 *
 * 之前每个播放页面各自写一个 `while (true) { 读 currentPosition; delay } `，
 * 有的 200ms、有的 400ms、有的 500ms，还各自缓存一份 duration，同一件事有三个实现、
 * 两个真相（父层缓存的 duration 和子组件轮询到的 duration 会对不上）。
 *
 * 使用约束：状态可以提升，但**只有真正显示位置的组件**才去读 [positionMs] /
 * [shownPositionMs] / [fraction]。读它的作用域每 500ms 重组一次；播放器本体
 * （AndroidView）和手势层不要读位置，否则会因为进度更新而反复重组、滑动掉帧。
 */
@Stable
class PlayerProgressState internal constructor() {
    /** 播放器位置；seek 之后由调用方立即对齐，不用等下一次轮询 */
    var positionMs by mutableLongStateOf(0L)
        private set

    /** 总时长；未知（未就绪/直播）时保持上一次的值，避免进度条瞬间跳成 0 */
    var durationMs by mutableLongStateOf(0L)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    /** 用户正在拖动进度（进度条自己拖，或全屏手势层在拖） */
    var scrubbing by mutableStateOf(false)
        private set

    private var scrubPositionMs by mutableLongStateOf(0L)

    /** 进度条/时间文本要显示的位置：拖动中显示手指拖到的位置 */
    val shownPositionMs: Long
        get() = if (scrubbing) scrubPositionMs else positionMs

    /** 进度条要显示的比例 */
    val fraction: Float
        get() =
            if (durationMs > 0L) {
                (shownPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }

    /**
     * 开始/更新拖动（第一次调用即进入拖动状态）。
     *
     * 拖动期间轮询不再覆盖显示值 —— 否则播放进度会把滑块"抢回去"，手感就是拖不动。
     */
    fun scrubTo(ms: Long) {
        scrubbing = true
        scrubPositionMs = clampToDuration(ms)
    }

    /** 跳转后立即对齐显示值（快进/快退按钮、外部 seek），不用等下一轮轮询 */
    fun commitSeek(targetMs: Long) {
        val target = clampToDuration(targetMs)
        positionMs = target
        scrubPositionMs = target
    }

    /** 结束拖动并对齐显示值：[committedMs] 是已经 seek 到的位置（取消时传原位置） */
    fun endScrub(committedMs: Long = shownPositionMs) {
        val target = clampToDuration(committedMs)
        positionMs = target
        scrubPositionMs = target
        scrubbing = false
    }

    private fun clampToDuration(ms: Long): Long {
        val atLeastZero = ms.coerceAtLeast(0L)
        return if (durationMs > 0L) atLeastZero.coerceAtMost(durationMs) else atLeastZero
    }

    /** 播放/暂停由播放器事件驱动，点一下按钮图标立刻变，不用等下一次轮询 */
    internal fun setPlaying(value: Boolean) {
        isPlaying = value
    }

    internal fun reset() {
        positionMs = 0L
        durationMs = 0L
        isPlaying = false
        scrubbing = false
        scrubPositionMs = 0L
    }

    internal fun syncFrom(player: Player) {
        if (!scrubbing) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val total = player.duration
            // C.TIME_UNSET 是负数；未就绪时不覆盖已知时长
            if (total > 0L) durationMs = total
        }
        isPlaying = player.isPlaying
    }
}

/**
 * 记住一个跟着 [player] 走的 [PlayerProgressState]。
 *
 * 播放器换了（换集/换曲/借用池里另一个实例）会重新开始轮询并清零，不会把上一首的
 * 进度显示到下一首上。
 */
@Composable
fun rememberPlayerProgress(player: Player?): PlayerProgressState {
    val state = remember { PlayerProgressState() }
    LaunchedEffect(player) {
        state.reset()
        val current = player ?: return@LaunchedEffect
        // 播放/暂停走监听器（点一下图标立刻变）；位置/时长只能轮询，
        // 播放器不推位置事件，而位置文本一秒两次已经看不出跳。
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    state.setPlaying(isPlaying)
                }
            }
        current.addListener(listener)
        try {
            while (true) {
                state.syncFrom(current)
                delay(if (state.durationMs > 0L) PROGRESS_POLL_MS.milliseconds else PROGRESS_WARMUP_MS.milliseconds)
            }
        } finally {
            current.removeListener(listener)
        }
    }
    return state
}

/**
 * 视频控制条（进度 + 时间 + 全屏）：发现页和查看器共用一份。
 *
 * 之前两个页面各写一套「进度条 + 时间 + 全屏按钮」，样式和取值方式都不一样
 * （一个用渐变底、时间行是"当前 / 总长"，另一个用半透明黑底、两端各一个时间）。
 * 现在只有这一份，行为不会再各自漂移。
 *
 * @param progress 调用方可以传入自己的状态（全屏手势层拖进度时也写它），不传就在内部建一个
 * @param bottomPadding 控制条底部要留出的安全距离（全屏/底栏不同）
 * @param onToggleFullscreen 传 null 就不显示全屏按钮
 */
@Composable
fun VideoControlBar(
    player: Player?,
    modifier: Modifier = Modifier,
    progress: PlayerProgressState = rememberPlayerProgress(player),
    bottomPadding: Dp = 12.dp,
    fullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    /** 快退/快进按钮一次跳多少毫秒；传 0 就不显示这两个按钮 */
    seekStepMs: Long = 10_000L,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))),
                ).padding(bottom = bottomPadding),
    ) {
        PlayerProgressBar(
            progress = progress.fraction,
            onScrub = { fraction -> progress.scrubTo(fractionToMs(fraction, progress.durationMs)) },
            onSeek = { fraction ->
                val target = fractionToMs(fraction, progress.durationMs)
                player?.seekTo(target)
                progress.endScrub(target)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            activeColor = Color.White,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatPlaybackTime(progress.shownPositionMs), color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            // 快退 / 快进：从前只在双击左/右三分区里藏着，撤掉后用户找不到入口，
            // 变成了「快进没了」。这里做成常驻按钮，双击手势保留（两者都能用）。
            if (seekStepMs > 0L) {
                IconButton(
                    onClick = { seekBy(player, progress, -seekStepMs) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Lucide.Rewind,
                        contentDescription = stringResource(R.string.cd_seek_back),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(formatPlaybackTime(progress.durationMs), color = Color.White, fontSize = 12.sp)
                IconButton(
                    onClick = { seekBy(player, progress, seekStepMs) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Lucide.FastForward,
                        contentDescription = stringResource(R.string.cd_seek_forward),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Text(formatPlaybackTime(progress.durationMs), color = Color.White, fontSize = 12.sp)
            }
            if (onToggleFullscreen != null) {
                Spacer(Modifier.width(10.dp))
                IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = if (fullscreen) Lucide.Minimize else Lucide.Maximize,
                        contentDescription =
                            stringResource(
                                if (fullscreen) R.string.cd_toggle_portrait else R.string.cd_toggle_fullscreen,
                            ),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun fractionToMs(
    fraction: Float,
    durationMs: Long,
): Long = (fraction.coerceIn(0f, 1f) * durationMs).toLong()

/** 相对当前位置跳 [deltaMs]（负数 = 后退），并让进度条立刻反映新位置 */
private fun seekBy(
    player: Player?,
    progress: PlayerProgressState,
    deltaMs: Long,
) {
    val p = player ?: return
    val total = progress.durationMs
    val target = (p.currentPosition + deltaMs).coerceIn(0L, if (total > 0L) total else Long.MAX_VALUE)
    p.seekTo(target)
    progress.commitSeek(target)
}

/**
 * 播放时间文本：`分:秒`。
 *
 * 超过一小时不进位成 `时:分:秒`，而是继续数分钟（`125:03`）—— 视频/音频进度条上
 * 两端对齐看着更整齐，两个页面之前分别写了两份同样的实现。
 *
 * 固定用 [Locale.ROOT]：跟随系统 locale 时，某些语言会把数字格式化成非 ASCII 数字。
 */
fun formatPlaybackTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}
