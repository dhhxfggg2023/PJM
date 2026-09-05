package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 检查更新器 + 应用内下载安装。
 *
 * 通过 GitHub API 查询 PJM 仓库的最新 Release，与本地安装版本比较。
 * 仓库已公开，匿名 API 即可访问（无需 token）。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val GITHUB_API = "https://api.github.com/repos/dhhxfggg2023/PJM/releases/latest"
    private const val RELEASE_PAGE = "https://github.com/dhhxfggg2023/PJM/releases/latest"
    private const val TIMEOUT_MS = 15000

    /** 下载目录（app cache 下，安装后自动清理） */
    private const val DOWNLOAD_DIR = "pjm_updates"

    /** 检查结果 */
    sealed class CheckResult {
        /** 发现新版本 */
        data class UpdateAvailable(
            val latestVersion: String,   // 远程 tag，如 v1.8.8
            val currentVersion: String,  // 本地版本，如 1.8.7
            val releaseNotes: String,    // 发布说明
            val releaseUrl: String,      // 发布页
            val apkUrl: String,          // APK 下载直链（Release asset）
        ) : CheckResult()

        /** 已是最新 */
        data class UpToDate(val currentVersion: String) : CheckResult()

        /** 检查失败（无网络/仓库不存在等） */
        data class Failed(val message: String) : CheckResult()
    }

    /** 下载结果 */
    sealed class DownloadResult {
        data class Success(val apkFile: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    /**
     * 获取本地安装版本名（如 1.8.7）。
     */
    fun getLocalVersionName(context: Context): String {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pkgInfo.versionName ?: "未知"
        } catch (_: PackageManager.NameNotFoundException) {
            "未知"
        }
    }

    /**
     * 检查是否有新版本（网络操作，需在 IO 线程调用）。
     */
    suspend fun checkForUpdate(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val current = getLocalVersionName(context)
        try {
            val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "PJM-Android")

                if (conn.responseCode != 200) {
                    return@withContext CheckResult.Failed("无法连接更新服务器 (HTTP ${conn.responseCode})")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val latestTag = json.optString("tag_name", "").removePrefix("v")
                val releaseNotes = json.optString("body", "").take(500)
                val htmlUrl = json.optString("html_url", RELEASE_PAGE)

                if (latestTag.isEmpty()) {
                    return@withContext CheckResult.Failed("远程版本信息为空")
                }
                // 版本比较：逐段数字比较（支持 1.8.7 / 1.10.0）
                return@withContext if (isNewer(latestTag, current)) {
                    // 从 assets 中找第一个 .apk 下载直链
                    val apkUrl = findApkUrl(json)
                    if (apkUrl == null) {
                        CheckResult.Failed("远程未找到 APK 安装包")
                    } else {
                        CheckResult.UpdateAvailable(
                            latestVersion = latestTag,
                            currentVersion = current,
                            releaseNotes = releaseNotes,
                            releaseUrl = htmlUrl,
                            apkUrl = apkUrl
                        )
                    }
                } else {
                    CheckResult.UpToDate(current)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            PjmLogger.w(TAG, "检查更新失败: ${e.message}")
            CheckResult.Failed("网络异常，请稍后重试")
        }
    }

    /**
     * 从 Release JSON 的 assets 数组中解析 APK 的 browser_download_url。
     */
    private fun findApkUrl(json: JSONObject): String? {
        return try {
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "").lowercase()
                if (name.endsWith(".apk")) {
                    val url = asset.optString("browser_download_url", "")
                    if (url.isNotEmpty()) return url
                }
            }
            null
        } catch (e: Exception) {
            PjmLogger.w(TAG, "解析 APK 直链失败: ${e.message}")
            null
        }
    }

    /**
     * 在应用内下载 APK（流式写入 cache 目录，支持进度回调）。
     * @param onProgress 0f..1f
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        // 清理旧下载残留
        val dir = File(context.cacheDir, DOWNLOAD_DIR)
        dir.deleteRecursively()
        dir.mkdirs()
        val apkFile = File(dir, "pjm_update.apk")

        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "PJM-Android")
            // GitHub release 下载是 302 到 objects.githubusercontent.com，自动跟随
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code != 200) {
                return@withContext DownloadResult.Error("下载失败 (HTTP $code)")
            }
            val total = conn.contentLengthLong
            var downloaded = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            conn.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (apkFile.length() <= 0) {
                return@withContext DownloadResult.Error("下载内容为空")
            }
            DownloadResult.Success(apkFile)
        } catch (e: Exception) {
            PjmLogger.e(TAG, "APK 下载失败", e)
            apkFile.delete()
            DownloadResult.Error("下载失败：${e.message ?: "网络异常"}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * 通过 FileProvider + 系统安装器拉起 APK 安装。
     * @return true 表示已成功拉起安装界面
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            PjmLogger.e(TAG, "拉起安装失败", e)
            false
        }
    }

    /**
     * 打开系统浏览器跳转到 Release 下载页（浏览器兜底）。
     */
    fun openReleasePage(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            PjmLogger.e(TAG, "无法打开 Release 页面", e)
        }
    }

    /**
     * 逐段数字比较版本号 a 是否比 b 新。
     * 非数字段会被忽略（如 "1.8.7-beta" 按 1.8.7 处理）。
     */
    private fun isNewer(a: String, b: String): Boolean {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(pa.size, pb.size)
        for (i in 0 until maxLen) {
            val na = pa.getOrElse(i) { 0 }
            val nb = pb.getOrElse(i) { 0 }
            if (na != nb) return na > nb
        }
        return false
    }
}
