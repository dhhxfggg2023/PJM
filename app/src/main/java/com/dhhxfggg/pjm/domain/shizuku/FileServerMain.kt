package com.dhhxfggg.pjm.domain.shizuku

import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    /** 客户端写请求用的临时文件前缀（与 EmbeddedPrivilegedIo.TMP_PREFIX 保持一致） */
    private const val TMP_PREFIX = "tmp_"

    /** 轮询间隔（毫秒） */
    private const val POLL_INTERVAL = 100L

    /**
     * `walk` 单次响应允许返回的最大文件数。
     *
     * 超过就返回 `ERR` 而不是**静默截断** —— 截断会让调用方拿到一份"看起来完整、
     * 实际缺文件"的列表，那种错误比慢得多更糟。调用方收到 ERR 会退回逐目录遍历。
     */
    private const val WALK_MAX_ENTRIES = 20000

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

        // 启动时清理残留请求/响应/临时文件。
        // 核心修复：只删本协议自己的文件。旧实现是 `listFiles()?.forEach { it.delete() }`，
        // 会把该目录下的**所有**文件删光 —— 一旦传错目录就是一次数据破坏。
        ioDir
            .listFiles { f ->
                f.isFile &&
                    (
                        f.name.startsWith(REQ_PREFIX) ||
                            f.name.startsWith(RESP_PREFIX) ||
                            f.name.startsWith(TMP_PREFIX)
                    )
            }?.forEach { it.delete() }

        // 请求去重：保证同一个请求只会被处理一次（见 RequestDispatcher 说明）
        val dispatcher = RequestDispatcher()

        while (true) {
            try {
                val reqFiles =
                    ioDir
                        .listFiles { f -> f.isFile && isRequestFileName(f.name) }
                        ?.sortedBy { it.name }
                reqFiles?.forEach { reqFile ->
                    // 去重：已在处理中的请求直接跳过。
                    // 大文件复制耗时超过一个轮询周期时，旧实现会为同一个请求再起一个线程，
                    // 两个线程同时写同一目标文件 → 文件被截断为 0 → 客户端报「复制失败」。
                    // 详见 [RequestDispatcher]。
                    if (!dispatcher.tryAcquire(reqFile.name)) return@forEach
                    // 核心修复：每请求一个线程处理（并发），
                    // 避免删除大目录（10G+）的 deleteRecursively 阻塞整个服务，导致其他命令全部超时。
                    Thread {
                        try {
                            handleRequest(reqFile, ioDir)
                        } catch (e: Exception) {
                            println("PJM privileged server: handle error: ${e.message}")
                            System.out.flush()
                        } finally {
                            // 顺序要紧：先删请求文件，再释放认领。
                            // 反过来的话，删除与释放之间的一轮轮询会重新认领同一个请求。
                            reqFile.delete()
                            dispatcher.release(reqFile.name)
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

    /**
     * 递归遍历目录树，返回（每项形如 `F<NUL>绝对路径<NUL>字节数`）以及是否因超限被截断。
     *
     * 抽成独立函数是为了可测：这段逻辑跑在 shell 进程里，真机上很难触发边界情况。
     *
     * ## 为什么字段分隔符用 NUL 而不是 `|`
     * `|` 是**合法文件名字符**。用 `|` 分隔时，路径里一旦出现 `|` 就会被切错，
     * 而且不会报错 —— 只会静默给出错误的路径和 0 大小。NUL（U+0000）是 POSIX
     * 文件名中**唯一保证不可能出现**的字节，因此不会与内容冲突。
     * 项与项之间用换行分隔。
     *
     * 用显式栈而非递归 —— B 站缓存目录层级深、目录多，递归有爆栈风险。
     *
     * @return `first` 为条目列表；`second` 为 true 表示达到 [WALK_MAX_ENTRIES] 被截断，
     *   调用方此时**必须**报错而不是使用这份不完整的列表。
     */
    internal fun walkTree(
        root: File,
        maxDepth: Int,
    ): Pair<List<String>, Boolean> {
        val out = ArrayList<String>(1024)
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            if (depth > maxDepth) continue
            val children = dir.listFiles() ?: continue
            for (c in children) {
                try {
                    if (c.isDirectory) {
                        stack.addLast(c to depth + 1)
                    } else {
                        if (out.size >= WALK_MAX_ENTRIES) return out to true
                        out.add("F\u0000${c.absolutePath}\u0000${c.length()}")
                    }
                } catch (_: Exception) {
                    // 单个条目读不到（权限/竞态）不应中断整棵树
                }
            }
        }
        return out to false
    }

    /**
     * 请求分发器：保证**同一个请求只会被处理一次**。
     *
     * ## 为什么必须有它（一个真实 bug 的修复）
     * 主循环每 100ms 重新列出请求目录。旧实现只靠「处理完就删掉请求文件」来去重，
     * 但**大文件复制要花很久**，处理线程删掉文件之前，下一轮会再次看到同一个请求，
     * 于是**又起一个线程处理它**。两个线程同时写同一个目标文件：
     *
     *  - 第二次 `outputStream()` 会把目标文件**截断为 0**；
     *  - 先完成的线程执行 `destFile.length()`，于是响应变成 `OK\t0`；
     *  - 客户端判定「复制了 0 字节」→ 当作失败 → 报
     *    「复制视频流失败（特权服务未就绪或超时）」→ **视频永远无法导入**。
     *
     * 文件越大复制越久、被重复处理的窗口越大，所以 4K / 1080P60 这类
     * 大码率视频最容易失败 —— 与线上观察完全一致。
     *
     * 更危险的是：两个线程交错写入，最终长度可能**看起来正确**，
     * 但内容已经损坏 —— 那会产出「导入成功却打不开」的坏文件。
     *
     * 抽成独立类是为了可测：并发时序在真机上极难稳定复现。
     */
    internal class RequestDispatcher {
        private val inFlight = ConcurrentHashMap.newKeySet<String>()

        /**
         * 尝试认领一个请求。返回 false 表示**已在处理中**，本次必须跳过。
         * 原子操作（基于 Set.add），因此并发轮询下也只会有一个成功。
         */
        fun tryAcquire(name: String): Boolean = inFlight.add(name)

        /** 处理结束（请求文件已删除后）调用，允许该名字将来被重新认领。 */
        fun release(name: String) {
            inFlight.remove(name)
        }

        /** 当前在处理的请求数（诊断用） */
        val inFlightCount: Int get() = inFlight.size
    }

    /**
     * 判断文件名是否是一个**待处理的请求**。
     *
     * ## 为什么必须同时要求 `.txt` 结尾（这是一个真实 bug 的修复）
     * 客户端写请求是「先写临时文件、再 rename」以保证原子性，临时文件叫
     * `req_<seq>.tmp`。旧实现只判断 `startsWith(REQ_PREFIX)`，于是：
     *
     *  1. 服务端把**尚未写完的临时文件**当成请求处理了；
     *  2. 处理完照例删除请求文件 —— 把客户端的临时文件删掉了；
     *  3. 客户端的 `renameTo` 因此失败，退回非原子写入；
     *  4. 而服务端早已把响应写到了 `resp_<seq>.tmp.txt`（**没有任何人读这个文件**）；
     *  5. 客户端等满 15 秒超时 → 文件操作**静默失败**。
     *
     * 只认 `.txt` 结尾即可根除：临时文件永远匹配不上，恰好写完的请求才被处理。
     */
    internal fun isRequestFileName(name: String): Boolean = name.startsWith(REQ_PREFIX) && name.endsWith(".txt")

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
                    "walk" -> {
                        val rootPath = parts.getOrNull(1) ?: ""
                        val maxDepth = parts.getOrNull(2)?.toIntOrNull() ?: 8
                        val root = File(rootPath)
                        if (!root.exists() || !root.isDirectory) {
                            "OK\t"
                        } else {
                            val (items, truncated) = walkTree(root, maxDepth)
                            if (truncated) {
                                "ERR\twalk too large (>$WALK_MAX_ENTRIES)"
                            } else {
                                "OK\t${items.joinToString("\n")}"
                            }
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
