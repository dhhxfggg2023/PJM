package com.dhhxfggg.pjm

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dhhxfggg.pjm.data.db.AppDatabase
import com.dhhxfggg.pjm.domain.util.CryptoUtils
import com.dhhxfggg.pjm.domain.util.SettingsManager
import com.dhhxfggg.pjm.domain.util.VaultManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * 加密/解密 round-trip 测试。
 *
 * 覆盖：
 * 1. XOR 流加解密一致性（含 32 字节 key 循环对齐边界）
 * 2. PJM 容器完整 round-trip（stored entry + data descriptor，验证历史 Bug 修复）
 * 3. 分卷打包后每个分卷独立可解密（内容与源文件一致）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CryptoRoundTripTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        // 小分卷（1MB），便于触发多卷
        runBlocking {
            SettingsManager(context).updateIntSetting(SettingsManager.KEY_EXPORT_SPLIT_SIZE, 1)
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun xorStreamRoundTrip_returnsOriginalBytes() {
        val data = ByteArray(256 * 1024) { (it * 31 + 7).toByte() }

        // 加密（XOR 一次）
        val encrypted = ByteArrayOutputStream()
        CryptoUtils.createXorStream(ByteArrayInputStream(data), 0).use { it.copyTo(encrypted) }
        assertFalse(
            "Encrypted bytes must differ from plaintext",
            data.contentEquals(encrypted.toByteArray()),
        )

        // 解密（再 XOR 一次）
        val decrypted = ByteArrayOutputStream()
        CryptoUtils.createXorStream(ByteArrayInputStream(encrypted.toByteArray()), 0).use { it.copyTo(decrypted) }
        assertArrayEquals("Round-trip must restore original bytes", data, decrypted.toByteArray())
    }

    @Test
    fun xorStreamRoundTrip_keyPositionAlignment() {
        // 验证 32 字节 key 循环对齐：长度跨越 key 边界
        val lengths = intArrayOf(1, 31, 32, 33, 63, 64, 65, 1000, 1_000_000)
        for (len in lengths) {
            val data = ByteArray(len) { (it % 256).toByte() }
            val enc = ByteArrayOutputStream()
            CryptoUtils.createXorStream(ByteArrayInputStream(data), 0).use { it.copyTo(enc) }
            val dec = ByteArrayOutputStream()
            CryptoUtils.createXorStream(ByteArrayInputStream(enc.toByteArray()), 0).use { it.copyTo(dec) }
            assertArrayEquals("Length $len round-trip mismatch", data, dec.toByteArray())
        }
    }

    @Test
    fun encryptDecryptRoundTrip_preservesContentAndNames() {
        val dir = context.cacheDir
        val plain1 = "Hello PJM 你好，这是加密测试。".repeat(100).toByteArray()
        val plain2 = ByteArray(512 * 1024) { (it % 251).toByte() }
        val f1 = File(dir, "sample.txt").apply { writeBytes(plain1) }
        val f2 = File(dir, "binary.bin").apply { writeBytes(plain2) }

        val out = File(dir, "roundtrip.pjm")
        val result =
            runBlocking {
                CryptoUtils.encryptUris(
                    context,
                    listOf(Uri.fromFile(f1), Uri.fromFile(f2)),
                    out.absolutePath,
                )
            }
        assertTrue("Encryption should succeed: $result", result.isSuccess)

        val entries = mutableMapOf<String, ByteArray>()
        runBlocking {
            CryptoUtils.decryptPjmToEntries(context, listOf(Uri.fromFile(out))) { name, input ->
                entries[name] = input.readBytes()
            }
        }

        assertEquals("Two entries expected", 2, entries.size)
        assertArrayEquals("Text file content mismatch", plain1, entries["sample.txt"])
        assertArrayEquals("Binary file content mismatch", plain2, entries["binary.bin"])
    }

    @Test
    fun splitVolumes_eachVolumeIndependentlyDecryptable() {
        val dir = context.cacheDir
        // 5 个文件各 700KB，分卷 1MB → 应生成 2~4 个分卷
        val files =
            (1..5).map { i ->
                File(dir, "vol_$i.dat").apply { writeBytes(ByteArray(700 * 1024) { (i * 13).toByte() }) }
            }

        val volumes =
            runBlocking {
                VaultManager.packUrisWithSplitting(
                    context = context,
                    uris = files.map { Uri.fromFile(it) },
                    category = VaultManager.CAT_PJM,
                    baseName = "TestPack",
                    fileDao = db.fileDao(),
                    onProgress = {},
                )
            }
        assertTrue("Pack should succeed: $volumes", volumes.isSuccess)
        val volumeCount = volumes.getOrThrow()
        assertTrue("Expected multiple volumes, got $volumeCount", volumeCount >= 2)

        // 每个分卷独立解密，验证内容
        val vaultDir = VaultManager.getCategoryDir(context, VaultManager.CAT_PJM)
        val volumeFiles =
            vaultDir
                .listFiles()
                ?.filter { it.name.startsWith("TestPack.pjm.") }
                ?.sortedBy { it.name.substringAfterLast('.').toInt() }
                ?: emptyList()
        assertEquals("Volume files on disk", volumeCount, volumeFiles.size)

        var totalEntries = 0
        volumeFiles.forEach { vol ->
            val entries = mutableMapOf<String, ByteArray>()
            runBlocking {
                CryptoUtils.decryptPjmToEntries(context, listOf(Uri.fromFile(vol))) { name, input ->
                    entries[name] = input.readBytes()
                }
            }
            assertTrue("Each volume must decrypt to at least 1 entry: ${vol.name}", entries.isNotEmpty())
            entries.forEach { (name, content) ->
                val src = files.firstOrNull { it.name == name }
                assertTrue("Source file not found: $name", src != null)
                assertArrayEquals("Content mismatch in ${vol.name} for $name", src!!.readBytes(), content)
            }
            totalEntries += entries.size
        }
        assertEquals("All files recovered across volumes", files.size, totalEntries)
    }

    // ==================== 容器头版本兼容（M-2） ====================

    /**
     * 历史容器（无版本字节 + LEGACY_MASK 密钥流）必须继续可读 ——
     * 这是 1.9.3 及更早版本产出的格式，不能因为换了算法就被误判成损坏。
     */
    @Test
    fun openPjmContent_recognizesLegacyHeader() {
        val zip = buildSampleZip()
        val plain = ByteArray(4 + zip.size)
        putMagic(plain, offset = 0)
        zip.copyInto(plain, 4)

        val restored =
            CryptoUtils.openPjmContent(
                ByteArrayInputStream(xorBytes(plain, CryptoUtils.KeyStreamMode.LEGACY_MASK)),
            )
        assertNotNull("旧版容器头应被识别", restored)
        assertArrayEquals("旧版容器应还原出原始 ZIP 字节", zip, restored!!.readBytes())
    }

    /**
     * 历史容器**也能带版本号**（0x02 那种布局）：读端要按版本号选模式。
     * 这里用 LEGACY_MASK 产出「magic + 0x02 + ZIP」，读端必须原样还原。
     */
    @Test
    fun openPjmContent_recognizesLegacyMaskVersionedHeader() {
        val zip = buildSampleZip()
        val plain = ByteArray(5 + zip.size)
        putMagic(plain, offset = 0)
        plain[4] = 0x02
        zip.copyInto(plain, 5)

        val restored =
            CryptoUtils.openPjmContent(
                ByteArrayInputStream(xorBytes(plain, CryptoUtils.KeyStreamMode.LEGACY_MASK)),
            )
        assertNotNull("0x02 容器头应被识别", restored)
        assertArrayEquals("0x02 容器应还原出原始 ZIP 字节", zip, restored!!.readBytes())
    }

    /**
     * 当前格式（magic + 0x03 + ZIP，MODULO 密钥流）必须可读。
     */
    @Test
    fun openPjmContent_recognizesModuloVersionedHeader() {
        val zip = buildSampleZip()
        val plain = ByteArray(5 + zip.size)
        putMagic(plain, offset = 0)
        plain[4] = CryptoUtils.CONTAINER_VERSION
        zip.copyInto(plain, 5)

        val restored =
            CryptoUtils.openPjmContent(
                ByteArrayInputStream(xorBytes(plain, CryptoUtils.KeyStreamMode.MODULO)),
            )
        assertNotNull("0x03 容器头应被识别", restored)
        assertArrayEquals("0x03 容器应还原出原始 ZIP 字节", zip, restored!!.readBytes())
    }

    @Test
    fun openPjmContent_rejectsNonPjmInput() {
        assertNull(
            "普通文本不应被当成容器",
            CryptoUtils.openPjmContent(ByteArrayInputStream("this is not a pjm file".toByteArray())),
        )
        assertNull(
            "过短的输入不应被当成容器",
            CryptoUtils.openPjmContent(ByteArrayInputStream(byteArrayOf(0x50, 0x4B))),
        )
    }

    /**
     * 写端必须输出【新版布局 + 新算法】：magic(4) + 版本号(1) + MODULO 密钥流的 ZIP。
     *
     * 这条断言是「密钥流 Bug 已修复」的守门测试。若有人把写端改回 `pos and 33`，
     * 或把版本字节去掉，这里会立刻失败。
     */
    @Test
    fun encryptUris_writesVersionedModuloHeader() {
        val dir = context.cacheDir
        val src = File(dir, "header_src.txt").apply { writeBytes("header layout check".toByteArray()) }
        val out = File(dir, "header.pjm")

        val result =
            runBlocking {
                CryptoUtils.encryptUris(context, listOf(Uri.fromFile(src)), out.absolutePath)
            }
        assertTrue("Encryption should succeed: $result", result.isSuccess)

        val raw = out.readBytes()
        // 容器是「明文整体异或」的结果，因此用同一密钥流解一遍即可看到明文头部
        val plain = xorBytes(raw, CryptoUtils.KeyStreamMode.MODULO)

        assertEquals(
            "第 5 字节必须是版本号（新格式），不能是 ZIP 的 'P'",
            CryptoUtils.CONTAINER_VERSION,
            plain[4],
        )
        assertArrayEquals(
            "魔数之后 + 版本字节之后应当就是 ZIP 本地文件头",
            byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            plain.copyOfRange(5, 9),
        )
        assertArrayEquals(
            "魔数本身必须正确",
            ByteBuffer.allocate(4).putInt(CryptoUtils.FILE_MAGIC).array(),
            plain.copyOfRange(0, 4),
        )
    }

    // ==================== 密钥流模式：固定向量（防止算法漂移） ====================

    /**
     * 用全 0 输入跑一遍异或，输出即密钥流本身（0 xor k == k）。
     */
    private fun keyStream(
        len: Int,
        mode: CryptoUtils.KeyStreamMode,
        startPos: Long = 0L,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        CryptoUtils.createXorStream(ByteArrayInputStream(ByteArray(len)), startPos, mode).use { it.copyTo(out) }
        return out.toByteArray()
    }

    /**
     * MODULO 模式的固定向量：位置 0..33 必须**逐个**用上密钥的第 0..33 个字节。
     *
     * 这是「34 个密钥字节全部参与运算」的直接证据 —— 历史实现只用得到其中 3 个。
     */
    @Test
    fun keyStream_moduloUsesEveryKeyByte() {
        val key = "dhhxfggg_is_the_best_pjm_key_fixed".toByteArray(Charsets.UTF_8)
        assertEquals("密钥长度前提", 34, key.size)

        // 位置 0..33 应正好是密钥本体（第一圈）
        assertArrayEquals(
            "MODULO 模式第一圈必须逐个用上密钥的 34 个字节",
            key,
            keyStream(34, CryptoUtils.KeyStreamMode.MODULO),
        )

        // 第 34 字节起回到开头 → 周期为 34
        val twoRounds = keyStream(68, CryptoUtils.KeyStreamMode.MODULO)
        assertArrayEquals("MODULO 周期应为 34", key + key, twoRounds)

        // 起始位置错开时也必须按 pos % 34 取字节
        val from7 = keyStream(34, CryptoUtils.KeyStreamMode.MODULO, startPos = 7)
        assertArrayEquals("起始位置 7 应从 KEY[7] 开始", key.copyOfRange(7, 34) + key.copyOfRange(0, 7), from7)
    }

    /**
     * LEGACY_MASK 模式的固定向量：这是历史容器的真实行为，必须**原样保留**才能解开旧文件。
     *
     * 关键在于暴露它的真实强度 —— 周期是 64 而不是 34，且只用得到 3 个不同字符。
     */
    @Test
    fun keyStream_legacyMaskIsPreservedForOldContainers() {
        val legacy = keyStream(64, CryptoUtils.KeyStreamMode.LEGACY_MASK)
        assertEquals(
            "LEGACY_MASK 的密钥流必须与历史行为逐字节一致",
            "dhdhdhdhdhdhdhdhdhdhdhdhdhdhdhdhedededededededededededededededed",
            String(legacy, Charsets.ISO_8859_1),
        )

        val distinct = legacy.toSet()
        assertEquals(
            "LEGACY_MASK 实际只用到 3 个不同密钥字节（这正是被修复的缺陷）",
            3,
            distinct.size,
        )
        assertEquals(setOf('d'.code.toByte(), 'h'.code.toByte(), 'e'.code.toByte()), distinct)

        // 周期为 64
        assertArrayEquals("LEGACY_MASK 周期应为 64", legacy + legacy, keyStream(128, CryptoUtils.KeyStreamMode.LEGACY_MASK))
    }

    /**
     * 两种模式必须产出**不同**的密钥流 —— 否则版本分流就失去意义，
     * 且说明其中一种实现写错了。
     */
    @Test
    fun keyStream_twoModesDifferAndNeitherIsIdentity() {
        val key = "dhhxfggg_is_the_best_pjm_key_fixed".toByteArray(Charsets.UTF_8)
        val modulo = keyStream(256, CryptoUtils.KeyStreamMode.MODULO)
        val legacy = keyStream(256, CryptoUtils.KeyStreamMode.LEGACY_MASK)

        assertFalse("两种模式不能产出相同密钥流", modulo.contentEquals(legacy))
        assertFalse("密钥流不能是恒等映射（全 0）", modulo.all { it == 0.toByte() })
        assertFalse("密钥流不能是恒等映射（全 0）", legacy.all { it == 0.toByte() })
        assertTrue("密钥流必须来自密钥字符集", modulo.all { it in key.toSet() })
    }

    /**
     * native 侧用 `mode.ordinal` 分发算法（0=LEGACY_MASK，1=MODULO）。
     * 枚举顺序一旦变动，native 就会**静默地用错算法** —— 不报错、不崩溃，只是加解密结果错位。
     * 这里把顺序钉死：想调整枚举顺序，必须先改 native-lib.cpp 的分发逻辑。
     */
    @Test
    fun keyStreamMode_ordinalsArePinnedForJniDispatch() {
        assertEquals("LEGACY_MASK 必须是 0", 0, CryptoUtils.KeyStreamMode.LEGACY_MASK.ordinal)
        assertEquals("MODULO 必须是 1", 1, CryptoUtils.KeyStreamMode.MODULO.ordinal)
    }

    /** 把明文按密钥流位置 0 起整体异或，得到"容器字节"。 */
    private fun xorBytes(
        plain: ByteArray,
        mode: CryptoUtils.KeyStreamMode = CryptoUtils.KeyStreamMode.MODULO,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        CryptoUtils.createXorStream(ByteArrayInputStream(plain), 0, mode).use { it.copyTo(out) }
        return out.toByteArray()
    }

    /** 在 [plain] 的 [offset] 处写入容器魔数（明文形式，后续整体异或）。 */
    private fun putMagic(
        plain: ByteArray,
        offset: Int,
    ) {
        val magic = ByteBuffer.allocate(4).putInt(CryptoUtils.FILE_MAGIC).array()
        magic.copyInto(plain, offset)
    }

    /** 构造一个最小但结构完整的 ZIP（本地文件头以 PK\x03\x04 开头）。 */
    private fun buildSampleZip(): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("hello.txt"))
            zos.write("PJM container header test".toByteArray())
            zos.closeEntry()
        }
        return bos.toByteArray()
    }
}
