package com.dhhxfggg.pjm.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.ui.theme.rememberIconPack

/**
 * Returns an appropriate icon vector based on the file extension.
 */
private fun getIconForExtension(
    extension: String,
    iconPack: com.dhhxfggg.pjm.ui.theme.IconPack,
): ImageVector =
    when (extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp" -> iconPack.fileImage
        "mp4", "avi", "mov", "mkv", "wmv", "flv", "webm" -> iconPack.fileVideo
        "mp3", "wav", "flac", "aac", "m4a" -> iconPack.fileAudio
        "pdf", "doc", "docx", "txt" -> iconPack.fileDoc
        "zip", "rar", "7z", "pjm" -> iconPack.fileArchive
        "apk" -> iconPack.fileApk
        else -> iconPack.fileGeneric
    }

/**
 * A card component representing a file in the vault.
 *
 * 相册网格（imageOnly）刻意不再套 [Card]/[Surface]：Material 卡片每一格都要额外画一层
 * 阴影 + 圆角裁剪，快速滑动时一屏十几格同时绘制，是掉帧的主要来源。网格里只保留
 * 「一层圆角底色 + 缩略图」，列表模式才用完整卡片。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(
    fileEntity: FileEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    showThumbnail: Boolean = true,
    imageOnly: Boolean = false,
    gridSpanCount: Int = 2,
    /** 右上角常驻菜单（分享/重命名/删除）；三个都为空时不显示 */
    onShare: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val iconPack = rememberIconPack()

    val icon = remember(fileEntity.extension, iconPack) { getIconForExtension(fileEntity.extension, iconPack) }

    if (imageOnly) {
        // 格子越小圆角越小：6 列时一格才 50dp 上下，用 12dp 圆角看着就是个圆
        val shape =
            RoundedCornerShape(
                when {
                    gridSpanCount >= 5 -> 4.dp
                    gridSpanCount >= 3 -> 8.dp
                    else -> 12.dp
                },
            )
        val selectionBorder =
            if (isSelected) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
            } else {
                Modifier
            }
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .clip(shape)
                    // 网格统一用主题实色底：能拿到封面就铺满，拿不到就露出下面同色底的图标，
                    // 不再让壁纸透上来把音频/文本格冲得半透明。
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .then(selectionBorder)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    // 缩略图/图标都把 contentDescription 设成 null，读屏逐格只会念「按钮」。
                    // 给根 Box 一个可理解的名字，选中态交给 selected 表达，不再靠勾选图标的语义。
                    .semantics {
                        contentDescription = FileUtils.normalizedDisplayName(fileEntity)
                        role = Role.Button
                        selected = isSelected
                    },
            contentAlignment = Alignment.Center,
        ) {
            // 非图片/视频没有缩略图，用类型图标兜底（图标一直有，缩略图加载后覆盖在上面）
            if (!fileEntity.isImage && !FileUtils.isVideoFile(fileEntity.name)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(if (gridSpanCount >= 6) 24.dp else 40.dp),
                )
            }
            if (showThumbnail) {
                FileThumbnail(
                    fileEntity = fileEntity,
                    size = thumbnailSizeFor(gridSpanCount),
                    crossfade = false, // 相册滚动时逐张淡入会持续触发动画，明显掉帧
                    // 必须裁剪填充：Fit 会在两侧/上下留出底色，看起来就是一圈浅灰色
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Lucide.CircleCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(24.dp),
                )
            }
            if (onShare != null || onRename != null || onDelete != null) {
                CardMoreMenu(
                    iconPack = iconPack,
                    onShare = onShare,
                    onRename = onRename,
                    onDelete = onDelete,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }
        }
        return
    }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (gridSpanCount >= 6) 0.dp else 1.dp),
        // 选中态只用「一圈描边」，不再叠加浅色底块 —— 之前是外层灰卡 + 内层浅色块两层视觉。
        border =
            if (isSelected) {
                androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.small),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }
                if (showThumbnail) {
                    FileThumbnail(
                        fileEntity = fileEntity,
                        size = 256,
                        crossfade = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector = Lucide.CircleCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 列表里必须能看见文件名：只给日期和大小根本认不出是哪个文件
                Text(
                    text = FileUtils.normalizedDisplayName(fileEntity),
                    // 列表模式的文件名要一眼能读：再放大 0.2 倍（16sp → 19sp）
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
                Text(
                    // 图片的标题本身就是入库时间，小字只放大小，别再把时间重复一遍
                    text =
                        if (fileEntity.isImage) {
                            FileUtils.formatFileSize(fileEntity.size)
                        } else {
                            FileUtils.formatFileTime(fileEntity.lastModified) + " · " + FileUtils.formatFileSize(fileEntity.size)
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isSelected) {
                Checkbox(checked = true, onCheckedChange = { onClick() })
            }
            if (onShare != null || onRename != null || onDelete != null) {
                CardMoreMenu(
                    iconPack = iconPack,
                    onShare = onShare,
                    onRename = onRename,
                    onDelete = onDelete,
                )
            }
        }
    }
}

/**
 * 卡片右上角的「⋮」常驻菜单。
 *
 * 分享/删除原来挂在长按上，跟"长按加速"打架；重命名也没有入口。
 * 现在统一收到这个菜单里，长按专心留给加速。
 */
@Composable
private fun CardMoreMenu(
    iconPack: com.dhhxfggg.pjm.ui.theme.IconPack,
    onShare: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        // 32dp 圆底只是视觉，触控区撑到 48dp；不然网格里这个小按钮点不准。
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = { expanded = true },
                modifier =
                    Modifier
                        .size(32.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            androidx.compose.foundation.shape.CircleShape,
                        ),
            ) {
                Icon(
                    imageVector = iconPack.actionMore,
                    contentDescription = stringResource(R.string.action_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onShare != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_share)) },
                    leadingIcon = { Icon(iconPack.actionShare, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onShare()
                    },
                )
            }
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    leadingIcon = { Icon(iconPack.actionRename, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onRename()
                    },
                )
            }
            if (onDelete != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(iconPack.actionDelete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        expanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}
