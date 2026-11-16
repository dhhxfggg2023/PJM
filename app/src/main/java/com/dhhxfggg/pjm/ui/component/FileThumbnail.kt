package com.dhhxfggg.pjm.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.CacheKeys
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.ThumbnailCache
import com.dhhxfggg.pjm.domain.util.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 统一缩略图解码尺寸：网格列数少（卡片大）用高分辨率，列数多（卡片小）用低分辨率。
 *
 * FileCard 与 SelectableFileCard 必须共用此函数，确保切换视图模式时
 * Coil 内存/磁盘缓存 key 一致、能互相命中。
 */
internal fun thumbnailSizeFor(gridSpanCount: Int): Int =
    when (gridSpanCount) {
        1, 2 -> 512
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
    // 不再在组合期 stat 文件：保险库目录里几万个文件，FUSE 上一次 stat 可能要几毫秒，
    // 滚动时每个新项都做一次会直接把主线程拖慢。
    // 文件缺失时 Coil 自己会走 error 分支，露出的就是卡片底色，和原来一致。

    // 视频优先加载半持久化缩略图 jpg（首次由 ViewModel 异步生成）。
    // 解析缩略图路径要 stat 两次，不能在组合期做成同步 IO —— 挪到 IO 线程异步取。
    val diskThumb by
        produceState<File?>(initialValue = null, fileEntity.relativePath) {
            value =
                if (FileUtils.isVideoFile(fileEntity.name)) {
                    withContext(Dispatchers.IO) { ThumbnailCache.getThumbnailFile(context, fileEntity) }
                } else {
                    null
                }
        }
    val cacheKey =
        remember(fileEntity.relativePath, fileEntity.size, fileEntity.lastModified) {
            CacheKeys.of(fileEntity)
        }

    // 请求只构建一次：每次重组都 new 一个 Builder 会反复分配对象，也让 Coil 更容易重新发起加载
    val thumbPath = diskThumb?.absolutePath
    val request =
        remember(file.absolutePath, size, crossfade, thumbPath, cacheKey) {
            ImageRequest
                .Builder(context)
                .size(size)
                // 内存缓存键必须带尺寸：Coil 默认键本来就含 size，这里显式覆盖后如果不带，
                // 160px 解码结果会被 512px 的请求命中，网格从 6 列切回 2 列时整片发虚。
                .memoryCacheKey(cacheKey + "@" + size)
                .crossfade(crossfade)
                // 保险库里的文件本来就在本地，Coil 再把它整份复制进磁盘缓存是纯双倍 IO：
                // 滚动时每个新缩略图都要"读整个 JPEG + 写整个 JPEG"，缓存还会不停淘汰重写。
                // 直接解码源文件，内存缓存负责同会话的复用。
                .diskCachePolicy(CachePolicy.DISABLED)
                .apply {
                    if (thumbPath != null) {
                        // 持久化缩略图是小文件，直接加载；
                        // 不写 diskCacheKey，避免缩略图重建后命中旧缓存
                        data(thumbPath)
                    } else {
                        data(file.absolutePath)
                        diskCacheKey(cacheKey)
                        if (FileUtils.isVideoFile(fileEntity.name)) {
                            decoderFactory(VideoFrameDecoder.Factory())
                        }
                    }
                }.build()
        }

    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
    )
}
