package com.dhhxfggg.pjm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dhhxfggg.pjm.data.db.AppDatabase
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.IntegritySweeper
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.domain.util.VaultScanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 后台完整性抽查（[IntegritySweeper]）测试。
 *
 * 重点验证三件事：
 *  1. 判定规则与手动检查一致（感知指纹/视频不能被误判为损坏）；
 *  2. 真正损坏的文件能被发现；
 *  3. 游标推进与「走完一圈归零」的正确性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntegritySweeperTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var fileDao: FileDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        fileDao = db.fileDao()
        // 每个用例都从干净的抽查进度开始。
        // 注意：clear() 之后还要显式归零 last_run_at —— Robolectric 的偏好文件会
        // 跨 Gradle 运行残留在磁盘上，只 clear 并不可靠。
        val prefs = context.getSharedPreferences("pjm_integrity_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit().putLong("last_run_at", 0L).commit()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 往保险库目录写一个文件并登记到数据库，返回登记后的实体 */
    private fun putFile(
        name: String,
        content: ByteArray,
        category: String = "images",
        contentHashOverride: String? = null,
    ): FileEntity {
        val dir = VaultManager.getCategoryDir(context, category).apply { mkdirs() }
        val f = File(dir, name).apply { writeBytes(content) }
        val hash = contentHashOverride ?: VaultScanner.calculateHash(f)
        return FileEntity(
            relativePath = "$category/$name",
            name = name,
            size = f.length(),
            category = category,
            lastModified = f.lastModified(),
            isImage = true,
            extension = name.substringAfterLast('.', ""),
            contentHash = hash,
        )
    }

    // ---------- 判定规则：必须与手动检查一致 ----------

    @Test
    fun isHashComparable_matchesManualCheckRules() {
        // 正常 MD5 → 可比对
        assertTrue(IntegritySweeper.isHashComparable("d41d8cd98f00b204e9800998ecf8427e", "a.jpg"))
        // null → 未计算，不能当成"通过"
        assertFalse(IntegritySweeper.isHashComparable(null, "a.jpg"))
        // 感知指纹 → 与 MD5 不可比
        assertFalse(IntegritySweeper.isHashComparable("fp:12|1920|1080|abcd", "a.jpg"))
        // 视频 → 一律不按 MD5 比对（历史上曾写入无前缀的感知指纹）
        assertFalse(IntegritySweeper.isHashComparable("d41d8cd98f00b204e9800998ecf8427e", "a.mp4"))
    }

    /** 抽查复用 VaultScanner 的前缀常量 —— 抄字面量会漂移，这里把它钉住 */
    @Test
    fun videoFingerprintPrefix_isSharedWithScanner() {
        assertEquals("fp:", VaultScanner.VIDEO_FP_PREFIX)
    }

    // ---------- 检出能力 ----------

    @Test
    fun sweep_detectsCorruptedFileAndIgnoresGoodOnes() =
        runBlocking {
            val good = putFile("good.jpg", ByteArray(2048) { (it % 251).toByte() })
            val bad = putFile("bad.jpg", ByteArray(2048) { (it % 241).toByte() })
            val video = putFile("clip.mp4", ByteArray(2048) { (it % 239).toByte() }, contentHashOverride = "fp:5|1920|1080|deadbeef")
            fileDao.upsertAll(listOf(good, bad, video))

            // 只破坏 bad.jpg 的内容（大小不变，避免被 size 变化掩盖）
            val badFile = VaultManager.getFileFromEntity(context, bad)
            badFile.writeBytes(ByteArray(2048) { 0x7F })

            val report = IntegritySweeper.sweep(context, fileDao)

            assertEquals("应抽查 3 个", 3, report.checked)
            assertEquals("应检出 1 个损坏", 1, report.corrupted)
            assertEquals("不应有丢失", 0, report.missing)
            assertTrue("问题清单应含 bad.jpg", report.problems.any { it.contains("bad.jpg") })
            assertTrue("不应把视频判为损坏", report.problems.none { it.contains("clip.mp4") })
        }

    @Test
    fun sweep_detectsMissingFile() =
        runBlocking {
            val gone = putFile("gone.jpg", ByteArray(1024) { it.toByte() })
            fileDao.upsertAll(listOf(gone))
            VaultManager.getFileFromEntity(context, gone).delete()

            val report = IntegritySweeper.sweep(context, fileDao)

            assertEquals(1, report.missing)
            assertEquals(0, report.corrupted)
            assertTrue(report.problems.any { it.contains("已丢失") })
        }

    // ---------- 游标推进 ----------

    @Test
    fun sweep_advancesCursorAndWrapsAround() =
        runBlocking {
            // 造 3 个正常文件，把每轮批量降到能分两次跑完
            val entities = (1..3).map { putFile("f$it.jpg", ByteArray(256) { b -> (b + it).toByte() }) }
            fileDao.upsertAll(entities)

            // 第一轮：全部覆盖（批量 500 > 3），应当判定为"走完一圈"
            val first = IntegritySweeper.sweep(context, fileDao)
            assertEquals(3, first.checked)
            assertTrue("文件数小于批量时应判定为已完成一圈", first.wrapped)

            // 走完一圈后游标应归零 → 再跑一轮仍覆盖全部
            val second = IntegritySweeper.sweep(context, fileDao)
            assertEquals("游标归零后应重新覆盖全部", 3, second.checked)
        }

    @Test
    fun sweepIfDue_respectsIntervalGate() =
        runBlocking {
            fileDao.upsertAll(listOf(putFile("a.jpg", ByteArray(128) { it.toByte() })))

            // 时间必须**显式注入**：门控是「距上次检查是否满 24 小时」，
            // 若依赖宿主时钟，结果在不同环境（尤其 Robolectric 的模拟时钟）下不确定。
            val t0 = 1_700_000_000_000L

            val first = IntegritySweeper.sweepIfDue(context, fileDao, now = t0)
            assertTrue("首次应执行", first != null)

            val second = IntegritySweeper.sweepIfDue(context, fileDao, now = t0 + 60_000L)
            assertNull("距上次仅 1 分钟，不应重复执行", second)

            val third = IntegritySweeper.sweepIfDue(context, fileDao, now = t0 + 25 * 60 * 60 * 1000L)
            assertTrue("超过 24 小时应再次执行", third != null)
        }

    /**
     * 回归测试：时间戳落在「未来」时不得把抽查永久锁死。
     *
     * 触发条件很常见 —— 用户改系统时间、跨时区、或时钟被校准回退一次，
     * 存下的 `last_run_at` 就会大于当前时间。此时 `now - lastRun` 为负，
     * 而负数永远 `< MIN_INTERVAL_MS`，若不加判断，抽查会**永久静默失效**。
     */
    @Test
    fun sweepIfDue_isNotLockedOutByFutureTimestamp() =
        runBlocking {
            fileDao.upsertAll(listOf(putFile("a.jpg", ByteArray(128) { it.toByte() })))
            // 制造一个"未来"的时间戳（模拟时钟被回拨）
            val prefs = context.getSharedPreferences("pjm_integrity_prefs", Context.MODE_PRIVATE)
            prefs.edit().putLong("last_run_at", 1_800_000_000_000L).commit()

            val report = IntegritySweeper.sweepIfDue(context, fileDao, now = 1_700_000_000_000L)

            assertTrue("时间戳在未来时应照常执行，而不是被永久锁死", report != null)
        }

    @Test
    fun sweep_emptyVaultIsHarmless() =
        runBlocking {
            val report = IntegritySweeper.sweep(context, fileDao)
            assertEquals(0, report.checked)
            assertEquals(0, report.corrupted)
        }
}
