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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-performance File Utilities for the PJM project.
 * Provides abstraction for MediaStore operations, URI resolution, and file type classification.
 */
object FileUtils {
    private const val TAG = "FileUtils"
    
    private val ALREADY_COMPRESSED = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif",
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp",
        "mp3", "aac", "ogg", "flac", "m4a", "wav", "opus",
        "zip", "rar", "7z", "pjm", "tar", "gz", "bz2", "xz", "iso",
    )

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "ico", "tiff", "svg")
    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts", "rmvb")
    private val AUDIO_EXT = setOf("mp3", "aac", "ogg", "flac", "m4a", "wav", "opus", "amr", "wma", "mid")

    private val cachedDateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }

    /**
     * Generates a unique fingerprint for a file entity based on its metadata.
     */
    fun getFileFingerprint(entity: FileEntity): String {
        return "${entity.extension}_${entity.size}_${entity.lastModified}"
    }

    /**
     * Retrieves the size of a file from its Uri.
     * Supports both "content://" and "file://" schemes.
     *
     * @return File size in bytes, or 0 if retrieval fails.
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        return try {
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
    }

    /**
     * Resolves the display name of a file from its Uri.
     */
    fun getFileName(context: Context, uri: Uri): String {
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
            result = uri.path?.substringAfterLast('/')
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
    fun isAudioFile(fileName: String): Boolean = getFileExtension(fileName) in AUDIO_EXT
    fun isArchiveFile(fileName: String): Boolean = getFileExtension(fileName) in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

    /**
     * Categorizes a file based on its extension.
     */
    fun getCategory(fileName: String): String {
        val ext = getFileExtension(fileName)
        return when {
            ext == "pjm" -> "pjm"
            ext in IMAGE_EXT -> "images"
            ext in VIDEO_EXT -> "videos"
            ext in AUDIO_EXT -> "audios"
            else -> "others"
        }
    }

    /**
     * Formats byte size into a human-readable string (GB, MB, KB, B).
     */
    fun formatFileSize(bytes: Long): String = when {
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
    fun openFileWithSystemApp(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "File no longer exists", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val extension = getFileExtension(file.name)
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
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
     */
    fun createDeleteRequest(context: Context, uris: List<Uri>): IntentSender? {
        if (uris.isEmpty()) return null
        val mediaStoreUris = uris.filter { uri ->
            (uri.authority == "media" && uri.pathSegments.isNotEmpty() && uri.lastPathSegment?.toLongOrNull() != null)
        }
        if (mediaStoreUris.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                MediaStore.createDeleteRequest(context.contentResolver, mediaStoreUris).intentSender
            } catch (e: Exception) { 
                PjmLogger.e(TAG, "Failed to create delete request", e)
                null 
            }
        } else null
    }

    /**
     * Exports a file to a public directory (Pictures/Movies) via MediaStore.
     */
    fun exportToPublicDirectory(context: Context, file: File, fileName: String): Boolean {
        if (!file.exists()) return false
        val extension = getFileExtension(fileName)
        val isImage = isImageFile(fileName)
        val isVideo = isVideoFile(fileName)
        if (!isImage && !isVideo) return false

        val contentValues = ContentValues().apply {
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
