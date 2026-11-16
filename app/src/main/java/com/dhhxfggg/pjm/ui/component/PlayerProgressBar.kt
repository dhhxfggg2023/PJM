package com.dhhxfggg.pjm.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 播放器进度条（自己画，不用 Material 的 Slider）。
 *
 * 原因：Material3 的 Slider 滑块是一条又高又细的竖线，放在视频/音频控件上很突兀。
 * 这里改成「5dp 圆角轨道 + 14dp 圆形滑块」，拖动时只回调本地位置，松手才真正 seek。
 *
 * @param progress 0f..1f
 * @param onScrub 拖动中回调（不要在这里 seek）
 * @param onSeek 松手/点击时回调（在这里 seek）
 */
@Composable
fun PlayerProgressBar(
    progress: Float,
    onScrub: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
) {
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    val shown = (if (dragging) dragFraction else progress).coerceIn(0f, 1f)
    val thumbPx = with(LocalDensity.current) { 7.dp.toPx() }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(28.dp)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(widthPx) {
                    if (widthPx <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset: Offset ->
                            dragging = true
                            dragFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                            onScrub(dragFraction)
                        },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                            onScrub(dragFraction)
                        },
                        onDragEnd = {
                            dragging = false
                            onSeek(dragFraction)
                        },
                        // 核心修复：拖动被系统取消时如果不回调 onSeek，外层 scrubbing 状态永远为 true，
                        // 进度条与时间文本会卡在拖动位置直到再点一下。这里回到真实播放位置。
                        onDragCancel = {
                            dragging = false
                            onSeek(progress.coerceIn(0f, 1f))
                        },
                    )
                }.pointerInput(widthPx) {
                    if (widthPx <= 0) return@pointerInput
                    detectTapGestures { offset ->
                        val f = (offset.x / widthPx).coerceIn(0f, 1f)
                        dragFraction = f
                        onSeek(f)
                    }
                },
    ) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(inactiveColor),
        )
        if (shown > 0f) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(shown)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(activeColor),
            )
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((widthPx * shown - thumbPx).roundToInt().coerceAtLeast(0), 0) }
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(activeColor),
        )
    }
}
