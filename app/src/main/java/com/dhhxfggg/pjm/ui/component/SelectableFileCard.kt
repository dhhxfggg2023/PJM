package com.dhhxfggg.pjm.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.FileArchive
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Check
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager

/**
 * Returns an appropriate icon vector based on the file extension.
 */
private fun getIconForExtension(extension: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when (extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp" -> Lucide.Image
        "mp4", "avi", "mov", "mkv", "wmv", "flv", "webm" -> Lucide.Film
        "mp3", "wav", "flac", "aac" -> Lucide.Music
        "pdf", "doc", "docx", "txt" -> Lucide.FileText
        "zip", "rar", "7z", "pjm" -> Lucide.FileArchive
        else -> Lucide.File
    }
}

/**
 * A selectable file card component, typically used in batch operations.
 *
 * @param fileEntity Metadata of the file to display.
 * @param isSelected Whether the file is currently selected.
 * @param onClick Callback invoked when the card is clicked.
 * @param onLongPress Callback invoked when the card is long-pressed.
 * @param modifier Modifier to be applied to the card.
 * @param imageOnly If true, displays only the image/thumbnail without textual metadata.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SelectableFileCard(
    fileEntity: FileEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    imageOnly: Boolean = false 
) {
    val context = LocalContext.current
    val elevation by animateDpAsState(
        targetValue = if (isSelected) 4.dp else 1.dp, 
        label = "elevation"
    )
    val strokeWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.dp, 
        label = "stroke_width"
    )
    val file = remember(fileEntity.relativePath) { 
        VaultManager.getFileFromEntity(context, fileEntity) 
    }
    val icon = remember(fileEntity.extension) { getIconForExtension(fileEntity.extension) }

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

                if (fileEntity.isImage || FileUtils.isVideoFile(file.name)) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(file.absolutePath)
                            .decoderFactory(VideoFrameDecoder.Factory())
                            .crossfade(true)
                            .size(512) 
                            .diskCacheKey(FileUtils.getFileFingerprint(fileEntity))
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
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

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = FileUtils.formatFileTime(fileEntity.lastModified),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
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

                        if (fileEntity.isImage || FileUtils.isVideoFile(file.name)) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(file.absolutePath)
                                    .decoderFactory(VideoFrameDecoder.Factory())
                                    .crossfade(true)
                                    .size(256) 
                                    .diskCacheKey(FileUtils.getFileFingerprint(fileEntity))
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
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
