package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 检查更新器。
 *
 * 通过 GitHub API 查询 PJM 仓库的最新 Release，与本地安装版本比较。
 * 仓库已公开，匿名 API 即可访问（无需 token）。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val GITHUB_API = "https://api.github.com/repos/dhhxfggg2023/PJM/releases/latest"
    private const val RELEASE_PAGE = "https://github.com/dhhxfggg2023/PJM/releases/latest"
    private const val TIMEOUT_MS = 10000

    /** 检查结果 */
    sealed class CheckResult {
        /** 发现新版本 */
        data class UpdateAvailable(
            val latestVersion: String,   // 远程 tag，如 v1.9.0
            val currentVersion: String,  // 本地版本，如 1.8.7
            val releaseNotes: String,    // 发布说明（截断）
            val releaseUrl: String,      // 下载/发布页
        ) : CheckResult()

        /** 已是最新 */
        data class UpToDate(val currentVersion: String) : CheckResult()

        /** 检查失败（无网络/仓库不存在等） */
        data class Failed(val message: String) : CheckResult()
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
                // 版本比较：简单的逐段数字比较（支持 1.8.7 / 1.10.0）
                return@withContext if (isNewer(latestTag, current)) {
                    CheckResult.UpdateAvailable(
                        latestVersion = latestTag,
                        currentVersion = current,
                        releaseNotes = releaseNotes,
                        releaseUrl = htmlUrl
                    )
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

    /**
     * 打开系统浏览器跳转到 Release 下载页。
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
}
