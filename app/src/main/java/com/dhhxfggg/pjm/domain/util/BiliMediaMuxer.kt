package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.dhhxfggg.pjm.domain.shizuku.ShizukuBridge
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * B 站分离音视频流（m4s）的合并与封装。
 *
 * 从 BiliBridge 拆出的自包含合并管线：
 *  - [merge]：复制/读取 m4s（优先特权路径）→ 去头 → mux 为 MP4
 *  - [sniffAndStrip]：定位 MP4 Box 头部，剔除 m4s 前导垃圾字节
 *  - [mux] / [writeTrack] / [findTrack]：MediaMuxer 封装
 */
object BiliMediaMuxer {
    private const val TAG = "BiliMediaMuxer"

    /** 单样本上限：4K/高码率 m4s 的单样本可能远超旧实现的 2MB */
    private const val INITIAL_SAMPLE_BYTES = 8 * 1024 * 1024
    private const val MAX_SAMPLE_BYTES = 64 * 1024 * 1024

    suspend fun merge(
        context: Context,
        item: BiliBridge.BiliCacheItem,
        outputFile: File,
    ): PjmResult<Unit> =
        withContext(VaultManager.PjmDispatchers.IO) {
            try {
                val cacheDir = context.cacheDir
                val vTmp = File(cacheDir, "bili_v_${System.nanoTime()}.m4v")
                val aTmp = File(cacheDir, "bili_a_${System.nanoTime()}.m4a")
                // 声明在 try 之外：失败路径的 finally 也要能清理临时副本
                var vLocal: File? = null
                var aLocal: File? = null
                try {
                    // 特权模式：先把 m4s 从 Android/data 复制到本地临时文件（主进程可读），再走既有流程
                    vLocal =
                        if (item.shizukuVideoPath != null) {
                            ShizukuBridge.copyToCache(context, item.shizukuVideoPath)
                        } else {
                            null
                        }
                    aLocal =
                        if (item.shizukuAudioPath != null) {
                            ShizukuBridge.copyToCache(context, item.shizukuAudioPath)
                        } else {
                            null
                        }

                    // 核心修复：有特权路径但复制失败时，不要回退到 file:// 打开 ——
                    // Android 15 对 file:// 访问 Android/data 必然 EACCES，回退会误报"权限拒绝"。
                    if (item.shizukuVideoPath != null && vLocal == null) {
                        throw IOException("复制视频流失败（特权服务未就绪或超时）: ${item.shizukuVideoPath}")
                    }
                    if (item.shizukuAudioPath != null && aLocal == null) {
                        throw IOException("复制音频流失败（特权服务未就绪或超时）: ${item.shizukuAudioPath}")
                    }

                    sniffAndStrip(
                        context,
                        if (vLocal != null) Uri.fromFile(vLocal) else item.videoM4s,
                        vTmp,
                    )
                    sniffAndStrip(
                        context,
                        if (aLocal != null) Uri.fromFile(aLocal) else item.audioM4s,
                        aTmp,
                    )
                    vLocal?.delete()
                    aLocal?.delete()

                    mux(vTmp, aTmp, outputFile)
                    if (outputFile.exists() && outputFile.length() > 0) {
                        PjmResult.Success(Unit)
                    } else {
                        PjmResult.Failure("合并结果为空")
                    }
                } finally {
                    vTmp.delete()
                    aTmp.delete()
                    // 核心修复：sniffAndStrip 抛异常时 vLocal/aLocal 会留在外部临时目录，
                    // 旧实现只在正常路径删，失败路径要等下一次合并的 10 分钟清理。
                    vLocal?.delete()
                    aLocal?.delete()
                    // 核心修复：只清理超过 10 分钟的老残留（copyToShared 产物），绝不误删并发任务的活跃文件
                    try {
                        context.getExternalFilesDir(null)?.let { ext ->
                            // 只清 10 分钟前的老残留：避免并发合并时删掉别的任务还在读的活跃临时文件
                            val cutoff = System.currentTimeMillis() - 10L * 60 * 1000
                            File(ext, "tmp").listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                PjmLogger.e(TAG, "合并致命失败: ${item.title}", e)
                PjmResult.Failure("合并失败: ${e.message}")
            }
        }

    /**
     * 剔除 m4s 前导字节并写为规范 MP4：定位 ftyp/moov 等 Box 标志，
     * 保留其前 4 字节 Size 字段，将其之前的多余字节跳过。
     */
    private fun sniffAndStrip(
        context: Context,
        srcUri: Uri,
        destFile: File,
    ) {
        val input = context.contentResolver.openInputStream(srcUri) ?: throw IOException("读取流失败: $srcUri")
        input.use { inputStream ->
            FileOutputStream(destFile).use { outputStream ->
                val buffer = VaultManager.acquireBuffer()
                try {
                    val firstRead = inputStream.read(buffer)
                    if (firstRead <= 0) return

                    // --- 核心修复：精准 MP4 Box 头部定位 (关键：保留 Size 4字节) ---
                    val boxTypes = listOf("ftyp", "moov", "mdat", "free", "skip", "styp", "sidx")
                    var boxIndex = -1
                    for (i in 0 until (firstRead - 4)) {
                        val potentialBox = String(buffer, i, 4, Charsets.US_ASCII)
                        if (potentialBox in boxTypes) {
                            boxIndex = i
                            break
                        }
                    }

                    // 修正后的跳过逻辑：如果找到了 ftyp 等标志位，应该保留它前面的 4 字节 Size 数据
                    val start = if (boxIndex >= 4) boxIndex - 4 else (if (firstRead > 9 && buffer[0] == 0.toByte()) 9 else 0)

                    val hexHead = buffer.take(16).joinToString(" ") { "%02X".format(it) }
                    PjmLogger.d(TAG, "Header Strip: Found Box at $boxIndex, Final Skip=$start. Head: $hexHead")

                    if (firstRead > start) outputStream.write(buffer, start, firstRead - start)
                    var bytes: Int
                    while (inputStream.read(buffer).also { bytes = it } != -1) outputStream.write(buffer, 0, bytes)
                    outputStream.flush()
                    try {
                        outputStream.fd.sync()
                    } catch (_: Exception) {
                    }
                } finally {
                    VaultManager.releaseBuffer(buffer)
                }
            }
        }
    }

    private fun mux(
        videoFile: File,
        audioFile: File,
        outputFile: File,
    ) {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vTrack = findTrack(videoExtractor, "video/")
            val aTrack = findTrack(audioExtractor, "audio/")
            if (vTrack < 0 || aTrack < 0) throw IOException("找不到音视频轨道 (V:$vTrack, A:$aTrack)")
            val vFormat = videoExtractor.getTrackFormat(vTrack)
            // 核心修复：MediaMuxer 不会从 track format 继承旋转，竖屏拍摄的视频合并后会躺倒
            runCatching { muxer.setOrientationHint(vFormat.getInteger(MediaFormat.KEY_ROTATION)) }
            val vIdx = muxer.addTrack(vFormat)
            val aIdx = muxer.addTrack(audioExtractor.getTrackFormat(aTrack))
            muxer.start()
            writeTrack(videoExtractor, vTrack, muxer, vIdx)
            writeTrack(audioExtractor, aTrack, muxer, aIdx)
            muxer.stop()
        } catch (e: Exception) {
            // 半成品 MP4 绝不能留下：上层若已建 DB 记录会得到不可播文件
            runCatching { outputFile.delete() }
            throw e
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            // 核心修复：stop() 抛异常后再 release() 可能再抛 IllegalStateException，
            // 会覆盖真正的 IOException。release 失败不应改变主异常。
            runCatching { muxer?.release() }
        }
    }

    private fun writeTrack(
        ex: MediaExtractor,
        track: Int,
        mx: MediaMuxer,
        mxIdx: Int,
    ) {
        ex.selectTrack(track)
        var buf = ByteBuffer.allocateDirect(INITIAL_SAMPLE_BYTES)
        val info = MediaCodec.BufferInfo()
        while (true) {
            info.offset = 0
            var size = ex.readSampleData(buf, 0)
            // 核心修复：样本大于缓冲区时 readSampleData 返回 -1，旧实现直接 break 当作 EOS ——
            // 4K/高码率视频因此"合并成功但后半段/大样本丢失"。按需扩容后重试（EOS 时 sampleTime=-1）。
            while (size < 0 && ex.sampleTime >= 0 && buf.capacity() < MAX_SAMPLE_BYTES) {
                buf = ByteBuffer.allocateDirect(minOf(buf.capacity() * 2, MAX_SAMPLE_BYTES))
                size = ex.readSampleData(buf, 0)
            }
            if (size < 0) break
            info.size = size
            info.presentationTimeUs = ex.sampleTime
            @Suppress("WrongConstant")
            info.flags = ex.sampleFlags
            mx.writeSampleData(mxIdx, buf, info)
            ex.advance()
        }
    }

    private fun findTrack(
        ex: MediaExtractor,
        prefix: String,
    ): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith(prefix, ignoreCase = true)) return i
        }
        return -1
    }
}
