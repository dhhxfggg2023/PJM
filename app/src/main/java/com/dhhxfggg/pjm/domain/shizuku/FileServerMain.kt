package com.dhhxfggg.pjm.domain.shizuku

import java.io.File

/**
 * PJM 内置特权服务入口（app_process 启动）。
 *
 * 通过 adb 以 shell (UID 2000) 身份运行：
 *   adb shell sh /sdcard/Android/data/com.dhhxfggg.pjm/start.sh
 *
 * 通信方式：文件队列（而非 TCP socket —— Android 14 SELinux 禁止
 * untrusted_app 连接 shell 域监听的 TCP 端口）。
 * - app 写请求文件 req_<seq>.txt 到共享目录
 * - server 轮询处理，写响应文件 resp_<seq>.txt
 * - 共享目录：/sdcard/Android/data/<pkg>/files/io/（app 与 shell 均可读写）
 *
 * 注意：本类仅依赖 java.*，不依赖 Android Context（shell 进程不可用）。
 */
object FileServerMain {

    /** 共享 IO 目录（相对外部 files 目录） */
    const val IO_DIR_NAME = "io"

    /** 简单鉴权令牌 */
    private const val AUTH_TOKEN = "pjm_privileged_v1"

    /** 请求/响应文件名前缀 */
    private const val REQ_PREFIX = "req_"
    private const val RESP_PREFIX = "resp_"

    /** 轮询间隔（毫秒） */
    private const val POLL_INTERVAL = 100L

    @JvmStatic
    fun main(args: Array<String>) {
        val ioDir = File(args.firstOrNull() ?: "")
        if (ioDir.path.isEmpty()) {
            println("PJM privileged server: usage: FileServerMain <io_dir>")
            return
        }
        println("PJM privileged server starting (uid=${android.os.Process.myUid()}, ioDir=$ioDir)")
        System.out.flush()
        if (!ioDir.exists()) ioDir.mkdirs()

        // 启动时清理残留请求/响应文件
        ioDir.listFiles()?.forEach { it.delete() }

        while (true) {
            try {
                val reqFiles = ioDir.listFiles { f -> f.isFile && f.name.startsWith(REQ_PREFIX) }
                    ?.sortedBy { it.name }
                reqFiles?.forEach { reqFile ->
                    // 核心修复：每请求一个线程处理（并发），
                    // 避免删除大目录（10G+）的 deleteRecursively 阻塞整个服务，导致其他命令全部超时。
                    Thread {
                        try {
                            handleRequest(reqFile, ioDir)
                        } catch (e: Exception) {
                            println("PJM privileged server: handle error: ${e.message}")
                            System.out.flush()
                        } finally {
                            reqFile.delete()
                        }
                    }.start()
                }
            } catch (e: Exception) {
                println("PJM privileged server: poll error: ${e.message}")
            }
            try { Thread.sleep(POLL_INTERVAL) } catch (_: InterruptedException) { break }
        }
    }

    private fun handleRequest(reqFile: File, ioDir: File) {
        val lines = reqFile.readLines()
        println("PJM server got req: ${reqFile.name}, lines=${lines.size}, first=${lines.firstOrNull()}")
        System.out.flush()
        if (lines.isEmpty()) return
        // 第一行鉴权
        if (lines[0] != AUTH_TOKEN) {
            println("PJM server: auth failed: [${lines[0]}] vs [$AUTH_TOKEN]")
            System.out.flush()
            return
        }
        // 第二行命令
        if (lines.size < 2) return
        val parts = lines[1].split("\t")
        val cmd = parts.getOrNull(0) ?: return
        val seq = reqFile.name.removePrefix(REQ_PREFIX).removeSuffix(".txt")

        val resp = try {
            when (cmd) {
                "list" -> {
                    val path = parts.getOrNull(1) ?: ""
                    val dir = File(path)
                    if (!dir.exists() || !dir.isDirectory) "OK\t"
                    else {
                        val items = dir.listFiles()?.mapNotNull { f ->
                            try {
                                val type = if (f.isDirectory) "D" else "F"
                                "$type|${f.name}|${f.length()}"
                            } catch (_: Exception) { null }
                        }?.joinToString("|||") ?: ""
                        "OK\t$items"
                    }
                }
                "copy" -> {
                    val src = parts.getOrNull(1) ?: ""
                    val dest = parts.getOrNull(2) ?: ""
                    val srcFile = File(src)
                    if (!srcFile.exists() || !srcFile.isFile) "ERR\tsource not found"
                    else {
                        val destFile = File(dest)
                        destFile.parentFile?.mkdirs()
                        srcFile.inputStream().use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output, 1 shl 20)
                                output.flush()
                                output.fd.sync()
                            }
                        }
                        "OK\t${destFile.length()}"
                    }
                }
                "delete" -> {
                    val path = parts.getOrNull(1) ?: ""
                    val f = File(path)
                    if (!f.exists()) "OK\ttrue"
                    else {
                        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                        "OK\t$ok"
                    }
                }
                "exists" -> {
                    val path = parts.getOrNull(1) ?: ""
                    "OK\t${File(path).exists()}"
                }
                "read" -> {
                    val path = parts.getOrNull(1) ?: ""
                    val f = File(path)
                    if (f.exists() && f.isFile && f.length() < 4L * 1024 * 1024) {
                        "OK\t${f.readText(Charsets.UTF_8)}"
                    } else "ERR\tread failed"
                }
                "ping" -> "OK\tpong"
                else -> "ERR\tunknown cmd: $cmd"
            }
        } catch (e: Exception) {
            "ERR\t${e.message}"
        }

        // 写响应文件（原子：先 .tmp 再 rename）
        val respFile = File(ioDir, "${RESP_PREFIX}${seq}.txt")
        val tmpFile = File(ioDir, "${RESP_PREFIX}${seq}.tmp")
        tmpFile.writeText(resp + "\n")
        if (tmpFile.renameTo(respFile)) {
            tmpFile.delete()
        } else {
            respFile.writeText(resp + "\n")
        }
    }
}
