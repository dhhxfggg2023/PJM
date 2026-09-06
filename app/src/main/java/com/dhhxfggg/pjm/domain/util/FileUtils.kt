package com.dhhxfggg.pjm.domain.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dhhxfggg.pjm.data.model.FileEntity
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-performance File Utilities for the PJM project.
 * Provides abstraction for MediaStore operations, URI resolution, and file type classification.
 */
object FileUtils {
    private const val TAG = "FileUtils"

    private val ALREADY_COMPRESSED =
        setOf(
            "jpg",
            "jpeg",
            "png",
            "gif",
            "webp",
            "heic",
            "heif",
            "mp4",
            "mkv",
            "avi",
            "mov",
            "wmv",
            "flv",
            "webm",
            "m4v",
            "3gp",
            "mp3",
            "aac",
            "ogg",
            "flac",
            "m4a",
            "wav",
            "opus",
            "zip",
            "rar",
            "7z",
            "pjm",
            "tar",
            "gz",
            "bz2",
            "xz",
            "iso",
        )

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "ico", "tiff", "svg")
    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts", "rmvb")
    private val AUDIO_EXT = setOf("mp3", "aac", "ogg", "flac", "m4a", "wav", "opus", "amr", "wma", "mid")

    private val cachedDateFormat =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        }

    private val compactTsFormat =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        }

    /** 规范命名分享目录（缓存目录下，可被系统自动回收；存放与原文件同内容的硬链接/副本） */
    private const val NAMED_SHARE_DIR = "pjm_named_share"

    /** 硬链接不可用时，超过该体积的文件不再复制（避免大文件分享卡顿），退回原始命名 */
    private const val MAX_COPY_FALLBACK_BYTES = 256L * 1024 * 1024

    /**
     * Generates a unique fingerprint for a file entity based on its metadata.
     */
    fun getFileFingerprint(entity: FileEntity): String = "${entity.extension}_${entity.size}_${entity.lastModified}"

    /**
     * 规范化显示名（用于列表/详情/导出/分享的文件名，磁盘物理文件不变）。
     * - PJM 加密容器：本身已是规范命名（Export_/Pack_/Random_ + 可读时间），原样返回；
     * - 其他文件：统一为 `PJM_yyyyMMdd_HHmmss.扩展名`（时间取入库时间 lastModified）。
     */
    fun normalizedDisplayName(entity: FileEntity): String {
        if (entity.extension.equals("pjm", ignoreCase = true)) return entity.name
        val ts = compactTsFormat.get()?.format(Date(entity.lastModified)) ?: "unknown"
        val base = "PJM_$ts"
        val ext = entity.extension.lowercase().trim('.')
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /**
     * 获得一个“文件名为规范名、且可被 FileProvider 暴露/分享”的副本。
     * 优先硬链接（同文件系统、零拷贝、瞬间完成），失败则对小文件复制兜底；
     * 磁盘原文件始终不被改动。大文件复制兜底不可用时退回原文件（原始命名）。
     */
    fun obtainNamedShareFile(
        context: Context,
        entity: FileEntity,
    ): File {
        val src = VaultManager.getFileFromEntity(context, entity)
        if (!src.exists()) return src
        val displayName = normalizedDisplayName(entity)
        // 磁盘文件已是规范名（如 pjm 容器或已被规范命名的文件）→ 直接用
        if (src.name == displayName) return src

        val dir = File(context.cacheDir, NAMED_SHARE_DIR)
        runCatching { dir.mkdirs() }
        cleanupOldNamedFiles(dir)

        val target = uniqueNamedFile(dir, displayName)
        // 1) 硬链接：零拷贝，与磁盘原文件同 inode，删除不影响原文件（API 26+ 支持）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                Files.createLink(target.toPath(), src.toPath())
                return target
            }
        }
        // 2) 复制兜底：仅限体积适中的文件，避免大文件分享明显卡顿
        if (src.length() <= MAX_COPY_FALLBACK_BYTES) {
            runCatching {
                src.copyTo(target)
                return target
            }
        }
        // 3) 都不行 → 退回原文件（外部看到的仍是磁盘原名）
        PjmLogger.w(TAG, "无法生成规范名副本，退回原文件: ${src.name}")
        return src
    }

    private fun uniqueNamedFile(
        dir: File,
        displayName: String,
    ): File {
        val candidate = File(dir, displayName)
        if (!candidate.exists()) return candidate
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var n = 2
        while (File(dir, "${base}_$n$ext").exists()) n++
        return File(dir, "${base}_$n$ext")
    }

    private fun cleanupOldNamedFiles(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            if (now - f.lastModified() > 24 * 60 * 60 * 1000L) runCatching { f.delete() }
        }
    }

    /**
     * Retrieves the size of a file from its Uri.
     * Supports both "content://" and "file://" schemes.
     *
     * @return File size in bytes, or 0 if retrieval fails.
     */
    fun getFileSize(
        context: Context,
        uri: Uri,
    ): Long =
        try {
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                } ?: 0L
            } else {
                File(uri.path ?: "").length()
            }
        } catch (e: Exception) {
            PjmLogger.w(TAG, "Failed to get file size for $uri", e)
            0L
        }

    /**
     * Resolves the display name of a file from its Uri.
     */
    fun getFileName(
        context: Context,
        uri: Uri,
    ): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) result = cursor.getString(0)
                }
            } catch (e: Exception) {
                PjmLogger.e(TAG, "Filename query failed for $uri", e)
            }
        }
        if (result == null) {
            // 核心修复：同时兼容 '/' 与 Windows '\\' 分隔符。
            // Android 真机路径均为正斜杠；但在 Windows JVM（如 Robolectric 单测）上
            // Uri.fromFile 的 path 使用反斜杠，只切 '/' 会返回整条完整路径，
            // 导致加密包内的条目名变成绝对路径。
            result = uri.path?.substringAfterLast('/')?.substringAfterLast('\\')
        }
        return result ?: "file_${System.currentTimeMillis()}"
    }

    /**
     * Extracts the extension from a file name.
     * Special handling for PJM multi-part containers.
     */
    fun getFileExtension(fileName: String): String {
        val lower = fileName.lowercase()
        if (lower.contains(".pjm.")) return "pjm"
        return fileName.substringAfterLast('.', "").lowercase()
    }

    fun isPjmFile(fileName: String): Boolean = getFileExtension(fileName) == "pjm"

    fun shouldCompress(fileName: String): Boolean = getFileExtension(fileName) !in ALREADY_COMPRESSED

    fun isImageFile(fileName: String): Boolean = getFileExtension(fileName) in IMAGE_EXT

    fun isVideoFile(fileName: String): Boolean = getFileExtension(fileName) in VIDEO_EXT

    fun isAudioFile(fileName: String): Boolean = (getFileExtension(fileName) in AUDIO_EXT)

    fun isArchiveFile(fileName: String): Boolean = getFileExtension(fileName) in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

    /**
     * Categorizes a file based on its extension.
     */
    fun getCategory(fileName: String): String {
        val ext = getFileExtension(fileName)
        return when (ext) {
            "pjm" -> "pjm"
            in IMAGE_EXT -> "images"
            in VIDEO_EXT -> "videos"
            in AUDIO_EXT -> "audios"
            else -> "others"
        }
    }

    /**
     * Formats byte size into a human-readable string (GB, MB, KB, B).
     */
    fun formatFileSize(bytes: Long): String =
        when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(Locale.US, bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.2f MB".format(Locale.US, bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.2f KB".format(Locale.US, bytes / 1_000.0)
            else -> "$bytes B"
        }

    /**
     * Formats a timestamp into a standard date/time string.
     */
    fun formatFileTime(timestamp: Long): String = cachedDateFormat.get()?.format(Date(timestamp)) ?: ""

    /**
     * Triggers a system intent to open a file with an external application.
     */
    fun openFileWithSystemApp(
        context: Context,
        file: File,
    ) {
        if (!file.exists()) {
            Toast.makeText(context, "File no longer exists", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val extension = getFileExtension(file.name)
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)

            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                    if (extension == "apk") {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                    } else {
                        setDataAndType(uri, mimeType)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            context.startActivity(intent)
        } catch (e: Exception) {
            PjmLogger.e(TAG, "Failed to open file: ${file.absolutePath}", e)
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Creates a MediaStore delete request for the given Uris (Android 11+).
     * Includes intelligent URI conversion for cross-provider compatibility.
     */
    fun createDeleteRequest(
        context: Context,
        uris: List<Uri>,
    ): IntentSender? {
        if (uris.isEmpty()) return null

        val mediaStoreUris = mutableListOf<Uri>()
        uris.forEach { uri ->
            if (uri.scheme != "content") return@forEach

            if (uri.authority == MediaStore.AUTHORITY || uri.authority?.contains("media") == true) {
                mediaStoreUris.add(uri)
            } else {
                // 核心修复：尝试从非 MediaStore URI 反查媒体库索引
                try {
                    val filePath = getPathFromUri(context, uri)
                    if (filePath != null) {
                        val mediaUri = getMediaUriFromPath(context, filePath)
                        if (mediaUri != null) {
                            PjmLogger.i(TAG, "Resolved MediaStore URI for non-media provider: $mediaUri")
                            mediaStoreUris.add(mediaUri)
                        }
                    }
                } catch (e: Exception) {
                    PjmLogger.w(TAG, "Failed to resolve MediaStore URI for $uri", e)
                }
            }
        }

        if (mediaStoreUris.isEmpty()) {
            PjmLogger.w(TAG, "No compatible MediaStore URIs found for auto-deletion.")
            return null
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // 使用去重后的 URI 列表
                MediaStore.createDeleteRequest(context.contentResolver, mediaStoreUris.distinct()).intentSender
            } catch (e: Exception) {
                PjmLogger.e(TAG, "System MediaStore delete request failed", e)
                null
            }
        } else {
            null
        }
    }

    private fun getPathFromUri(
        context: Context,
        uri: Uri,
    ): String? {
        if (uri.scheme == "file") return uri.path
        return try {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getMediaUriFromPath(
        context: Context,
        path: String,
    ): Uri? {
        val file = File(path)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DATA} = ?"
        val args = arrayOf(file.absolutePath)

        // 分别尝试图片、视频和音频集合，因为 "external" 集合在某些版本上查询 _data 受限
        val collections =
            listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Files.getContentUri("external"),
            )

        for (collection in collections) {
            try {
                context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        return Uri.withAppendedPath(collection, id.toString())
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Exports a file to a public directory (Pictures/Movies) via MediaStore.
     */
    fun exportToPublicDirectory(
        context: Context,
        file: File,
        fileName: String,
    ): Boolean {
        if (!file.exists()) return false
        val extension = getFileExtension(fileName)
        val isImage = isImageFile(fileName)
        val isVideo = isVideoFile(fileName)
        if (!isImage && !isVideo) return false

        val contentValues =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relativePath = if (isImage) Environment.DIRECTORY_PICTURES + "/PJM" else Environment.DIRECTORY_MOVIES + "/PJM"
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

        val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, contentValues) ?: return false

        return try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output, VaultManager.ADAPTIVE_BUFFER_SIZE)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            PjmLogger.i(TAG, "Successfully exported $fileName to public gallery")
            true
        } catch (e: Exception) {
            PjmLogger.e(TAG, "Export failed for $fileName", e)
            context.contentResolver.delete(uri, null, null)
            false
        }
    }
}
