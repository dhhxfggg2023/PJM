package com.dhhxfggg.pjm.domain.transfer

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

data class PcFolder(
    val category: String,
    val date: String,
    val count: Int,
    val size: Long,
) {
    val key: String get() = "$category|$date"
}

data class PcState(
    val root: String,
    val fs: String,
    val free: Long,
    val total: Long,
    val writable: Boolean,
    val folders: List<PcFolder>,
    val task: PcTask?,
)

data class PcTask(
    val active: Boolean,
    val done: Int,
    val total: Int,
    val bytes: Long,
    val speed: Long,
    val skipped: Int,
    val failed: Int,
    val error: String?,
    /** 本次传输的总平均速度 = 累计字节 / 累计耗时；与瞬时 speed 并列显示 */
    val avgSpeed: Long = 0L,
)

data class PcFile(
    val name: String,
    val size: Long,
    val date: String,
)

/**
 * 电脑端 PJM-Link 工具（127.0.0.1:8788，经 adb reverse 打通）的客户端。
 * 手机是驱动方：状态、文件夹清单、逐个文件下载都由这里发起。
 */
class PcLinkClient(
    private val base: String = "http://127.0.0.1:8788",
) {
    // 快速上传通道：PC 端在 8789 开了裸 TCP 接收器，专门服务大量小文件推送。
    // 实测原 HttpListener(8788) 每请求约 93ms、整体约 10 MB/s；裸 TCP 约 25ms、约 39 MB/s。
    // 全部走 adb reverse 隧道，不消耗手机流量。快速通道不可用时自动回退到 8788。
    private val fastBase = base.replace(":8788", ":8789")
    private val fastHost: String get() = java.net.URI(fastBase).host
    private val fastPort: Int get() =
        java.net
            .URI(fastBase)
            .port
            .takeIf { it > 0 } ?: 8789

    fun state(): PcState? =
        runCatching {
            val obj = JSONObject(get("/api/pc/state"))
            val dirs = obj.optJSONArray("folders")
            val folders = ArrayList<PcFolder>()
            if (dirs != null) {
                for (i in 0 until dirs.length()) {
                    val f = dirs.getJSONObject(i)
                    folders.add(PcFolder(f.optString("cat"), f.optString("date"), f.optInt("count"), f.optLong("size")))
                }
            }
            val disk = obj.optJSONObject("disk")
            // 核心修复：PC 端 PJM-Link 在 /api/pc/state 里把拉取进度放在 `push` 键下，
            // 手机端只读 `task` —— 于是「电脑端拉取」的进度永远是 null、界面永远不显示。
            // 两个键都认（新 PC 端用 push，老响应用 task），方法签名与返回类型不变。
            val taskObj = obj.optJSONObject("task") ?: obj.optJSONObject("push")
            val task =
                taskObj?.let {
                    PcTask(
                        active = it.optBoolean("active"),
                        done = it.optInt("done"),
                        total = it.optInt("total"),
                        bytes = it.optLong("bytes"),
                        speed = it.optLong("speedBps"),
                        skipped = it.optInt("skipped"),
                        failed = it.optInt("failed"),
                        error = it.optString("error").takeIf { e -> e.isNotBlank() && e != "null" },
                    )
                }
            PcState(
                root = obj.optString("root"),
                fs = disk?.optString("fs").orEmpty(),
                free = disk?.optLong("free") ?: 0L,
                total = disk?.optLong("total") ?: 0L,
                writable = disk?.optBoolean("writable") ?: false,
                folders = folders,
                task = task,
            )
        }.getOrNull()

    fun files(
        category: String,
        date: String,
    ): List<PcFile> =
        runCatching {
            val arr = org.json.JSONArray(get("/api/pc/files?cat=" + enc(category) + "&date=" + enc(date)))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                PcFile(o.optString("name"), o.optLong("size"), date)
            }
        }.getOrDefault(emptyList())

    /**
     * 开始让电脑端把手机文件拉过去（手机端只负责下发任务+看进度）。
     *
     * [dates] 为空 = 全部；否则只传这些日期（yyyy-MM-dd）的文件，支持任意多选。
     */
    fun startPull(
        category: String,
        dates: List<String>,
    ): Boolean =
        runCatching {
            val arr = org.json.JSONArray()
            dates.forEach { arr.put(it) }
            val body =
                JSONObject()
                    .put("category", category)
                    .put("dates", arr)
                    .toString()
            post("/api/pc/pull", body).contains("\"ok\":true")
        }.getOrDefault(false)

    fun stopPull(): Boolean = runCatching { post("/api/pc/stop", "{}").contains("\"ok\":true") }.getOrDefault(false)

    /** 快速通道的一条长连接：多个文件复用同一条 TCP，避免每条建连开销 */
    private class FastConn(
        val socket: java.net.Socket,
        val out: java.io.OutputStream,
        val input: java.io.InputStream,
    ) {
        /**
         * 读一行 ASCII 响应头（每字符对应一字节）。
         *
         * 不能用 BufferedReader.readLine()：响应体是按【字节】声明长度的，
         * 中文文件名的 UTF-8 字节数大于字符数，按字符读会一直等不够而卡到超时。
         */
        fun readAsciiLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\r'.code) {
                    input.read()
                    return sb.toString()
                }
                sb.append(c.toChar())
            }
        }

        /** 按字节精确读满 len 字节；返回实际读到的字节数 */
        fun readFully(
            buffer: ByteArray,
            len: Int,
        ): Int {
            var read = 0
            while (read < len) {
                val n = input.read(buffer, read, len - read)
                if (n <= 0) break
                read += n
            }
            return read
        }
    }

    @Volatile
    private var fastConn: FastConn? = null

    /** 打开快速通道连接；PC 端没有该服务（旧版本）时返回 null，调用方回退到 HTTP */
    private fun openFastConn(): FastConn? =
        runCatching {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(fastHost, fastPort), 3000)
            socket.soTimeout = 60000
            socket.tcpNoDelay = true
            FastConn(
                socket,
                socket.getOutputStream().buffered(256 * 1024),
                socket.getInputStream().buffered(64 * 1024),
            )
        }.getOrNull()

    private fun closeFastConn() {
        runCatching { fastConn?.socket?.close() }
        fastConn = null
    }

    /**
     * 发到电脑：优先走 8789 裸 TCP 快速通道，不可用时回退到 8788 的 HttpListener。
     *
     * 快速通道协议（见 PJM-Link.ps1 的 PjmFastUpload）：定长二进制 body + 文本头，
     * 与 HTTP/1.1 兼容但由 C# 直接解析，绕开 Windows HTTP.sys 的每请求开销。
     * 全程走 adb reverse 隧道，不消耗手机流量。
     */
    fun upload(
        category: String,
        date: String,
        name: String,
        uuid: String,
        file: File,
        index: Int,
        total: Int,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ): Boolean {
        var conn = fastConn ?: openFastConn().also { fastConn = it }
        if (conn != null) {
            try {
                return uploadFast(conn, category, date, name, uuid, file, index, total, isCancelled, onBytes)
            } catch (e: Exception) {
                closeFastConn()
                if (isCancelled()) throw e
                // 旧连接可能已被对端关闭（工具重启）：重开一条再试一次
                conn = openFastConn().also { fastConn = it }
                if (conn != null) {
                    try {
                        return uploadFast(conn, category, date, name, uuid, file, index, total, isCancelled, onBytes)
                    } catch (e2: Exception) {
                        closeFastConn()
                        throw e2
                    }
                }
            }
        }
        // 旧版电脑端没有 8789 服务：回退到原 HTTP 路径，保证兼容
        return uploadHttp(category, date, name, uuid, file, index, total, isCancelled, onBytes)
    }

    /** 在快速通道连接上上传单个文件，返回电脑端是否报告 skipped */
    private fun uploadFast(
        conn: FastConn,
        category: String,
        date: String,
        name: String,
        uuid: String,
        file: File,
        index: Int,
        total: Int,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ): Boolean {
        val length = file.length()

        // 头部值去掉 CR/LF，防止破坏协议；中文按 UTF-8 写入（PC 端同样按 UTF-8 解析）
        fun header(v: String) = v.replace("\r", "").replace("\n", "")
        val head =
            "POST / HTTP/1.1\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: " + length + "\r\n" +
                "X-PJM-Cat: " + header(category) + "\r\n" +
                "X-PJM-Date: " + header(date) + "\r\n" +
                "X-PJM-Name: " + header(name) + "\r\n" +
                "X-PJM-Uuid: " + header(uuid) + "\r\n" +
                "X-PJM-Index: " + index + "\r\n" +
                "X-PJM-Total: " + total + "\r\n\r\n"
        conn.out.write(head.toByteArray(Charsets.UTF_8))
        var sent = 0L
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                if (isCancelled()) error("已取消")
                val n = input.read(buffer)
                if (n <= 0) break
                conn.out.write(buffer, 0, n)
                sent += n
                onBytes(sent)
            }
        }
        if (sent != length) error("读取长度不一致：" + length + " → " + sent)
        conn.out.flush()

        // 读取响应：状态行 + 头 + 定长 body（必须读满，否则下个请求错位）。
        // body 一律按字节读再按 UTF-8 解码 —— Content-Length 是字节数，
        // 中文文件名的响应体字节数大于字符数，按字符读会卡到套接字超时（实测每文件 60 秒）。
        val status = conn.readAsciiLine() ?: error("快速通道无响应")
        if (!status.contains("200")) error("快速通道响应异常：" + status)
        var contentLength = 0
        while (true) {
            val line = conn.readAsciiLine() ?: error("快速通道响应头提前结束")
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toInt()
            }
        }
        val body = ByteArray(contentLength)
        val read = conn.readFully(body, contentLength)
        return String(body, 0, read, Charsets.UTF_8).contains("\"skipped\":true")
    }

    /** 原 HttpListener 上传路径（旧版电脑端兼容 / 快速通道不可用时的回退） */
    private fun uploadHttp(
        category: String,
        date: String,
        name: String,
        uuid: String,
        file: File,
        index: Int,
        total: Int,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ): Boolean {
        val length = file.length()
        val conn =
            (
                URL(
                    base + "/api/pc/upload?cat=" + enc(category) +
                        "&date=" + enc(date) + "&name=" + enc(name) + "&uuid=" + enc(uuid),
                ).openConnection() as HttpURLConnection
            )
        conn.connectTimeout = 8000
        conn.readTimeout = 60000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("X-PJM-Index", index.toString())
        conn.setRequestProperty("X-PJM-Total", total.toString())
        conn.setFixedLengthStreamingMode(length)
        try {
            var sent = 0L
            conn.outputStream.buffered(256 * 1024).use { out ->
                file.inputStream().buffered(256 * 1024).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (isCancelled()) error("已取消")
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        sent += n
                        onBytes(sent)
                    }
                }
            }
            if (sent != length) error("读取长度不一致：" + length + " → " + sent)
            val code = conn.responseCode
            val text =
                (if (code == 200) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
            if (code != 200) error("HTTP " + code + " " + text)
            return text.contains("\"skipped\":true")
        } finally {
            conn.disconnect()
        }
    }

    /** 告诉电脑端这次推送结束（用于结束对端的「接收中」状态） */
    fun uploadDone(): Boolean {
        // 推送结束：关闭快速通道长连接，下次推送重新建连
        closeFastConn()
        return runCatching { post("/api/pc/uploaddone", "{}").contains("\"ok\":true") }.getOrDefault(false)
    }

    /**
     * 把一个文件下载到 target（先写 .part，再由调用方改名）。
     *
     * [isCancelled] 每写一块检查一次：用户点「停止」时能在当前文件的中途退出，
     * 而不是等整个文件传完 —— 大文件（几个 GB）等一整块就直接等于没法停。
     */
    fun download(
        category: String,
        date: String,
        name: String,
        target: File,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val conn =
            (
                URL(base + "/api/pc/file?cat=" + enc(category) + "&date=" + enc(date) + "&name=" + enc(name))
                    .openConnection() as HttpURLConnection
            )
        conn.connectTimeout = 8000
        conn.readTimeout = 30000
        conn.requestMethod = "GET"
        try {
            val code = conn.responseCode
            if (code != 200) error("HTTP " + code)
            val total = conn.contentLengthLong
            var read = 0L
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                target.outputStream().buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onBytes(read)
                        if (isCancelled()) error("已取消")
                    }
                }
            }
            if (total > 0 && read != total) error("文件大小不一致：期望 " + total + "，实际 " + read)
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String): String {
        val conn = (URL(base + path).openConnection() as HttpURLConnection)
        conn.connectTimeout = 1500
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        try {
            if (conn.responseCode != 200) error("HTTP " + conn.responseCode)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun post(
        path: String,
        body: String,
    ): String {
        val conn = (URL(base + path).openConnection() as HttpURLConnection)
        conn.connectTimeout = 2500
        conn.readTimeout = 8000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) error("HTTP " + conn.responseCode)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(value: String): String =
        java.net.URLEncoder
            .encode(value, "UTF-8")
            .replace("+", "%20")

    companion object {
        val DATE_FORMAT: SimpleDateFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
