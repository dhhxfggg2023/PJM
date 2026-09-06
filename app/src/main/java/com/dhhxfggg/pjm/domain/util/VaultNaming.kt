package com.dhhxfggg.pjm.domain.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PJM 加密容器命名规范工具（纯逻辑，无 Android 依赖，可单测）。
 *
 * 统一命名规范：`前缀_yyyyMMdd_HHmmss.pjm.N`（可读时间戳，N 从 1 开始）。
 * 支持从旧版命名（13 位毫秒时间戳 / 缺省数字后缀）迁移到该规范。
 */
object VaultNaming {

    /** 旧版命名中的 13 位毫秒时间戳片段（如 Export_1767225600000） */
    private val LEGACY_MILLIS_NAME = Regex("^(.*)_(\\d{13})$")

    /** 分卷后缀：`主体.pjm.N` */
    private val VOLUME_SUFFIX = Regex("^(.*)\\.pjm\\.(\\d+)$")

    /** 当前时间 → 可读命名时间戳（yyyyMMdd_HHmmss），加密容器命名统一使用 */
    fun readableTimestamp(): String = formatReadable(System.currentTimeMillis())

    /** 毫秒 → 可读命名时间戳（yyyyMMdd_HHmmss） */
    fun formatReadable(millis: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(millis))

    /**
     * 旧式容器名 → 规范名 `前缀_yyyyMMdd_HHmmss.pjm.N`。
     * @return 规范名；已是规范名返回原名；非 PJM 容器返回 null
     */
    fun legacyToCanonicalName(name: String): String? {
        val volume: Int?
        val body: String
        val volMatch = VOLUME_SUFFIX.matchEntire(name)
        if (volMatch != null) {
            body = volMatch.groupValues[1]
            volume = volMatch.groupValues[2].toIntOrNull() ?: return null
        } else if (name.endsWith(".pjm")) {
            body = name.removeSuffix(".pjm")
            volume = null
        } else {
            return null
        }

        // 主体尾部是 13 位毫秒时间戳 → 换算为可读时间
        val timeMatch = LEGACY_MILLIS_NAME.matchEntire(body)
        val newBody = if (timeMatch != null) {
            val prefix = timeMatch.groupValues[1]
            val millis = timeMatch.groupValues[2].toLongOrNull()
            if (millis == null) body else "${prefix}_${formatReadable(millis)}"
        } else {
            body
        }

        // 缺省数字后缀的单卷 → 补全为 .pjm.1
        return if (volume == null) "$newBody.pjm.1" else "$newBody.pjm.$volume"
    }
}
