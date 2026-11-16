package com.dhhxfggg.pjm.domain.util

/**
 * 备份已安装应用时的文件命名规则（纯 Kotlin，无 Android 依赖，可直接单测）。
 *
 * 为什么把命名单独抽出来：应用名由系统/开发者决定，可能是中文、带冒号（"微信：测试版"）、
 * 带斜杠（"A/B"），也可能是整段表情符号。这些名字要直接变成保险库里的物理文件名，
 * 必须做非法字符清洗 + 长度截断；一旦把规则写进带 Context 的导出器，就只能靠
 * Instrumented 测试覆盖，回归成本太高。
 */
object ApkNaming {
    /**
     * 单个文件名的长度上限（含 .apk）。
     *
     * Android 常见文件系统单个文件名上限是 255 字节，中文按 UTF-8 占 3 字节，
     * 120 字符（全中文 = 360 字节）仍然安全 —— 取 120 是为了让列表里能完整显示，
     * 而不是贴到文件系统的极限。
     */
    const val MAX_FILE_NAME_LENGTH = 120

    /** 应用名参与命名的最大长度：过长时先截应用名，保住版本号这个真正有区分度的部分 */
    private const val MAX_LABEL_LENGTH = 60

    /** 版本名参与命名的最大长度 */
    private const val MAX_VERSION_LENGTH = 24

    private const val APK_SUFFIX = ".apk"

    /**
     * Windows 与 Android 都不接受的字符（含路径分隔符与控制字符）。
     *
     * 只清 Windows 那一套是不够的：'/' 会直接把文件名变成子目录，
     * 而控制字符在列表里看不见、在分享时被转义成乱码。
     */
    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f\\u007f]")

    /**
     * 空白与控制字符一起折成一个空格：应用名里的换行/制表符在文件名里毫无意义；
     * 先折空白再清非法字符，否则 "My \n App" 会先被换成 "My _ App"。
     */
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * 清洗成可用作单段文件名的字符串（不截断长度、不决定后缀）。
     *
     * 规则：空白折成单个空格；非法字符 → `_`；去掉开头的点（保险库里以点开头的文件
     * 会被当成隐藏文件）以及结尾的点与空格（Windows 会静默丢弃）；全空时退回 `app`。
     */
    fun sanitizeFileName(raw: String): String {
        val collapsed = raw.replace(WHITESPACE_RUN, " ")
        val cleaned = collapsed.replace(ILLEGAL_CHARS, "_").trim().trim('.', ' ')
        return cleaned.ifEmpty { "app" }
    }

    /**
     * 生成入库文件名（含 `.apk` 后缀），形如 `应用名_版本号.apk`。
     *
     * 注意这里不能用 [sanitizeFileName] 的空值兜底：版本号为空时应该直接不出现版本段，
     * 而不是把占位符 "app" 拼进去。
     */
    fun buildApkFileName(
        label: String,
        versionName: String?,
    ): String {
        val safeLabel = sanitizeFileName(label).take(MAX_LABEL_LENGTH)
        val safeVersion = sanitizeSegment(versionName.orEmpty()).take(MAX_VERSION_LENGTH)
        val stem =
            buildString {
                append(safeLabel)
                if (safeVersion.isNotEmpty()) append('_').append(safeVersion)
            }
        val maxStem = MAX_FILE_NAME_LENGTH - APK_SUFFIX.length
        val truncated = stem.take(maxStem).ifEmpty { "app" }
        return truncated + APK_SUFFIX
    }

    /** 清洗单个命名片段（版本号），空白或无内容时返回空串表示「这段不出现」 */
    private fun sanitizeSegment(raw: String): String {
        val collapsed = raw.replace(WHITESPACE_RUN, " ")
        return collapsed.replace(ILLEGAL_CHARS, "_").trim().trim('.', ' ')
    }
}
