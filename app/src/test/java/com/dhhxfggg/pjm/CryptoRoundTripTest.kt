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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

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
            data.contentEquals(encrypted.toByteArray())
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
        val result = runBlocking {
            CryptoUtils.encryptUris(
                context,
                listOf(Uri.fromFile(f1), Uri.fromFile(f2)),
                out.absolutePath
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
        val files = (1..5).map { i ->
            File(dir, "vol_$i.dat").apply { writeBytes(ByteArray(700 * 1024) { (i * 13).toByte() }) }
        }

        val volumes = runBlocking {
            VaultManager.packUrisWithSplitting(
                context = context,
                uris = files.map { Uri.fromFile(it) },
                category = VaultManager.CAT_PJM,
                baseName = "TestPack",
                fileDao = db.fileDao(),
                onProgress = {}
            )
        }
        assertTrue("Pack should succeed: $volumes", volumes.isSuccess)
        val volumeCount = volumes.getOrThrow()
        assertTrue("Expected multiple volumes, got $volumeCount", volumeCount >= 2)

        // 每个分卷独立解密，验证内容
        val vaultDir = VaultManager.getCategoryDir(context, VaultManager.CAT_PJM)
        val volumeFiles = vaultDir.listFiles()
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
}
