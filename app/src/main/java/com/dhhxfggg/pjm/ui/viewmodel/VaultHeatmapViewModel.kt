package com.dhhxfggg.pjm.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.domain.util.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

/** 入库热力图：单日入库数量 */
data class HeatmapDay(
    val epochDay: Long,
    val count: Int,
)

data class HeatmapUiState(
    val counts: Map<Long, Int> = emptyMap(),
    /** 最近 53 周网格的第一天（周一） */
    val startDay: Long = 0L,
    val today: Long = 0L,
    val total: Long = 0L,
    val last30: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    /**
     * 各分类的「单个文件大小分布」直方图（热力图下方用）。
     *
     * 只保留统计结果（total + 每档 count），不再持有原始 List<Long>：
     * 4 万条 size 随每次重组挂在 State 上会白白占内存，而界面只需要形状和真实总数。
     */
    val sizeHistograms: Map<String, SizeHistogram> = emptyMap(),
    val isLoading: Boolean = true,
)

/**
 * 首页「保险库占用」长按进入的入库热力图。
 *
 * 数据来自 files.importedAt（v13 起有，老数据为 0 时退回 lastModified），
 * 按天聚合一次即可，4 万多行是几十毫秒级，不落任何中间表。
 */
@HiltViewModel
class VaultHeatmapViewModel
    @Inject
    constructor(
        private val fileDao: FileDao,
    ) : ViewModel() {
        private val _state = MutableStateFlow(HeatmapUiState())
        val state: StateFlow<HeatmapUiState> = _state.asStateFlow()

        /**
         * 大小分布里选中的「分类 + 档位」。存在 ViewModel 而不是 Composable 的 remember 里：
         * 点某一档会导航到文件库，热力页的 Composable 会被销毁；返回时仍是同一个返回栈条目，
         * ViewModel 还在，所以选中的分类和档位能保留下来。
         */
        private val _sizeSelection = MutableStateFlow<Pair<String, Int>?>(null)
        val sizeSelection: StateFlow<Pair<String, Int>?> = _sizeSelection.asStateFlow()

        fun setSizeSelection(selection: Pair<String, Int>?) {
            _sizeSelection.value = selection
        }

        init {
            load()
        }

        fun load() {
            viewModelScope.launch(Dispatchers.IO) {
                val rows = runCatching { fileDao.getImportCountsByDay() }.getOrDefault(emptyList())
                val counts = HashMap<Long, Int>(rows.size * 2)
                var total = 0L
                rows.forEach { row ->
                    val parts = row.day.split('-')
                    if (parts.size == 3) {
                        val epochDay =
                            epochDayOf(
                                parts[0].toIntOrNull() ?: 0,
                                parts[1].toIntOrNull() ?: 0,
                                parts[2].toIntOrNull() ?: 0,
                            )
                        counts[epochDay] = (counts[epochDay] ?: 0) + row.count
                        total += row.count
                    }
                }
                val today = todayEpochDay()
                val todayWeekday = weekdayOf(today)
                // 53 列，最后一列是本周；列首均为周一
                val startDay = today - todayWeekday - 52L * 7L
                val last30 = (today - 29L..today).sumOf { counts[it] ?: 0 }
                // 热力图下方的「单个文件大小分布」：按分类取全部 size，在 IO 线程算完直方图即丢，
                // 不抽样 —— 抽样会让界面上的「共 N 个文件」和各档计数全是样本数，不是真实数据。
                val sizeCategories =
                    listOf(
                        VaultManager.CAT_IMAGES,
                        VaultManager.CAT_VIDEOS,
                        VaultManager.CAT_BILI_VIDEOS,
                        VaultManager.CAT_AUDIOS,
                        VaultManager.CAT_TEXTS,
                        VaultManager.CAT_OTHERS,
                        VaultManager.CAT_PJM,
                    )
                val sizeHistograms =
                    sizeCategories.associateWith { category ->
                        // getSizesByCategory 会把该分类的 size 全读出来（4 万行都可能）。这里逐个分类
                        // 顺序处理，算完直方图后列表即可被回收，峰值内存只有单个分类的全量列表。
                        buildSizeHistogram(runCatching { fileDao.getSizesByCategory(category) }.getOrDefault(emptyList()))
                    }
                var current = 0
                var cursor = if ((counts[today] ?: 0) > 0) today else today - 1
                while ((counts[cursor] ?: 0) > 0) {
                    current++
                    cursor--
                }
                var longest = 0
                var run = 0
                var prev = Long.MIN_VALUE
                counts.keys.sorted().forEach { day ->
                    run = if (prev != Long.MIN_VALUE && day == prev + 1) run + 1 else 1
                    if (run > longest) longest = run
                    prev = day
                }
                _state.value =
                    HeatmapUiState(
                        counts = counts,
                        startDay = startDay,
                        today = today,
                        total = total,
                        last30 = last30,
                        currentStreak = current,
                        longestStreak = longest,
                        sizeHistograms = sizeHistograms,
                        isLoading = false,
                    )
            }
        }
    }

/** 公历日期 → 自 1970-01-01 起的天数（Howard Hinnant 的 days_from_civil） */
internal fun epochDayOf(
    year: Int,
    month: Int,
    day: Int,
): Long {
    var y = year
    var m = month
    if (m <= 2) {
        y -= 1
        m += 12
    }
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m - 3 + (if (m > 2) 0 else 12)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return (era * 146097 + doe - 719468).toLong()
}

/** 周一 = 0 … 周日 = 6 */
internal fun weekdayOf(epochDay: Long): Int = ((((epochDay + 3) % 7) + 7) % 7).toInt()

internal fun todayEpochDay(): Long {
    val cal = Calendar.getInstance()
    return epochDayOf(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
}

/** 供界面把 epochDay 还原成 "yyyy-MM-dd" */
internal fun formatEpochDay(epochDay: Long): String {
    // 反解：先粗算年，再逐月推进（范围只有几百年，循环最多 12 次）
    var y = (epochDay / 366.0).toInt() + 1970
    while (epochDayOf(y + 1, 1, 1) <= epochDay) y++
    while (epochDayOf(y, 1, 1) > epochDay) y--
    var m = 1
    while (m < 12 && epochDayOf(y, m + 1, 1) <= epochDay) m++
    val d = (epochDay - epochDayOf(y, m, 1)).toInt() + 1
    return String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
}
