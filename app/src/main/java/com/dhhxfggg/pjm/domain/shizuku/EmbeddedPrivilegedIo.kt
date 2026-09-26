package com.dhhxfggg.pjm.domain.shizuku

import android.content.Context
import com.dhhxfggg.pjm.domain.util.PjmLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内置特权服务客户端（文件队列模式）。
 *
 * 与 [FileServerMain]（app_process + adb 以 shell 身份启动）通过
 * 共享目录中的请求/响应文件通信，访问 Android/data。
 *
 * 为何不用 socket：Android 14 SELinux 禁止 untrusted_app 连接 shell 域
 * 监听的 TCP 端口（EPERM）。文件队列双方都可读写，绕开限制。
 */
object EmbeddedPrivilegedIo {
    private const val TAG = "EmbeddedPrivilegedIo"
    private const val AUTH_TOKEN = "pjm_privileged_v1"
    private const val REQ_PREFIX = "req_"
    private const val RESP_PREFIX = "resp_"

    /**
     * 请求临时文件的前缀。**刻意不以 `req_` 开头** —— 服务端按 `req_*.txt` 判定
     * 待处理请求，若临时文件也叫 `req_<seq>.tmp`，会被服务端当成半成品请求处理并删除，
     * 随后的 renameTo 就失败了，客户端只能空等到超时。
     */
    private const val TMP_PREFIX = "tmp_"

    /** 等待响应超时（毫秒）：普通命令 15s；删除/复制大文件（GB 级）用长超时 180s */
    private const val RESPONSE_TIMEOUT = 15000L
    private const val DELETE_RESPONSE_TIMEOUT = 180000L
    private const val COPY_RESPONSE_TIMEOUT = 180000L
    private const val POLL_INTERVAL = 100L

    /** 简易文件信息 */
    data class FileInfo(
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
    )

    /**
     * 获取共享 IO 目录（app 外部私有目录下，shell 也可读写）。
     */
    private fun ioDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: return File(context.filesDir, "io_fallback")
        return File(base, FileServerMain.IO_DIR_NAME).apply { mkdirs() }
    }

    /**
     * 获取服务端启动脚本所在目录（用于 start.sh 定位 IO 目录）。
     */
    fun getIoDirAbsolutePath(context: Context): String = ioDir(context).absolutePath

    /**
     * 执行一条命令，返回响应行。
     * @param timeoutMs 自定义超时（删除大目录时用长超时）
     */
    private suspend fun exec(
        context: Context,
        vararg args: String,
        timeoutMs: Long = RESPONSE_TIMEOUT,
    ): String? =
        withContext(Dispatchers.IO) {
            val dir = ioDir(context)
            val seq = "${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}"
            val reqFile = File(dir, "${REQ_PREFIX}$seq.txt")
            val respFile = File(dir, "${RESP_PREFIX}$seq.txt")

            try {
                // 写请求（原子）
                // 临时文件名**刻意不以 `req_` 开头**：服务端按 `req_*.txt` 判定请求，
                // 若临时文件也叫 `req_xxx.tmp`，服务端会把半成品当请求处理并把它删掉，
                // 导致随后的 renameTo 失败、客户端空等 15 秒超时。（服务端也已收紧判定）
                val tmpReq = File(dir, "$TMP_PREFIX$seq")
                val body = AUTH_TOKEN + "\n" + args.joinToString("\t") + "\n"
                tmpReq.writeText(body)
                if (tmpReq.renameTo(reqFile)) tmpReq.delete() else reqFile.writeText(body)

                // 等待响应
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (respFile.exists()) {
                        val resp = respFile.readText().trim()
                        respFile.delete()
                        return@withContext resp
                    }
                    Thread.sleep(POLL_INTERVAL)
                }
                null // 超时
            } catch (e: Exception) {
                PjmLogger.e(TAG, "exec failed: ${args.firstOrNull()}", e)
                try {
                    reqFile.delete()
                    respFile.delete()
                } catch (_: Exception) {
                }
                null
            }
        }

    /**
     * 检测内置特权服务是否在线。
     */
    suspend fun isAvailable(context: Context): Boolean = exec(context, "ping") == "OK\tpong"

    suspend fun listFiles(
        context: Context,
        path: String,
    ): List<FileInfo>? {
        val resp = exec(context, "list", path) ?: return null
        if (!resp.startsWith("OK\t")) return null
        val data = resp.removePrefix("OK\t")
        if (data.isEmpty()) return emptyList()
        return data.split("|||").mapNotNull { line ->
            try {
                val parts = line.split("|")
                if (parts.size >= 2) {
                    FileInfo(parts[1], parts.getOrNull(2)?.toLongOrNull() ?: 0L, parts[0] == "D")
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun copyFile(
        context: Context,
        srcPath: String,
        destPath: String,
    ): Long {
        // 核心修复：大视频 m4s 复制可能超过 15s，用长超时避免超时返回 -1，
        // 否则上层 copyToCache 返回 null → merge 回退 file:// → Android 15 EACCES。
        val resp = exec(context, "copy", srcPath, destPath, timeoutMs = COPY_RESPONSE_TIMEOUT) ?: return -1
        return if (resp.startsWith("OK\t")) resp.removePrefix("OK\t").toLongOrNull() ?: -1 else -1
    }

    suspend fun deletePath(
        context: Context,
        path: String,
    ): Boolean {
        // 核心修复：删除可能是大目录（10G+），用长超时避免 15s 超时静默失败
        val resp = exec(context, "delete", path, timeoutMs = DELETE_RESPONSE_TIMEOUT) ?: return false
        return resp == "OK\ttrue"
    }

    suspend fun exists(
        context: Context,
        path: String,
    ): Boolean {
        val resp = exec(context, "exists", path) ?: return false
        return resp == "OK\ttrue"
    }

    suspend fun readTextFile(
        context: Context,
        path: String,
    ): String? {
        val resp = exec(context, "read", path) ?: return null
        return if (resp.startsWith("OK\t")) resp.removePrefix("OK\t") else null
    }

    /**
     * 递归收集目录下所有文件，返回 **路径 → 字节数**。
     *
     * ## 为什么返回 size（重要）
     * 旧实现返回 `List<String>`，把已经从服务端拿到的 size **丢掉了**。
     * 调用方（BiliBridge 要按体积挑出最大的视频/音频 m4s）只能**逐个文件**
     * 再调一次 `listFiles` 去查体积 —— 那是一次完整的 IPC，而每次 IPC 的下限是
     * [POLL_INTERVAL]（100ms）。实测 903 个文件的目录因此多花了 258 次往返。
     *
     * 手里已有的信息就不要再跨进程问第二遍。
     *
     * ## 实现
     * 优先走服务端 `walk` 指令 —— **整棵树一次 IPC**。如果树大到一次响应装不下
     * （服务端返回 ERR，见 `WALK_MAX_ENTRIES`），退回逐目录遍历；回退路径同样保留 size。
     */
    suspend fun walkFiles(
        context: Context,
        path: String,
        maxDepth: Int = 8,
    ): Map<String, Long> {
        val batch = walkOnce(context, path, maxDepth)
        if (batch != null) return batch
        return walkPerDirectory(context, path, maxDepth)
    }

    /** 走 `walk` 指令一次拿全树；服务端不支持或树太大时返回 null（交由回退处理） */
    private suspend fun walkOnce(
        context: Context,
        path: String,
        maxDepth: Int,
    ): Map<String, Long>? {
        val resp = exec(context, "walk", path, maxDepth.toString()) ?: return null
        if (!resp.startsWith("OK\t")) {
            PjmLogger.w(TAG, "walk 未成功，退回逐目录遍历: ${resp.take(120)}")
            return null
        }
        val payload = resp.removePrefix("OK\t")
        return parseWalkResponse(payload)
    }

    /**
     * 解析 `walk` 响应体：每行一项 `F<NUL>绝对路径<NUL>字节数`。
     *
     * 抽出来是为了可测 —— 分隔符嵌套是最容易写错且真机上不易发现的地方。
     *
     * ## 为什么用 NUL 而不是 `|`
     * `|` 是合法文件名字符，路径里出现它就会被切错且**不会报错**（只会静默给出
     * 错误路径 + 0 大小）。NUL 是 POSIX 文件名中唯一不可能出现的字节。
     *
     * 容错策略：**跳过**格式不符的项，而不是整体失败。一项坏掉不应让整次扫描落空。
     */
    internal fun parseWalkResponse(payload: String): Map<String, Long> {
        if (payload.isEmpty()) return emptyMap()
        val result = HashMap<String, Long>(payload.length / 48 + 16)
        payload.lineSequence().forEach { line ->
            if (line.isEmpty()) return@forEach
            // 需要 F / 路径 / 大小 三段；目录项（D）在 walk 里不会出现，出现也忽略
            val p = line.split('\u0000')
            if (p.size >= 3 && p[0] == "F") {
                result[p[1]] = p[2].toLongOrNull() ?: 0L
            }
        }
        return result
    }

    /** 回退路径：逐目录遍历（每个目录一次 IPC）。同样返回 size，不再让调用方重复查询。 */
    private suspend fun walkPerDirectory(
        context: Context,
        path: String,
        maxDepth: Int,
    ): Map<String, Long> {
        val results = LinkedHashMap<String, Long>()

        suspend fun walk(
            p: String,
            depth: Int,
        ) {
            if (depth > maxDepth) return
            val entries = listFiles(context, p) ?: return
            for (e in entries) {
                val full = "$p/${e.name}"
                if (e.isDirectory) {
                    walk(full, depth + 1)
                } else {
                    results[full] = e.size
                }
            }
        }
        walk(path, 0)
        return results
    }

    /**
     * 将特权文件复制到双方共享目录（外部私有目录，app 与 shell 均可读写）。
     * 注意：不能用 cacheDir（/data/user/0/... 私有目录），shell 进程无写权限。
     * @return 复制后的本地文件，失败返回 null
     */
    suspend fun copyToShared(
        context: Context,
        srcPath: String,
    ): File? {
        val externalDir = context.getExternalFilesDir(null) ?: return null
        val tmpDir = File(externalDir, "tmp").apply { mkdirs() }
        val srcName = srcPath.substringAfterLast('/')
        val dest = File(tmpDir, "embedded_${System.nanoTime()}_$srcName")
        val copied = copyFile(context, srcPath, dest.absolutePath)
        return if (copied > 0 && dest.exists()) {
            dest
        } else {
            dest.delete()
            null
        }
    }

    /**
     * 获取设备序列号（用于 adb -s 指定设备，避免开着模拟器时命令发错设备）。
     * 反射读取 Build.SERIAL；Android 10+ 可能返回 UNKNOWN，届时返回 null。
     */
    @Suppress("DEPRECATION")
    fun getDeviceSerial(): String? =
        try {
            val field = android.os.Build::class.java.getField("SERIAL")
            field.isAccessible = true
            val serial = field.get(null) as? String
            if (!serial.isNullOrBlank() && !serial.equals("unknown", true)) serial else null
        } catch (_: Exception) {
            null
        }

    /**
     * 把 assets 中的 start.sh 复制到应用外部私有目录，供电脑 adb 以 shell 身份执行。
     * 这是 PJM 内置特权服务（FileServerMain）的启动脚本，不依赖 Shizuku。
     * @return 可执行脚本文件；失败返回 null
     */
    fun prepareStartScript(context: Context): File? {
        return try {
            val externalDir = context.getExternalFilesDir(null) ?: return null
            val target = File(externalDir, "start.sh")
            context.assets.open("start.sh").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true)
            target
        } catch (e: Exception) {
            PjmLogger.e(TAG, "prepareStartScript failed", e)
            null
        }
    }

    /**
     * 生成通过电脑 adb 启动 PJM 内置特权服务的命令。
     * 优先带设备序列号（adb -s <serial>），避免开着模拟器时命令发错设备。
     */
    fun getStartCommand(context: Context): String {
        val externalDir = context.getExternalFilesDir(null)
        val scriptPath =
            externalDir?.let { "/sdcard/Android/data/${context.packageName}/files/start.sh" }
                ?: "/sdcard/Android/data/${context.packageName}/start.sh"
        val serial = getDeviceSerial()
        return if (serial != null) {
            "adb -s $serial shell sh $scriptPath"
        } else {
            "adb shell sh $scriptPath"
        }
    }
}
