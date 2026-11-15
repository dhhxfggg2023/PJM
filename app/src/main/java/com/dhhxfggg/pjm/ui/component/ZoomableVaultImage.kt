package com.dhhxfggg.pjm.ui.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File

/**
 * 图片缩放查看（库内查看器与发现页共用同一套实现）。
 *
 * 边界、双指缩放、双击放大、放大后的平移都由组件统一计算，不需要各自维护
 * scale/offset 状态。之前发现页自己写了一套手势 + graphicsLayer，缩放后能把图拖出屏幕；
 * 换成这个组件后两处行为完全一致。
 *
 * @param onZoomedChange 是否处于放大状态（发现页用它锁住竖向翻页）
 * @param onLongPress 长按时回调（发现页用来做触感反馈）
 */
@Composable
fun ZoomableVaultImage(
    file: File,
    modifier: Modifier = Modifier,
    onZoomedChange: (Boolean) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // 只读文件头取像素尺寸（不解码整张图），用来算"最大放大到 1:1 是几倍"
    val dims by
        produceState<IntArray?>(initialValue = null, file.absolutePath) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, options)
                        if (options.outWidth > 0 && options.outHeight > 0) {
                            intArrayOf(options.outWidth, options.outHeight)
                        } else {
                            null
                        }
                    }.getOrNull()
                }
        }
    val currentOnZoomedChange by rememberUpdatedState(onZoomedChange)
    BoxWithConstraints(modifier = modifier) {
        val viewWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val viewHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val maxZoomFactor =
            remember(dims, viewWidthPx, viewHeightPx) {
                maxZoomFor(dims, viewWidthPx, viewHeightPx)
            }
        key(file.absolutePath) {
            // 缩放状态按「文件」隔离：Telephoto 用 rememberSaveable，不隔离会把上一张的缩放带到下一张
            val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = maxZoomFactor))
            LaunchedEffect(zoomableState) {
                snapshotFlow { zoomableState.zoomFraction }.collect {
                    currentOnZoomedChange((it ?: 1f) > 1.05f)
                }
            }
            ZoomableAsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(file)
                        .size(2560)
                        .precision(Precision.INEXACT)
                        // 本地文件不需要 Coil 的磁盘缓存副本（解码结果它也不缓存）
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                contentDescription = null,
                state = rememberZoomableImageState(zoomableState),
                modifier = Modifier.fillMaxSize(),
                onLongClick = { onLongPress?.invoke() },
            )
        }
    }
}

/**
 * 最大缩放倍率 = 解码目标（2560px）与 Fit 缩放之比。
 * 比如 4000x3000 的图在 1080 宽的屏上，Fit 缩放是 0.27、解码缩放是 0.64 → 能放到约 2.4 倍正好 1:1。
 */
private fun maxZoomFor(
    dims: IntArray?,
    viewWidthPx: Float,
    viewHeightPx: Float,
): Float {
    if (dims == null || dims[0] <= 0 || dims[1] <= 0) return 3f
    val fileWidth = dims[0].toFloat()
    val fileHeight = dims[1].toFloat()
    val fitScale = minOf(viewWidthPx / fileWidth, viewHeightPx / fileHeight)
    if (fitScale <= 0f) return 3f
    val decodeScale = minOf(1f, 2560f / maxOf(fileWidth, fileHeight))
    return (decodeScale / fitScale).coerceIn(2f, 12f)
}
