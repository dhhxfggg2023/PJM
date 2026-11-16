package com.dhhxfggg.pjm.domain.shizuku

import java.io.File

/**
 * 特权文件服务的路径白名单（纯 java.io，可在 Shizuku UserService / app_process 进程复用）。
 *
 * 服务以 shell(UID 2000) 或 root 身份运行，任何路径校验缺失都等于把整个设备暴露给
 * 能构造请求的调用方。此处统一定义边界：
 *  - 读/列/删只允许 /storage/emulated/0 之内；
 *  - 删除额外禁止共享存储根与其一级子目录（/DCIM、/Download 等），避免误删整本相册；
 *  - 复制的落地目录必须位于应用自己的 external-files 目录内。
 */
object PrivilegedPaths {
    const val SHARED_ROOT = "/storage/emulated/0"

    /**
     * 纯字符串归一化：把路径按 '/' 切段，丢弃空段与 '.'，处理 '..' 回退。
     *
     * 关键：不能依赖 [java.io.File.canonicalPath] —— 在 Windows 上跑 JVM 单测时，
     * 它会把 /storage/emulated/0/... 解析成 D:\storage\emulated\0\...，
     * 与本常量的字符串比较必然失败；而在 Android(Linux) 上又是对的。
     * 这套白名单逻辑本身只处理 POSIX 路径，用字符串归一化即可跨平台得到一致结果。
     */
    private fun normalize(path: String): String? {
        if (path.isEmpty()) return null
        // Windows 上跑 JVM 单测时 File.absolutePath 会带盘符（D:\storage\...）；
        // 统一把反斜杠转成正斜杠、去掉盘符前缀，还原成 POSIX 形态再比较。
        var p = path.replace('\\', '/')
        if (p.length >= 2 && p[1] == ':' && p[0].isLetter()) p = p.substring(2)
        if (p.isEmpty() || p[0] != '/') return null
        val parts = ArrayDeque<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> {
                    if (parts.isEmpty()) return null
                    parts.removeLast()
                }
                else -> parts.addLast(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** 规范化后判断路径是否位于共享存储内（含根本身）。拒绝 .. 逃逸与 /data、/system 等。 */
    fun isSharedStorage(path: String): Boolean {
        val canonical = normalize(path) ?: return false
        return canonical == SHARED_ROOT || canonical.startsWith(SHARED_ROOT + "/")
    }

    /** 是否位于指定根目录内（用于限制 copy 的落点），root 也用同一套归一化。 */
    fun isInside(
        path: String,
        root: File,
    ): Boolean {
        val canonical = normalize(path) ?: return false
        val rootPath = normalize(root.path.replace('\\', '/')) ?: return false
        return canonical == rootPath || canonical.startsWith(rootPath + "/")
    }

    /** 应用自己的 external-files 根：copy 只允许落到它里面（与 ShizukuBridge.copyToCache 一致） */
    private const val APP_EXTERNAL_ROOT = SHARED_ROOT + "/Android/data/com.dhhxfggg.pjm/files"

    /** 复制的落地路径是否安全（必须在应用 external-files 目录内）。 */
    fun isAllowedCopyDestination(path: String): Boolean = isInside(path, File(APP_EXTERNAL_ROOT))

    /**
     * 删除是否安全：必须在共享存储内，且不能是根或根的一级子目录。
     * 旧 AIDL 实现只要求「绝对路径 + 至少两级 + 无 ..」，/storage/emulated/0/DCIM 直接满足。
     */
    fun isSafeDeleteTarget(path: String): Boolean {
        val canonical = normalize(path) ?: return false
        if (canonical == SHARED_ROOT) return false
        if (!isSharedStorage(canonical)) return false
        val rest = canonical.removePrefix(SHARED_ROOT + "/")
        // 一级子目录（DCIM / Download / Pictures ...）不允许整体删除
        return rest.contains("/")
    }
}
