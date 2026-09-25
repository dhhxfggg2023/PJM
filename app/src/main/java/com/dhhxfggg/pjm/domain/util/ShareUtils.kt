package com.dhhxfggg.pjm.domain.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 统一的系统分享出口。
 *
 * 背景（真实缺陷）：批量分享此前把 Intent 的 MIME 硬编码为全通配，
 * 于是 QQ / 微信等接收方只会匹配到它们的「文件」过滤器，
 * 图片多选分享出去就变成了「文件」而不是图片。修复要点有三：
 *
 * 1. **MIME 必须反映真实内容**：全部为图片时声明 `image/&#42;`，视频 `video/&#42;`，
 *    音频 `audio/&#42;`；混合类型才退化为全通配。这是接收方选择「图片处理器」
 *    还是「文件处理器」的唯一依据。
 * 2. **ClipData 必须写入**：`FLAG_GRANT_READ_URI_PERMISSION` 只对
 *    `getData()` / `getClipData()` 中的 URI 生效。仅靠 `EXTRA_STREAM`
 *    在部分 ROM / 部分接收方上会拿不到读权限，表现为接收方打不开或退化成文件。
 * 3. **Chooser 也要带上授权标志**：Android 会把内层 Intent 的授权标志
 *    迁移到 chooser，但显式再补一次可以规避个别 ROM 的迁移遗漏。
 */
object ShareUtils {
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private const val FALLBACK_MIME = "application/octet-stream"
    private const val ANY_MIME = "*/*"

    /** 媒体大类：这些大类在混合子类型时应使用 `大类/&#42;` 而不是具体 MIME。 */
    private val MEDIA_FAMILIES = setOf("image", "video", "audio")

    /** 构造 FileProvider URI；失败时返回 null（而不是抛异常打断整批分享）。 */
    fun fileProviderUri(
        context: Context,
        file: File,
    ): Uri? =
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
        }.getOrNull()

    /**
     * 解析单个 URI 的真实 MIME：
     * 先问 ContentResolver（FileProvider 会按扩展名回答），
     * 拿不到或只拿到 `application/octet-stream` 时再按 URI 末段的扩展名兜底。
     */
    fun resolveMimeType(
        context: Context,
        uri: Uri,
    ): String {
        val fromResolver = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (!fromResolver.isNullOrBlank() && fromResolver != FALLBACK_MIME) return fromResolver
        val name = uri.lastPathSegment ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return fromResolver ?: FALLBACK_MIME
    }

    /**
     * 推导多文件分享的公共 MIME：
     * - 同属一个媒体大类（image / video / audio）→ 该大类的通配类型（QQ / 微信识别图片的关键）；
     * - 所有 MIME 完全一致 → 该 MIME；
     * - 其余情况 → 全通配。
     */
    fun resolveCommonMimeType(
        context: Context,
        uris: List<Uri>,
    ): String {
        if (uris.isEmpty()) return ANY_MIME
        val types = uris.map { resolveMimeType(context, it) }
        val families = types.map { it.substringBefore('/') }.toSet()
        if (families.size == 1 && families.first() in MEDIA_FAMILIES) {
            return "${families.first()}/*"
        }
        val distinct = types.toSet()
        return if (distinct.size == 1) distinct.first() else ANY_MIME
    }

    /**
     * 由文件列表构造可分享的 URI 列表，跳过无法暴露的文件。
     * 返回 (成功的 URI, 失败的文件名)，调用方据此提示用户而不是静默少发。
     */
    fun toShareUris(
        context: Context,
        files: List<File>,
    ): Pair<List<Uri>, List<String>> {
        val uris = ArrayList<Uri>(files.size)
        val failed = ArrayList<String>()
        files.forEach { file ->
            val uri = fileProviderUri(context, file)
            if (uri != null) uris.add(uri) else failed.add(file.name)
        }
        return uris to failed
    }

    /**
     * 构造最终交给 `startActivity` 的 chooser Intent。
     * URI 为空时返回 null。
     */
    fun createShareChooser(
        context: Context,
        uris: List<Uri>,
        chooserTitle: String,
    ): Intent? {
        if (uris.isEmpty()) return null
        val mimeType = resolveCommonMimeType(context, uris)
        val multiple = uris.size > 1
        val target =
            Intent(if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = mimeType
                if (multiple) {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                } else {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 关键：让所有 URI 的读权限随 ClipData 一并授予接收方
                clipData = buildClipData(context, uris)
            }
        return Intent.createChooser(target, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 单文件分享（已持有 File 时使用）。
     * [mimeType] 为 null 时按真实文件类型推导。
     */
    fun createShareChooser(
        context: Context,
        file: File,
        chooserTitle: String,
        mimeType: String? = null,
    ): Intent? {
        val uri = fileProviderUri(context, file) ?: return null
        if (mimeType == null) return createShareChooser(context, listOf(uri), chooserTitle)
        val target =
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = buildClipData(context, listOf(uri))
            }
        return Intent.createChooser(target, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildClipData(
        context: Context,
        uris: List<Uri>,
    ): ClipData {
        val clip = ClipData.newUri(context.contentResolver, CLIP_LABEL, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }

    private const val CLIP_LABEL = "pjm_share"
}
