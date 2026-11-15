package com.dhhxfggg.pjm.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 应用内统一的调节滑条。
 *
 * 不用 Material 默认 Slider：那根 4dp 轨道 + 两侧留白 + 默认波纹，
 * 放在设置页里显得又厚又碎，跟播放器的进度条也不是一套视觉。
 * 这里只用一条细轨 + 一个圆点，点按/拖动都能改值，尺寸和配色跟着主题走。
 */
@Composable
fun PjmSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val knobFill = MaterialTheme.colorScheme.surface

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(enabled, valueRange) {
                    if (!enabled) return@pointerInput
                    val pad = 9.dp.toPx()
                    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
                    detectTapGestures { offset ->
                        val usable = (size.width - pad * 2).coerceAtLeast(1f)
                        val f = ((offset.x - pad) / usable).coerceIn(0f, 1f)
                        latestChange(valueRange.start + span * f)
                        latestFinished?.invoke()
                    }
                }.pointerInput(enabled, valueRange) {
                    if (!enabled) return@pointerInput
                    val pad = 9.dp.toPx()
                    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f

                    fun emit(x: Float) {
                        val usable = (size.width - pad * 2).coerceAtLeast(1f)
                        val f = ((x - pad) / usable).coerceIn(0f, 1f)
                        latestChange(valueRange.start + span * f)
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { emit(it.x) },
                        onDragEnd = { latestFinished?.invoke() },
                        onDragCancel = { latestFinished?.invoke() },
                    ) { change, _ ->
                        change.consume()
                        emit(change.position.x)
                    }
                },
    ) {
        val pad = 9.dp.toPx()
        val trackH = 6.dp.toPx()
        val knobR = 8.dp.toPx()
        val usable = (size.width - pad * 2).coerceAtLeast(1f)
        val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
        val f = ((value - valueRange.start) / span).coerceIn(0f, 1f)
        val cy = size.height / 2f
        drawRoundRect(
            color = inactive,
            topLeft = Offset(pad, cy - trackH / 2f),
            size = Size(usable, trackH),
            cornerRadius = CornerRadius(trackH / 2f),
        )
        if (f > 0f) {
            drawRoundRect(
                color = primary,
                topLeft = Offset(pad, cy - trackH / 2f),
                size = Size(usable * f, trackH),
                cornerRadius = CornerRadius(trackH / 2f),
            )
        }
        val knobX = pad + usable * f
        // 圆点：主题色描边 + 内部底色，暗色主题下也不会糊成一团
        drawCircle(color = primary, radius = knobR, center = Offset(knobX, cy))
        drawCircle(color = knobFill, radius = knobR - 2.5.dp.toPx(), center = Offset(knobX, cy))
    }
}
