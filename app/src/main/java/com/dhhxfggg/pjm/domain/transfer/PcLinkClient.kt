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

    /**
     * 把一个文件直接推给电脑端（手机是发送方）。
     *
     * 进度、速度、剩余全部在手机本地算，不再依赖电脑端回报的任务状态：
     * 传多大、传多快由手机这边的读取速度决定，电脑端只是被动收字节。
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
            // 电脑端按 uuid 记过账：这个文件它已经有了，只是手机这边不知道
            return text.contains("\"skipped\":true")
        } finally {
            conn.disconnect()
        }
    }

    /** 告诉电脑端这次推送结束（用于结束对端的「接收中」状态） */
    fun uploadDone(): Boolean = runCatching { post("/api/pc/uploaddone", "{}").contains("\"ok\":true") }.getOrDefault(false)

    /** 把一个文件下载到 target（先写 .part，再由调用方改名） */
    fun download(
        category: String,
        date: String,
        name: String,
        target: File,
        onBytes: (Long) -> Unit,
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
