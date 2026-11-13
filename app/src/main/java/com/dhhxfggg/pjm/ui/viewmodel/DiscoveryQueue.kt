package com.dhhxfggg.pjm.ui.viewmodel

/**
 * 发现页的「全量打乱队列」——纯逻辑，不依赖 Android 与数据库，便于单测。
 *
 * ## 两个不同的职责，分别由两份状态负责
 *
 * | 职责 | 状态 | 生命周期 |
 * |---|---|---|
 * | 避免**本次会话内**重复推送同一条 | [servedThisSession] | 进程内；重建时由持久化历史播种 |
 * | 记录**用户确实看过**（决定一轮何时走完） | `view_history`（调用方维护） | 持久化 |
 *
 * 这两件事**不能由同一个动作驱动**。早期实现是"取批即写入浏览历史"，
 * 后果是：用户翻到第 1 张就退出，这一批 10 张全被记为"看过"，
 * 剩下 9 张再也不会出现 —— 用户白白少看 9 张。
 * 现在浏览历史只在**真正上屏**时由调用方写入（[DiscoveryViewModel.markViewed]）。
 *
 * ## 不变量
 *
 * **在一次会话内，同一个路径绝不会被产出两次**：队列只放"没有浏览记录、
 * 且本次会话还没送出过"的路径，送出后即进入 [servedThisSession]。
 *
 * 会话结束（进程被杀）时 `servedThisSession` 会丢，队列由 `view_history` 重建 ——
 * 这意味着"取到了但用户没翻到"的那些会回到队列，这正是我们想要的：
 * 用户本来就没看过它们，不该被当作已消费。
 *
 * @param unviewedPaths 分类内"还没有浏览记录"的全部路径
 * @param hasAnyPaths 分类内是否存在任何路径（用于区分"分类为空"与"本轮走完"）
 * @param onRoundRestart 本轮走完后的重置动作（清空该分类的浏览历史）
 */
class DiscoveryQueue(
    private val unviewedPaths: suspend () -> List<String>,
    private val hasAnyPaths: suspend () -> Boolean = { true },
    private val onRoundRestart: suspend () -> Unit = {},
) {
    private val queue = ArrayDeque<String>()

    /** 本次会话已经交出过的路径（进程内；重建时由浏览历史播种） */
    private val servedThisSession = HashSet<String>()

    /** 上一批最后交出的一条：重开一轮时用来避免"轮次交界处"立刻重播同一条 */
    private var lastServedPath: String? = null

    /** 当前待消费队列长度（诊断/测试用）。 */
    val pendingCount: Int get() = queue.size

    /** 重开一轮的次数（诊断/测试用：调用方据此区分"同一会话内"与"跨轮"）。 */
    var roundRestarts: Int = 0
        private set

    /** 由调用方用持久化历史播种"本次会话交出过什么"（ViewModel 构造时调用一次）。 */
    fun seedServed(paths: Collection<String>) {
        servedThisSession.addAll(paths)
    }

    /** 标记某条已被用户看过（调用方在真正上屏时调用，用于本会话去重）。 */
    fun markServed(path: String) {
        if (path.isNotBlank()) servedThisSession.add(path)
    }

    /**
     * 取下一批路径；不足 [limit] 条时返回剩余的（可能少于 [limit]，这正是"一轮的尾巴"）。
     *
     * **只在整个队列被取空时才补队**，绝不在队列还有存量时往里追加：
     * 那样会把两批洗牌结果混在一起，调用方就再也判断不出"本轮是否走完"。
     *
     * **也不在这里自动重开一轮**：重开会让本次调用跨越轮次边界，
     * 调用方（以及它的测试）就再也无法把某一批归属到确定的轮次。
     * 重开由调用方在拿到空批次后调用 [restartNextRound] 显式完成。
     */
    suspend fun nextBatch(limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        while (queue.isEmpty()) {
            if (!pump()) break
        }
        val batch = ArrayList<String>(minOf(limit, queue.size))
        while (batch.size < limit) {
            val next = queue.removeFirstOrNull() ?: break
            servedThisSession.add(next)
            batch.add(next)
        }
        if (batch.isNotEmpty()) lastServedPath = batch.last()
        return batch
    }

    /**
     * 补队一次：纳入"还没有浏览记录、且本次会话没送出过、也不在队列里"的路径。
     *
     * @return 队列是否获得新内容
     */
    private suspend fun pump(): Boolean {
        val pending = queue.toHashSet()
        val candidates =
            unviewedPaths().filter {
                it.isNotBlank() && it !in servedThisSession && it !in pending
            }
        if (candidates.isEmpty()) return false
        queue.addAll(candidates.shuffled())
        return true
    }

    /**
     * 预排一批路径到队头（"没看过的优先"顺序偏好）。
     *
     * 只在队列为空时生效，理由同 [nextBatch]。
     *
     * @return 实际排入的条数
     */
    fun seed(paths: List<String>): Int {
        if (queue.isNotEmpty()) return 0
        val visible = paths.filter { it.isNotBlank() && it !in servedThisSession }.distinct()
        if (visible.isEmpty()) return 0
        queue.addAll(visible)
        return visible.size
    }

    /**
     * 本轮是否已经走完（分类内有内容，且已无"没看过"的路径）。
     *
     * **只看浏览历史**：一轮的边界是"用户把库看完一遍"，与本次会话送出过什么无关。
     * 分类为空时返回 false —— 空库不是"看完了"，否则调用方会不断重开一轮、空转。
     */
    suspend fun isRoundComplete(): Boolean = hasAnyPaths() && unviewedPaths().isEmpty()

    /**
     * 只清空待消费队列（切换分类时用）。
     *
     * 与 [restartNextRound] 的区别：那个是"本轮走完了，重开一轮"，会连浏览历史一起清；
     * 切分类只是换了内容来源，"看过什么"仍然有效，清掉会让用户切回来时重播。
     */
    fun dropPending() {
        queue.clear()
    }

    /**
     * 放开"本次会话已送出"的占位。
     *
     * 快速划过的页会被标记 served 却从未上屏（没写浏览历史），于是队列取空、而
     * isRoundComplete() 又为 false —— 本轮永远收不了尾。放开占位后，这些"真的没看过"
     * 的路径会重新入队；已看过的仍被 view_history 过滤，不会重播。
     */
    fun forgetServed() {
        servedThisSession.clear()
    }

    /**
     * 开新一轮：清空该分类的浏览历史，并把全库重新洗牌。
     *
     * **只应在 [isRoundComplete] 为 true 时调用** —— 否则会把本轮还没送出的路径
     * 一起重排，等于变相允许重复。
     *
     * @return 是否成功排入内容（分类为空时为 false）
     */
    suspend fun restartNextRound(): Boolean {
        queue.clear()
        servedThisSession.clear()
        onRoundRestart()
        roundRestarts++
        val ok = pump()
        // 新一轮的第一条如果正好是上一轮最后一条，往队尾挪一下，避免交界处立刻重播
        if (ok && queue.size > 1 && queue.first() == lastServedPath) {
            queue.addLast(queue.removeFirst())
        }
        return ok
    }
}
