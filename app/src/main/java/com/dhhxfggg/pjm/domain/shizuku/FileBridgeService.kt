package com.dhhxfggg.pjm.domain.shizuku

import android.util.Log
import com.dhhxfggg.pjm.IFileBridgeService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Shizuku UserService 文件桥接实现。
 *
 * 本类在 Shizuku 启动的独立进程（shell UID 2000 / root UID 0）中运行，
 * 因此其中所有 java.io.File 操作都拥有 ADB shell 权限，可自由访问
 * /storage/emulated/0/Android/data/<pkg> 等受保护目录 —— 这是普通应用进程做不到的。
 *
 * 注意：UserService 进程不是普通应用进程，ContentResolver / registerReceiver
 * 等 Context 能力不可用，这里只做纯文件 I/O。
 */
class FileBridgeService : IFileBridgeService.Stub() {
    companion object {
        private const val TAG = "FileBridgeService"

        // Shizuku 官方保留的销毁事务码（AIDL 中 transaction id 16777114 + 1）
        private const val DESTROY_TRANSACTION = android.os.IBinder.LAST_CALL_TRANSACTION + 1
    }

    override fun listFiles(path: String): Array<String> {
        return try {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) return emptyArray()
            dir
                .listFiles()
                ?.mapNotNull { f ->
                    try {
                        val type = if (f.isDirectory) "D" else "F"
                        "$type|${f.name}|${f.length()}"
                    } catch (_: Exception) {
                        null
                    }
                }?.toTypedArray() ?: emptyArray()
        } catch (e: Exception) {
            Log.e(TAG, "listFiles failed: $path", e)
            emptyArray()
        }
    }

    override fun copyFile(
        srcPath: String,
        destPath: String,
    ): Long {
        var copied = 0L
        try {
            val src = File(srcPath)
            val dest = File(destPath)
            if (!src.exists() || !src.isFile) return -1
            dest.parentFile?.mkdirs()
            FileInputStream(src).use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(1024 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        copied += n
                    }
                    output.fd.sync()
                }
            }
            return copied
        } catch (e: Exception) {
            Log.e(TAG, "copyFile failed: $srcPath -> $destPath", e)
            // 清理半成品
            try {
                File(destPath).delete()
            } catch (_: Exception) {
            }
            return -1
        }
    }

    override fun deletePath(path: String): Boolean {
        return try {
            val f = File(path)
            if (!f.exists()) return true
            if (f.isDirectory) {
                f.deleteRecursively()
            } else {
                f.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "deletePath failed: $path", e)
            false
        }
    }

    override fun exists(path: String): Boolean =
        try {
            File(path).exists()
        } catch (_: Exception) {
            false
        }

    override fun readTextFile(path: String): String? {
        return try {
            val f = File(path)
            if (!f.exists() || !f.isFile) return null
            if (f.length() > 4 * 1024 * 1024) return null // 仅限小文件
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "readTextFile failed: $path", e)
            null
        }
    }

    override fun destroy() {
        Log.i(TAG, "destroy called, exiting process")
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
