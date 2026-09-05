package com.dhhxfggg.pjm.ui.component

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.CircleCheck
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.ui.theme.rememberIconPack
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Returns an appropriate icon vector based on the file extension.
 */
private fun getIconForExtension(extension: String, iconPack: com.dhhxfggg.pjm.ui.theme.IconPack): ImageVector {
    return when (extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp" -> iconPack.fileImage
        "mp4", "avi", "mov", "mkv", "wmv", "flv", "webm" -> iconPack.fileVideo
        "mp3", "wav", "flac", "aac", "m4a" -> iconPack.fileAudio
        "pdf", "doc", "docx", "txt" -> iconPack.fileDoc
        "zip", "rar", "7z", "pjm" -> iconPack.fileArchive
        "apk" -> iconPack.fileApk
        else -> iconPack.fileGeneric
    }
}

/**
 * A card component representing a file in the vault.
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
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val iconPack = rememberIconPack()

    val icon = remember(fileEntity.extension, iconPack) { getIconForExtension(fileEntity.extension, iconPack) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                } else {
                    Modifier
                }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (gridSpanCount >= 6) 0.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        if (imageOnly) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer, 
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon, 
                            contentDescription = null, 
                            tint = MaterialTheme.colorScheme.onSecondaryContainer, 
                            modifier = Modifier.size(if (gridSpanCount >= 6) 24.dp else 48.dp)
                        )
                    }
                }

                if (showThumbnail) {
                    FileThumbnail(
                        fileEntity = fileEntity,
                        size = thumbnailSizeFor(gridSpanCount),
                        crossfade = gridSpanCount <= 3,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                if (isSelected) {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.2f)))
                    Icon(
                        imageVector = Lucide.CircleCheck, 
                        contentDescription = null, 
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(24.dp)
                    )
                }

                // 核心修复：根据用户要求，网格模式（imageOnly）下彻底移除所有文字覆盖层（包括时间/名字）
            }
        } else {
            Row(
                modifier = Modifier.padding(12.dp), 
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center, 
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.small)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer, 
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = icon, 
                            contentDescription = null, 
                            tint = MaterialTheme.colorScheme.onSecondaryContainer, 
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    if (showThumbnail) {
                        FileThumbnail(
                            fileEntity = fileEntity,
                            size = 256,
                            crossfade = false,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (isSelected) {
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f)))
                        Icon(
                            imageVector = Lucide.CircleCheck, 
                            contentDescription = null, 
                            tint = MaterialTheme.colorScheme.primary, 
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = FileUtils.formatFileTime(fileEntity.lastModified), 
                        style = MaterialTheme.typography.titleMedium, 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis, 
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified
                    )
                    Text(
                        text = FileUtils.formatFileSize(fileEntity.size), 
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isSelected) {
                    Checkbox(checked = true, onCheckedChange = { onClick() })
                }
            }
        }
    }
}
