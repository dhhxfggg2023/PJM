package com.dhhxfggg.pjm.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 视频画面层（播放器与渲染层共用一份）。
 *
 * 查看器和发现页都复用它：只负责把 `ExoPlayer` 绑到 Media3 的 `PlayerView` 上，
 * 控制条、手势由调用方决定 —— 避免两个页面各写一套 PlayerView 配置，行为漂移。
 *
 * 注意：Media3 的 @UnstableApi 是 androidx 的 opt-in 标记，不是 Kotlin 的 @RequiresOptIn。
 * 这里必须用 @androidx.annotation.OptIn；写成 Kotlin 的 @OptIn 只会得到一条编译警告，
 * 而 lint 的 UnsafeOptInUsageError 会直接报错（lint 门禁打开后就是构建失败）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoSurface(
    player: ExoPlayer?,
    modifier: Modifier = Modifier,
    useController: Boolean = false,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                this.useController = useController
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { view -> view.player = player },
        modifier = modifier,
    )
}
