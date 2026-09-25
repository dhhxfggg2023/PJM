package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * GitHub Release 检查更新器 + 应用内下载安装。
 *
 * 通过 GitHub API 查询 PJM 仓库的最新 Release，与本地安装版本比较。
 * 仓库已公开，匿名 API 即可访问（无需 token）。
 *
 * 安全约束（本文件是「应用内安装」这条供应链通道的唯一入口）：
 * 1. **下载地址白名单**：只接受 https + GitHub 自家域名。Release JSON 是网络数据，
 *    不能当作可信输入 —— 否则任何能改写响应的一方（企业/私人 CA、恶意代理、仓库被接管）
 *    都能把 URL 指向任意主机。
 * 2. **安装包签名比对**：下载完成后解析 APK 的签名证书，必须与当前已安装包的证书一致，
 *    否则直接丢弃。没有这一步，上面的白名单只能防「换主机」，防不住「在 GitHub 上换包」。
 * 3. **体积上限**：防止恶意/异常响应把缓存目录写满。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_API = "https://api.github.com/repos/dhhxfggg2023/PJM/releases/latest"
    private const val RELEASE_PAGE = "https://github.com/dhhxfggg2023/PJM/releases/latest"
    private const val TIMEOUT_MS = 15000

    /** 下载目录（app cache 下，安装后自动清理） */
    private const val DOWNLOAD_DIR = "pjm_updates"

    /** 允许下载 APK 的域名（GitHub Release 会 302 到 objects.githubusercontent.com） */
    private val ALLOWED_DOWNLOAD_HOSTS =
        setOf(
            "github.com",
            "api.github.com",
            "codeload.github.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

    /** 安装包体积上限（防止恶意响应写满缓存） */
    private const val MAX_APK_BYTES = 300L * 1024 * 1024

    /** 检查结果 */
    sealed class CheckResult {
        /** 发现新版本 */
        data class UpdateAvailable(
            val latestVersion: String, // 远程 tag，如 v1.8.8
            val currentVersion: String, // 本地版本，如 1.8.7
            val releaseNotes: String, // 发布说明
            val releaseUrl: String, // 发布页
            val apkUrl: String, // APK 下载直链（Release asset）
        ) : CheckResult()

        /** 已是最新 */
        data class UpToDate(
            val currentVersion: String,
        ) : CheckResult()

        /** 检查失败（无网络/仓库不存在等） */
        data class Failed(
            val message: String,
        ) : CheckResult()
    }

    /** 下载结果 */
    sealed class DownloadResult {
        data class Success(
            val apkFile: File,
        ) : DownloadResult()

        data class Error(
            val message: String,
        ) : DownloadResult()
    }

    /**
     * 获取本地安装版本名（如 1.8.7）。
     */
    fun getLocalVersionName(context: Context): String =
        try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pkgInfo.versionName ?: "未知"
        } catch (_: PackageManager.NameNotFoundException) {
            "未知"
        }

    /**
     * 检查是否有新版本（网络操作，需在 IO 线程调用）。
     */
    suspend fun checkForUpdate(context: Context): CheckResult =
        withContext(Dispatchers.IO) {
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
                                apkUrl = apkUrl,
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
     * 只接受通过 [isTrustedDownloadUrl] 校验的地址。
     */
    private fun findApkUrl(json: JSONObject): String? {
        return try {
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "").lowercase()
                if (name.endsWith(".apk")) {
                    val url = asset.optString("browser_download_url", "")
                    if (url.isEmpty()) continue
                    if (!isTrustedDownloadUrl(url)) {
                        // 明确记录并拒绝，而不是「跳过继续找下一个」—— 出现不可信地址本身就是异常信号
                        PjmLogger.e(TAG, "拒绝不可信的 APK 下载地址: $url")
                        return null
                    }
                    return url
                }
            }
            null
        } catch (e: Exception) {
            PjmLogger.w(TAG, "解析 APK 直链失败: ${e.message}")
            null
        }
    }

    /**
     * 下载地址白名单校验：必须 https，且 host 在 [ALLOWED_DOWNLOAD_HOSTS] 内。
     * internal：供单元测试直接验证。
     */
    internal fun isTrustedDownloadUrl(raw: String): Boolean =
        try {
            val url = URL(raw)
            url.protocol.equals("https", ignoreCase = true) &&
                url.host.lowercase() in ALLOWED_DOWNLOAD_HOSTS
        } catch (_: Exception) {
            false
        }

    /**
     * 校验下载到的 APK 与当前已安装包同源（包名一致 + 签名证书一致）。
     *
     * 采用「归档包的签名证书必须包含在已安装包的已知证书集合内」的判定，
     * 已安装侧同时取 signingCertificateHistory，以兼容密钥轮换。
     */
    private fun isSameSignerAsInstalled(
        context: Context,
        apkFile: File,
    ): Boolean {
        val pm = context.packageManager
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
        return try {
            val archive = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return false
            if (archive.packageName != context.packageName) {
                PjmLogger.e(TAG, "APK 包名不匹配: ${archive.packageName}")
                return false
            }
            val archiveDigests = apkSignerDigests(archive, historyPreferred = false)
            val installedDigests = apkSignerDigests(pm.getPackageInfo(context.packageName, flags), historyPreferred = true)
            if (archiveDigests.isEmpty() || installedDigests.isEmpty()) {
                PjmLogger.e(TAG, "无法读取签名证书，拒绝安装")
                return false
            }
            val trusted = archiveDigests.all { it in installedDigests }
            if (!trusted) PjmLogger.e(TAG, "APK 签名与已安装包不一致，已丢弃")
            trusted
        } catch (e: Exception) {
            PjmLogger.e(TAG, "签名校验失败", e)
            false
        }
    }

    /** 取签名证书的 SHA-256 摘要集合 */
    private fun apkSignerDigests(
        info: PackageInfo,
        historyPreferred: Boolean,
    ): Set<String> {
        val signatures: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signing = info.signingInfo
                when {
                    signing == null -> null
                    signing.hasMultipleSigners() -> signing.apkContentsSigners
                    historyPreferred -> signing.signingCertificateHistory
                    else -> signing.apkContentsSigners
                }
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
        return signatures
            ?.map { sig ->
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(sig.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }?.toSet()
            ?: emptySet()
    }

    /**
     * 在应用内下载 APK（流式写入 cache 目录，支持进度回调）。
     * @param onProgress 0f..1f
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult =
        withContext(Dispatchers.IO) {
            // 纵深防御：即使调用方绕过了 checkForUpdate，这里也再校验一次地址
            if (!isTrustedDownloadUrl(url)) {
                PjmLogger.e(TAG, "下载地址不在白名单内: $url")
                return@withContext DownloadResult.Error("下载地址不可信，已阻止")
            }

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
                if (total > MAX_APK_BYTES) {
                    return@withContext DownloadResult.Error("安装包体积异常，已阻止")
                }
                var downloaded = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                conn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            downloaded += read
                            // 服务器可能不返回 Content-Length，因此边下边卡上限
                            if (downloaded > MAX_APK_BYTES) {
                                apkFile.delete()
                                return@withContext DownloadResult.Error("安装包体积异常，已阻止")
                            }
                            output.write(buffer, 0, read)
                            if (total > 0) {
                                onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (apkFile.length() <= 0) {
                    return@withContext DownloadResult.Error("下载内容为空")
                }
                // 关键：签名不一致的包直接丢弃，绝不交给系统安装器
                if (!isSameSignerAsInstalled(context, apkFile)) {
                    apkFile.delete()
                    return@withContext DownloadResult.Error("安装包签名校验未通过，已丢弃")
                }
                DownloadResult.Success(apkFile)
            } catch (e: Exception) {
                PjmLogger.e(TAG, "APK 下载失败", e)
                apkFile.delete()
                DownloadResult.Error("下载失败：${e.message ?: "网络异常"}")
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }

    /**
     * 通过 FileProvider + 系统安装器拉起 APK 安装。
     * @return true 表示已成功拉起安装界面
     */
    fun installApk(
        context: Context,
        apkFile: File,
    ): Boolean =
        try {
            val uri: Uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile,
                )
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
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

    /**
     * 打开系统浏览器跳转到 Release 下载页（浏览器兜底）。
     */
    fun openReleasePage(context: Context) {
        try {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE)).apply {
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
     * internal：供 UpdateCheckerTest 做纯 JVM 单元测试。
     */
    internal fun isNewer(
        a: String,
        b: String,
    ): Boolean {
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
