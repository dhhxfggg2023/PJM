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
 * 目录访问边界（澄清一个常见的误判）：本目录位于 `Android/data/<pkg>/` 之下，
 * 自 Android 4.4（API 19）起该路径就是**应用专属**的 —— 其它应用即使持有
 * WRITE_EXTERNAL_STORAGE 也无法在其中创建/覆盖文件。而本项目 minSdk = 24，
 * 因此「其它应用伪造请求文件/替换 start.sh」这一攻击路径在受支持的版本上并不成立；
 * 固定 token 的实际作用是防止**误触发**，不是防伪。下面仍然做了参数校验与
 * 「只删自己的文件」，作为纵深防御与防手滑。
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
        // 核心修复：校验传入目录。
        // 下面会清理该目录，若参数写错（例如误传 /sdcard 或 /data/local/tmp），
        // 旧实现会把这个 shell 可写目录里的文件全部删掉。这里要求路径必须是
        // 本应用的共享 IO 目录 `<外部私有目录>/files/io`，与 EmbeddedPrivilegedIo
        // 的实际用法（以及 start.sh 传参）完全一致，不影响正常启动。
        val normalized = ioDir.absolutePath.replace('\\', '/').trimEnd('/')
        if (!normalized.endsWith("/files/$IO_DIR_NAME")) {
            println("PJM privileged server: 拒绝启动 —— ioDir 必须是 <外部私有目录>/files/$IO_DIR_NAME，实际为: $normalized")
            System.out.flush()
            return
        }
        println("PJM privileged server starting (uid=${android.os.Process.myUid()}, ioDir=$ioDir)")
        System.out.flush()
        if (!ioDir.exists()) ioDir.mkdirs()

        // 启动时清理残留请求/响应文件。
        // 核心修复：只删本协议自己的文件。旧实现是 `listFiles()?.forEach { it.delete() }`，
        // 会把该目录下的**所有**文件删光 —— 一旦传错目录就是一次数据破坏。
        ioDir
            .listFiles { f -> f.isFile && (f.name.startsWith(REQ_PREFIX) || f.name.startsWith(RESP_PREFIX)) }
            ?.forEach { it.delete() }

        while (true) {
            try {
                val reqFiles =
                    ioDir
                        .listFiles { f -> f.isFile && f.name.startsWith(REQ_PREFIX) }
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
            try {
                Thread.sleep(POLL_INTERVAL)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun handleRequest(
        reqFile: File,
        ioDir: File,
    ) {
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

        val resp =
            try {
                when (cmd) {
                    "list" -> {
                        val path = parts.getOrNull(1) ?: ""
                        val dir = File(path)
                        if (!dir.exists() || !dir.isDirectory) {
                            "OK\t"
                        } else {
                            val items =
                                dir
                                    .listFiles()
                                    ?.mapNotNull { f ->
                                        try {
                                            val type = if (f.isDirectory) "D" else "F"
                                            "$type|${f.name}|${f.length()}"
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }?.joinToString("|||") ?: ""
                            "OK\t$items"
                        }
                    }
                    "copy" -> {
                        val src = parts.getOrNull(1) ?: ""
                        val dest = parts.getOrNull(2) ?: ""
                        val srcFile = File(src)
                        if (!srcFile.exists() || !srcFile.isFile) {
                            "ERR\tsource not found"
                        } else {
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
                        if (!f.exists()) {
                            "OK\ttrue"
                        } else {
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
                        } else {
                            "ERR\tread failed"
                        }
                    }
                    "ping" -> "OK\tpong"
                    else -> "ERR\tunknown cmd: $cmd"
                }
            } catch (e: Exception) {
                "ERR\t${e.message}"
            }

        // 写响应文件（原子：先 .tmp 再 rename）
        val respFile = File(ioDir, "${RESP_PREFIX}$seq.txt")
        val tmpFile = File(ioDir, "${RESP_PREFIX}$seq.tmp")
        tmpFile.writeText(resp + "\n")
        if (tmpFile.renameTo(respFile)) {
            tmpFile.delete()
        } else {
            respFile.writeText(resp + "\n")
        }
    }
}
