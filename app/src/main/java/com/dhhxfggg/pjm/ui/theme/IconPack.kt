package com.dhhxfggg.pjm.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircleFilled
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * PJM 萌系图标系统 —— 清爽二次元 (Kawaii Clean) 标准
 *
 * 采用 Material Symbols Rounded（圆润描边、Q 弹可爱），替代早期“极客细线”风格，
 * 契合“白底 + 蓝青 + 圆角”的手游初始界面视觉。
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
    
    val actionShare: ImageVector
    val actionDelete: ImageVector

    val fileImage: ImageVector
    val fileVideo: ImageVector
    val fileAudio: ImageVector
    val fileDoc: ImageVector
    val fileArchive: ImageVector
    val fileApk: ImageVector
    val fileGeneric: ImageVector
}

/**
 * 清爽二次元（唯一视觉标准）
 */
object KawaiiCleanIconPack : IconPack {
    override val home = Icons.Rounded.Home
    override val discovery = Icons.Rounded.Explore
    override val settings = Icons.Rounded.Settings
    
    override val catPjm = Icons.Rounded.Lock
    override val catBiliVideos = Icons.Rounded.Tv
    override val catImages = Icons.Rounded.PhotoLibrary
    override val catVideos = Icons.Rounded.PlayCircleFilled
    override val catAudios = Icons.Rounded.LibraryMusic
    override val catOthers = Icons.Rounded.Folder
    
    override val actionShare = Icons.Rounded.Share
    override val actionDelete = Icons.Rounded.Delete

    override val fileImage = Icons.Rounded.Image
    override val fileVideo = Icons.Rounded.PlayCircleFilled
    override val fileAudio = Icons.Rounded.MusicNote
    override val fileDoc = Icons.Rounded.Description
    override val fileArchive = Icons.Rounded.Archive
    override val fileApk = Icons.Rounded.Android
    override val fileGeneric = Icons.AutoMirrored.Rounded.InsertDriveFile
}

@Composable
fun rememberIconPack(): IconPack {
    return KawaiiCleanIconPack
}
