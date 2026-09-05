package com.dhhxfggg.pjm.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Check
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
 * A selectable file card component, typically used in batch operations.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SelectableFileCard(
    fileEntity: FileEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    imageOnly: Boolean = false,
    gridSpanCount: Int = 2,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val iconPack = rememberIconPack()

    val elevation by animateDpAsState(
        targetValue = if (isSelected) 4.dp else 1.dp, 
        label = "elevation"
    )
    val strokeWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.dp, 
        label = "stroke_width"
    )
    val icon = remember(fileEntity.extension, iconPack) { getIconForExtension(fileEntity.extension, iconPack) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = if (isSelected) {
            BorderStroke(strokeWidth, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        if (imageOnly) {
            Box(modifier = Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                FileThumbnail(
                    fileEntity = fileEntity,
                    size = thumbnailSizeFor(gridSpanCount),
                    crossfade = gridSpanCount <= 3,
                    modifier = Modifier.fillMaxSize()
                )
                
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(24.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Lucide.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // 核心修复：根据用户要求，网格模式下不再显示任何文字（时间或名字）
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .align(Alignment.CenterStart),
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
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        FileThumbnail(
                            fileEntity = fileEntity,
                            size = 256,
                            crossfade = false,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = FileUtils.formatFileTime(fileEntity.lastModified),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = FileUtils.formatFileSize(fileEntity.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(24.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Lucide.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
