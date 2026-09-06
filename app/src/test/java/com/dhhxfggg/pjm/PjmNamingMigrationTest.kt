package com.dhhxfggg.pjm

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dhhxfggg.pjm.data.db.AppDatabase
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.VaultManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 旧命名 → 规范命名迁移的抽查验证（运行在开发机沙箱，不触碰真机数据）。
 *
 * 注意：必须用普通 [Application]（而非 manifest 中的 MainApplication），
 * 否则 MainApplication.onCreate 里自动启动的迁移协程会在后台抢先执行，干扰断言。
 *
 * 验证目标（回答"会不会导致数据库读不到文件"）：
 *  1. 旧式毫秒命名 Export_<13位数字>.pjm.N → 规范 Export_yyyyMMdd_HHmmss.pjm.N，且内容不变；
 *  2. 缺省数字后缀的单卷 X.pjm → X.pjm.1；
 *  3. 数据库索引与磁盘同步更新：新路径可查、旧路径不可查、其余字段（contentHash 等）保留；
 *  4. 已是规范命名的容器、普通文件（jpg/mp4/txt）一律不受影响、记录不丢；
 *  5. 目标名已存在 → 安全跳过，不覆盖、不丢失；
 *  6. 幂等：第二次执行返回 0；
 *  7. 全程不删除任何文件（迁移前后物理文件数一致，内容保留）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PjmNamingMigrationTest {
    private lateinit var context: Context
    private lateinit var dao: FileDao
    private lateinit var db: AppDatabase
    private lateinit var vaultRoot: File

    // 旧式毫秒时间戳样本（具体可读值随测试时区换算）
    private val legacyMillis = 1767225600000L
    private val legacyTs: String
        get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(legacyMillis))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.fileDao()
        vaultRoot = File(context.filesDir, "pjm_vault")
        // 仅清理 Robolectric 临时 filesDir，不涉及真机数据
        vaultRoot.deleteRecursively()
        PjmLogger.init(context)
    }

    @After
    fun tearDown() {
        db.close()
        vaultRoot.deleteRecursively()
    }

    /** 遍历 vault 下所有文件（朴素 listFiles 递归，避免 walkTopDown 在沙箱环境的不确定性） */
    private fun allFiles(): List<File> {
        fun walk(dir: File): List<File> = dir.listFiles()?.flatMap { if (it.isDirectory) walk(it) else listOf(it) } ?: emptyList()
        return walk(vaultRoot)
    }

    /** 在指定分类目录下建一个文件并写入内容，返回文件 */
    private fun createVaultFile(
        category: String,
        name: String,
        content: String = "pjm-data",
    ): File {
        val dir = VaultManager.getCategoryDir(context, category).apply { mkdirs() }
        val f = File(dir, name)
        f.writeText(content)
        return f
    }

    /** 为文件建立一条与磁盘一致的数据库索引记录 */
    private suspend fun index(
        f: File,
        category: String,
    ) {
        dao.upsert(
            FileEntity(
                relativePath = VaultManager.getRelativePath(context, f),
                name = f.name,
                size = f.length(),
                category = category,
                lastModified = f.lastModified(),
                isImage = false,
                extension = "pjm",
                contentHash = "fake-hash", // 用于验证迁移后字段被保留
            ),
        )
    }

    private fun runMigration(): Int =
        runBlocking {
            VaultManager.migrateLegacyPjmNaming(context, dao)
        }

    private fun categoryOf(f: File): String = f.parentFile?.name ?: VaultManager.CAT_PJM

    // ---------- 1. 主流程：毫秒命名 + 缺后缀单卷，全部规范化且索引同步 ----------
    @Test
    fun legacyMillisAndNoVolumeSuffix_areNormalized_withIndexInSync() =
        runBlocking {
            // 旧式毫秒多卷
            val vol1 = createVaultFile(VaultManager.CAT_PJM, "Export_$legacyMillis.pjm.1")
            val vol2 = createVaultFile(VaultManager.CAT_PJM, "Export_$legacyMillis.pjm.2")
            // 可读时间但缺 .1 后缀的单卷
            val single = createVaultFile(VaultManager.CAT_PJM, "Random_20260715_183000.pjm")
            // 已是规范命名（不应改动）
            val canonical = createVaultFile(VaultManager.CAT_PJM, "Pack_20260715_183000.pjm.1")
            // 非 pjm 普通文件（不应改动）
            val photo = createVaultFile(VaultManager.CAT_IMAGES, "photo.jpg", "jpg-bytes")
            val doc = createVaultFile(VaultManager.CAT_OTHERS, "notes.txt", "hello")

            listOf(vol1, vol2, single, canonical, photo, doc).forEach { index(it, categoryOf(it)) }
            val totalBefore = allFiles().size
            val expected =
                listOf(
                    "Export_$legacyTs.pjm.1",
                    "Export_$legacyTs.pjm.2",
                    "Random_20260715_183000.pjm.1",
                    "Pack_20260715_183000.pjm.1",
                    "photo.jpg",
                    "notes.txt",
                )

            val migrated = runMigration()

            assertEquals("应迁移 3 个旧命名文件", 3, migrated)
            val totalAfter = allFiles().size
            assertEquals("迁移不得删除任何文件", totalBefore, totalAfter)

            // 磁盘层面：改名正确，内容保留
            val finalNames = allFiles().map { it.name }.toSet()
            expected.forEach { assertTrue("缺少文件 $it", finalNames.contains(it)) }
            val renamedVol1 = File(vaultRoot, "pjm/Export_$legacyTs.pjm.1")
            assertTrue("改名后的分卷 1 必须存在", renamedVol1.exists())
            assertEquals("内容应保留", "pjm-data", renamedVol1.readText())

            // 数据库层面：新路径可查、旧路径不可查、其余字段保留
            val newEnt = dao.findByRelativePath(VaultManager.getRelativePath(context, renamedVol1))!!
            assertEquals("Export_$legacyTs.pjm.1", newEnt.name)
            assertEquals("fake-hash", newEnt.contentHash) // 字段保留
            assertNull("旧路径记录应已删除", dao.findByRelativePath("pjm/Export_$legacyMillis.pjm.1"))
            // 普通文件索引仍在
            assertNotNull("photo.jpg 索引不得丢失", dao.findByRelativePath(VaultManager.getRelativePath(context, photo)))
            assertNotNull("notes.txt 索引不得丢失", dao.findByRelativePath(VaultManager.getRelativePath(context, doc)))
            // 规范命名文件未被改动
            assertEquals("Pack_20260715_183000.pjm.1", canonical.name)
            assertTrue(canonical.exists())

            // 幂等：再跑一次返回 0，且无副作用
            assertEquals("幂等：二次执行应为 0", 0, runMigration())
            assertEquals("幂等后文件数不变", totalBefore, allFiles().size)
        }

    // ---------- 2. 目标名已存在：安全跳过，不覆盖 ----------
    @Test
    fun targetAlreadyExists_isSkipped_withoutOverwrite() =
        runBlocking {
            // 已有一个规范命名文件（假设用户/历史已存在该目标）
            val target = createVaultFile(VaultManager.CAT_PJM, "Export_$legacyTs.pjm.1", "keep-me")
            // 旧毫秒同名 baseName 的待迁移文件，其规范目标正是上面那个
            val legacy = createVaultFile(VaultManager.CAT_PJM, "Export_$legacyMillis.pjm.1", "legacy-body")
            index(target, VaultManager.CAT_PJM)
            index(legacy, VaultManager.CAT_PJM)

            val migrated = runMigration()

            assertEquals("冲突文件应被跳过而非覆盖", 0, migrated)
            assertTrue("目标文件必须原样保留", target.exists())
            assertTrue("待迁移文件保持原名（不丢失）", legacy.exists())
            assertEquals("目标内容不得被覆盖", "keep-me", target.readText())
            // 数据库仍与磁盘一致（旧文件记录仍在旧路径）
            assertNotNull(
                "被跳过的文件索引应保持旧路径",
                dao.findByRelativePath(VaultManager.getRelativePath(context, legacy)),
            )
        }

    // ---------- 3. 随机抽查：混合命名批量样本，迁移后无丢失、无错位 ----------
    @Test
    fun mixedRandomSample_noFileLost_noIndexLost() =
        runBlocking {
            // 一批混合命名（伪随机前缀 + 旧毫秒/可读时间 + 有/无卷号）
            val prefixes = listOf("Export", "Pack", "Random", "Backup")
            // 时间戳互不相同且格式化到秒后也不同（避免迁移目标同名冲突），含两个毫秒 + 两个可读时间
            val timestamps = listOf("$legacyMillis", "1767225900001", "20260715_183000", "20250101_120000")
            val suffixes = listOf(".pjm", ".pjm.1", ".pjm.2")
            val samples = mutableListOf<File>()
            prefixes.forEachIndexed { pi, pre ->
                timestamps.forEachIndexed { ti, ts ->
                    val suffix = suffixes[(pi + ti) % suffixes.size]
                    samples.add(createVaultFile(VaultManager.CAT_PJM, "${pre}_$ts$suffix", "content-$pi-$ti"))
                }
            }
            // 混入普通文件
            samples.add(createVaultFile(VaultManager.CAT_VIDEOS, "clip.mp4", "vid"))
            samples.add(createVaultFile(VaultManager.CAT_AUDIOS, "song.mp3", "aud"))
            samples.forEach { index(it, categoryOf(it)) }
            val totalBefore = allFiles().size

            val migrated = runMigration()
            val totalAfter = allFiles().size

            assertEquals("不得丢失文件", totalBefore, totalAfter)
            assertTrue("应存在需要迁移的样本", migrated > 0)

            // 数据库记录集合（全部）
            val dbRecords = dao.getAllFiles().first()
            val allPaths = dbRecords.map { it.relativePath }.toSet()

            // 每个物理文件都必须在数据库有对应记录
            allFiles().forEach { f ->
                val rel = VaultManager.getRelativePath(context, f)
                assertTrue("物理文件 $rel 必须在数据库中有索引", rel in allPaths)
            }
            // 每条数据库记录都必须有对应物理文件（无孤儿索引）
            allPaths.forEach { rel ->
                assertTrue("数据库记录 $rel 必须有对应物理文件", File(vaultRoot, rel).exists())
            }
            // 不再残留旧式毫秒命名
            val remainingLegacy = allFiles().count { Regex("_(\\d{13})\\.pjm").containsMatchIn(it.name) }
            assertEquals("不应残留旧式毫秒命名", 0, remainingLegacy)
        }
}
