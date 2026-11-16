package com.dhhxfggg.pjm

import android.app.Application
import com.dhhxfggg.pjm.ui.viewmodel.FileListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * 文件列表按天分桶的回归测试：桶边界必须是【本地午夜】，不是 UTC 午夜。
 *
 * 旧实现 `floorDiv(timestamp, 86_400_000)` 按 UTC 切天，而表头用本地时区的
 * SimpleDateFormat 格式化。UTC+8 下本地 0–8 点的文件会落进前一天的桶，
 * 表头日期与文件显示日期不一致（并可能出现重复表头）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileViewerGroupingTest {
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
    private val utc = TimeZone.getTimeZone("UTC")

    /** 按 UTC 字段构造时间戳，避免依赖测试宿主机时区 */
    private fun utcMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long {
        val c = Calendar.getInstance(utc)
        c.clear()
        c.set(year, month - 1, day, hour, minute, 0)
        return c.timeInMillis
    }

    @Test
    fun localDayBucket_splitsAtLocalMidnight_notUtcMidnight() {
        // 2026-01-01 15:30 UTC == 2026-01-01 23:30 上海（本地仍是 1 日）
        val before = utcMillis(2026, 1, 1, 15, 30)
        // 2026-01-01 16:30 UTC == 2026-01-02 00:30 上海（本地已是 2 日）
        val after = utcMillis(2026, 1, 1, 16, 30)

        val cal = Calendar.getInstance(shanghai)
        val bucketBefore = FileListItem.localDayBucket(cal, before)
        val bucketAfter = FileListItem.localDayBucket(cal, after)

        assertEquals(20260101L, bucketBefore)
        assertEquals(20260102L, bucketAfter)
        assertNotEquals("本地跨午夜必须分成两个桶；旧 UTC 分桶会把两者合并", bucketBefore, bucketAfter)
    }

    @Test
    fun localDayBucket_sameLocalCalendarDaySharesOneBucket() {
        // 2026-01-01 17:00 UTC == 2026-01-02 01:00 上海
        val a = utcMillis(2026, 1, 1, 17, 0)
        // 2026-01-01 20:00 UTC == 2026-01-02 04:00 上海（同一本地日）
        val b = utcMillis(2026, 1, 1, 20, 0)
        val cal = Calendar.getInstance(shanghai)

        assertEquals(
            "同一本地日历日的两条文件必须共用同一个表头",
            FileListItem.localDayBucket(cal, a),
            FileListItem.localDayBucket(cal, b),
        )
    }
}
