package com.dhhxfggg.pjm.ui.navigation

/**
 * 热力图点某一档进入文件库时的大小筛选路由（纯字符串拼装/解析，无 Android 依赖）。
 *
 * 为什么抽出来：末档没有上限，必须省略 sizeMax 参数；解析侧还得能把空串/非法数字
 * 归一成 null（「清除筛选」按钮是否出现就靠它）。这几条规则在 Compose 路由表里
 * 没法单测，提到纯函数后可以直接断言。
 */
object SizeFilterRoute {
    const val PREFIX = "file_viewer/"

    /** 生成带大小区间的文件库路由；末档（sizeMax == Long.MAX_VALUE）不传 sizeMax */
    fun build(
        encodedCategory: String,
        sizeMin: Long,
        sizeMax: Long,
    ): String {
        val base = PREFIX + encodedCategory + "?sizeMin=" + sizeMin
        return if (sizeMax >= Long.MAX_VALUE) base else base + "&sizeMax=" + sizeMax
    }

    /** 从路由里取出 sizeMin；缺失、空串、非法数字都归一成 null */
    fun parseSizeMin(route: String): Long? = parseParam(route, "sizeMin")

    /** 从路由里取出 sizeMax；缺失、空串、非法数字都归一成 null */
    fun parseSizeMax(route: String): Long? = parseParam(route, "sizeMax")

    private fun parseParam(
        route: String,
        name: String,
    ): Long? {
        val query = route.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            val key = part.substringBefore('=', "")
            if (key != name) continue
            return part.substringAfter('=', "").toLongOrNull()
        }
        return null
    }
}
