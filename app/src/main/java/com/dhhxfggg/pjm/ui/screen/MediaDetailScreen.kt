package com.dhhxfggg.pjm.ui.screen

import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.ui.component.rememberIsAppVisible
import com.dhhxfggg.pjm.ui.viewmodel.MediaDetailViewModel
import kotlin.time.Duration.Companion.seconds

/**
 * A full-screen media viewer screen supporting shared element transitions,
 * image zooming, and video playback.
 *
 * @param relativePath The relative path of the media file in the vault.
 * @param onBack Callback to navigate back.
 * @param sharedTransitionScope The scope for shared element animations.
 * @param animatedVisibilityScope The scope for visibility animations.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailScreen(
    relativePath: String,
    onBack: () -> Unit,
    viewModel: MediaDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val fileEntity by viewModel.fileEntity.collectAsState()

    LaunchedEffect(relativePath) {
        viewModel.loadFile(relativePath)
    }

    val file =
        remember(fileEntity) {
            fileEntity?.let { VaultManager.getFileFromEntity(context, it) }
        }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    fileEntity?.let {
                        // 显示规范化名称（PJM_入库时间.ext）；pjm 容器显示其规范原名
                        Text(FileUtils.normalizedDisplayName(it), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.5f)),
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (fileEntity != null && file != null) {
                val entity = fileEntity
                if (entity?.isImage == true) {
                    ImageViewer(
                        filePath = file.absolutePath,
                    )
                } else if (FileUtils.isVideoFile(file.name)) {
                    VideoViewer(
                        filePath = file.absolutePath,
                    )
                } else {
                    Text(stringResource(R.string.error_unsupported_preview), color = Color.White)
                }
            } else if (fileEntity == null) {
                // Check if we are still loading or if it failed
                var showEmptyState by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(3.seconds)
                    showEmptyState = true
                }
                if (showEmptyState) {
                    Text(stringResource(R.string.error_media_load_failed), color = Color.White)
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ImageViewer(filePath: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    AsyncImage(
        model =
            ImageRequest
                .Builder(LocalContext.current)
                .data(filePath)
                .build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ).pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offset += pan
                        } else {
                            offset = androidx.compose.ui.geometry.Offset.Zero
                        }
                    }
                },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoViewer(filePath: String) {
    val context = LocalContext.current
    // 核心修复：remember 必须以 filePath 为 key。
    // 同一组合槽位换视频（列表里点开另一个文件）时，原来不会重建播放器，
    // 也不会复位播放位置，看到的仍是上一个视频。
    val exoPlayer =
        remember(filePath) {
            ExoPlayer.Builder(context.applicationContext).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes
                        .Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                setMediaItem(MediaItem.fromUri(filePath))
                prepare()
                playWhenReady = true
            }
        }

    DisposableEffect(filePath) {
        onDispose {
            exoPlayer.release()
        }
    }

    // 核心修复：退到后台/熄屏时暂停，回到前台再继续（否则音频会一直在后台响）
    val isAppVisible = rememberIsAppVisible()
    LaunchedEffect(exoPlayer, isAppVisible) {
        exoPlayer.playWhenReady = isAppVisible
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
