package com.dhhxfggg.pjm.domain.util

import android.content.Context
import com.dhhxfggg.pjm.data.db.FileDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 定期完整性**抽查**（后台、静默、只报告不修改）。
 *
 * ## 为什么需要它
 * 几十 GB 的文件长期放在手机闪存上，**静默损坏是真实存在的**（位翻转、坏块、
 * 断电写入中断）。没有这个机制的话，某个文件坏了你不会知道 —— 直到哪天打开它。
 *
 * ## 它做什么
 * 每天抽查一批文件：把磁盘上的内容重新算一遍哈希，和数据库里记录的比对。
 * 正常时**完全静默**（不打扰用户），只有发现问题才提示。
 *
 * ## 它**不**做什么（重要）
 * - **绝不**删除、移动、重写任何文件 —— 只读 + 报告
 * - **绝不**自动"修复" —— 是否处理由用户决定
 * - 不阻塞启动：由调用方放进后台作用域
 *
 * ## 与手动「检查完整性」的区别
 * | | 手动检查（设置页） | 本抽查 |
 * |---|---|---|
 * | 范围 | 全库 22699 个，一次跑完 | 每轮 500 个，轮流推进 |
 * | 触发 | 用户点击 | 每 24 小时自动 |
 * | 影响 | 前台进度条 | 后台静默 |
 *
 * 两者共用同一套判定规则（见 [isHashComparable]），避免出现"手动说没事、后台说坏了"。
 */
object IntegritySweeper {
    private const val PREFS = "pjm_integrity_prefs"
    private const val KEY_LAST_RUN = "last_run_at"
    private const val KEY_CURSOR = "cursor_path"
    private const val KEY_TOTAL_BAD = "total_bad"
    private const val KEY_LAST_SUMMARY = "last_summary"

    /** 每轮抽查的文件数。500 × 平均 2MB ≈ 1GB 读取，约几秒；全库约 45 轮走完一轮 */
    const val BATCH_SIZE = 500

    /** 两次抽查的最小间隔（24 小时） */
    private const val MIN_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** 等待用户操作结束时，两次轮询的间隔 */
    private const val BUSY_POLL_MS = 30_000L

    /**
     * 等用户操作结束的**最长**时间，超过就照常执行。
     *
     * 抽查是只读的、低优先级的，不值得为了"绝对不打扰"而无限等待 ——
     * 否则只要有任何一个任务卡住（或长期挂着），抽查就永远不会执行，
     * 这个功能等于不存在。宁可稍微抢一点 IO，也不能不跑。
     */
    private const val MAX_BUSY_WAIT_MS = 5 * 60 * 1000L

    private val _problems = MutableStateFlow<List<String>>(emptyList())

    /** 最近一轮发现的问题（相对路径 + 原因）。UI 可订阅用于展示。 */
    val problems: StateFlow<List<String>> = _problems.asStateFlow()

    /**
     * 判定一条 [com.dhhxfggg.pjm.data.model.FileEntity.contentHash] 能否用于字节级比对。
     *
     * 只有**确定是 MD5** 的记录才能比对：
     *  - `null` → 尚未计算，无从比对（跳过的语义是"未检查"，不能当成"通过"）
     *  - `fp:` 前缀 → 视频感知指纹，与 MD5 不可比
     *  - 视频文件 → 历史上曾直接写入无前缀的感知指纹，一律不按 MD5 比对
     *
     * 这条规则与 `VaultScanner.checkIntegrity` 保持一致 —— 规则只此一处定义，
     * 避免两处实现漂移导致结论互相矛盾。
     */
    fun isHashComparable(
        contentHash: String?,
        fileName: String,
    ): Boolean {
        if (contentHash == null) return false
        if (contentHash.startsWith(VaultScanner.VIDEO_FP_PREFIX)) return false
        return !FileUtils.isVideoFile(fileName)
    }

    /**
     * 抽查结果。
     *
     * @param wrapped 本轮是否走完了一整圈（回到列表开头）
     */
    data class Report(
        val checked: Int,
        val missing: Int,
        val corrupted: Int,
        val totalBad: Long,
        val wrapped: Boolean,
        val problems: List<String>,
    )

    /**
     * 距上次抽查已超过 [MIN_INTERVAL_MS] 时才执行。
     *
     * @param now 当前时间，**可注入**。生产代码用默认值即可；测试必须显式传入，
     *   否则门控逻辑依赖宿主机时钟，在不同环境（尤其 Robolectric）下结果不确定 ——
     *   这个参数就是为了让"24 小时门控"能被确定性地测试。
     * @return 未到期返回 null；否则返回本轮结果
     */
    suspend fun sweepIfDue(
        context: Context,
        fileDao: FileDao,
        now: Long = System.currentTimeMillis(),
    ): Report? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastRun = prefs.getLong(KEY_LAST_RUN, 0L)
        val elapsed = now - lastRun

        // 只有「确实在过去、且不满 24 小时」才跳过。
        //
        // 必须显式排除 lastRun 位于未来的情况：那时 elapsed 是负数，
        // 而负数**永远** `< MIN_INTERVAL_MS`，会让抽查被永久锁死 ——
        // 用户改过系统时间、跨时区、或时钟被校准回退一次，这个功能就再也不会执行，
        // 而且完全静默、没有任何迹象。所以未来时间戳一律视为「需要重新执行」。
        val notDue = lastRun in 1..now && elapsed < MIN_INTERVAL_MS
        if (notDue) return null

        // 先落时间戳再跑：即使本次中途被杀，也不会每次启动都重跑一遍
        prefs.edit().putLong(KEY_LAST_RUN, now).apply()
        return sweep(context, fileDao)
    }

    /**
     * 执行一轮抽查。进度（游标 + 累计问题数）持久化，下次从断点继续。
     */
    suspend fun sweep(
        context: Context,
        fileDao: FileDao,
    ): Report =
        withContext(Dispatchers.IO) {
            // 让位用户操作：抽查不紧急，不值得和导入/删除抢 IO。
            // 但**不无限等** —— 见 [MAX_BUSY_WAIT_MS]。
            var waited = 0L
            while (VaultManager.isOperationActive && waited < MAX_BUSY_WAIT_MS) {
                delay(BUSY_POLL_MS)
                waited += BUSY_POLL_MS
            }

            val all = fileDao.getAllFiles().first()
            if (all.isEmpty()) return@withContext Report(0, 0, 0, 0, false, emptyList())

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val sorted = all.sortedBy { it.relativePath }
            val cursor = prefs.getString(KEY_CURSOR, null)
            // 按 relativePath 排序后二分定位游标，避免依赖列表顺序稳定
            val start =
                if (cursor == null) {
                    0
                } else {
                    sorted.indexOfFirst { it.relativePath > cursor }.let { if (it < 0) 0 else it }
                }

            val batch = sorted.drop(start).take(BATCH_SIZE)
            var missing = 0
            var corrupted = 0
            val problems = mutableListOf<String>()

            batch.forEach { entity ->
                val file = VaultManager.getFileFromEntity(context, entity)
                if (!file.exists()) {
                    missing++
                    problems += "${entity.name} ｜ 文件已丢失"
                    return@forEach
                }
                if (isHashComparable(entity.contentHash, entity.name) &&
                    VaultScanner.calculateHash(file) != entity.contentHash
                ) {
                    corrupted++
                    problems += "${entity.name} ｜ 内容不一致（可能已损坏）"
                }
            }

            val wrapped = start + batch.size >= sorted.size
            val totalBad = prefs.getLong(KEY_TOTAL_BAD, 0L) + missing + corrupted
            val summary =
                "抽查 ${batch.size} 个，丢失 $missing，损坏 $corrupted" +
                    if (wrapped) "（已完成一整轮，游标归零）" else ""

            prefs
                .edit()
                .putString(KEY_CURSOR, if (wrapped) null else batch.lastOrNull()?.relativePath)
                .putLong(KEY_TOTAL_BAD, totalBad)
                .putString(KEY_LAST_SUMMARY, summary)
                .apply()

            _problems.value = problems

            PjmLogger.i(
                "IntegritySweeper",
                "完整性抽查：$summary（累计 $totalBad）",
            )
            problems.take(20).forEach { PjmLogger.w("IntegritySweeper", "  · $it") }

            Report(batch.size, missing, corrupted, totalBad, wrapped, problems)
        }

    /** 上次抽查的可读摘要（供设置页展示），没有则返回 null */
    fun lastSummary(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_SUMMARY, null)
    }

    /** 累计发现的问题数 */
    fun totalBad(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_TOTAL_BAD, 0L)
    }
}
