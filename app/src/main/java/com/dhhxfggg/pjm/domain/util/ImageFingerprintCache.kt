package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.dhhxfggg.pjm.data.model.FileEntity
import java.io.File
import kotlin.math.abs

/**
 * 图片感知指纹缓存（半永久）。
 *
 * 用途：找出【内容相同但分辨率不同】的图片（原图 vs QQ 缩略图等）。
 * 原理：同一张图缩到小尺寸后 dHash（感知哈希）一致 —— 缩放/重压缩不影响
 *       相邻像素的相对亮度，因此不同分辨率的同图指纹相同。
 *
 * 存储：filesDir/image_fingerprints/<relativePath 哈希>.txt
 * 内容：dHash 二进制串 | 原始宽度 | 原始高度
 *
 * 特性：
 * 1. 半永久 —— 与缩略图一样，只在对应源文件删除时清理（delete），
 *    不随"清除缓存"消失，实现【增量检测】（只算新入库图片）。
 * 2. 完全独立于数据库 —— 不动 Room schema（避免迁移清库风险），
 *    也不占用 FileEntity.contentHash（那是 MD5，完整性校验要用）。
 */
data class ImageFingerprint(
    val dHash: String, // 64 位二进制串（'0'/'1'）
    val width: Int, // 原始宽度（用于"保留分辨率最高"）
    val height: Int, // 原始高度
)

object ImageFingerprintCache {
    private const val DIR_NAME = "image_fingerprints"

    /** 指纹解码目标宽度（足够小，解码快；dHash64 内部还会缩到 9x8） */
    private const val FP_WIDTH = 64

    /** 像素验证解码宽度（64px 足够区分内容，比 128px 快 4 倍 —— 修复海量候选对时验证阶段卡 80%） */
    private const val VERIFY_WIDTH = 64

    /** 像素验证阈值：平均亮度差 ≤ 12（0-255），同图不同分辨率通常 < 6 */
    private const val PIXEL_THRESHOLD = 12f

    /** 宽高比容差：比例差异 ≤ 3% 视为一致（排除 4:3 vs 16:9） */
    private const val RATIO_TOLERANCE = 0.03f

    /** 32×32 灰度预筛阈值：平均亮度差 ≤ 15 才可能内容一致（同图<6，不同图>20） */
    private const val GRAY32_PRESCREEN_THRESHOLD = 15f

    private fun fpDir(context: Context): File = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun fpFile(
        context: Context,
        entity: FileEntity,
    ): File {
        val hash =
            entity.relativePath
                .hashCode()
                .toUInt()
                .toString(16)
        return File(fpDir(context), "$hash.txt")
    }

    /** 32×32 灰度缓存文件名（基于同一哈希，扩展名 .g32 区分） */
    private fun gray32File(
        context: Context,
        entity: FileEntity,
    ): File {
        val hash =
            entity.relativePath
                .hashCode()
                .toUInt()
                .toString(16)
        return File(fpDir(context), "$hash.g32")
    }

    fun hasFingerprint(
        context: Context,
        entity: FileEntity,
    ): Boolean = fpFile(context, entity).exists()

    fun getFingerprint(
        context: Context,
        entity: FileEntity,
    ): ImageFingerprint? {
        val f = fpFile(context, entity)
        if (!f.exists()) return null
        return try {
            val parts = f.readText().split("|")
            if (parts.size >= 3) {
                ImageFingerprint(parts[0], parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveFingerprint(
        context: Context,
        entity: FileEntity,
        fp: ImageFingerprint,
    ) {
        try {
            fpFile(context, entity).writeText("${fp.dHash}|${fp.width}|${fp.height}")
        } catch (_: Exception) {
        }
    }

    /** 删除某实体的指纹 + 灰度缓存（源文件删除时调用） */
    fun delete(
        context: Context,
        entity: FileEntity,
    ) {
        try {
            fpFile(context, entity).delete()
        } catch (_: Exception) {
        }
        try {
            gray32File(context, entity).delete()
        } catch (_: Exception) {
        }
    }

    /** 清空整个指纹 + 灰度缓存 */
    fun clearAll(context: Context) {
        try {
            fpDir(context).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /**
     * 计算图片感知指纹：采样解码 + dHash + 原始分辨率。
     * 失败（非图片/损坏/无法解码）返回 null。
     *
     * 性能优化：一次读取图片头（bounds）即用于采样计算 + 返回原始分辨率，
     * 避免旧实现中 readBounds + decodeSampled 各读一次文件头的重复 I/O。
     */
    fun computeFingerprint(
        context: Context,
        entity: FileEntity,
    ): ImageFingerprint? {
        val file = VaultManager.getFileFromEntity(context, entity)
        if (!file.exists() || !FileUtils.isImageFile(entity.name)) return null
        return try {
            val decoded = decodeSampledWithBounds(file, FP_WIDTH) ?: return null
            try {
                val dHash = VaultManager.dHash64(decoded.first)
                ImageFingerprint(dHash, decoded.second.first, decoded.second.second)
            } finally {
                // 核心修复：显式回收解码 Bitmap，降低 1.2 万张的 GC 压力（防 OOM）
                try {
                    decoded.first.recycle()
                } catch (_: Exception) {
                }
            }
        } catch (_: Throwable) {
            // 核心修复：捕获 Throwable（含 OutOfMemoryError）—— 单张图失败跳过，绝不崩溃闪退
            null
        }
    }

    /**
     * 内容验证（组内精确确认，排除误报）：
     * 1. 宽高比差异 ≤ 3%（4:3 vs 16:9 直接排除）。
     * 2. 各自等比缩到 ~128 宽，比较公共区域的平均亮度差 ≤ [PIXEL_THRESHOLD]。
     * 只有"比例一致 + 内容一致"才算同图不同分辨率。
     */
    fun verifySameContent(
        context: Context,
        e1: FileEntity,
        e2: FileEntity,
    ): Boolean {
        return try {
            val f1 = VaultManager.getFileFromEntity(context, e1)
            val f2 = VaultManager.getFileFromEntity(context, e2)
            if (!f1.exists() || !f2.exists()) return false

            val b1 = readBounds(f1) ?: return false
            val b2 = readBounds(f2) ?: return false
            val r1 = b1.first.toFloat() / b1.second.coerceAtLeast(1)
            val r2 = b2.first.toFloat() / b2.second.coerceAtLeast(1)
            if (abs(r1 - r2) / maxOf(r1, r2) > RATIO_TOLERANCE) return false

            val s1 = decodeSampledWithBounds(f1, VERIFY_WIDTH)?.first ?: return false
            val s2 = decodeSampledWithBounds(f2, VERIFY_WIDTH)?.first ?: return false
            var s1s: Bitmap? = null
            var s2s: Bitmap? = null
            try {
                // 等比缩到同一宽度（比例一致时高度也应一致，仅取整误差 1px）
                s1s =
                    Bitmap.createScaledBitmap(
                        s1,
                        VERIFY_WIDTH,
                        (s1.height * VERIFY_WIDTH / s1.width.coerceAtLeast(1)).coerceAtLeast(1),
                        true,
                    )
                s2s =
                    Bitmap.createScaledBitmap(
                        s2,
                        VERIFY_WIDTH,
                        (s2.height * VERIFY_WIDTH / s2.width.coerceAtLeast(1)).coerceAtLeast(1),
                        true,
                    )
                val cmpW = minOf(s1s.width, s2s.width)
                val cmpH = minOf(s1s.height, s2s.height)

                var totalDiff = 0L
                var total = 0L
                for (y in 0 until cmpH) {
                    for (x in 0 until cmpW) {
                        val p1 = s1s.getPixel(x, y)
                        val p2 = s2s.getPixel(x, y)
                        val l1 = (((p1 shr 16) and 0xFF) * 299 + ((p1 shr 8) and 0xFF) * 587 + (p1 and 0xFF) * 114) / 1000
                        val l2 = (((p2 shr 16) and 0xFF) * 299 + ((p2 shr 8) and 0xFF) * 587 + (p2 and 0xFF) * 114) / 1000
                        totalDiff += abs(l1 - l2)
                        total++
                    }
                }
                if (total == 0L) return false
                totalDiff.toFloat() / total <= PIXEL_THRESHOLD
            } finally {
                // 核心修复：回收中间 Bitmap，降低 GC 压力（防 OOM）
                try {
                    s1s?.takeIf { it != s1 }?.recycle()
                } catch (_: Exception) {
                }
                try {
                    s2s?.takeIf { it != s2 }?.recycle()
                } catch (_: Exception) {
                }
                try {
                    s1.recycle()
                } catch (_: Exception) {
                }
                try {
                    s2.recycle()
                } catch (_: Exception) {
                }
            }
        } catch (_: Throwable) {
            // 核心修复：捕获 Throwable（含 OOM）—— 单对验证失败跳过，绝不崩溃
            false
        }
    }

    /**
     * 读取已落盘的 32×32 灰度（半永久缓存）。
     * @return 1024 字节灰度数组；未缓存/损坏返回 null
     */
    fun getGray32(
        context: Context,
        entity: FileEntity,
    ): ByteArray? {
        val f = gray32File(context, entity)
        if (!f.exists() || f.length() != 1024L) return null
        return try {
            f.readBytes()
        } catch (_: Throwable) {
            null
        }
    }

    /** 将 32×32 灰度落盘（半永久，下次直接复用） */
    fun saveGray32(
        context: Context,
        entity: FileEntity,
        gray: ByteArray,
    ) {
        if (gray.size != 1024) return
        try {
            gray32File(context, entity).writeBytes(gray)
        } catch (_: Throwable) {
        }
    }

    /**
     * 获取 32×32 灰度缩略图（读缓存优先；未缓存则计算并落盘）。
     *
     * 核心修复（半永久化）：灰度图落盘后，首次查重计算一次（5 分钟/1.3 万张），
     * 之后的查重全部走缓存 —— 秒级跳过灰度阶段，不再重复解码 1.3 万张大图。
     * 删除源文件时 delete() 会同步清理 .g32，保证无孤儿残留。
     *
     * @return 32×32=1024 字节灰度数组（0-255），失败返回 null
     */
    fun getOrComputeGray32(
        context: Context,
        entity: FileEntity,
    ): ByteArray? {
        getGray32(context, entity)?.let { return it }
        val file = VaultManager.getFileFromEntity(context, entity)
        if (!file.exists()) return null
        return try {
            val decoded = decodeSampledWithBounds(file, 64)?.first ?: return null
            try {
                val scaled = Bitmap.createScaledBitmap(decoded, 32, 32, true)
                try {
                    val gray = ByteArray(1024)
                    for (y in 0 until 32) {
                        for (x in 0 until 32) {
                            val p = scaled.getPixel(x, y)
                            gray[y * 32 + x] =
                                ((((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000).toByte()
                        }
                    }
                    // 落盘半永久缓存
                    saveGray32(context, entity, gray)
                    gray
                } finally {
                    try {
                        if (scaled != decoded) scaled.recycle()
                    } catch (_: Exception) {
                    }
                }
            } finally {
                try {
                    decoded.recycle()
                } catch (_: Exception) {
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 32×32 灰度快速预筛：平均亮度差 ≤ [GRAY32_PRESCREEN_THRESHOLD] 才可能内容一致。
     * 同图不同分辨率（含 JPEG 重压缩）灰度差通常 < 6；内容不同的图通常 > 20。
     * 阈值取 15 留有裕量，宁可多留候选（完整验证兜底）也不误杀。
     */
    fun gray32Similar(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) return false
        var diff = 0L
        for (i in a.indices) {
            diff += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        }
        return (diff.toFloat() / a.size) <= GRAY32_PRESCREEN_THRESHOLD
    }

    /** 读取图片原始宽高（不解码像素） */
    private fun readBounds(file: File): Pair<Int, Int>? =
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        } catch (_: Exception) {
            null
        }

    /**
     * 采样解码到目标宽度（2 的幂采样，避免全尺寸解码），并应用 EXIF 旋转。
     * @return (解码后的 Bitmap, 原始宽高) —— 一次读文件头同时用于采样与返回原始分辨率
     */
    private fun decodeSampledWithBounds(
        file: File,
        targetWidth: Int,
    ): Pair<Bitmap, Pair<Int, Int>>? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            applyExifRotation(raw, file.absolutePath) to (bounds.outWidth to bounds.outHeight)
        } catch (_: Exception) {
            null
        }
    }

    /** 按 EXIF orientation 旋转（QQ 原图常带旋转标签，缩略图已应用，必须对齐） */
    private fun applyExifRotation(
        bmp: Bitmap,
        path: String,
    ): Bitmap {
        val orientation =
            try {
                ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bmp, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bmp, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bmp, 270f)
            else -> bmp
        }
    }

    private fun rotateBitmap(
        bmp: Bitmap,
        degrees: Float,
    ): Bitmap =
        try {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (_: Exception) {
            bmp
        }
}
