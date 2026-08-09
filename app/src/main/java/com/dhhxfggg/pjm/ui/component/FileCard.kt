package com.dhhxfggg.pjm.ui.component

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Returns an appropriate icon vector based on the file extension.
 */
private fun getIconForExtension(extension: String): ImageVector {
    return when (extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp" -> Icons.Default.Image
        "mp4", "avi", "mov", "mkv", "wmv", "flv", "webm" -> Icons.Default.Movie
        "mp3", "wav", "flac", "aac", "m4a" -> Icons.Default.AudioFile
        "pdf" -> Icons.Default.PictureAsPdf
        "doc", "docx", "txt" -> Icons.Default.Description
        "zip", "rar", "7z", "pjm" -> Icons.Default.FolderZip
        "apk" -> Icons.Default.Android
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

/**
 * A card component representing a file in the vault.
 * Supports thumbnail previews, selection states, and different display modes.
 *
 * @param fileEntity Metadata of the file to display.
 * @param onClick Callback invoked when the card is clicked.
 * @param modifier Modifier to be applied to the card.
 * @param isSelected Whether the file is currently selected.
 * @param onDelete Optional callback for a delete action.
 * @param onLongClick Optional callback for long-press interaction.
 * @param showThumbnail Whether to attempt loading a thumbnail preview.
 * @param imageOnly If true, displays only the image/thumbnail without textual metadata.
 * @param gridSpanCount Number of columns in the grid, used for thumbnail size optimization.
 * @param sharedTransitionScope Optional scope for shared element transitions.
 * @param animatedVisibilityScope Optional scope for animated visibility during transitions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(
    fileEntity: FileEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    showThumbnail: Boolean = true,
    imageOnly: Boolean = false,
    gridSpanCount: Int = 2,
    thumbnail: Bitmap? = null
) {
    val context = LocalContext.current
    val file = remember(fileEntity.relativePath) { 
        VaultManager.getFileFromEntity(context, fileEntity) 
    }

    val thumbnailSize = remember(gridSpanCount) {
        when {
            gridSpanCount >= 6 -> 180
            gridSpanCount >= 4 -> 300
            else -> 512
        }
    }

    val icon = remember(fileEntity.extension) { getIconForExtension(fileEntity.extension) }

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

                thumbnail?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(), 
                        contentDescription = null, 
                        modifier = Modifier.fillMaxSize(), 
                        contentScale = ContentScale.Crop
                    )
                } ?: run {
                    if (showThumbnail && file.exists() && (fileEntity.isImage || FileUtils.isVideoFile(file.name))) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(file.absolutePath)
                                .decoderFactory(VideoFrameDecoder.Factory())
                                .crossfade(gridSpanCount <= 3)
                                .size(thumbnailSize, thumbnailSize)
                                .diskCacheKey(FileUtils.getFileFingerprint(fileEntity))
                                .memoryCachePolicy(coil3.request.CachePolicy.ENABLED)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                
                if (isSelected) {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.2f)))
                    Icon(
                        imageVector = Icons.Default.CheckCircle, 
                        contentDescription = null, 
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(24.dp)
                    )
                }

                if (gridSpanCount <= 3) {
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
                            modifier = Modifier.align(Alignment.Center),
                            maxLines = 1
                        )
                    }
                }
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
                    thumbnail?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(), 
                            contentDescription = null, 
                            modifier = Modifier.fillMaxSize(), 
                            contentScale = ContentScale.Crop
                        )
                    } ?: run {
                        if (showThumbnail && file.exists() && (fileEntity.isImage || FileUtils.isVideoFile(file.name))) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(file.absolutePath)
                                    .decoderFactory(VideoFrameDecoder.Factory())
                                    .size(256, 256)
                                    .diskCacheKey(FileUtils.getFileFingerprint(fileEntity))
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    if (isSelected) {
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f)))
                        Icon(
                            imageVector = Icons.Default.CheckCircle, 
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
                } else if (onDelete != null) {
                    IconButton(onClick = onDelete) { 
                        Icon(
                            imageVector = Icons.Default.Close, 
                            contentDescription = null, 
                            modifier = Modifier.size(20.dp)
                        ) 
                    }
                }
            }
        }
    }
}
