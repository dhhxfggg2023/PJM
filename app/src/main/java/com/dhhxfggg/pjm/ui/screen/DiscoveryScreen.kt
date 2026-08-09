package com.dhhxfggg.pjm.ui.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import com.dhhxfggg.pjm.ui.viewmodel.DiscoveryItem
import com.dhhxfggg.pjm.ui.viewmodel.DiscoveryMode
import com.dhhxfggg.pjm.ui.viewmodel.DiscoveryViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Screen that allows users to discover random media items from their vault.
 *
 * @param navController Navigation controller for navigating between screens.
 * @param bottomPadding Bottom padding to avoid overlap with navigation components.
 * @param isFullScreen Whether the screen is currently in full-screen mode.
 * @param onFullScreenChange Callback invoked when full-screen mode is toggled.
 * @param viewModel ViewModel providing discovery data and logic.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiscoveryScreen(
    navController: NavHostController,
    bottomPadding: Dp = 0.dp,
    isFullScreen: Boolean = false,
    onFullScreenChange: (Boolean) -> Unit = {},
    viewModel: DiscoveryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    
    var isInteractionLocked by remember { mutableStateOf(false) } 
    val pagerState = rememberPagerState(pageCount = { uiState.items.size })
    var showDeleteConfirm by remember { mutableStateOf<DiscoveryItem?>(null) }

    BackHandler(enabled = isFullScreen) { onFullScreenChange(false) }

    LaunchedEffect(isFullScreen) {
        activity?.let {
            val window = it.window
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (isFullScreen) {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(pagerState.currentPage, uiState.items.size) {
        isInteractionLocked = false 
        if (uiState.items.isNotEmpty() && pagerState.currentPage >= uiState.items.size - 2) {
            viewModel.loadMoreItems()
        }
    }

    val finalBottomPadding = if (isFullScreen) 0.dp else bottomPadding

    Box(modifier = Modifier
        .fillMaxSize()
        .background(if (isFullScreen) Color.Black else Color.Transparent)) {
        if (uiState.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
            }
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = !isInteractionLocked, 
                key = { index -> uiState.items.getOrNull(index)?.displayId ?: index }
            ) { page ->
                val item = uiState.items.getOrNull(page)
                if (item != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        DiscoveryItemRenderer(
                            item = item, 
                            isActive = pagerState.currentPage == page,
                            isScrolling = pagerState.isScrollInProgress,
                            isFullScreen = isFullScreen,
                            bottomPadding = finalBottomPadding,
                            onToggleFullScreen = { onFullScreenChange(!isFullScreen) },
                            onLockChange = { isInteractionLocked = it }
                        )
                        
                        if (!isFullScreen && uiState.mode == DiscoveryMode.IMAGES) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 16.dp)
                                    .offset(y = (-150).dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                DiscoveryActionIcon(Icons.Default.Share, "分享") { shareDiscoveryItem(context, item) }
                                DiscoveryActionIcon(Icons.Default.Delete, "删除", Color.Red.copy(alpha = 0.8f)) { showDeleteConfirm = item }
                            }
                        }
                    }
                }
            }
        }

        if (!isFullScreen) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                color = Color.Black.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                    DiscoveryModeTab("图片", uiState.mode == DiscoveryMode.IMAGES) { viewModel.setMode(DiscoveryMode.IMAGES) }
                    DiscoveryModeTab("视频", uiState.mode == DiscoveryMode.VIDEOS) { viewModel.setMode(DiscoveryMode.VIDEOS) }
                }
            }
        }
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要永久移除这项资源吗？") },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm?.let { viewModel.deleteFile(it.entity) }
                    showDeleteConfirm = null
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") } }
        )
    }
}

/**
 * Renders an individual discovery item, choosing between image and video display.
 */
@Composable
fun DiscoveryItemRenderer(
    item: DiscoveryItem, 
    isActive: Boolean, 
    isScrolling: Boolean, 
    isFullScreen: Boolean, 
    bottomPadding: Dp,
    onToggleFullScreen: () -> Unit,
    onLockChange: (Boolean) -> Unit
) {
    key(item.displayId) {
        when (item) {
            is DiscoveryItem.Image -> DiscoveryImageRenderer(item, onLockChange)
            is DiscoveryItem.Video -> DiscoveryVideoRenderer(item, isActive, isScrolling, isFullScreen, bottomPadding, onToggleFullScreen, onLockChange)
        }
    }
}

/**
 * Handles rendering and interactive gestures for image discovery items.
 */
@Composable
fun DiscoveryImageRenderer(item: DiscoveryItem.Image, onLockChange: (Boolean) -> Unit) {
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.displayId) {
                detectTapGestures(onDoubleTap = {
                    scope.launch {
                        if (scale.value > 1.05f) {
                            onLockChange(false)
                            launch { scale.animateTo(1f) }
                            launch { offsetX.animateTo(0f) }
                            launch { offsetY.animateTo(0f) }
                        } else {
                            onLockChange(true)
                            scale.animateTo(3f)
                        }
                    }
                })
            }
            .pointerInput(item.displayId) {
                awaitEachGesture {
                    var pan = Offset.Zero
                    var zoom = 1f
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop

                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                pan += panChange
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1 - zoom) * centroidSize
                                val panMotion = pan.getDistance()

                                if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }

                            if (pastTouchSlop) {
                                val isMultiTouch = event.changes.size > 1
                                val isZooming = abs(1f - zoomChange) > 0.01f
                                val isPanningWhenZoomed = scale.value > 1.05f

                                if (isMultiTouch || isZooming || isPanningWhenZoomed) {
                                    event.changes.forEach { it.consume() }
                                    scope.launch {
                                        val newScale = (scale.value * zoomChange).coerceIn(1f, 5f)
                                        scale.snapTo(newScale)
                                        val zoomed = newScale > 1.05f
                                        onLockChange(zoomed)
                                        if (zoomed) {
                                            offsetX.snapTo(offsetX.value + panChange.x)
                                            offsetY.snapTo(offsetY.value + panChange.y)
                                        } else if (newScale <= 1.01f) {
                                            offsetX.snapTo(0f)
                                            offsetY.snapTo(0f)
                                        }
                                    }
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val context = LocalContext.current
        val imageRequest = remember(item.file) {
            ImageRequest.Builder(context)
                .data(item.file)
                .size(Size.ORIGINAL)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .precision(Precision.EXACT)
                .allowHardware(true)
                .crossfade(true)
                .build()
        }
        
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value; scaleY = scale.value
                    translationX = offsetX.value; translationY = offsetY.value
                },
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * Handles rendering and video playback for video discovery items.
 */
@OptIn(UnstableApi::class)
@Composable
fun DiscoveryVideoRenderer(
    item: DiscoveryItem.Video, 
    isActive: Boolean, 
    isScrolling: Boolean, 
    isFullScreen: Boolean, 
    bottomPadding: Dp,
    onToggleFullScreen: () -> Unit,
    onLockChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember(item.displayId) { 
        ExoPlayer.Builder(context).build().apply { 
            repeatMode = Player.REPEAT_MODE_ONE 
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
        } 
    }

    var progress by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableLongStateOf(0L) }
    var currentTime by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var seekLabel by remember { mutableStateOf("") }

    DisposableEffect(item.displayId) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlaybackStateChanged(s: Int) { 
                if (s == Player.STATE_READY) duration = exoPlayer.duration 
            }
        }
        exoPlayer.addListener(listener)
        onDispose { 
            exoPlayer.removeListener(listener)
            exoPlayer.release() 
        }
    }

    LaunchedEffect(isActive, isScrolling) {
        if (isActive && !isScrolling) exoPlayer.play() else exoPlayer.pause()
    }

    LaunchedEffect(isActive) {
        while (isActive) {
            currentTime = exoPlayer.currentPosition
            if (duration > 0) progress = currentTime.toFloat() / duration
            delay(500)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply { 
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT 
                } 
            }, 
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .pointerInput(item.displayId) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }
                    )
                }
                .pointerInput(item.displayId) {
                    var accumulatedDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { 
                            onLockChange(true)
                            showControls = true
                            accumulatedDrag = 0f
                        },
                        onDragEnd = { onLockChange(false); seekLabel = "" },
                        onDragCancel = { onLockChange(false); seekLabel = "" },
                        onHorizontalDrag = { change, dragAmount ->
                            accumulatedDrag += dragAmount
                            val threshold = 100f 
                            if (abs(accumulatedDrag) >= threshold) {
                                val direction = if (accumulatedDrag > 0) 1 else -1
                                val seekStepMs = (duration * 0.05f).toLong().coerceAtLeast(1000L)
                                val target = (exoPlayer.currentPosition + direction * seekStepMs).coerceIn(0, duration)
                                exoPlayer.seekTo(target)
                                seekLabel = if (direction > 0) "快进 +5%" else "后退 -5%"
                                accumulatedDrag = 0f
                                change.consume()
                            }
                        }
                    )
                }
        )

        if (seekLabel.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 60.dp),
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(seekLabel, color = Color.White, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
            }
        }

        if (showControls && !isPlaying) {
            IconButton(
                onClick = { exoPlayer.play() },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(
                    Icons.Default.PlayCircleFilled,
                    null,
                    modifier = Modifier.size(80.dp),
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(bottom = bottomPadding + (if (isFullScreen) 16.dp else 48.dp))
            ) {
                Slider(
                    value = progress,
                    onValueChange = { 
                        progress = it
                        exoPlayer.seekTo((it * duration).toLong()) 
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(32.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${formatTime(currentTime)} / ${formatTime(duration)}", color = Color.White, fontSize = 13.sp)
                    
                    IconButton(
                        onClick = onToggleFullScreen,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, 
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Formats a duration in milliseconds to a mm:ss string.
 */
private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * A circular action icon for sharing or deleting items.
 */
@Composable
fun DiscoveryActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
            .size(48.dp)
    ) {
        Icon(icon, label, tint = tint)
    }
}

/**
 * A tab button for switching between discovery modes.
 */
@Composable
fun DiscoveryModeTab(text: String, isSelected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f)
        )
    }
}

/**
 * Shares a discovery item using a system share intent.
 */
private fun shareDiscoveryItem(context: Context, item: DiscoveryItem) {
    val file = item.file
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (item is DiscoveryItem.Image) "image/*" else "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享内容"))
}
