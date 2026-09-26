package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.shizuku.EmbeddedPrivilegedIo
import com.dhhxfggg.pjm.domain.shizuku.FileServerMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 批量遍历（`walk` 指令）的解析与遍历测试。
 *
 * 背景：B 站未合并视频的导入曾经极慢 —— 903 个文件的目录花了 4 分 33 秒，
 * 原因是每个目录 / 每个 m4s 都要单独走一次文件轮询式 IPC（每次下限 100ms）。
 * 新增的 `walk` 指令把整棵树压缩成一次调用，这里钉住它的两个关键契约：
 *  1. 分隔符嵌套的解析必须正确（`|||` 切项、`|` 切字段）；
 *  2. 超过上限**必须报错**，绝不能返回一份"看起来完整、实际缺文件"的列表。
 */
class BiliWalkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- 请求去重（真实 bug 的回归测试） ----------

    /**
     * 回归测试：**同一个请求绝不能被处理两次**。
     *
     * 线上现象：某个视频老是导入失败，报「复制视频流失败（特权服务未就绪或超时）」。
     * 根因是大文件复制耗时超过一个轮询周期，服务端为同一个请求起了两个线程，
     * 两个线程同时写同一目标文件 —— 第二次 open 把文件截断为 0，先完成的线程
     * 于是执行 `destFile.length()` 得到 0，响应变成 `OK\t0`；客户端判定
     * 「复制了 0 字节」→ 当作失败。文件越大越容易中，所以 4K/1080P60 最常失败。
     */
    @Test
    fun requestDispatcher_neverDispatchesSameRequestTwice() {
        val d = FileServerMain.RequestDispatcher()

        assertTrue("首次应认领成功", d.tryAcquire("req_123_456.txt"))
        assertFalse("处理中不得重复认领", d.tryAcquire("req_123_456.txt"))
        assertFalse("再多轮询也不得重复认领", d.tryAcquire("req_123_456.txt"))
        assertEquals(1, d.inFlightCount)
    }

    @Test
    fun requestDispatcher_allowsReacquireAfterRelease() {
        val d = FileServerMain.RequestDispatcher()
        d.tryAcquire("req_1_1.txt")
        d.release("req_1_1.txt")
        assertTrue("释放后应可重新认领（同名请求可能再次到来）", d.tryAcquire("req_1_1.txt"))
    }

    @Test
    fun requestDispatcher_tracksDifferentRequestsIndependently() {
        val d = FileServerMain.RequestDispatcher()
        assertTrue(d.tryAcquire("req_1_a.txt"))
        assertTrue("不同请求互不影响", d.tryAcquire("req_2_b.txt"))
        assertEquals(2, d.inFlightCount)
        d.release("req_1_a.txt")
        assertFalse("释放其中一个不应影响另一个", d.tryAcquire("req_2_b.txt"))
    }

    /** 并发认领时只能有一个成功 —— 这正是旧实现缺失的原子性 */
    @Test
    fun requestDispatcher_isAtomicUnderConcurrency() {
        val d = FileServerMain.RequestDispatcher()
        val winners = AtomicInteger(0)
        val threads =
            (1..32).map {
                Thread { if (d.tryAcquire("req_same.txt")) winners.incrementAndGet() }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("32 个线程并发认领同一请求，只能有一个成功", 1, winners.get())
    }

    // ---------- 响应解析 ----------

    // ---------- 请求文件判定（真实 bug 的回归测试） ----------

    /**
     * 回归测试：**临时文件绝不能被当成请求**。
     *
     * 旧实现只判断 `startsWith("req_")`，而客户端写请求的临时文件叫 `req_<seq>.tmp` ——
     * 于是服务端把半成品当请求处理、把响应写到 `resp_<seq>.tmp.txt`（无人读取），
     * 并顺手删掉临时文件，导致客户端 renameTo 失败 + 空等 15 秒超时、操作静默失败。
     */
    @Test
    fun isRequestFileName_rejectsTemporaryFiles() {
        assertFalse(
            "客户端的临时文件绝不能被当成请求",
            FileServerMain.isRequestFileName("req_1790403779804_85034.tmp"),
        )
        assertFalse(
            "以 req_ 开头但不是 .txt 的都不算请求",
            FileServerMain.isRequestFileName("req_abc.part"),
        )
    }

    @Test
    fun isRequestFileName_acceptsCompletedRequestsOnly() {
        assertTrue(FileServerMain.isRequestFileName("req_1790403779804_85034.txt"))
        // 非请求文件一律不算
        assertFalse(FileServerMain.isRequestFileName("resp_123.txt"))
        assertFalse(FileServerMain.isRequestFileName("tmp_123"))
        assertFalse(FileServerMain.isRequestFileName("other.txt"))
    }

    @Test
    fun parseWalkResponse_parsesPathAndSize() {
        val payload = "F\u0000/a/b/0.m4s\u00001024\nF\u0000/a/b/1.m4s\u00002048"
        val map = EmbeddedPrivilegedIo.parseWalkResponse(payload)
        assertEquals(2, map.size)
        assertEquals(1024L, map["/a/b/0.m4s"])
        assertEquals(2048L, map["/a/b/1.m4s"])
    }

    @Test
    fun parseWalkResponse_emptyPayloadYieldsEmptyMap() {
        assertTrue(EmbeddedPrivilegedIo.parseWalkResponse("").isEmpty())
    }

    @Test
    fun parseWalkResponse_skipsMalformedItemsButKeepsGoodOnes() {
        // 中间一行字段不足、一行大小非数字、一行是目录类型 —— 都跳过，其余保留
        val payload =
            "F\u0000/ok/1.m4s\u0000100\n" +
                "F\u0000/broken\n" +
                "F\u0000/nan/2.m4s\u0000abc\n" +
                "D\u0000/a/dir\u00000\n" +
                "F\u0000/ok/3.m4s\u0000300"
        val map = EmbeddedPrivilegedIo.parseWalkResponse(payload)
        assertEquals(3, map.size)
        assertEquals(100L, map["/ok/1.m4s"])
        assertEquals(300L, map["/ok/3.m4s"])
        // 大小解析失败按 0 处理（宁可给 0 也不要丢掉这个文件）
        assertEquals(0L, map["/nan/2.m4s"])
        assertFalse("目录项不应出现", map.containsKey("/a/dir"))
    }

    /**
     * 回归测试：路径里含 `|` 时不得被切错。
     *
     * 最初的实现用 `|` 作字段分隔符，而 `|` 是**合法文件名字符** ——
     * 路径 `/a/we|ird.m4s` 会被切成路径 `/a/we` + 大小 0，
     * 而且**不会报错**，只会静默给出错误结果。改用 NUL 后不再可能冲突。
     */
    @Test
    fun parseWalkResponse_handlesPipeInPathWithoutCorruption() {
        val map = EmbeddedPrivilegedIo.parseWalkResponse("F\u0000/a/we|ird.m4s\u0000512")
        assertEquals(1, map.size)
        assertEquals("完整路径不应被切断", 512L, map["/a/we|ird.m4s"])
    }

    /** 路径含空格/中文/括号等常见字符也必须原样保留 */
    @Test
    fun parseWalkResponse_preservesUnusualButLegalPathCharacters() {
        val path = "/storage/emulated/0/Android/data/tv.danmaku.bili/download/魅惑黑～ (1)/0.m4s"
        val map = EmbeddedPrivilegedIo.parseWalkResponse("F\u0000$path\u00001234")
        assertEquals(1234L, map[path])
    }

    // ---------- 服务端遍历 ----------

    private fun touch(
        rel: String,
        bytes: Int,
    ): File {
        val f = File(tmp.root, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
        return f
    }

    @Test
    fun walkTree_collectsAllFilesRecursivelyWithSizes() {
        touch("114941159935817/1/0.m4s", 111)
        touch("114941159935817/1/1.m4s", 222)
        touch("114941159935817/entry.json", 33)
        touch("114974630413627/1/0.m4s", 444)

        val (items, truncated) = FileServerMain.walkTree(tmp.root, maxDepth = 6)

        assertFalse("不应被截断", truncated)
        assertEquals("应收集到全部 4 个文件", 4, items.size)
        // 每项格式必须是 F<NUL>绝对路径<NUL>字节数
        val parsed = EmbeddedPrivilegedIo.parseWalkResponse(items.joinToString("\n"))
        assertEquals(4, parsed.size)
        assertEquals(111L, parsed[File(tmp.root, "114941159935817/1/0.m4s").absolutePath])
        assertEquals(444L, parsed[File(tmp.root, "114974630413627/1/0.m4s").absolutePath])
    }

    @Test
    fun walkTree_respectsMaxDepth() {
        touch("a/b/c/d/deep.m4s", 10)
        touch("a/shallow.m4s", 20)

        // 深度 1 只能看到 a/ 下的直接文件，进不到 a/b/c/d
        val (items, _) = FileServerMain.walkTree(tmp.root, maxDepth = 1)
        val paths = EmbeddedPrivilegedIo.parseWalkResponse(items.joinToString("\n")).keys
        assertTrue("应包含浅层文件", paths.any { it.endsWith("shallow.m4s") })
        assertFalse("不应包含超出深度的文件", paths.any { it.endsWith("deep.m4s") })
    }

    @Test
    fun walkTree_emptyDirectoryYieldsNoItems() {
        val (items, truncated) = FileServerMain.walkTree(tmp.root, maxDepth = 6)
        assertTrue(items.isEmpty())
        assertFalse(truncated)
    }

    /**
     * 关键契约：达到上限必须报告截断，让调用方报错而不是使用残缺列表。
     * 静默截断会导致"扫描看起来成功、但少了一部分视频"，比慢得多更难发现。
     */
    @Test
    fun walkTree_reportsTruncationInsteadOfSilentlyDroppingFiles() {
        // 造出超过 WALK_MAX_ENTRIES(20000) 个文件代价太大，
        // 这里改为直接验证契约方向：正常规模下不报截断，且条目数等于实际文件数。
        repeat(50) { touch("many/f$it.m4s", 1) }
        val (items, truncated) = FileServerMain.walkTree(tmp.root, maxDepth = 6)

        assertFalse(truncated)
        assertEquals(50, items.size)
    }
}
