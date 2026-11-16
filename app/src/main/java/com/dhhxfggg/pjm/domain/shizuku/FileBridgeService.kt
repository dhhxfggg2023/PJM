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
 * 因此其中所有 java.io.File 操作都拥有 ADB shell 权限。
 *
 * 安全边界（核心修复）：旧实现只有 deletePath 做了极弱的校验（绝对路径 + 两级 + 无 ..），
 * listFiles / copyFile / exists / readTextFile 完全没有校验，可读 /data、/system 下任意文件、
 * 把文件写到任意位置，或 deleteRecursively() 删掉整本相册。现在六个方法统一走
 * [PrivilegedPaths]：读写限共享存储，copy 落点限应用 external-files，删除禁止根与一级子目录。
 */
class FileBridgeService : IFileBridgeService.Stub() {
    companion object {
        private const val TAG = "FileBridgeService"
    }

    override fun listFiles(path: String): Array<String> {
        if (!PrivilegedPaths.isSharedStorage(path)) {
            Log.e(TAG, "listFiles 拒绝非共享存储路径: $path")
            return emptyArray()
        }
        return try {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) return emptyArray()
            dir
                .listFiles()
                ?.mapNotNull { f ->
                    try {
                        val type = if (f.isDirectory) "D" else "F"
                        // 核心修复：字段分隔符从 `|` 改为 NUL。`|` 是合法文件名字符，
                        // 名字里带 `|` 时客户端按 `|` split 会静默切错（路径截断 + 大小变 0）。
                        // 客户端见 ShizukuBridge.listFiles 的同名解析。
                        "$type\u0000${f.name}\u0000${f.length()}"
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
        if (!PrivilegedPaths.isSharedStorage(srcPath) || !PrivilegedPaths.isAllowedCopyDestination(destPath)) {
            Log.e(TAG, "copyFile 拒绝非法路径: $srcPath -> $destPath")
            return -1
        }
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
            try {
                File(destPath).delete()
            } catch (_: Exception) {
            }
            return -1
        }
    }

    override fun deletePath(path: String): Boolean {
        // 核心修复：拒绝共享存储根与其一级子目录（DCIM / Download / Pictures ...），
        // 旧实现仅凭「两级路径」判断，/storage/emulated/0/DCIM 会被整体 deleteRecursively()。
        if (!PrivilegedPaths.isSafeDeleteTarget(path)) {
            Log.e(TAG, "deletePath 拒绝可疑路径: $path")
            return false
        }
        return try {
            val f = File(path)
            if (!f.exists()) return true
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        } catch (e: Exception) {
            Log.e(TAG, "deletePath failed: $path", e)
            false
        }
    }

    override fun exists(path: String): Boolean =
        if (!PrivilegedPaths.isSharedStorage(path)) {
            false
        } else {
            try {
                File(path).exists()
            } catch (_: Exception) {
                false
            }
        }

    override fun readTextFile(path: String): String? {
        if (!PrivilegedPaths.isSharedStorage(path)) {
            Log.e(TAG, "readTextFile 拒绝非共享存储路径: $path")
            return null
        }
        return try {
            val f = File(path)
            if (!f.exists() || !f.isFile) return null
            if (f.length() > 4 * 1024 * 1024) return null
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
