package com.dhhxfggg.pjm.domain.util

/**
 * PJM 保险库资产分类常量（纯逻辑，无 Android 依赖）。
 *
 * 分类键存于 FileEntity.category 与磁盘目录名；UI 显示名/图标由各 Screen 负责映射。
 */
object VaultCategories {
    const val CAT_PJM = "pjm"
    const val CAT_BILI_VIDEOS = "bili_videos"
    const val CAT_IMAGES = "images"
    const val CAT_VIDEOS = "videos"
    const val CAT_AUDIOS = "audios"
    const val CAT_OTHERS = "others"

    /** 全部分类（顺序即主界面/封面的展示顺序） */
    val CATEGORIES = listOf(CAT_PJM, CAT_IMAGES, CAT_VIDEOS, CAT_BILI_VIDEOS, CAT_AUDIOS, CAT_OTHERS)
}
