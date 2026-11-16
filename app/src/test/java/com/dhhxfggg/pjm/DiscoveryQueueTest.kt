package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.ui.viewmodel.DiscoveryQueue
import com.dhhxfggg.pjm.ui.viewmodel.computeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发现页「全量打乱队列」的回归测试（纯 JVM，不依赖 Android/Robolectric）。
 *
 * ## 锁定的缺陷
 *
 * 用户报告：**在所有资源轮完一遍之前就刷到重复的已经看过的资源。**
 *
 * 原因有两层，缺一不可：
 *
 * 1. **v1.9.3 把 `seenInRound` 从补队过滤里删掉了。** 该版本之前是
 *    `all.filter { it !in seenInRound && it !in queued }`，改成了只排除 `queued`
 *    —— 而送出的条目早已出队，"本轮已送出"这条信息在补队时完全失效。
 * 2. **轮次状态只活在内存里。** 发现页的 ViewModel 随导航条目销毁重建
 *    （离开该页再回来就是新实例），轮次记录一并丢失；而 `view_history` 是持久化的，
 *    于是重建后兜底逻辑认为"全都看过了"，立刻重排全库。
 *
 * 现在的实现把"看过没有"**完全交给持久化的 `view_history`**，
 * 不再有任何进程内的轮次集合：补队只取"没有浏览记录的路径"，
 * 因此送出的内容不可能再次入选。这条约束同时让进度可以跨启动延续
 * （用户库大、一次看不完）。
 */
class DiscoveryQueueTest {
    private companion object {
        const val VAULT_SIZE = 26
        const val LIMIT = 10
    }

    /** 固定 1..size 的路径全集（不随机），保证测试确定性 */
    private fun vault(size: Int = VAULT_SIZE): List<String> = (1..size).map { "images/img_$it.jpg" }

    /**
     * 模仿真实调用方（DiscoveryViewModel）与 `view_history` 表：
     *
     * · [persistedHistory] 就是 `view_history`（持久化）——"看过没有"的唯一来源；
     * · `onServed` 写入它，`onRoundRestart` 清空它（真实实现里都是 DAO 调用）；
     * · 轮的归属由队列自己的 [DiscoveryQueue.roundRestarts] 计数确定，
     *   避免测试另立一套"轮"的概念而与实现漂移。
     */
    private class Harness(
        var paths: List<String>,
        initialHistory: Collection<String> = emptyList(),
    ) {
        /** 兼容既有测试用例：单分类时统一用 ALL 这个键 */
        private companion object {
            const val ALL = "images"
        }

        /** 模拟每个分类各自一份持久化浏览历史（真实 DAO 按 category 分列） */
        val histories = HashMap<String, HashSet<String>>()

        /** 记录每个 DAO 回调实际收到的分类，供分类隔离回归测试断言 */
        val observedCategories = mutableListOf<String>()

        init {
            histories[ALL] = HashSet(initialHistory)
        }

        /** 单分类视图，供既有测试用例直接读写 */
        val persistedHistory: MutableSet<String>
            get() = histories.getOrPut(ALL) { HashSet() }

        /** 暴露底层队列，供分类隔离回归测试直接用显式 category 调用 */
        fun queueForTest(): DiscoveryQueue = queue

        private val queue =
            DiscoveryQueue(
                unviewedPaths = { category ->
                    observedCategories += category
                    paths.filter { it !in histories.getOrPut(category) { HashSet() } }
                },
                hasAnyPaths = { _ -> paths.isNotEmpty() },
                onRoundRestart = { category -> histories[category]?.clear() },
            ).also { it.seedServed(persistedHistory) }

        /**
         * 与 ViewModel.takeBatch 完全同构：先取；只有取到空且本轮确实走完，
         * 才显式重开一轮再取。
         *
         * @param settleAll true（默认）表示"取到的这批都翻到了" —— 与 DiscoveryScreen
         *   逐页停稳即标记的行为一致；测"只翻了一部分就退出"时传 false。
         */
        suspend fun takeBatch(
            limit: Int = LIMIT,
            settleAll: Boolean = true,
        ): Pair<Int, List<String>> {
            val epochBefore = queue.roundRestarts
            var out = queue.nextBatch(limit, ALL)
            if (out.isEmpty() && queue.isRoundComplete(ALL)) {
                val restarted = queue.restartNextRound(ALL)
                if (restarted) out = queue.nextBatch(limit, ALL)
                if (restarted) {
                    settleServed(out, settleAll)
                    return queue.roundRestarts to out
                }
            }
            settleServed(out, settleAll)
            return epochBefore to out
        }

        /** 默认：取到的都算翻到了；[settleAll] 为 false 时留给测试自己调 [settleOn] */
        private fun settleServed(
            out: List<String>,
            settleAll: Boolean,
        ) {
            if (!settleAll) return
            out.forEach { queue.markServed(it) }
            persistedHistory.addAll(out)
        }

        suspend fun batch(limit: Int = LIMIT): List<String> = takeBatch(limit).second

        /**
         * 与 DiscoveryScreen 一致：某一页停稳 = 这一张真的被看了
         * （只有它会写进浏览历史，同批其它条目不受牵连）。
         */
        fun settleOn(path: String) {
            queue.markServed(path)
            persistedHistory.add(path)
        }

        suspend fun pendingCount(): Int = queue.pendingCount

        suspend fun isRoundComplete(): Boolean = queue.isRoundComplete(ALL)

        /** 对应 ViewModel 构造时的"未看过优先"预排 */
        fun seedUnviewed(paths: Collection<String>) {
            queue.seed(paths.toList())
        }

        /** 对应 ViewModel.setMode：丢弃已排队内容但保留浏览历史 */
        fun clearQueueForModeSwitch() {
            queue.dropPending()
        }

        /** 预置"上一轮/更早"的浏览历史 */
        fun seedHistory(paths: Collection<String>) {
            persistedHistory.addAll(paths)
        }

        /**
         * 模拟 ViewModel 因导航重建/进程被杀：`view_history` 保留，
         * 进程内状态（本次会话交出过什么、已排队内容）全部丢失。
         */
        fun recreate() = Harness(paths, persistedHistory)
    }

    /** 按「轮」把批次分组（轮次归属在取批时确定） */
    private suspend fun roundsUpTo(
        h: Harness,
        batches: Int,
        limit: Int = LIMIT,
    ): List<List<String>> {
        val byEpoch = LinkedHashMap<Int, MutableList<String>>()
        repeat(batches) {
            val (epoch, batch) = h.takeBatch(limit)
            byEpoch.getOrPut(epoch) { mutableListOf() }.addAll(batch)
        }
        return byEpoch.values.toList()
    }

    // ---------- 核心不变量 ----------

    /**
     * 本轮走完整个库之前，**任何一条路径都不允许出现两次**。
     *
     * 这条断言在旧实现（v1.9.3 起）下会在第 4 批（26 条库、limit=10）就失败。
     */
    @Test
    fun noPathRepeatsBeforeWholeVaultIsServed() =
        runBlockingTest {
            val h = Harness(vault())
            val rounds = roundsUpTo(h, batches = 2 * VAULT_SIZE)

            for ((index, served) in rounds.withIndex()) {
                assertEquals(
                    "第 ${index + 1} 轮内出现重复：共 ${served.size} 条、去重后 ${served.toSet().size} 条",
                    served.size,
                    served.toSet().size,
                )
            }
            assertTrue("应至少走完一轮", rounds.size >= 2)
            assertEquals("所有路径都应来自同一个库", VAULT_SIZE, vault().size)
        }

    /** 整库走完一遍时：每一批各不重复，且全集恰好被覆盖一次 */
    @Test
    fun oneFullRoundCoversEveryPathExactlyOnce() =
        runBlockingTest {
            val h = Harness(vault())
            val rounds = roundsUpTo(h, batches = VAULT_SIZE)

            val first = rounds.first()
            assertEquals("一轮应恰好覆盖全库一次", VAULT_SIZE, first.size)
            assertEquals("一轮内不得重复", VAULT_SIZE, first.toSet().size)
            assertEquals("不应漏掉任何路径", vault().toSet(), first.toSet())
        }

    /** 只有整轮真的走完，才允许重置并开始新一轮 */
    @Test
    fun roundRestartsOnlyAfterEveryPathWasServed() =
        runBlockingTest {
            val h = Harness(vault())
            val rounds = roundsUpTo(h, batches = VAULT_SIZE + 1)

            assertEquals("重开新一轮前必须已播完全库", VAULT_SIZE, rounds.first().size)
            assertNotEquals("应确实进入了新一轮", 0, (h.takeBatch().first))
            assertTrue("新一轮应继续产出内容", rounds.getOrNull(1)?.isNotEmpty() == true)
        }

    // ---------- 用户关心的两个性质 ----------

    /**
     * **进度跨启动延续**：关掉 App 再打开（ViewModel 重建）后，
     * 已经看过的不会重播，没看过的接着看 —— 因为"看过没有"来自持久化的浏览历史。
     *
     * 用户库很大、一次看不完，这条是刚需。
     */
    @Test
    fun progressSurvivesViewmodelRecreation() =
        runBlockingTest {
            val all = vault()

            var h = Harness(all)
            val firstSeg = mutableListOf<String>()
            // 第一次打开，看 12 条
            repeat(4) { firstSeg.addAll(h.batch(limit = 3)) }
            assertEquals("第一段应产出 12 条", 12, firstSeg.size)

            // 关掉 App → 重建：只剩持久化的浏览历史
            h = h.recreate()

            // 第二次打开，继续看，直到这一轮走完
            val secondSeg = mutableListOf<String>()
            val (epochAtStart, firstOfSecond) = h.takeBatch(limit = 3)
            secondSeg.addAll(firstOfSecond)
            var guard = 0
            while (true) {
                val (e, b) = h.takeBatch(limit = 3)
                if (e != epochAtStart) break
                secondSeg.addAll(b)
                check(++guard < 30) { "重建后疑似死循环" }
            }

            val combined = firstSeg + secondSeg
            assertEquals(
                "跨启动不应重播已看过的：共 ${combined.size} 条、去重后 ${combined.toSet().size} 条",
                combined.size,
                combined.toSet().size,
            )
            assertEquals("两次打开合起来应恰好覆盖全库一次", all.toSet(), combined.toSet())
        }

    /** 本轮走完后自动重来：清空历史后全库重新可看（新一轮允许与上一轮重复） */
    @Test
    fun roundAutomaticallyRestartsAfterFullCoverage() =
        runBlockingTest {
            val h = Harness(vault(6))
            val (firstEpoch, first) = h.takeBatch(limit = 10)
            assertEquals("一轮应取完全库", 6, first.size)
            assertEquals("首轮应为第 0 轮", 0, firstEpoch)

            // 取空之后由调用方显式重开一轮；重开后取到的是新一轮内容
            val (secondEpoch, second) = h.takeBatch(limit = 10)
            assertEquals("应确实重开了一轮", firstEpoch + 1, secondEpoch)
            assertEquals("新一轮应能再次取到全库", 6, second.size)
            assertEquals("新一轮内不得重复", 6, second.toSet().size)
        }

    /**
     * **只翻了 1 张就退出，同批剩下 9 张不能被算作已看过。**
     *
     * 这是用户明确提出的要求：每一张独立，不该被批次绑定。
     * 早期实现是"取批即写入浏览历史"，翻到第 1 张就退出会白丢 9 张。
     */
    @Test
    fun quittingAfterOneItemDoesNotConsumeTheRestOfTheBatch() =
        runBlockingTest {
            val all = vault()
            var h = Harness(all)

            // 取一批 10 张，但只翻到第 1 张就退出
            val (_, batch) = h.takeBatch(limit = 10, settleAll = false)
            assertEquals("应取到一批 10 张", 10, batch.size)
            val viewed = batch.first()
            h.settleOn(viewed)

            val neverSeen = batch.drop(1)
            assertEquals("同批还有 9 张没翻到", 9, neverSeen.size)

            // 下次打开（进程内状态全丢，只剩浏览历史）
            h = h.recreate()

            // 那 9 张必须仍然算"没看过"，因此一定会被重新推到
            val secondSeg = mutableListOf<String>()
            repeat(3) { secondSeg.addAll(h.batch(limit = 10)) }
            assertTrue(
                "没翻到的那 9 张应当再次出现，实际缺少：${neverSeen.filter { it !in secondSeg }}",
                secondSeg.containsAll(neverSeen),
            )
            // 真正看过的那张不必重复推送（会话内去重仍生效）
            assertFalse("已看过的那张不该被重播", viewed in secondSeg)
        }

    // ---------- 边界 ----------

    /** 库里的文件被删光时不应产出内容，也不应死循环 */
    @Test
    fun emptyVaultYieldsNothing() =
        runBlockingTest {
            val h = Harness(emptyList())
            assertEquals(emptyList<String>(), h.batch())
            assertFalse(h.isRoundComplete())
        }

    /** 批次大于库容量：一次取完，且不重复 */
    @Test
    fun limitLargerThanVaultYieldsVaultOnce() =
        runBlockingTest {
            val h = Harness(vault(5))
            val first = h.batch(limit = 50)
            assertEquals("应一次取完全库", 5, first.size)
            assertEquals("不应重复", 5, first.toSet().size)
        }

    /** 库小于一批时，不足量返回而不是凑数重复 */
    @Test
    fun batchIsShortWhenVaultIsExhausted() =
        runBlockingTest {
            val h = Harness(vault(3))
            val first = h.batch(limit = 10)
            assertEquals(3, first.size)
        }

    /** 中途新增文件：并入队列，且不与本轮已看过的重复 */
    @Test
    fun pathsAddedMidRoundJoinWithoutDuplicating() =
        runBlockingTest {
            val h = Harness(vault(6))
            val seen = mutableListOf<String>()
            val (epoch, first) = h.takeBatch(limit = 3) // 送出 3 条，库内还剩 3 条
            seen.addAll(first)
            assertEquals(3, first.size)

            h.paths = h.paths + listOf("images/new_a.jpg", "images/new_b.jpg")

            // 取到本轮结束（轮次不变就继续），中途新增的应并入本轮
            var guard = 0
            while (true) {
                val (e, b) = h.takeBatch(limit = 3)
                if (e != epoch) break
                for (p in b) {
                    assertFalse("新文件并入时重复产出了 $p", p in seen)
                    seen.add(p)
                }
                check(++guard < 20) { "疑似死循环" }
            }
            assertEquals("6 条原有 + 2 条新增应在本轮全部覆盖且不重复", 8, seen.size)
            assertEquals(8, seen.toSet().size)
            assertTrue("新增文件应并入当前一轮", seen.containsAll(listOf("images/new_a.jpg", "images/new_b.jpg")))
        }

    /** 切分类时浏览历史保留，避免切回来重播刚看过的内容 */
    @Test
    fun modeSwitchKeepsHistory() =
        runBlockingTest {
            val h = Harness(vault(8))
            val first = h.batch(limit = 3)
            assertEquals(3, first.size)

            h.clearQueueForModeSwitch()
            assertEquals("队列应被清空", 0, h.pendingCount())

            // 历史保留：只送出 3/8，不应判定本轮走完
            assertFalse("只送出 3/8，不应判定本轮走完", h.isRoundComplete())
            assertEquals("浏览历史应原样保留", first.toSet(), h.persistedHistory)
        }

    /**
     * 回归测试：DAO 回调的分类必须由调用方固化传入，不能读"当前模式"。
     *
     * 旧实现的 unviewedPaths/hasAnyPaths/onRoundRestart 都从闭包读可变模式；
     * 切分类时在途的旧协程继续跑到 onRoundRestart，就会清掉**新**分类的浏览历史。
     * 这里直接以两个不同 category 调用队列，断言回调收到的始终是传入的分类。
     */
    @Test
    fun daoCallbacksAlwaysUseTheCategoryPassedByCaller() =
        runBlockingTest {
            val h = Harness(vault(4))
            val all = h.persistedHistory
            all.addAll(listOf("images/img_1.jpg", "images/img_2.jpg"))

            // 模拟"旧分类的一轮"：显式传 images
            val out = h.queueForTest().nextBatch(10, "images")
            assertEquals("images 分类应取到 2 条未看过", 2, out.size)

            // 模拟切到 videos 分类后仍在途的旧批次重开一轮：
            // 只要回调读的是"当前模式"而非传入的 images，videos 的历史就会被误清。
            h.histories["videos"] = HashSet(listOf("videos/v_1.mp4"))
            h.queueForTest().restartNextRound("images")

            assertEquals(
                "重开 images 一轮不得清掉 videos 分类的浏览历史",
                setOf("videos/v_1.mp4"),
                h.histories["videos"],
            )
            assertTrue(
                "所有 DAO 回调里出现的分类都必须是调用方显式传入的值",
                h.observedCategories.all { it == "images" },
            )
        }

    // ---------- 浏览队列窗口裁剪（computeWindow，纯函数） ----------

    /**
     * 窗口裁剪的规则：「当前页 - 窗口起点」不超过 300 条时原地不动（不是每翻一页都搬运列表），
     * 超过才裁一次，把当前页前方保留的条数收敛到 keep（100）—— 长会话里 UI 列表因此有上界。
     */
    @Test
    fun computeWindowOnlyTrimsAfterThresholdAndKeepsWindowBounded() {
        // 相对页号 300：还没到阈值
        assertEquals(0, computeWindow(total = 400, settled = 300, base = 0, keep = 100))
        // 相对页号 301：裁掉 201 条，当前页前方恰好剩 100 条
        assertEquals(201, computeWindow(total = 400, settled = 301, base = 0, keep = 100))
        // 已经裁过一段（base=200）：仍用同一套相对判据
        assertEquals(200, computeWindow(total = 1000, settled = 500, base = 200, keep = 100))
        assertEquals(401, computeWindow(total = 1000, settled = 501, base = 200, keep = 100))
    }

    /**
     * 裁完必须自洽：当前页相对新起点恰好剩 keep 条，且同一状态再算一次不会再裁（幂等）——
     * 否则每次停稳都会继续裁，当前页自己会被裁掉。
     */
    @Test
    fun computeWindowIsIdempotentAndNeverTrimsPastCurrentPage() {
        val base = 200
        val settled = 501
        val trimmed = computeWindow(total = 1000, settled = settled, base = base, keep = 100)
        assertEquals("当前页前方应恰好保留 keep 条", 100, settled - trimmed)
        assertTrue("只允许向前抬起点", trimmed >= base)
        assertTrue("当前页必须仍在窗口内", settled >= trimmed)

        // 窗口内容同步变成 1000 - (401 - 200) = 799 条；同一状态下重算不得继续裁
        val totalAfterTrim = 1000 - (trimmed - base)
        assertEquals(799, totalAfterTrim)
        assertEquals(trimmed, computeWindow(total = totalAfterTrim, settled = settled, base = trimmed, keep = 100))
    }

    /** 边界：空窗口/页号越界时不得返回非法起点（Screen 会拿它去 getOrNull 取页内容） */
    @Test
    fun computeWindowHandlesEmptyAndOutOfRangePages() {
        assertEquals("空窗口不裁", 0, computeWindow(total = 0, settled = 0, base = 0, keep = 100))
        assertEquals("空窗口保留已有起点", 250, computeWindow(total = 0, settled = 300, base = 250, keep = 100))
        // 页号比窗口末尾还大（切分类/清表的瞬间）：按最后一页算，起点仍落在合法范围内
        assertEquals(1399, computeWindow(total = 1500, settled = 1500, base = 0, keep = 100))
        // 页号落在起点之前（不应该发生）：宁可原地不动，也不返回负下标
        assertEquals(250, computeWindow(total = 500, settled = 100, base = 250, keep = 100))
    }
}

/** kotlinx-coroutines-test 不在本项目依赖里，这里用 runBlocking 跑协程测试 */
private fun runBlockingTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
