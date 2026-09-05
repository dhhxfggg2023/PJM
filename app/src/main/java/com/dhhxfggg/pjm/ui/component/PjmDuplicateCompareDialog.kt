package com.dhhxfggg.pjm.ui.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.composables.icons.lucide.*
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.DuplicateGroup
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager

/**
 * PJM 重复内容对比确认弹窗（归一化 UI）。
 *
 * 核心特性：
 * 1. 每个重复组【并排展示组内图片】（每行最多两张，左右对比），
 *    让用户亲眼确认两张图是否真的相同/相似，再决定删除 —— 杜绝误删。
 * 2. 组内多张（≥3）时依次两两成行展示（第 1、2 张一行，第 3、4 张一行…），
 *    一行超过两张会看不清，故每行固定两张。
 * 3. 每张图独立 Checkbox：勾选 = 删除；默认勾选 [DuplicateGroup.recommendedDelete]
 *    （推荐保留的分辨率最高/最早导入的原图默认不勾选）。
 * 4. 顶部"已选 N / 总数 M" + 组间分隔标题（第 N 组 · M 张）。
 * 5. 确认按钮"删除选中 (N)"，未勾选任何文件时禁用。
 *
 * @param groups 查重分组结果（非空）
 * @param onDismiss 关闭弹窗
 * @param onConfirm 用户确认删除，回调参数为【勾选要删除】的文件列表
 */
@Composable
fun PjmDuplicateCompareDialog(
    groups: List<DuplicateGroup>,
    onDismiss: () -> Unit,
    onConfirm: (List<FileEntity>) -> Unit,
) {
    // 勾选状态：默认勾选各组推荐删除的文件
    val selected = remember(groups) {
        mutableStateListOf<FileEntity>().apply {
            groups.forEach { g -> g.recommendedDelete.forEach { path ->
                g.members.firstOrNull { it.relativePath == path }?.let { add(it) }
            } }
        }
    }
    val allMembers = remember(groups) { groups.flatMap { it.members } }
    val isAllSelected = selected.size == allMembers.size && allMembers.isNotEmpty()

    PjmAeroDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Lucide.Brush, null, tint = MaterialTheme.colorScheme.error) },
        title = stringResource(R.string.dialog_title_clean_duplicates_confirm),
        confirmButton = {
            Button(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.action_delete_selected, selected.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 已选统计 + 全选/取消全选
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.label_selected_count_total, selected.size, allMembers.size),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    if (isAllSelected) selected.clear()
                    else { selected.clear(); selected.addAll(allMembers) }
                }) {
                    Text(if (isAllSelected) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all))
                }
            }

            Text(
                text = stringResource(R.string.msg_duplicates_cleanup_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Spacer(Modifier.height(4.dp))

            // 分组列表：每组标题 + 每行两张对比图
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                groups.forEachIndexed { groupIndex, group ->
                    item(key = "header_$groupIndex") {
                        GroupHeader(
                            index = groupIndex + 1,
                            count = group.members.size,
                            group = group
                        )
                    }
                    // 每行两张：chunked(2)
                    group.members.chunked(2).forEachIndexed { rowIndex, rowItems ->
                        item(key = "group_${groupIndex}_row_$rowIndex") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowItems.forEach { entity ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        CompareImageCard(
                                            entity = entity,
                                            isChecked = selected.contains(entity),
                                            isRecommendedDelete = entity.relativePath in group.recommendedDelete,
                                            onToggle = {
                                                if (selected.contains(entity)) selected.remove(entity)
                                                else selected.add(entity)
                                            }
                                        )
                                    }
                                }
                                // 单张补空占位，保持对齐
                                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 可回收空间
            val totalSize = remember(groups) { allMembers.sumOf { it.size } }
            Text(
                text = stringResource(R.string.label_reclaimable_space, FileUtils.formatFileSize(totalSize)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 组标题：第 N 组 · M 张 · 推荐保留项说明 */
@Composable
private fun GroupHeader(index: Int, count: Int, group: DuplicateGroup) {
    val keepName = group.members.firstOrNull()?.name ?: ""
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.label_duplicate_group, index, count),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.label_recommended_keep, keepName),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
}

/**
 * 单张对比卡片：大缩略图 + 文件名 + 分辨率/大小 + 勾选框。
 * 点击卡片任意处切换勾选。被推荐保留的原图显示"推荐保留"角标。
 */
@Composable
private fun CompareImageCard(
    entity: FileEntity,
    isChecked: Boolean,
    isRecommendedDelete: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val file = remember(entity.relativePath) { VaultManager.getFileFromEntity(context, entity) }
    // 分辨率（只读图片头，成本极低）
    val resolution = remember(entity.relativePath) {
        if (file.exists()) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) "${opts.outWidth}×${opts.outHeight}" else null
            } catch (_: Exception) { null }
        } else null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onToggle)
            .padding(8.dp)
    ) {
        // 大缩略图（固定高度，等比裁剪，便于并排对比）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (file.exists()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(file.absolutePath).size(320, 240).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Lucide.Brush, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(6.dp))

        // 文件名
        Text(
            entity.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 元信息行：分辨率 · 大小
        Text(
            buildString {
                if (resolution != null) append(resolution)
                append("  ·  ")
                append(FileUtils.formatFileSize(entity.size))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(4.dp))

        // 勾选 + 推荐保留标签
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.error)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (isChecked) stringResource(R.string.action_delete_mark)
                       else if (!isRecommendedDelete) stringResource(R.string.label_recommended_keep_short)
                       else stringResource(R.string.action_keep),
                style = MaterialTheme.typography.labelSmall,
                color = if (isChecked) MaterialTheme.colorScheme.error
                        else if (!isRecommendedDelete) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            // 右侧空白（保持卡片内文字对齐）
        }
    }
}
