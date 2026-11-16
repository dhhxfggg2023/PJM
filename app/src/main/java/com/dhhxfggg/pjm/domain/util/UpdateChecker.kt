package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
                    // 版本前缀同时接受小写 v 与大写 V（GitHub tag 由人写，历史上出现过 V1.8.8）
                    val latestTag = json.optString("tag_name", "").removePrefix("v").removePrefix("V")
                    val releaseNotes = json.optString("body", "").take(500)
                    val htmlUrl = json.optString("html_url", RELEASE_PAGE)

                    if (latestTag.isEmpty()) {
                        return@withContext CheckResult.Failed("远程版本信息为空")
                    }
                    // 核心修复：版本号解析失败必须报失败态。旧实现把不可解析的版本按 0 处理，
                    // 于是「本地版本号解析不出来」（如 versionName 为 null → "未知"）时
                    // 远程永远被判定为更新 —— 界面永远显示有更新。这里前置校验两侧都能解析。
                    if (parseVersion(latestTag) == null) {
                        return@withContext CheckResult.Failed("远程版本号格式无法解析: $latestTag")
                    }
                    if (parseVersion(current) == null) {
                        return@withContext CheckResult.Failed("无法解析本地版本号: $current")
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
                // 核心修复：关闭自动重定向，手工逐跳校验 Location。
                // 旧实现 instanceFollowRedirects=true，且在 responseCode 之前读 conn.url ——
                // 此时请求尚未发出，拿到的恒为初始 URL，注释宣称的「302 后再校验」完全不存在，
                // GitHub 的 Location 指向任意主机都会被自动跟随下载。
                var currentUrl = url
                var finalConn: HttpURLConnection? = null
                for (hop in 0 until 5) {
                    if (!isTrustedDownloadUrl(currentUrl)) {
                        PjmLogger.e(TAG, "下载地址不在白名单内: $currentUrl")
                        return@withContext DownloadResult.Error("下载地址不可信，已阻止")
                    }
                    val c = URL(currentUrl).openConnection() as HttpURLConnection
                    conn = c
                    c.requestMethod = "GET"
                    c.connectTimeout = TIMEOUT_MS
                    c.readTimeout = 30000
                    c.setRequestProperty("User-Agent", "PJM-Android")
                    c.instanceFollowRedirects = false
                    val code = c.responseCode
                    if (code in 301..308 && code != 304) {
                        val location = c.getHeaderField("Location")
                        if (location.isNullOrBlank()) {
                            return@withContext DownloadResult.Error("重定向缺少 Location")
                        }
                        // 相对 Location 按当前 URL 解析，再走下一跳的白名单校验
                        currentUrl = runCatching { URL(URL(currentUrl), location).toString() }.getOrDefault(location)
                        c.disconnect()
                        conn = null
                        continue
                    }
                    if (code != 200) {
                        return@withContext DownloadResult.Error("下载失败 (HTTP $code)")
                    }
                    finalConn = c
                    break
                }
                val code = finalConn?.responseCode ?: return@withContext DownloadResult.Error("重定向次数过多，已阻止")
                if (code != 200) {
                    return@withContext DownloadResult.Error("下载失败 (HTTP $code)")
                }
                conn = finalConn
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
                // 核心修复：有 Content-Length 时必须校验实际收到的字节数。
                // 旧实现直接进入签名校验，截断的包会让 getPackageArchiveInfo 解析失败，
                // 于是用户看到的是「安装包签名校验未通过」（误导），而不是「下载不完整」。
                if (total > 0 && downloaded != total) {
                    apkFile.delete()
                    return@withContext DownloadResult.Error("下载不完整（$downloaded/$total 字节），请重试")
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
     *
     * Android 8.0+ 必须先获得「安装未知应用」授权，否则系统安装器会被直接拒绝拉起
     * （用户只看到"无法安装"却不知道去哪开）。这里先引导到系统授权页并返回 false，
     * 由调用方用 `R.string.msg_update_install_fallback` 提示"允许后重试"。
     *
     * @return true 表示已成功拉起安装界面（未授权时返回 false）
     */
    fun installApk(
        context: Context,
        apkFile: File,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            PjmLogger.w(TAG, "缺少「安装未知应用」授权，引导到系统设置")
            openUnknownSourcesSettings(context)
            return false
        }
        return launchInstaller(context, apkFile)
    }

    /** 打开本应用的「安装未知应用」授权页（失败只记日志，不影响调用方拿到 false） */
    private fun openUnknownSourcesSettings(context: Context) {
        try {
            val intent =
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:" + context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
        } catch (e: Exception) {
            PjmLogger.w(TAG, "打开「安装未知应用」设置失败: " + e.message)
        }
    }

    private fun launchInstaller(
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
     * 解析版本号的数字段；一个数字段都没有时返回 null（表示"不可比较"）。
     *
     * 与 [isNewer] 互补：[isNewer] 只做宽松比较，本函数用于「两侧到底能不能比」的前置校验，
     * 避免把一个解析不出来的版本号当成 0 去比，从而永远判定"有新版本"。
     */
    internal fun parseVersion(raw: String): List<Int>? =
        raw
            .removePrefix("v")
            .removePrefix("V")
            .split(".")
            .mapNotNull { it.toIntOrNull() }
            .takeIf { it.isNotEmpty() }

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
