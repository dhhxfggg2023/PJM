package com.dhhxfggg.pjm.data.model

/**
 * 定义应用程序设置的常量键和数据结构。
 */
object Settings {
    /** 主题设置键 (system, light, dark) */
    const val KEY_THEME = "theme"

    /** 是否启用 Android 12+ 壁纸动态取色（Material You）；关闭后使用内置海洋蓝莫兰迪配色 */
    const val KEY_DYNAMIC_COLOR = "dynamic_color"

    /** 是否启用自定义背景 */
    const val KEY_CUSTOM_BACKGROUND_ENABLED = "custom_background_enabled"

    /** 自定义背景的 URI */
    const val KEY_CUSTOM_BACKGROUND_URI = "custom_background_uri"

    /** 背景不透明度 (0.0 - 1.0) */
    const val KEY_BACKGROUND_OPACITY = "background_opacity"

    /** 全局 UI 缩放系数 (1.0 为默认) */
    const val KEY_GLOBAL_UI_SCALE = "global_ui_scale"

    /** 自定义字体 URI */
    const val KEY_CUSTOM_FONT_URI = "custom_font_uri"

    /** 文件查看模式 (grid, list) */
    const val KEY_FILE_VIEW_MODE = "file_view_mode"

    /** 网格布局列数 */
    const val KEY_GRID_SPAN_COUNT = "grid_span_count"

    /** 导出分卷大小 (单位: MB) */
    const val KEY_EXPORT_SPLIT_SIZE = "export_split_size"

    /** 存入后是否自动删除源文件 */
    const val KEY_AUTO_DELETE_ORIGINAL = "auto_delete_original"

    /** 是否启用压缩包自动提取 */
    const val KEY_ARCHIVE_AUTO_EXTRACTION = "archive_auto_extraction"

    /** Bili 下载根目录 URI */
    const val KEY_BILI_ROOT_URI = "bili_root_uri"

    /** Bili 导入后是否自动清理缓存 */
    const val KEY_BILI_AUTO_DELETE = "bili_auto_delete"

    /** Bili 已合并视频导入后是否删除源文件 */
    const val KEY_BILI_MERGED_AUTO_DELETE = "bili_merged_auto_delete"

    /**
     * 应用程序设置的数据类。
     */
    data class AppSettings(
        val theme: String = "system",
        val isDynamicColorEnabled: Boolean = true,
        val isCustomBackgroundEnabled: Boolean = false,
        val customBackgroundUri: String? = null,
        val backgroundOpacity: Float = 1.0f,
        val globalUiScale: Float = 1.0f,
        val fileViewMode: String = "grid",
        val gridSpanCount: Int = 2,
        val exportSplitSize: Int = 1024,
        val autoDeleteOriginal: Boolean = false,
        val isArchiveAutoExtractionEnabled: Boolean = true,
        val isMigrationDone: Boolean = false,
        val biliRootUri: String? = null,
        val biliAutoDelete: Boolean = false,
        val biliMergedAutoDelete: Boolean = false,
    )
}
