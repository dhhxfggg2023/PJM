package com.dhhxfggg.pjm.ui.screen

import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
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
    viewModel: MediaDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val fileEntity by viewModel.fileEntity.collectAsState()
    
    LaunchedEffect(relativePath) {
        viewModel.loadFile(relativePath)
    }

    val file = remember(fileEntity) {
        fileEntity?.let { VaultManager.getFileFromEntity(context, it) }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { 
                    fileEntity?.let { 
                        Text(it.name, color = Color.White, style = MaterialTheme.typography.titleMedium) 
                    } 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.5f))
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (fileEntity != null && file != null) {
                if (fileEntity!!.isImage) {
                    ImageViewer(
                        filePath = file.absolutePath
                    )
                } else if (FileUtils.isVideoFile(file.name)) {
                    VideoViewer(
                        filePath = file.absolutePath
                    )
                } else {
                    Text("不支持预览此类型文件", color = Color.White)
                }
            } else if (fileEntity == null) {
                // Check if we are still loading or if it failed
                var showEmptyState by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(3.seconds)
                    showEmptyState = true
                }
                if (showEmptyState) {
                    Text("无法加载媒体内容", color = Color.White)
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ImageViewer(
    filePath: String
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(filePath)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offset += pan
                    } else {
                        offset = androidx.compose.ui.geometry.Offset.Zero
                    }
                }
            }
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoViewer(
    filePath: String
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            setMediaItem(MediaItem.fromUri(filePath))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
