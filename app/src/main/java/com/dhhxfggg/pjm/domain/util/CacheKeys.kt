package com.dhhxfggg.pjm.domain.util

import com.dhhxfggg.pjm.data.model.FileEntity
import java.security.MessageDigest

/**
 * 派生缓存（缩略图 / 感知指纹）的文件名键。
 *
 * 背景（真实缺陷）：原来直接用 `relativePath.hashCode()` 作为键 ——
 * 1) 只有 32 位；在万级文件规模下，生日问题给出的碰撞概率约 2%，
 *    一旦碰撞，两个不同文件会共用同一张缩略图（A 的缩略图显示成 B 的内容，隐私问题），
 *    或 `getFingerprint` 返回**别的图**的 dHash → 误判为重复并进入默认勾选的删除列表。
 * 2) 键里不含 size / lastModified；同一路径的内容被替换后会命中陈旧缓存。
 *
 * 现在改为 `SHA-256(relativePath|size|lastModified)` 的前 16 字节十六进制（128 位）。
 * 键变化会让旧缓存文件成为孤儿 —— 缩略图与指纹目录都配有孤儿清扫，会自动回收。
 */
internal object CacheKeys {
    private const val DIGEST_BYTES = 16

    /** 每个线程复用一个 MessageDigest（MessageDigest 非线程安全，但重复创建开销更大）。 */
    private val digest =
        object : ThreadLocal<MessageDigest>() {
            override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
        }

    /**
     * 计算实体对应的稳定缓存键（32 位十六进制小写）。
     *
     * 包含 size 与 lastModified 是为了让「同路径内容被替换」这类情况自然失效。
     */
    fun of(entity: FileEntity): String {
        val md = digest.get() ?: MessageDigest.getInstance("SHA-256")
        md.reset()
        md.update(entity.relativePath.toByteArray(Charsets.UTF_8))
        md.update('|'.code.toByte())
        md.update(entity.size.toString().toByteArray(Charsets.UTF_8))
        md.update('|'.code.toByte())
        md.update(entity.lastModified.toString().toByteArray(Charsets.UTF_8))
        val bytes = md.digest()
        val sb = StringBuilder(DIGEST_BYTES * 2)
        for (i in 0 until DIGEST_BYTES) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
