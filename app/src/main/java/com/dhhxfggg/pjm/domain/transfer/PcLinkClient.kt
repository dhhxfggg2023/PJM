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
            val taskObj = obj.optJSONObject("task")
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

    /** 开始让电脑端把手机文件拉过去（手机端只负责下发任务+看进度） */
    fun startPull(
        category: String,
        fromMs: Long,
        toMs: Long,
    ): Boolean =
        runCatching {
            val body =
                JSONObject()
                    .put("category", category)
                    .put("from", fromMs)
                    .put("to", toMs)
                    .toString()
            post("/api/pc/pull", body).contains("\"ok\":true")
        }.getOrDefault(false)

    fun stopPull(): Boolean = runCatching { post("/api/pc/stop", "{}").contains("\"ok\":true") }.getOrDefault(false)

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
