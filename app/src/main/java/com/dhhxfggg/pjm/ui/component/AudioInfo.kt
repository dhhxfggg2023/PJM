package com.dhhxfggg.pjm.ui.component

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 音频元数据 + 内嵌封面（一次 retriever 同时取出来，避免两遍解析） */
data class AudioInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val artwork: Bitmap?,
)

private fun readAudioInfo(file: File): AudioInfo? {
    if (file.length() <= 0L) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val artwork =
            retriever.embeddedPicture?.let { bytes ->
                runCatching {
                    // 核心修复：内嵌封面常是 3000×3000 以上（ARGB_8888 约 36MB），原来直接全尺寸解码。
                    // 先读边界算采样、按 ~1024 边长解码，避免大分配 + GC 抖动（OOM 被 runCatching
                    // 兜住时用户看到的就是"没有封面"）。
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val longest = maxOf(bounds.outWidth, bounds.outHeight)
                    var sample = 1
                    while (longest > 0 && longest / (sample * 2) >= 1024) sample *= 2
                    BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }.getOrNull()
            }
        AudioInfo(
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            durationMs =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            artwork = artwork,
        )
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * 读取音频的标题/艺术家/专辑/时长/内嵌封面。
 *
 * 解析在 IO 线程做：一首 flac 的元数据要几百 KB，放在主线程会让打开播放页明显卡一下。
 */
@Composable
fun rememberAudioInfo(file: File): AudioInfo? {
    val info by
        produceState<AudioInfo?>(initialValue = null, file.absolutePath, file.length()) {
            value = withContext(Dispatchers.IO) { readAudioInfo(file) }
        }
    return info
}
