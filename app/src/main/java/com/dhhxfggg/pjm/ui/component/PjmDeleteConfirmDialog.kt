package com.dhhxfggg.pjm.ui.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquareCheck
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.ThumbnailCache
import com.dhhxfggg.pjm.domain.util.VaultManager

/**
 * PJM 统一删除确认弹窗（归一化 UI）。
 *
 * 核心特性：
 * 1. 磨砂玻璃风格（复用 [PjmAeroDialog] 容器），与全 App 弹窗统一。
 * 2. 待删除文件列表【可勾选】—— 默认全选，用户可取消勾选以保留某些文件，杜绝误删。
 * 3. 顶部显示"已选 N / 总数 M"，支持一键全选/取消全选。
 * 4. 每个条目展示缩略图 + 文件名 + 大小，便于确认内容。
 * 5. 确认按钮为"删除选中 (N)"，未勾选任何文件时禁用。
 *
 * @param title 弹窗标题
 * @param candidates 待删除候选文件（必须非空）
 * @param onDismiss 关闭弹窗
 * @param onConfirm 用户确认删除，回调参数为【用户勾选】的文件列表
 * @param icon 可选头部图标
 * @param message 可选说明文字（显示在列表上方）
 * @param confirmText 自定义确认按钮文案（默认 "删除选中 (N)"）
 */
@Composable
fun PjmDeleteConfirmDialog(
    title: String,
    candidates: List<FileEntity>,
    onDismiss: () -> Unit,
    onConfirm: (List<FileEntity>) -> Unit,
    icon: (@Composable () -> Unit)? = null,
    message: String? = null,
    confirmText: String? = null,
) {
    // 勾选状态：默认全选
    val selected = remember(candidates) { mutableStateListOf<FileEntity>().apply { addAll(candidates) } }
    val isAllSelected = selected.size == candidates.size && candidates.isNotEmpty()

    PjmAeroDialog(
        onDismissRequest = onDismiss,
        icon = icon,
        title = title,
        confirmButton = {
            Button(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(confirmText ?: stringResource(R.string.action_delete_selected, selected.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 已选统计 + 全选/取消全选
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.label_selected_count_total, selected.size, candidates.size),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    if (isAllSelected) {
                        selected.clear()
                    } else {
                        selected.clear()
                        selected.addAll(candidates)
                    }
                }) {
                    Icon(
                        if (isAllSelected) Lucide.SquareCheck else Lucide.Square,
                        null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isAllSelected) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all))
                }
            }

            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Spacer(Modifier.height(4.dp))

            // 可勾选文件列表
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(candidates, key = { it.relativePath }) { entity ->
                    DeletableFileRow(
                        entity = entity,
                        isChecked = selected.contains(entity),
                        onToggle = {
                            if (selected.contains(entity)) {
                                selected.remove(entity)
                            } else {
                                selected.add(entity)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 可回收空间
            val size = remember(candidates) { candidates.sumOf { it.size } }
            Text(
                text = stringResource(R.string.label_reclaimable_space, FileUtils.formatFileSize(size)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 可勾选的文件行：缩略图 + 文件名 + 大小 + Checkbox。
 * 点击整行可切换勾选状态。
 */
@Composable
private fun DeletableFileRow(
    entity: FileEntity,
    isChecked: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val file = remember(entity.relativePath) { VaultManager.getFileFromEntity(context, entity) }
    val cachedThumb =
        remember(entity.relativePath) {
            if (FileUtils.isVideoFile(entity.name)) ThumbnailCache.getThumbnailFile(context, entity) else null
        }
    // 图片显示分辨率（原图 vs 缩略图直观可辨；只读图片头，成本极低）
    val resolution =
        remember(entity.relativePath) {
            if (entity.isImage && file.exists()) {
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) "${opts.outWidth}×${opts.outHeight}" else null
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 缩略图 48dp
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            when {
                entity.isImage && file.exists() ->
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(context)
                                .data(file.absolutePath)
                                .size(96, 96)
                                .crossfade(true)
                                .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                FileUtils.isVideoFile(entity.name) && cachedThumb != null ->
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(context)
                                .data(cachedThumb.absolutePath)
                                .size(96, 96)
                                .crossfade(true)
                                .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                FileUtils.isVideoFile(entity.name) && file.exists() ->
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(context)
                                .data(file.absolutePath)
                                .decoderFactory(VideoFrameDecoder.Factory())
                                .size(96, 96)
                                .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                else ->
                    Icon(
                        Lucide.File,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // 文件名统一为规范化显示名（PJM_入库时间.ext），与文件柜/媒体详情一致；pjm 容器显示其规范原名
            Text(
                FileUtils.normalizedDisplayName(entity),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(FileUtils.formatFileSize(entity.size))
                    if (resolution != null) append("  ·  $resolution")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle() },
            colors =
                CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.error,
                ),
        )
    }
}
