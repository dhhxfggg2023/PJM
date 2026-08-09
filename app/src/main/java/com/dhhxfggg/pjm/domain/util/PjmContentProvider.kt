package com.dhhxfggg.pjm.domain.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * 【优化项 2】流式 ContentProvider
 * 直接提供私有目录下的文件访问，无需拷贝到缓存目录。
 */
class PjmContentProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY_SUFFIX = ".pjm_provider"
        
        fun getUriForFile(context: android.content.Context, file: File): Uri {
            val authority = "${context.packageName}$AUTHORITY_SUFFIX"
            return Uri.Builder()
                .scheme("content")
                .authority(authority)
                .path(file.absolutePath)
                .build()
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = File(uri.path ?: throw FileNotFoundException())
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? {
        val file = File(uri.path ?: return null)
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = File(uri.path ?: throw FileNotFoundException())
        if (!file.exists()) throw FileNotFoundException(file.absolutePath)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
