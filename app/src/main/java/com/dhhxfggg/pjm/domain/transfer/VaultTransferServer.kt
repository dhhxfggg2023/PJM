package com.dhhxfggg.pjm.domain.transfer

import com.dhhxfggg.pjm.data.model.FileEntity
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内置的「传到电脑」服务：手机自己当服务器，电脑用浏览器就能把库拉走。
 *
 * 不依赖任何第三方 Web 框架，只用 JDK 的 ServerSocket：
 * · 支持 Range（bytes=a-b）→ 断点续传、下载器多线程分段都能用；
 * · 每个连接一个线程（固定 8 个），多个文件并行下载不会互相卡；
 * · 目录页带分类/时间段筛选，点条目直接下载，文件名用库内显示名。
 *
 * USB 场景下电脑执行一次 `adb forward tcp:8787 tcp:8787`，然后开 http://127.0.0.1:8787/；
 * 同一 WiFi 下直接用手机 IP 访问。
 */
class VaultTransferServer(
    private val vaultRoot: File,
    private val filesProvider: () -> List<FileEntity>,
    private val displayName: (FileEntity) -> String,
    private val preferredPort: Int = 8787,
) {
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(8)
    private val connectionId = AtomicInteger(0)

    @Volatile
    var isRunning: Boolean = false
        private set

    val port: Int
        get() = serverSocket?.localPort ?: preferredPort

    fun start(): Boolean {
        if (isRunning) return true
        val socket =
            runCatching {
                var s: ServerSocket? = null
                for (p in preferredPort..(preferredPort + 8)) {
                    s = runCatching { ServerSocket(p) }.getOrNull()
                    if (s != null) break
                }
                s
            }.getOrNull() ?: return false
        serverSocket = socket
        isRunning = true
        Thread({
            while (isRunning) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(client) } }
            }
        }, "pjm-transfer-accept").start()
        return true
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15000
            val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            // 读掉请求头
            val headers = HashMap<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
            }
            val out = BufferedOutputStream(client.getOutputStream())
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            when {
                path == "/" || path == "/index.html" -> writeIndex(out, query)
                path == "/file" -> writeFile(out, method, query["path"].orEmpty(), headers["range"])
                else -> writeText(out, 404, "text/plain; charset=utf-8", "404")
            }
            out.flush()
        }
    }

    private fun writeIndex(
        out: OutputStream,
        query: Map<String, String>,
    ) {
        val category = query["category"].orEmpty()
        val from = query["from"].orEmpty()
        val to = query["to"].orEmpty()
        val fromMs = parseDate(from)
        val toMs = parseDate(to)
        val all = filesProvider()
        val filtered =
            all.filter { entity ->
                (category.isEmpty() || entity.category == category) &&
                    (fromMs == null || entity.lastModified >= fromMs) &&
                    (toMs == null || entity.lastModified <= toMs)
            }
        val totalBytes = filtered.sumOf { it.size }
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
        body.append("<p class='muted'>共 ").append(filtered.size).append(" 个文件 · ").append(formatSize(totalBytes)).append("</p>")
        body.append("<form method='get' action='/'>")
        body.append("<select name='category'><option value=''>全部分类</option>")
        listOf("images" to "图片", "videos" to "视频", "bili_videos" to "B站视频", "audios" to "音频", "texts" to "文本", "pjm" to "PJM 容器", "others" to "其它").forEach { (key, label) ->
            body.append("<option value='").append(key).append("'"); if (key == category) body.append(" selected"); body.append(">").append(label).append("</option>")
        }
        body.append("</select> ")
        body.append("<input type='date' name='from' value='").append(from).append("'> ")
        body.append("<input type='date' name='to' value='").append(to).append("'> ")
        body.append("<button type='submit'>筛选</button></form><ul>")
        filtered.take(20000).forEach { entity ->
            body.append("<li><a href='/file?path=").append(urlEncode(entity.relativePath)).append("'>")
            body.append(escape(displayName(entity))).append("</a> <span class='muted'>").append(formatSize(entity.size)).append("</span></li>")
        }
        body.append("</ul></body></html>")
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
        var start = 0L
        var end = length - 1
        var partial = false
        if (!range.isNullOrBlank() && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val a = spec.substringBefore('-').trim().toLongOrNull()
            val b = spec.substringAfter('-', "").trim().toLongOrNull()
            if (a != null) start = a
            if (b != null) end = b.coerceAtMost(length - 1)
            partial = true
        }
        val contentLength = (end - start + 1).coerceAtLeast(0L)
        val header = StringBuilder()
        header.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        header.append("Content-Type: application/octet-stream\r\n")
        header.append("Accept-Ranges: bytes\r\n")
        header.append("Content-Length: ").append(contentLength).append("\r\n")
        if (partial) header.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(length).append("\r\n")
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
        }
    }

    private fun writeText(
        out: OutputStream,
        code: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head =
            "HTTP/1.1 " + code + " OK\r\nContent-Type: " + contentType +
                "\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
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

    private fun urlEncode(value: String): String = runCatching { java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20") }.getOrDefault(value)

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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
