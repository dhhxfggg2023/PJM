package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.dhhxfggg.pjm.data.model.FileEntity
import java.io.File
import java.io.FileOutputStream

/**
 * 视频/图片缩略图持久缓存。
 *
 * 背景：视频缩略图若每次实时解码视频第一帧（VideoFrameDecoder），
 * 成本远高于解码图片（慢 10~100 倍）。本工具将首次生成的视频缩略图
 * 落盘为 JPEG 文件，之后 UI 直接加载该小图 —— "半永久"缓存，
 * 源文件不删除则缩略图一直有效。
 *
 * 目录：filesDir/thumbnails/<relativePath 哈希>.jpg
 */
object ThumbnailCache {

    private const val DIR_NAME = "thumbnails"
    private const val MAX_WIDTH = 640
    private const val JPEG_QUALITY = 72

    /** 缩略图目录（公开给后台同步管理器做孤儿清理） */
    fun thumbDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 缩略图文件名（基于 relativePath 稳定哈希，源文件路径不变则文件名不变；公开给后台同步管理器） */
    fun thumbName(entity: FileEntity): String {
        val hash = entity.relativePath.hashCode().toUInt().toString(16)
        return "$hash.jpg"
    }

    /** 缩略图文件对象（不保证存在） */
    fun thumbFile(context: Context, entity: FileEntity): File =
        File(thumbDir(context), thumbName(entity))

    /** 缩略图是否已缓存 */
    fun hasThumbnail(context: Context, entity: FileEntity): Boolean {
        val f = thumbFile(context, entity)
        return f.exists() && f.length() > 0
    }

    /** 返回缓存的缩略图文件（存在时），否则 null */
    fun getThumbnailFile(context: Context, entity: FileEntity): File? =
        if (hasThumbnail(context, entity)) thumbFile(context, entity) else null

    /**
     * 用 MediaMetadataRetriever 从视频取一帧生成缩略图并写盘。
     * @return 生成的缩略图文件；失败返回 null
     */
    fun generateVideoThumbnail(context: Context, entity: FileEntity): File? {
        val source = VaultManager.getFileFromEntity(context, entity)
        if (!source.exists() || !FileUtils.isVideoFile(entity.name)) return null

        val output = thumbFile(context, entity)
        if (output.exists()) output.delete()

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            // 取 1 秒处关键帧（首帧常为黑屏），回退任意帧
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null

            val scaled = scaleDown(frame, MAX_WIDTH)
            FileOutputStream(output).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                fos.flush()
            }
            output
        } catch (_: Exception) {
            output.delete()
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 用 BitmapFactory 采样解码图片生成缩略图并写盘（半永久缓存）。
     * 与视频缩略图互补 —— 图片不再每次浏览都全尺寸解码。
     * @return 生成的缩略图文件；失败返回 null
     */
    fun generateImageThumbnail(context: Context, entity: FileEntity): File? {
        val source = VaultManager.getFileFromEntity(context, entity)
        if (!source.exists() || !FileUtils.isImageFile(entity.name)) return null

        val output = thumbFile(context, entity)
        if (output.exists()) output.delete()

        return try {
            // 先读边界，计算 inSampleSize 避免全尺寸解码
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_WIDTH && bounds.outWidth > 0) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(source.absolutePath, decodeOpts) ?: return null
            val scaled = scaleDown(bmp, MAX_WIDTH)
            FileOutputStream(output).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                fos.flush()
            }
            output
        } catch (_: Exception) {
            output.delete()
            null
        }
    }

    /** 把现成 Bitmap 写为缩略图文件（用于预生成） */
    fun writeThumbnail(context: Context, entity: FileEntity, bitmap: Bitmap): File? {
        val output = thumbFile(context, entity)
        return try {
            val scaled = scaleDown(bitmap, MAX_WIDTH)
            FileOutputStream(output).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                fos.flush()
            }
            output
        } catch (_: Exception) {
            output.delete()
            null
        }
    }

    /** 删除某实体的缩略图（源文件删除时调用） */
    fun delete(context: Context, entity: FileEntity) {
        try { thumbFile(context, entity).delete() } catch (_: Exception) {}
    }

    /** 清空整个缩略图缓存 */
    fun clearAll(context: Context) {
        try { thumbDir(context).listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }

    /** 按最长边等比缩放 */
    private fun scaleDown(bmp: Bitmap, maxWidth: Int): Bitmap {
        if (bmp.width <= maxWidth) return bmp
        val ratio = maxWidth.toFloat() / bmp.width
        return Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * ratio).toInt().coerceAtLeast(1), true)
    }
}
