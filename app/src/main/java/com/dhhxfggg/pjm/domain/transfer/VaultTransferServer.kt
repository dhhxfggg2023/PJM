package com.dhhxfggg.pjm.domain.transfer

import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.PjmLogger
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内置的「传到电脑」服务：手机自己当服务器，电脑用浏览器就能把库拉走。
 *
 * 不依赖任何第三方 Web 框架，只用 JDK 的 ServerSocket：
 * · 支持 Range（bytes=a-b）→ 断点续传、下载器多线程分段都能用；
 * · 每个连接一个线程（固定 8 个），多个文件并行下载不会互相卡；
 * · 目录页带分类/时间段筛选，点条目直接下载，文件名用库内显示名。
 *
 * 只监听 127.0.0.1，且**只绑 preferredPort（默认 8787）**：电脑端执行一次
 * `adb forward tcp:8787 tcp:8787` 后访问 http://127.0.0.1:8787/。端口被占用时 [start] 直接
 * 返回 false（不再漂移到 8788+，否则 PC 端 forward 到 8787 永远连不上）。
 *
 * 安全边界说明：回环地址**不按 UID 隔离**，同机任意应用都能连 127.0.0.1:8787。
 * 鉴权：数据接口（`/`、`/file`、`/api/…`）要求请求头 `X-PJM-Token` 或查询参数 `?token=` 与
 * [authTokenProvider] 返回的令牌一致，否则 401（响应体带 `upgrade:1`，让旧 PJM-Link 能提示「请更新」）。
 * `/api/hello` 保持开放，只报告端口与是否需要鉴权，便于 PC 端在拿令牌前先探测。
 * 令牌只防「同机其它应用误连/顺手抓一把」：回环地址不按 UID 隔离，能读应用外部私有目录的进程
 * 同样能读到令牌文件，因此本服务仍只适用于「通过 adb 隧道、设备上无可信疑虑」的场景，不是加密通道。
 */
class VaultTransferServer(
    private val vaultRoot: File,
    private val filesProvider: () -> List<FileEntity>,
    private val displayName: (FileEntity) -> String,
    private val preferredPort: Int = 8787,
    /**
     * 访问令牌提供者；返回 null 表示不鉴权（单元测试/旧行为）。
     * 手机端每次开启服务随机生成并写入外部私有目录，PC 通过 adb 读取后随请求带上。
     */
    private val authTokenProvider: () -> String? = { null },
) {
    private var serverSocket: ServerSocket? = null
    private var pool = newPool()
    private val connectionId = AtomicInteger(0)

    private companion object {
        /** 并发下载连接数上限 */
        const val POOL_SIZE = 8

        /** 请求行/请求头单行字符上限：无上限时一行 10MB 的头就能把解析线程撑爆 */
        const val MAX_LINE_CHARS = 8192

        /** 索引页每页条数：旧实现固定 take(20000)，4 万条的库第一页就要渲染 2 万行 */
        const val PAGE_SIZE = 200
    }

    private fun newPool() =
        java.util.concurrent.ThreadPoolExecutor(
            POOL_SIZE,
            POOL_SIZE,
            30L,
            java.util.concurrent.TimeUnit.SECONDS,
            // 核心修复：原 FixedThreadPool 用无界 LinkedBlockingQueue —— 只连不发的客户端
            // 可以无限排队占住 fd，8 个 worker 卡 15s 后服务整体假死。改为有界队列，
            // 队列满时直接拒绝（AbortPolicy 抛异常），由 accept 循环关掉这个连接。
            java.util.concurrent.ArrayBlockingQueue(POOL_SIZE * 2),
            { r -> Thread(r, "pjm-transfer-worker").apply { isDaemon = true } },
        )

    @Volatile
    var isRunning: Boolean = false
        private set

    val port: Int
        get() = serverSocket?.localPort ?: preferredPort

    fun start(): Boolean {
        if (isRunning) return true
        // 停止时线程池已关闭，重新开启要换一个新的
        if (pool.isShutdown) pool = newPool()
        val socket =
            runCatching {
                // 只绑回环：PC 端通过 adb forward 接 127.0.0.1:8787，
                // 同一 Wi-Fi/局域网的任何人都连不上这台手机。
                //
                // 核心修复：**只绑 preferredPort，不再漂移到 8788..8795**。
                // PC 端 forward 固定在 8787，端口漂移后手机页面显示「运行中」，
                // 电脑却永远连不上（adbd 转发到一个没人监听的端口）。
                // 端口被占用时直接返回 false，由 UI 提示「端口被占用」。
                val loopback = java.net.InetAddress.getByName("127.0.0.1")
                ServerSocket(preferredPort, 50, loopback)
            }.getOrNull() ?: return false
        serverSocket = socket
        isRunning = true
        Thread({
            while (isRunning) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                val executor = pool
                if (executor.isShutdown) break
                // soTimeout 必须在入队前设置：排队中的 socket 不会进入 handle，也就设不到超时
                runCatching { client.soTimeout = 15000 }
                val queued =
                    runCatching {
                        executor.execute {
                            runCatching { handle(client) }.onFailure { PjmLogger.w("Transfer", "request failed", it) }
                        }
                    }
                if (queued.isFailure) runCatching { client.close() }
            }
        }, "pjm-transfer-accept").start()
        return true
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        // 不关池会一直挂着 8 个线程：反复开关服务会把线程越堆越多
        runCatching { pool.shutdownNow() }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15000
            val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = readLineLimited(input, MAX_LINE_CHARS) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            // 读掉请求头
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLineLimited(input, MAX_LINE_CHARS) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
            }
            val out = BufferedOutputStream(client.getOutputStream())
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))

            // 数据端点必须带令牌（/api/hello 保持开放，供 PC 探测版本与鉴权能力）。
            // 老版 PJM-Link 不带令牌会收到 401 + upgrade 提示；浏览器访问用 ?token=<令牌>。
            if (isTokenRequired(path) && !isAuthorized(headers, query)) {
                writeUnauthorized(out)
                out.flush()
                return
            }

            when {
                path == "/" || path == "/index.html" -> writeIndex(out, query, query["token"] ?: headers["x-pjm-token"])
                path == "/file" || path == "/api/file" -> writeFile(out, method, query["path"].orEmpty(), headers["range"])
                path == "/api/hello" -> writeHello(out)
                path == "/api/files" -> writeFilesJson(out, query)
                else -> writeText(out, 404, "text/plain; charset=utf-8", "404")
            }
            out.flush()
        }
    }

    /** PC 工具握手：确认对面是 PJM 手机端，并拿到文件总数与实际监听端口 */
    private fun writeHello(out: OutputStream) {
        // port 是新增字段（只加不改）：老客户端忽略未知字段，新客户端可用它校验
        // adb forward 的目标端口是否真的是本服务，避免"页面运行中但连不上"的静默错配。
        // auth=1 告诉新客户端「必须带令牌」；老客户端忽略该字段（随后会收到 401）。
        val authRequired = authTokenProvider() != null
        val json =
            "{\"app\":\"pjm\",\"files\":" + filesProvider().size +
                ",\"port\":" + port + ",\"auth\":" + (if (authRequired) 1 else 0) + "}"
        writeText(out, 200, "application/json; charset=utf-8", json)
    }

    /** 文件清单（PC 工具据此决定拉哪些、跳过哪些）；筛选按入库时间 */
    private fun writeFilesJson(
        out: OutputStream,
        query: Map<String, String>,
    ) {
        val category = query["category"].orEmpty()
        val fromMs = query["from"]?.toLongOrNull() ?: 0L
        val toMs = query["to"]?.toLongOrNull() ?: 0L
        // 可选 limit：PC 端可以分批拉，默认 0 = 不限制（已发布客户端不带这个参数，行为不变）
        val limit = query["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 0
        // 同一实体在一次请求里只算一次显示名（normalizedDisplayName 是字符串处理，4 万条时不值得重复跑）
        val nameCache = HashMap<String, String>()
        val json = java.lang.StringBuilder()
        json.append('[')
        var first = true
        var emitted = 0
        for (entity in filesProvider()) {
            if (limit > 0 && emitted >= limit) break
            val stamp = entity.importedAt.takeIf { it > 0 } ?: entity.lastModified
            val keep =
                (category.isEmpty() || entity.category == category) &&
                    (fromMs <= 0L || stamp >= fromMs) &&
                    (toMs <= 0L || stamp <= toMs)
            if (keep) {
                if (!first) json.append(',')
                first = false
                emitted++
                json.append("{\"p\":\"").append(jsonEscape(entity.relativePath))
                json
                    .append("\",\"n\":\"")
                    .append(jsonEscape(nameCache.getOrPut(entity.relativePath) { displayName(entity) }))
                json.append("\",\"s\":").append(entity.size)
                json.append(",\"m\":").append(entity.lastModified)
                json.append(",\"i\":").append(entity.importedAt)
                json.append(",\"c\":\"").append(jsonEscape(entity.category)).append("\"}")
            }
        }
        json.append(']')
        writeText(out, 200, "application/json; charset=utf-8", json.toString())
    }

    private fun jsonEscape(value: String): String {
        val sb = java.lang.StringBuilder(value.length + 8)
        value.forEach { ch ->
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(' ') else sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun writeIndex(
        out: OutputStream,
        query: Map<String, String>,
        token: String?,
    ) {
        // 令牌透传：浏览器是「按链接跳转」而非按请求头，不往链接里带上令牌，
        // 用户点第一个文件/下一页就会 401。表头/分页/文件链接全都继承当前令牌。
        val tokenQuery = if (token.isNullOrEmpty()) "" else "&token=" + urlEncode(token)
        val tokenInput = if (token.isNullOrEmpty()) "" else "<input type='hidden' name='token' value='" + escape(token) + "'>"
        val category = query["category"].orEmpty()
        val from = query["from"].orEmpty()
        val to = query["to"].orEmpty()
        val fromMs = parseDate(from)
        // 核心修复：to 解析出来是当天 00:00，用 stamp <= toMs 会把「今天」入库的文件全过滤掉
        val toMs = parseDate(to)?.let { it + 86_399_999L }
        val all = filesProvider()
        val filtered =
            all.filter { entity ->
                // 与 /api/files 用同一个时间口径（入库时间），否则页面和 PC 端拿到的集合不一致
                val stamp = entity.importedAt.takeIf { it > 0 } ?: entity.lastModified
                (category.isEmpty() || entity.category == category) &&
                    (fromMs == null || stamp >= fromMs) &&
                    (toMs == null || stamp <= toMs)
            }
        val totalBytes = filtered.sumOf { it.size }
        // 分页：每页 PAGE_SIZE 条，默认第 0 页。旧实现固定 take(20000)，
        // 4 万条的库光第一页就要拼 2 万行 HTML，PC 浏览器打开明显变慢。
        val pageCount = if (filtered.isEmpty()) 1 else (filtered.size + PAGE_SIZE - 1) / PAGE_SIZE
        val page = (query["page"]?.toIntOrNull() ?: 0).coerceIn(0, pageCount - 1)
        val pageItems = filtered.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val body = StringBuilder()
        body.append("<!doctype html><html><head><meta charset='utf-8'>")
        body.append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
        body.append("<title>PJM 传输</title><style>")
        body.append("body{font-family:system-ui;background:#111;color:#eee;margin:0;padding:16px}")
        body.append("a{color:#8ab4f8;text-decoration:none}a:hover{text-decoration:underline}")
        body.append("input,select{background:#222;color:#eee;border:1px solid #444;padding:6px;border-radius:6px}")
        body.append("li{margin:2px 0;font-size:13px}.muted{color:#888;font-size:12px}")
        body.append("</style></head><body>")
        body.append("<h2>PJM 文件传输</h2>")
        body
            .append("<p class='muted'>共 ")
            .append(filtered.size)
            .append(" 个文件 · ")
            .append(formatSize(totalBytes))
            .append("</p>")
        body.append("<form method='get' action='/'>").append(tokenInput)

        body.append("<select name='category'><option value=''>全部分类</option>")
        listOf("images" to "图片", "videos" to "视频", "bili_videos" to "B站视频", "audios" to "音频", "texts" to "文本", "pjm" to "PJM 容器", "others" to "其它").forEach { (key, label) ->
            body.append("<option value='").append(key).append("'")
            if (key == category) body.append(" selected")
            body.append(">").append(label).append("</option>")
        }
        body.append("</select> ")
        // 核心修复：from/to 直接来自 query 参数，原样拼进属性会被注入（反射型 XSS）
        body.append("<input type='date' name='from' value='").append(escape(from)).append("'> ")
        body.append("<input type='date' name='to' value='").append(escape(to)).append("'> ")
        body.append("<button type='submit'>筛选</button></form><ul>")
        // 同一实体在一次请求里只算一次显示名（与 /api/files 同理）
        val nameCache = HashMap<String, String>()
        pageItems.forEach { entity ->
            body
                .append("<li><a href='/file?path=")
                .append(urlEncode(entity.relativePath))
                .append(tokenQuery)
                .append("'>")
            body
                .append(escape(nameCache.getOrPut(entity.relativePath) { displayName(entity) }))
                .append("</a> <span class='muted'>")
                .append(formatSize(entity.size))
                .append("</span></li>")
        }
        // 上一页/下一页：只带筛选参数 + page，不再把整份清单塞进一页
        val listQuery = "category=" + urlEncode(category) + "&from=" + urlEncode(from) + "&to=" + urlEncode(to) + tokenQuery
        body
            .append("</ul><p class='muted'>第 ")
            .append(page + 1)
            .append(" / ")
            .append(pageCount)
            .append(" 页")
        if (page > 0) {
            body
                .append(" · <a href='/?")
                .append(listQuery)
                .append("&page=")
                .append(page - 1)
                .append("'>上一页</a>")
        }
        if (page < pageCount - 1) {
            body
                .append(" · <a href='/?")
                .append(listQuery)
                .append("&page=")
                .append(page + 1)
                .append("'>下一页</a>")
        }
        body.append("</p></body></html>")
        writeText(out, 200, "text/html; charset=utf-8", body.toString())
    }

    private fun writeFile(
        out: OutputStream,
        method: String,
        relativePath: String,
        range: String?,
    ) {
        if (relativePath.isEmpty() || relativePath.contains("..")) {
            writeText(out, 400, "text/plain; charset=utf-8", "bad path")
            return
        }
        val entity = filesProvider().firstOrNull { it.relativePath == relativePath }
        if (entity == null) {
            writeText(out, 404, "text/plain; charset=utf-8", "not found")
            return
        }
        val file = File(vaultRoot, entity.relativePath)
        if (!file.exists() || !file.isFile) {
            writeText(out, 404, "text/plain; charset=utf-8", "missing on disk")
            return
        }
        val length = file.length()
        if (length == 0L) {
            // 核心修复：空文件是合法资产，旧实现统一走 416，PC 端永远下不下来
            writeText(out, 200, "application/octet-stream", "")
            return
        }
        var start = 0L
        var end = length - 1
        var partial = false
        if (!range.isNullOrBlank() && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',').trim()
            val firstPart = spec.substringBefore('-').trim()
            val lastPart = spec.substringAfter('-', "").trim()
            when {
                // bytes=-N：最后 N 个字节（后缀范围以前会被忽略，直接返回整个文件）
                firstPart.isEmpty() && lastPart.isNotEmpty() -> {
                    val suffix = lastPart.toLongOrNull() ?: 0L
                    if (suffix > 0L) {
                        start = (length - suffix).coerceAtLeast(0L)
                        partial = true
                    }
                }
                firstPart.isNotEmpty() -> {
                    val a = firstPart.toLongOrNull()
                    if (a != null) {
                        start = a
                        lastPart.toLongOrNull()?.let { end = it.coerceAtMost(length - 1) }
                        partial = true
                    }
                }
            }
        }
        if (start >= length || (partial && end < start)) {
            // 416：范围不可满足必须明说，否则下载器会拿着 206 + 0 长度一直重试
            writeText(
                out = out,
                code = 416,
                contentType = "text/plain; charset=utf-8",
                body = "range not satisfiable",
                extraHeaders = "Content-Range: bytes */" + length + "\r\n",
            )
            return
        }
        val contentLength = (end - start + 1).coerceAtLeast(0L)
        val header = StringBuilder()
        header.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        header.append("Content-Type: application/octet-stream\r\n")
        header.append("Accept-Ranges: bytes\r\n")
        header.append("Content-Length: ").append(contentLength).append("\r\n")
        if (partial) {
            header
                .append("Content-Range: bytes ")
                .append(start)
                .append('-')
                .append(end)
                .append('/')
                .append(length)
                .append("\r\n")
        }
        header.append("Content-Disposition: attachment; filename*=UTF-8''").append(urlEncode(displayName(entity))).append("\r\n")
        header.append("Connection: close\r\n\r\n")
        out.write(header.toString().toByteArray(Charsets.UTF_8))
        if (method == "HEAD") return
        file.inputStream().use { stream ->
            var skipped = 0L
            while (skipped < start) {
                val s = stream.skip(start - skipped)
                if (s <= 0) break
                skipped += s
            }
            var remaining = contentLength
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            // 核心修复：边读边发必须校验实际发出的字节数。文件在发送过程中被并发删除/改写
            // （入库、清理、改名都可能）时，读循环会提前 break，PC 拿到的是**截断包**。
            // 此时响应头已发出，改不了状态码；至少记日志，绝不静默当成功。
            if (remaining != 0L) {
                PjmLogger.w(
                    "Transfer",
                    "文件在发送途中被改动，已中断: " + entity.relativePath +
                        " 期望 " + contentLength + " 字节，剩余 " + remaining,
                )
            }
        }
    }

    /** 需要令牌的路径：索引页与文件数据；握手/健康检查保持开放 */
    private fun isTokenRequired(path: String): Boolean =
        path == "/" ||
            path == "/index.html" ||
            path == "/file" ||
            path == "/api/file" ||
            path == "/api/files"

    private fun isAuthorized(
        headers: Map<String, String>,
        query: Map<String, String>,
    ): Boolean {
        val expected = authTokenProvider() ?: return true
        val supplied = headers["x-pjm-token"] ?: query["token"] ?: return false
        // 常量时间比较，避免按字节提前返回
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8),
        )
    }

    private fun writeUnauthorized(out: OutputStream) {
        writeText(
            out = out,
            code = 401,
            contentType = "application/json; charset=utf-8",
            body = "{\"error\":\"auth\",\"upgrade\":1,\"hint\":\"请更新 PJM-Link，或带 ?token=<令牌> 访问\"}",
            extraHeaders = "WWW-Authenticate: PJM-Token\r\n",
        )
    }

    private fun writeText(
        out: OutputStream,
        code: Int,
        contentType: String,
        body: String,
        extraHeaders: String = "",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head =
            "HTTP/1.1 " + statusText(code) + "\r\nContent-Type: " + contentType +
                "\r\nContent-Length: " + bytes.size + "\r\n" + extraHeaders +
                "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
    }

    /** 状态码必须写对：以前不管传什么都输出 "200 OK"，PC 端会把 404 当成功 */
    private fun statusText(code: Int): String =
        when (code) {
            200 -> "200 OK"
            206 -> "206 Partial Content"
            400 -> "400 Bad Request"
            404 -> "404 Not Found"
            401 -> "401 Unauthorized"
            416 -> "416 Range Not Satisfiable"
            else -> code.toString()
        }

    /** 带长度上限的 readLine：超长直接返回 null（调用方放弃该请求），避免分配无上限的字符串 */
    private fun readLineLimited(
        reader: java.io.Reader,
        maxChars: Int,
    ): String? {
        val sb = StringBuilder(128)
        while (true) {
            val c = reader.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= maxChars) return null
            sb.append(c.toChar())
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        raw.split('&').forEach { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            if (k.isNotEmpty()) map[urlDecode(k)] = urlDecode(v)
        }
        return map
    }

    private fun urlDecode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun urlEncode(value: String): String =
        runCatching {
            java.net.URLEncoder
                .encode(value, "UTF-8")
                .replace("+", "%20")
        }.getOrDefault(value)

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            // 本页属性用单引号包裹，单引号也必须转义，否则 value='...' 仍可被闭合注入
            .replace("'", "&#39;")

    private fun parseDate(raw: String): Long? {
        if (raw.isBlank()) return null
        return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw)?.time }.getOrNull()
    }

    private fun formatSize(bytes: Long): String =
        when {
            bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
            else -> bytes.toString() + " B"
        }
}
