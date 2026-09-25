package com.dhhxfggg.pjm.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.*

/**
 * PJM 图标系统 —— 统一使用 Lucide（ISC 许可）。
 *
 * ## 为什么是 Lucide
 * 项目自 v1.9.x 起已在 14 个文件中直接使用 `Lucide.*`（约 66 处），
 * 但本文件早期用的是 **Material Symbols Rounded**，于是全应用同时存在两套图标风格：
 * 底部导航 / 主页分类卡 / 文件类型图标是 Material 的粗笔画，而设置页 / 弹窗 / 文件柜
 * 是 Lucide 的细线条 —— 同一张文件卡片上就能同时看到两种笔画。
 *
 * 现在全部收敛到 Lucide，**不再混用 Material Icons**。
 * 好处：零新增依赖（`com.composables:icons-lucide-cmp` 已是项目依赖）、
 * 描边风格统一（24 网格 / 2px / 圆头）、APK 体积不增加。
 *
 * ## 约定
 * 新增图标一律**走本接口**，不要在界面里直接写 `Lucide.XXX` ——
 * 这样将来换图标库只需要改这一个文件。
 */
interface IconPack {
    val home: ImageVector
    val discovery: ImageVector
    val settings: ImageVector

    val catPjm: ImageVector
    val catBiliVideos: ImageVector
    val catImages: ImageVector
    val catVideos: ImageVector
    val catAudios: ImageVector
    val catOthers: ImageVector

    // 动作
    val actionShare: ImageVector
    val actionDelete: ImageVector
    val actionSearch: ImageVector
    val actionRename: ImageVector
    val actionExport: ImageVector
    val actionImport: ImageVector
    val actionFilter: ImageVector
    val actionSort: ImageVector
    val actionSelectAll: ImageVector
    val actionClose: ImageVector
    val actionMore: ImageVector
    val actionCopy: ImageVector
    val actionRotate: ImageVector
    val actionOpen: ImageVector
    val actionChevron: ImageVector

    // 文件类型
    val fileImage: ImageVector
    val fileVideo: ImageVector
    val fileAudio: ImageVector
    val fileDoc: ImageVector
    val fileArchive: ImageVector
    val fileApk: ImageVector
    val fileGeneric: ImageVector

    // 设置项
    val setTheme: ImageVector
    val setContrast: ImageVector
    val setBackground: ImageVector
    val setPermission: ImageVector
    val setData: ImageVector
    val setBackup: ImageVector
    val setUpdate: ImageVector
    val setLog: ImageVector
    val setKey: ImageVector
    val setNetwork: ImageVector
    val setStorage: ImageVector
    val setLanguage: ImageVector
    val setNotice: ImageVector
    val setAbout: ImageVector

    // 状态
    val stateEmpty: ImageVector
    val stateSearchEmpty: ImageVector
    val stateWarning: ImageVector
    val stateSuccess: ImageVector
    val stateLoading: ImageVector
    val stateSparkle: ImageVector
}

/**
 * Lucide 图标包（唯一视觉标准）。
 *
 * 命名对照（旧 Material → 新 Lucide）：
 * ```
 * Icons.Rounded.Home         → Lucide.House
 * Icons.Rounded.Explore      → Lucide.Compass
 * Icons.Rounded.Settings     → Lucide.Settings
 * Icons.Rounded.Lock         → Lucide.Lock
 * Icons.Rounded.Tv           → Lucide.Tv
 * Icons.Rounded.PhotoLibrary → Lucide.Images
 * Icons.Rounded.PlayCircleFilled → Lucide.Clapperboard
 * Icons.Rounded.LibraryMusic → Lucide.Music
 * Icons.Rounded.Folder       → Lucide.Folder
 * Icons.Rounded.Share        → Lucide.Share2
 * Icons.Rounded.Delete       → Lucide.Trash2
 * Icons.Rounded.Image        → Lucide.Image
 * Icons.Rounded.MusicNote    → Lucide.FileAudio
 * Icons.Rounded.Description  → Lucide.FileText
 * Icons.Rounded.Archive      → Lucide.FileArchive
 * Icons.Rounded.Android      → Lucide.Package
 * Icons.AutoMirrored.Rounded.InsertDriveFile → Lucide.File
 * ```
 */
object LucideIconPack : IconPack {
    // ---- 主导航 ----
    override val home = Lucide.House
    override val discovery = Lucide.Compass
    override val settings = Lucide.Settings

    // ---- 分类 ----
    // 注：分类图标选择的是「语义」而非「容器」—— B站视频用场记板（内容创作）、
    // 视频分类用胶片（本地视频），两者视觉上可以区分开。
    //
    // 命名注意：本项目所用的 Lucide 快照里 **没有 file-video / file-audio**，
    // 且 filter 已更名为 funnel。以下用的是该版本真实存在的图标名（已编译验证）。
    override val catPjm = Lucide.Lock
    override val catBiliVideos = Lucide.Clapperboard
    override val catImages = Lucide.Images
    override val catVideos = Lucide.Film
    override val catAudios = Lucide.Music
    override val catOthers = Lucide.Folder

    // ---- 动作 ----
    override val actionShare = Lucide.Share2
    override val actionDelete = Lucide.Trash2
    override val actionSearch = Lucide.Search
    override val actionRename = Lucide.Pencil
    override val actionExport = Lucide.Download
    override val actionImport = Lucide.FolderInput
    override val actionFilter = Lucide.Funnel
    override val actionSort = Lucide.ArrowUpDown
    override val actionSelectAll = Lucide.SquareCheck
    override val actionClose = Lucide.X
    override val actionMore = Lucide.EllipsisVertical
    override val actionCopy = Lucide.Copy
    override val actionRotate = Lucide.RotateCw
    override val actionOpen = Lucide.FolderOpen
    override val actionChevron = Lucide.ChevronRight

    // ---- 文件类型 ----
    override val fileImage = Lucide.Image
    override val fileVideo = Lucide.Video
    override val fileAudio = Lucide.AudioWaveform
    override val fileDoc = Lucide.FileText
    override val fileArchive = Lucide.FileArchive
    override val fileApk = Lucide.Package
    override val fileGeneric = Lucide.File

    // ---- 设置项 ----
    override val setTheme = Lucide.Palette
    override val setContrast = Lucide.Contrast
    override val setBackground = Lucide.Image
    override val setPermission = Lucide.ShieldCheck
    override val setData = Lucide.Database
    override val setBackup = Lucide.CloudUpload
    override val setUpdate = Lucide.RefreshCw
    override val setLog = Lucide.Bug
    override val setKey = Lucide.KeyRound
    override val setNetwork = Lucide.Wifi
    override val setStorage = Lucide.HardDrive
    override val setLanguage = Lucide.Globe
    override val setNotice = Lucide.Bell
    override val setAbout = Lucide.Info

    // ---- 状态 ----
    override val stateEmpty = Lucide.Inbox
    override val stateSearchEmpty = Lucide.SearchX
    override val stateWarning = Lucide.TriangleAlert
    override val stateSuccess = Lucide.CircleCheck
    override val stateLoading = Lucide.LoaderCircle
    override val stateSparkle = Lucide.Sparkles
}

@Composable
fun rememberIconPack(): IconPack = LucideIconPack
