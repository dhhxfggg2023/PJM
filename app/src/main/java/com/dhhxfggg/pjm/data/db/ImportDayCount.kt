package com.dhhxfggg.pjm.data.db

/** 入库热力图的单日聚合结果（day 为本地时区的 yyyy-MM-dd） */
data class ImportDayCount(
    val day: String,
    val count: Int,
)
