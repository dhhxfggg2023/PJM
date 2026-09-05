package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.dhhxfggg.pjm.domain.shizuku.EmbeddedPrivilegedIo
import com.dhhxfggg.pjm.domain.shizuku.ShizukuBridge
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * PJM Bilibili收割机引擎 - 最终复盘版
 */
object BiliBridge {
    private const val TAG = "BiliBridge"

    val BILI_PKGS = listOf("tv.danmaku.bili", "tv.danmaku.bili.xl", "com.bilibili.app.blue")

    data class BiliCacheItem(
        val title: String,
        val partName: String?,
        val videoM4s: Uri,
        val audioM4s: Uri,
        val parentFolder: Uri,
        // Shizuku 模式下的文件路径（shell 身份访问 Android/data 时填充）
        val shizukuVideoPath: String? = null,
        val shizukuAudioPath: String? = null,
        val shizukuParentPath: String? = null,
    )

    /** 已合并完成的视频文件（非 m4s 碎片） */
    data class MergedVideoItem(
        val uri: Uri,
        val name: String,
        val parentFolder: Uri? = null,
        // 特权模式下源文件真实路径（shell 可访问，用于可靠删除）
        val shizukuPath: String? = null,
    )

    fun getDetectedPackages(): List<Pair<String, String>> {
        val dataDir = File(Environment.getExternalStorageDirectory(), "Android/data")
        return BILI_PKGS.filter { File(dataDir, it).exists() }.map { it to "Bilibili" }
    }

    suspend fun scan(context: Context, rootUri: Uri, onProgress: (String) -> Unit = {}): List<BiliCacheItem> = withContext(VaultManager.PjmDispatchers.IO) {
        val traceId = PjmLogger.generateTraceId()
        val finalResults = mutableListOf<BiliCacheItem>()
        
        PjmLogger.i(TAG, "==== 启动全路径扫描 ====", traceId = traceId)
        PjmLogger.i(TAG, "初始输入 Uri: $rootUri", traceId = traceId)
        
        val persistedPerms = context.contentResolver.persistedUriPermissions
        PjmLogger.i(TAG, "当前持有的永久令牌总数: ${persistedPerms.size}", traceId = traceId)
        persistedPerms.forEachIndexed { i, p -> PjmLogger.d(TAG, "Token #$i: ${p.uri}", traceId = traceId) }

        // 0. 优先特权路径（内置服务 或 Shizuku，shell/root 身份直接访问 Android/data）
        if (ShizukuBridge.isAvailable() || ShizukuBridge.isEmbeddedAvailable(context)) {
            PjmLogger.i(TAG, "目录访问服务可用，优先扫描", traceId = traceId)
            try {
                scanShizuku(context, finalResults, onProgress, traceId)
                // 核心新增：无论是否命中视频，顺手清理缓存目录中的空文件夹（下载失败/取消下载残留）
                cleanupEmptyBiliDirs(context, onProgress, traceId)
                if (finalResults.isNotEmpty()) {
                    PjmLogger.i(TAG, "Shizuku 扫描命中 ${finalResults.size} 条", traceId = traceId)
                    return@withContext finalResults.distinctBy { it.videoM4s.toString() + it.audioM4s.toString() }
                }
            } catch (e: Exception) {
                PjmLogger.w(TAG, "Shizuku 扫描失败，回退 SAF: ${e.message}", traceId = traceId)
            }
        }

        // 1. 尝试直接扫描输入路径
        try {
            if (rootUri.scheme == "content") {
                // 核心修复：输入可能是 tree URI（ACTION_OPEN_DOCUMENT_TREE 返回）或 document URI（
                // buildDocumentUriUsingTree 产生），fromTreeUri 只认 tree，需双路兜底。
                val doc = DocumentFile.fromTreeUri(context, rootUri) ?: DocumentFile.fromSingleUri(context, rootUri)
                doc?.let { scanDocDir(context, it, finalResults, onProgress, traceId) }
            } else {
                val rawPath = rootUri.path ?: ""
                val decodedPath = try { java.net.URLDecoder.decode(rawPath, "UTF-8") } catch (_: Exception) { rawPath }
                val cleanPath = decodedPath.replace("/document/primary:", "/storage/emulated/0/").replace("primary:", "/storage/emulated/0/")
                val file = File(cleanPath)
                if (file.exists()) scanLocalDir(file, finalResults, onProgress, traceId)
            }
        } catch (e: Exception) { PjmLogger.w(TAG, "直接路径扫描受阻: ${e.message}", traceId = traceId) }

        // 2. 核心修复：更鲁棒的令牌识别逻辑 (针对 Android 15 + 根授权穿透)
        // 根授权 (tree/primary%3A 或 /tree/primary) 可穿透到任意 Android/data/<pkg>；
        // 历史版本可能已授权具体包目录或 Android/data 目录，一并兼容。
        val hasRootAuth = persistedPerms.any {
            val u = it.uri.toString().lowercase()
            u.contains("tree/primary%3a") || u.endsWith("/tree/primary") || u.contains("primary%3a") && u.contains("/tree/")
        }
        val authPkgs = BILI_PKGS.filter { pkg ->
            hasRootAuth || persistedPerms.any {
                val u = it.uri.toString().lowercase()
                u.contains(pkg.lowercase()) || u.contains("primary%3aandroid%2fdata") || u.contains("android%2fdata")
            }
        }
        
        PjmLogger.i(TAG, "识别到潜在授权包名: $authPkgs", traceId = traceId)

        authPkgs.forEach { pkg ->
            // 优先匹配包专属授权，其次 Android/data 授权，最后根授权 (primary:)
            val treeUri = persistedPerms.find { it.uri.toString().lowercase().contains(pkg.lowercase()) }?.uri
                ?: persistedPerms.find { it.uri.toString().lowercase().contains("android%2fdata") }?.uri
                ?: persistedPerms.find {
                    val u = it.uri.toString().lowercase()
                    u.contains("tree/primary%3a") || u.endsWith("/tree/primary") || u.contains("primary%3a") && u.contains("/tree/")
                }?.uri
                ?: return@forEach
            
            // 核心修复：分层穿透，兼容根授权 (primary:) / Android/data 授权 / 包目录授权
            listOf("download", "files/download").forEach { relPath ->
                try {
                    val dir = penetrate(context, treeUri, pkg, relPath) ?: return@forEach
                    PjmLogger.i(TAG, "已访问: $pkg/$relPath", traceId = traceId)
                    scanDocDir(context, dir, finalResults, onProgress, traceId)
                } catch (_: Exception) {}
            }
        }

        // 3. 终极兜底：原生 IO 探测 (仅在拥有 MANAGE_EXTERNAL_STORAGE 且扫描结果为空时)
        if (finalResults.isEmpty()) {
            PjmLogger.w(TAG, "SAF 扫描未果，尝试原生 IO 穿透...", traceId = traceId)
            BILI_PKGS.forEach { pkg ->
                val path = "/storage/emulated/0/Android/data/$pkg/download"
                val file = File(path)
                if (file.exists()) scanLocalDir(file, finalResults, onProgress, traceId)
            }
        }

        PjmLogger.i(TAG, "扫描复盘结束. 有效视频条目: ${finalResults.size}", traceId = traceId)
        finalResults.distinctBy { it.videoM4s.toString() + it.audioM4s.toString() }
    }

    /**
     * 目录访问服务扫描：以 shell/root 身份直接递归 Android/data/<pkg>/download。
     * 找到 entry.json 后解析标题，并收集同目录（含子目录）的 m4s 文件。
     */
    private suspend fun scanShizuku(context: Context, result: MutableList<BiliCacheItem>, onProgress: (String) -> Unit, traceId: String) {
        BILI_PKGS.forEach { pkg ->
            val base = "/storage/emulated/0/Android/data/$pkg/download"
            if (!ShizukuBridge.exists(context, base)) return@forEach
            PjmLogger.i(TAG, "Shizuku 扫描: $base", traceId = traceId)
            onProgress("正在遍历缓存目录...")

            // 递归收集所有文件
            val allFiles = ShizukuBridge.walkFiles(context, base, maxDepth = 6)
            val entryFiles = allFiles.filter { it.endsWith("entry.json") }
            onProgress("发现 ${entryFiles.size} 个缓存条目...")

            entryFiles.forEachIndexed { entryIndex, entryPath ->
                onProgress("正在解析 (${entryIndex + 1}/${entryFiles.size})")
                try {
                    val jsonStr = ShizukuBridge.readTextFile(context, entryPath) ?: return@forEach
                    val json = JSONObject(jsonStr)
                    // 核心修复：标题二次 unescape + 净化，空值回退文件夹名
                    val folderName = entryPath.substringBeforeLast('/').substringAfterLast('/')
                    val title = sanitizeBiliTitle(json.optString("title", ""), folderName)
                    val partName = json.optJSONObject("page_data")?.optString("part")?.let { sanitizeBiliTitle(it, "") }
                        ?: json.optJSONObject("ep")?.optString("index_title")?.let { sanitizeBiliTitle(it, "") }

                    // 收集同目录树下的 m4s（取体积最大的两个作为视频/音频）
                    val folder = entryPath.substringBeforeLast('/')
                    val m4sFiles = allFiles.filter { it.startsWith(folder) && (it.endsWith(".m4s") || it.endsWith("0.m4s") || it.endsWith("1.m4s")) }
                        .mapNotNull { path ->
                            val size = ShizukuBridge.listFiles(context, path.substringBeforeLast('/'))
                                ?.find { it.name == path.substringAfterLast('/') }?.size ?: 0L
                            path to size
                        }
                        .sortedByDescending { it.second }

                    if (m4sFiles.size >= 2) {
                        val videoPath = m4sFiles[0].first
                        val audioPath = m4sFiles[1].first
                        val item = BiliCacheItem(
                            title = title,
                            partName = partName,
                            videoM4s = Uri.fromFile(File(videoPath)),
                            audioM4s = Uri.fromFile(File(audioPath)),
                            parentFolder = Uri.fromFile(File(folder)),
                            shizukuVideoPath = videoPath,
                            shizukuAudioPath = audioPath,
                            shizukuParentPath = folder,
                        )
                        result.add(item)
                        onProgress("发现: $title")
                    }
                } catch (e: Exception) {
                    PjmLogger.w(TAG, "Shizuku entry 解析失败: $entryPath: ${e.message}", traceId = traceId)
                }
            }
        }
    }

    /**
     * 清理 B站 缓存目录中的【无音视频残留文件夹】。
     *
     * 核心逻辑：一个 B站 下载目录只要不含任何音视频文件（m4s / 视频 / 音频），
     * 就是无效的"死文件夹"——要么下载失败，要么是历史版本 PJM 删除源文件后
     * 留下的 entry.json / xml 等残留。这类文件夹应整体递归删除。
     *
     * 安全策略：
     * 1. 只删除【整个目录树内不含任何音视频文件】的目录，含音视频的目录绝不误删。
     * 2. 从最深层的目录开始删（按深度降序），删完后父目录若仍无音视频则继续向上清理。
     * 3. 不删 download 根目录本身，也不删 Android/data/<pkg> 上层。
     * 4. 一切异常捕获，列表失败视为"含音视频"（安全优先，不误删）。
     *
     * 核心修复：改为 internal 公开 —— 扫描与【导入完成后】都会调用，
     * 确保导入删掉源文件夹后，download 下其余残留空文件夹也被一并清理。
     *
     * @return 清理的文件夹数量
     */
    internal suspend fun cleanupEmptyBiliDirs(context: Context, onProgress: (String) -> Unit = {}, traceId: String? = null): Int {
        if (!(ShizukuBridge.isAvailable() || ShizukuBridge.isEmbeddedAvailable(context))) return 0
        var cleaned = 0
        BILI_PKGS.forEach { pkg ->
            val base = "/storage/emulated/0/Android/data/$pkg/download"
            if (!ShizukuBridge.exists(context, base)) return@forEach
            cleaned += cleanupDirsWithoutMedia(context, base, onProgress, "B站")
        }
        if (cleaned > 0) PjmLogger.i(TAG, "无音视频残留文件夹清理完成，共 $cleaned 个", traceId = traceId)
        return cleaned
    }

    /** 判断文件是否为音视频（B站 m4s 碎片 / 常规视频 / 常规音频） */
    private fun isMediaFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".m4s") || FileUtils.isVideoFile(name) || FileUtils.isAudioFile(name)
    }

    /**
     * 通用无媒体目录清理：递归收集 base 下所有子目录（不含 base 本身），按深度降序，
     * 删除【整个目录树内不含任何音视频文件】的目录（含 entry.json/xml 等残留）。
     * 仅在特权模式下可靠执行；一切异常捕获，列表失败视为"含音视频"（安全优先）。
     *
     * @param label 日志/进度提示用的名称（如 "B站" / "已合并扫描"）
     * @return 清理的文件夹数量
     */
    private suspend fun cleanupDirsWithoutMedia(context: Context, base: String, onProgress: (String) -> Unit, label: String): Int {
        var cleaned = 0
        // 1) 收集 base 下所有子目录（不含 base 本身）
        val dirs = mutableListOf<String>()
        suspend fun collect(d: String, depth: Int) {
            if (depth > 8) return
            val entries = try {
                if (EmbeddedPrivilegedIo.isAvailable(context)) EmbeddedPrivilegedIo.listFiles(context, d)
                else try { ShizukuBridge.listFiles(context, d) } catch (_: Exception) { null }
            } catch (_: Exception) { null } ?: return
            entries.filter { it.isDirectory }.forEach { sub ->
                val full = "$d/${sub.name}"
                dirs.add(full)
                collect(full, depth + 1)
            }
        }
        collect(base, 0)
        if (dirs.isEmpty()) return 0

        // 2) 收集 base 下所有音视频文件路径（walk 一次，供快速判断）
        val mediaFiles = mutableListOf<String>()
        suspend fun walkMedia(d: String, depth: Int) {
            if (depth > 8) return
            val entries = try {
                if (EmbeddedPrivilegedIo.isAvailable(context)) EmbeddedPrivilegedIo.listFiles(context, d)
                else try { ShizukuBridge.listFiles(context, d) } catch (_: Exception) { null }
            } catch (_: Exception) { null } ?: return
            entries.forEach { e ->
                val full = "$d/${e.name}"
                if (e.isDirectory) walkMedia(full, depth + 1)
                else if (isMediaFileName(e.name)) mediaFiles.add(full)
            }
        }
        walkMedia(base, 0)

        // 3) 深度优先（路径中 '/' 越多越深），删除目录树内不含任何音视频的目录
        dirs.sortedByDescending { it.count { c -> c == '/' } }.forEach { dir ->
            if (dir == base || !dir.startsWith(base)) return@forEach
            // 目录树内是否有音视频（含自身及所有后代）
            val hasMedia = mediaFiles.any { it.startsWith("$dir/") }
            if (hasMedia) return@forEach
            val ok = try {
                if (EmbeddedPrivilegedIo.isAvailable(context)) EmbeddedPrivilegedIo.deletePath(context, dir)
                else try { ShizukuBridge.deletePath(context, dir) } catch (_: Exception) { false }
            } catch (_: Exception) { false }
            if (ok) {
                cleaned++
                PjmLogger.i(TAG, "已清理 $label 无音视频残留文件夹: $dir")
                onProgress("已清理残留文件夹: ${dir.substringAfterLast('/')}")
            }
        }
        return cleaned
    }

    /**
     * 递归扫描目录（含子文件夹），收集所有已合并的视频文件。
     * 支持 SAF (content://) 与本地路径 (file://) 两种来源。
     */
    suspend fun scanMergedVideos(context: Context, rootUri: Uri, onProgress: (String) -> Unit = {}): List<MergedVideoItem> = withContext(VaultManager.PjmDispatchers.IO) {
        val results = mutableListOf<MergedVideoItem>()
        try {
            // 核心修复：特权服务可用时，用 shell 身份扫描（拿到真实路径，删除可靠）。
            // 覆盖 SAF/File API 在 Android 14+ 看不到 Android/data 的限制。
            val selectedPath = rootUri.path?.let { raw ->
                val decoded = try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
                decoded.replace("/document/primary:", "/storage/emulated/0/").replace("primary:", "/storage/emulated/0/")
            }
            if (selectedPath != null && selectedPath.startsWith("/storage/emulated/0/") && ShizukuBridge.isEmbeddedAvailable(context)) {
                val base = File(selectedPath)
                if (base.exists() || ShizukuBridge.exists(context, selectedPath)) {
                    scanMergedShizukuDir(context, selectedPath, results, onProgress, maxDepth = 0)
                    // 核心新增：已合并视频扫描同样顺手清理所选目录下不含音视频的残留文件夹
                    cleanupDirsWithoutMedia(context, selectedPath, onProgress, "已合并扫描")
                    if (results.isNotEmpty()) return@withContext results.distinctBy { it.uri.toString() }
                }
            }

            if (rootUri.scheme == "content") {
                DocumentFile.fromTreeUri(context, rootUri)?.let { scanMergedDocDir(context, it, results, onProgress) }
            } else {
                val rawPath = rootUri.path ?: ""
                val decodedPath = try { java.net.URLDecoder.decode(rawPath, "UTF-8") } catch (_: Exception) { rawPath }
                val cleanPath = decodedPath.replace("/document/primary:", "/storage/emulated/0/").replace("primary:", "/storage/emulated/0/")
                val file = File(cleanPath)
                if (file.exists()) scanMergedLocalDir(file, results, onProgress)
            }
        } catch (e: Exception) {
            PjmLogger.w(TAG, "已合并视频扫描受阻: ${e.message}")
        }
        results.distinctBy { it.uri.toString() }
    }

    /** 特权模式递归扫描已合并视频（shell 身份，可访问 Android/data） */
    private suspend fun scanMergedShizukuDir(context: Context, path: String, result: MutableList<MergedVideoItem>, onProgress: (String) -> Unit, maxDepth: Int) {
        if (maxDepth > 8) return
        val entries = ShizukuBridge.listFiles(context, path) ?: return
        entries.forEach { e ->
            val full = "$path/${e.name}"
            if (e.isDirectory) {
                scanMergedShizukuDir(context, full, result, onProgress, maxDepth + 1)
            } else if (FileUtils.isVideoFile(e.name)) {
                result.add(MergedVideoItem(Uri.fromFile(File(full)), e.name, Uri.fromFile(File(path)), shizukuPath = full))
                onProgress("发现: ${e.name}")
            }
        }
    }

    private fun scanMergedDocDir(context: Context, dir: DocumentFile, result: MutableList<MergedVideoItem>, onProgress: (String) -> Unit) {
        val documentId = try { android.provider.DocumentsContract.getDocumentId(dir.uri) } catch (_: Exception) { return }
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(dir.uri, documentId)
        val files = mutableListOf<DocumentFile>()
        try {
            context.contentResolver.query(childrenUri, arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(dir.uri, id)
                    DocumentFile.fromSingleUri(context, fileUri)?.let { files.add(it) }
                }
            }
        } catch (_: Exception) {}

        files.forEach { f ->
            if (f.isDirectory) scanMergedDocDir(context, f, result, onProgress)
            else {
                val name = f.name ?: ""
                if (FileUtils.isVideoFile(name)) {
                    result.add(MergedVideoItem(f.uri, name, dir.uri))
                    onProgress("发现: $name")
                }
            }
        }
    }

    private fun scanMergedLocalDir(dir: File, result: MutableList<MergedVideoItem>, onProgress: (String) -> Unit) {
        val files = dir.listFiles() ?: return
        files.forEach { f ->
            if (f.isDirectory) scanMergedLocalDir(f, result, onProgress)
            else if (FileUtils.isVideoFile(f.name)) {
                result.add(MergedVideoItem(Uri.fromFile(f), f.name, Uri.fromFile(dir)))
                onProgress("发现: ${f.name}")
            }
        }
    }

    /**
     * 从已授权的树 URI 分层穿透到 Android/data/<pkg>/<relPath>。
     * 兼容三种授权形态：根授权 (primary:) / Android/data 授权 / 具体包目录授权。
     * @return 目标目录的 [DocumentFile]，无法穿透时返回 null。
     */
    private fun penetrate(context: Context, treeUri: Uri, pkg: String, relPath: String): DocumentFile? {
        val treeDocId = try { android.provider.DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { "" }
        var dir: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri) ?: return null

        // 按授权形态决定起始层级：
        // - 根授权 "primary:" → 需要依次进入 Android → data → <pkg>
        // - "primary:Android/data" → 需要进入 <pkg>
        // - "primary:Android/data/<pkg>" → 直接使用
        val segments = when {
            treeDocId == "primary:" || treeDocId.isEmpty() -> listOf("Android", "data", pkg)
            treeDocId == "primary:Android/data" -> listOf(pkg)
            treeDocId.startsWith("primary:Android/data/") -> emptyList()
            else -> return null
        }

        for (seg in segments) {
            dir = dir?.findFile(seg) ?: return null
        }
        // 进入 relPath（如 download 或 files/download）
        for (seg in relPath.split("/")) {
            if (seg.isBlank()) continue
            dir = dir?.findFile(seg) ?: return null
        }
        return dir?.takeIf { it.exists() && it.isDirectory }
    }

    private fun scanDocDir(context: Context, dir: DocumentFile, result: MutableList<BiliCacheItem>, onProgress: (String) -> Unit, traceId: String) {
        val documentId = try { android.provider.DocumentsContract.getDocumentId(dir.uri) } catch (_: Exception) { return }
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(dir.uri, documentId)
        val files = mutableListOf<DocumentFile>()
        
        try {
            context.contentResolver.query(childrenUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(dir.uri, id)
                    DocumentFile.fromSingleUri(context, fileUri)?.let { files.add(it) }
                }
            }
        } catch (_: Exception) {}

        val entryFile = files.find { it.name == "entry.json" || it.uri.path?.endsWith("entry.json") == true }
        if (entryFile != null) {
            parseDocEntry(context, entryFile, dir)?.let { result.add(it); onProgress("发现: ${it.title}") }
        } else {
            files.forEach { if (it.isDirectory) scanDocDir(context, it, result, onProgress, traceId) }
        }
    }

    private fun parseDocEntry(context: Context, entryFile: DocumentFile, folder: DocumentFile): BiliCacheItem? {
        val jsonStr = try { context.contentResolver.openInputStream(entryFile.uri)?.use { it.bufferedReader().readText() } } catch (_: Exception) { null } ?: return null
        val json = JSONObject(jsonStr)
        // 核心修复：B站 entry.json 的标题常为双重转义（字面 \uXXXX），直接 optString 会得到乱码，
        // 必须二次 unescape + 过滤非法文件名字符；空值时回退文件夹名。
        val title = sanitizeBiliTitle(json.optString("title", ""), folder.name ?: "Unknown")
        val partName = json.optJSONObject("page_data")?.optString("part")?.let { sanitizeBiliTitle(it, "") }
            ?: json.optJSONObject("ep")?.optString("index_title")?.let { sanitizeBiliTitle(it, "") }
            
        val mediaFiles = mutableListOf<Pair<Uri, Long>>()
        fun collectMedia(d: DocumentFile) {
            val dId = try { android.provider.DocumentsContract.getDocumentId(d.uri) } catch (_: Exception) { return }
            val cUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(d.uri, dId)
            context.contentResolver.query(cUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: ""
                    val mime = cursor.getString(2)
                    val size = cursor.getLong(3)
                    val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(d.uri, id)
                    if (name.endsWith(".m4s") || name == "0.m4s" || name == "1.m4s") mediaFiles.add(uri to size)
                    else if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) DocumentFile.fromSingleUri(context, uri)?.let { collectMedia(it) }
                }
            }
        }
        
        collectMedia(folder)
        if (mediaFiles.size < 2) return null
        val sorted = mediaFiles.sortedByDescending { it.second }
        return BiliCacheItem(title, partName, sorted[0].first, sorted[1].first, folder.uri)
    }

    private fun scanLocalDir(dir: File, result: MutableList<BiliCacheItem>, onProgress: (String) -> Unit, traceId: String) {
        val files = dir.listFiles() ?: return
        val entryFile = files.find { it.name == "entry.json" }
        if (entryFile != null) {
            parseLocalEntry(entryFile, dir)?.let { result.add(it); onProgress("发现: ${it.title}") }
        } else {
            files.forEach { if (it.isDirectory) scanLocalDir(it, result, onProgress, traceId) }
        }
    }

    private fun parseLocalEntry(entryFile: File, folder: File): BiliCacheItem? {
        val jsonStr = try { entryFile.readText() } catch (_: Exception) { return null }
        val json = JSONObject(jsonStr)
        // 核心修复：标题二次 unescape + 净化，空值回退文件夹名
        val title = sanitizeBiliTitle(json.optString("title", ""), folder.name ?: "Unknown")
        val partName = json.optJSONObject("page_data")?.optString("part")?.let { sanitizeBiliTitle(it, "") }
            ?: json.optJSONObject("ep")?.optString("index_title")?.let { sanitizeBiliTitle(it, "") }
        val mediaFiles = folder.walkTopDown().filter { it.isFile && (it.name.endsWith(".m4s") || it.name == "0.m4s" || it.name == "1.m4s") }.toList()
        if (mediaFiles.size < 2) return null
        val sorted = mediaFiles.sortedByDescending { it.length() }
        return BiliCacheItem(title, partName, Uri.fromFile(sorted[0]), Uri.fromFile(sorted[1]), Uri.fromFile(folder))
    }

    /**
     * 净化 B站 标题：
     * 1. 二次 unescape —— B站 entry.json 的标题常为双重转义（JSON 字符串内含字面 \uXXXX），
     *    JSONObject 只解一层，剩余字面转义会显示成乱码，这里手动再解一层。
     * 2. 过滤非法文件名字符（/ \ : * ? " < > |）与控制字符。
     * 3. 空值回退 fallback（文件夹名），截断 100 字符。
     */
    private fun sanitizeBiliTitle(title: String, fallback: String): String {
        var t = title.trim()
        if (t.contains("\\u")) {
            try {
                val sb = StringBuilder()
                var i = 0
                while (i < t.length) {
                    if (t[i] == '\\' && i + 1 < t.length && t[i + 1] == 'u' && i + 6 <= t.length) {
                        val code = t.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6; continue }
                    }
                    sb.append(t[i]); i++
                }
                t = sb.toString().trim()
            } catch (_: Exception) {}
        }
        t = t.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "").trim()
        if (t.isEmpty()) t = fallback
        return t.take(100)
    }

    suspend fun merge(context: Context, item: BiliCacheItem, outputFile: File): PjmResult<Unit> = withContext(VaultManager.PjmDispatchers.IO) {
        try {
            val cacheDir = context.cacheDir
            val vTmp = File(cacheDir, "bili_v_${System.nanoTime()}.m4v")
            val aTmp = File(cacheDir, "bili_a_${System.nanoTime()}.m4a")
            try {
                // 特权模式：先把 m4s 从 Android/data 复制到本地临时文件（主进程可读），再走既有流程
                val vLocal = if (item.shizukuVideoPath != null) {
                    ShizukuBridge.copyToCache(context, item.shizukuVideoPath)
                } else null
                val aLocal = if (item.shizukuAudioPath != null) {
                    ShizukuBridge.copyToCache(context, item.shizukuAudioPath)
                } else null

                // 核心修复：有特权路径但复制失败时，不要回退到 file:// 打开 ——
                // Android 15 对 file:// 访问 Android/data 必然 EACCES，回退会误报"权限拒绝"。
                // 直接抛异常给出明确原因，避免 merge 静默失败/进度卡住。
                if (item.shizukuVideoPath != null && vLocal == null) {
                    throw IOException("复制视频流失败（特权服务未就绪或超时）: ${item.shizukuVideoPath}")
                }
                if (item.shizukuAudioPath != null && aLocal == null) {
                    throw IOException("复制音频流失败（特权服务未就绪或超时）: ${item.shizukuAudioPath}")
                }

                sniffAndStrip(
                    context,
                    if (vLocal != null) Uri.fromFile(vLocal) else item.videoM4s,
                    vTmp
                )
                sniffAndStrip(
                    context,
                    if (aLocal != null) Uri.fromFile(aLocal) else item.audioM4s,
                    aTmp
                )
                vLocal?.delete()
                aLocal?.delete()

                mux(vTmp, aTmp, outputFile)
                if (outputFile.exists() && outputFile.length() > 0) PjmResult.Success(Unit)
                else PjmResult.Failure("合并结果为空")
            } finally {
                vTmp.delete(); aTmp.delete()
                // 核心优化：清理共享 tmp 目录残留（copyToShared 产物）
                try {
                    context.getExternalFilesDir(null)?.let { ext ->
                        File(ext, "tmp").listFiles()?.forEach { it.delete() }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { PjmLogger.e(TAG, "合并致命失败: ${item.title}", e); PjmResult.Failure("合并失败: ${e.message}") }
    }

    private fun sniffAndStrip(context: Context, srcUri: Uri, destFile: File) {
        val input = context.contentResolver.openInputStream(srcUri) ?: throw IOException("读取流失败: $srcUri")
        input.use { inputStream ->
            FileOutputStream(destFile).use { outputStream ->
                val buffer = VaultManager.acquireBuffer()
                try {
                    val firstRead = inputStream.read(buffer)
                    if (firstRead <= 0) return
                    
                    // --- 核心修复：精准 MP4 Box 头部定位 (关键：保留 Size 4字节) ---
                    val boxTypes = listOf("ftyp", "moov", "mdat", "free", "skip", "styp", "sidx")
                    var boxIndex = -1
                    for (i in 0 until (firstRead - 4)) {
                        val potentialBox = String(buffer, i, 4, Charsets.US_ASCII)
                        if (potentialBox in boxTypes) {
                            boxIndex = i; break
                        }
                    }
                    
                    // 修正后的跳过逻辑：如果找到了 ftyp 等标志位，应该保留它前面的 4 字节 Size 数据
                    val start = if (boxIndex >= 4) boxIndex - 4 else (if (firstRead > 9 && buffer[0] == 0.toByte()) 9 else 0)
                    
                    val hexHead = buffer.take(16).joinToString(" ") { "%02X".format(it) }
                    PjmLogger.d(TAG, "Header Strip: Found Box at $boxIndex, Final Skip=$start. Head: $hexHead")

                    if (firstRead > start) outputStream.write(buffer, start, firstRead - start)
                    var bytes: Int
                    while (inputStream.read(buffer).also { bytes = it } != -1) outputStream.write(buffer, 0, bytes)
                    outputStream.flush()
                    try { outputStream.fd.sync() } catch (_: Exception) {}
                } finally { VaultManager.releaseBuffer(buffer) }
            }
        }
    }

    private fun mux(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vTrack = findTrack(videoExtractor, "video/")
            val aTrack = findTrack(audioExtractor, "audio/")
            if (vTrack < 0 || aTrack < 0) throw IOException("找不到音视频轨道 (V:$vTrack, A:$aTrack)")
            val vIdx = muxer.addTrack(videoExtractor.getTrackFormat(vTrack))
            val aIdx = muxer.addTrack(audioExtractor.getTrackFormat(aTrack))
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            writeTrack(videoExtractor, vTrack, muxer, vIdx, buffer, info)
            writeTrack(audioExtractor, aTrack, muxer, aIdx, buffer, info)
            muxer.stop()
        } finally { videoExtractor.release(); audioExtractor.release(); muxer?.release() }
    }

    private fun writeTrack(ex: MediaExtractor, track: Int, mx: MediaMuxer, mxIdx: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        ex.selectTrack(track)
        while (true) {
            info.offset = 0
            info.size = ex.readSampleData(buf, 0)
            if (info.size < 0) break
            info.presentationTimeUs = ex.sampleTime
            @Suppress("WrongConstant")
            info.flags = ex.sampleFlags
            mx.writeSampleData(mxIdx, buf, info)
            ex.advance()
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith(prefix, ignoreCase = true)) return i
        }
        return -1
    }
}
