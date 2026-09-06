package com.dhhxfggg.pjm.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.ThumbnailCache
import com.dhhxfggg.pjm.domain.util.VaultManager

/**
 * 统一缩略图解码尺寸：网格列数少（卡片大）用高分辨率，列数多（卡片小）用低分辨率。
 *
 * FileCard 与 SelectableFileCard 必须共用此函数，确保切换视图模式时
 * Coil 内存/磁盘缓存 key 一致、能互相命中。
 */
internal fun thumbnailSizeFor(gridSpanCount: Int): Int =
    when (gridSpanCount) {
        1, 2 -> 640
        3, 4 -> 320
        else -> 160
    }

/**
 * 文件缩略图层，由 [FileCard] 与 [SelectableFileCard] 共用。
 *
 * 加载优先级：
 *  1. 视频持久化缩略图 jpg（ThumbnailCache）→ Coil 解码小图，秒开。
 *  2. 图片原文件 → Coil 采样解码（diskCacheKey = 指纹，跨会话命中）。
 *  3. 视频原文件 → VideoFrameDecoder 抽帧（diskCacheKey = 指纹，仅首次慢，之后落盘持久缩略图）。
 *
 * 非图片/视频（文档、压缩包、apk 等）不绘制缩略图层，由外层图标兜底。
 * 内存管理：不再传入已解码的 Bitmap，统一由 Coil 的三级缓存（内存 → 磁盘）按需加载，
 * 避免 ViewModel 强引用 Bitmap 导致 OOM。
 */
@Composable
internal fun FileThumbnail(
    fileEntity: FileEntity,
    size: Int,
    crossfade: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (!fileEntity.isImage && !FileUtils.isVideoFile(fileEntity.name)) return

    val context = LocalContext.current
    val file =
        remember(fileEntity.relativePath) {
            VaultManager.getFileFromEntity(context, fileEntity)
        }
    if (!file.exists()) return

    // 视频优先加载半持久化缩略图 jpg（首次由 ViewModel 异步生成）
    val diskThumb =
        remember(fileEntity.relativePath) {
            if (FileUtils.isVideoFile(fileEntity.name)) {
                ThumbnailCache.getThumbnailFile(context, fileEntity)
            } else {
                null
            }
        }
    val fingerprint =
        remember(fileEntity.relativePath) {
            FileUtils.getFileFingerprint(fileEntity)
        }

    val request =
        ImageRequest
            .Builder(context)
            .size(size)
            .crossfade(crossfade)
            .apply {
                if (diskThumb != null) {
                    // 持久化缩略图是小文件，直接加载；
                    // 不写 diskCacheKey，避免缩略图重建后命中旧缓存
                    data(diskThumb.absolutePath)
                } else {
                    data(file.absolutePath)
                    diskCacheKey(fingerprint)
                    if (FileUtils.isVideoFile(fileEntity.name)) {
                        decoderFactory(VideoFrameDecoder.Factory())
                    }
                }
            }.build()

    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
    )
}
