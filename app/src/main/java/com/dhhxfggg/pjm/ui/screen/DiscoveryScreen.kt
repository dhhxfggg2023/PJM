package com.dhhxfggg.pjm.ui.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Minimize
import com.composables.icons.lucide.Maximize
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.util.DiscoveryPlayerPool
import com.dhhxfggg.pjm.ui.component.PjmDeleteConfirmDialog
import com.dhhxfggg.pjm.ui.theme.rememberIconPack
import com.dhhxfggg.pjm.ui.viewmodel.DiscoveryItem
import com.dhhxfggg.pjm.ui.viewmodel.DiscoveryMode
import com.dhhxfggg.pjm.ui.viewmodel.DiscoveryViewModel
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Screen that allows users to discover random media items from their vault.
 */
@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun DiscoveryScreen(
    navController: NavHostController,
    bottomPadding: Dp = 0.dp,
    isFullScreen: Boolean = false,
    onFullScreenChange: (Boolean) -> Unit = {},
    viewModel: DiscoveryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val activity = context as? Activity
    
    val iconPack = rememberIconPack()
    
    val shareContentTitle = stringResource(R.string.chooser_title_share_content)

    var isInteractionLocked by remember { mutableStateOf(value = false) } 
    val pagerState = rememberPagerState(
        pageCount = { uiState.items.size },
    )
    var showDeleteConfirm by remember { mutableStateOf<DiscoveryItem?>(null) }
    
    // 发现页交互优化：长按显示操作按钮
    var showActions by remember { mutableStateOf(value = false) }
    
    LaunchedEffect(showActions) {
        if (showActions) {
            delay(3000.milliseconds)
            showActions = false
        }
    }

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
        if (uiState.items.isNotEmpty() && (pagerState.currentPage >= (uiState.items.size - 2))) {
            viewModel.loadMoreItems()
        }
    }

    val finalBottomPadding = if (isFullScreen) 0.dp else bottomPadding

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = if (isFullScreen) Color.Black else Color.Transparent)
    ) {
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
                            isActive = (pagerState.currentPage == page),
                            isScrolling = pagerState.isScrollInProgress,
                            isFullScreen = isFullScreen,
                            bottomPadding = finalBottomPadding,
                            onToggleFullScreen = { onFullScreenChange(!isFullScreen) },
                            onLockChange = { isInteractionLocked = it },
                            onLongPress = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                showActions = true
                            }
                        )
                        
                        AnimatedVisibility(
                            visible = (!isFullScreen && showActions),
                            enter = fadeIn() + slideInHorizontally { it },
                            exit = fadeOut() + slideOutHorizontally { it },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 16.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                DiscoveryActionIcon(iconPack.actionShare, stringResource(R.string.action_share)) { 
                                    showActions = false
                                    shareDiscoveryItem(context, item, shareContentTitle) 
                                }
                                DiscoveryActionIcon(iconPack.actionDelete, stringResource(R.string.action_delete), Color.Red.copy(alpha = 0.8f)) { 
                                    showActions = false
                                    showDeleteConfirm = item 
                                }
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
                    .padding(top = 16.dp)
                    .statusBarsPadding(), 
                color = Color.Black.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                    DiscoveryModeTab(stringResource(R.string.mode_bili_videos), uiState.mode == DiscoveryMode.BILI_VIDEOS) { viewModel.setMode(DiscoveryMode.BILI_VIDEOS) }
                    DiscoveryModeTab(stringResource(R.string.mode_images), uiState.mode == DiscoveryMode.IMAGES) { viewModel.setMode(DiscoveryMode.IMAGES) }
                    DiscoveryModeTab(stringResource(R.string.mode_videos), uiState.mode == DiscoveryMode.VIDEOS) { viewModel.setMode(DiscoveryMode.VIDEOS) }
                }
            }
        }
    }

    if (showDeleteConfirm != null) {
        val item = showDeleteConfirm ?: return
        // 核心修复：归一化删除确认弹窗
        PjmDeleteConfirmDialog(
            title = stringResource(R.string.dialog_delete_title),
            candidates = listOf(item.entity),
            message = stringResource(R.string.dialog_delete_msg_discovery),
            onDismiss = { showDeleteConfirm = null },
            onConfirm = {
                viewModel.deleteFile(item.entity)
                showDeleteConfirm = null
            }
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
    onLockChange: (Boolean) -> Unit,
    onLongPress: () -> Unit
) {
    key(item.displayId) {
        when (item) {
            is DiscoveryItem.Image -> DiscoveryImageRenderer(item, onLockChange, onLongPress)
            is DiscoveryItem.Video -> DiscoveryVideoRenderer(item, isActive, isScrolling, isFullScreen, bottomPadding, onToggleFullScreen, onLockChange, onLongPress)
        }
    }
}

/**
 * Handles rendering and interactive gestures for image discovery items.
 */
@Composable
fun DiscoveryImageRenderer(item: DiscoveryItem.Image, onLockChange: (Boolean) -> Unit, onLongPress: () -> Unit) {
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.displayId) {
                detectTapGestures(
                    onDoubleTap = {
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
                    },
                    onLongPress = { onLongPress() }
                )
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

                                if ((zoomMotion > touchSlop) || (panMotion > touchSlop)) {
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
        // 核心优化：按屏幕尺寸解码而非 Size.ORIGINAL（避免整张原始分辨率位图常驻内存 → OOM）。
        // 用屏幕像素 * 2 作为采样上限（支持两级缩放不糊），Coil 会自动按 inSampleSize 采样，
        // 既保留足够清晰度（可缩放到接近原图细节）又不让单张位图爆内存。
        val density = LocalDensity.current
        val decodeSize = remember(item.file, density) {
            val wPx = with(density) { maxWidth.toPx() }.toInt()
            val hPx = with(density) { maxHeight.toPx() }.toInt()
            Size(wPx * 2, hPx * 2)
        }
        val imageRequest = remember(item.file, decodeSize, density) {
            ImageRequest.Builder(context)
                .data(item.file)
                .size(decodeSize)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .precision(Precision.INEXACT)
                .allowHardware(enable = true)
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun DiscoveryVideoRenderer(
    item: DiscoveryItem.Video, 
    isActive: Boolean, 
    isScrolling: Boolean, 
    isFullScreen: Boolean, 
    bottomPadding: Dp,
    onToggleFullScreen: () -> Unit,
    onLockChange: (Boolean) -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    // 核心优化：从复用池借播放器（避免滑动时反复创建/销毁 MediaCodec 实例）
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    // 进度相关状态改进：
    //  - duration（低频，仅播放器 READY 时更新一次）留在本层，供拖拽 seek 计算用；
    //  - currentTime / progress（高频 500ms 轮询）移至子组件 VideoProgressBar，
    //    避免每 500ms 重组整个视频渲染器（含 AndroidView）→ 丢帧。
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var seekLabel by remember { mutableStateOf("") }

    // 核心优化：仅激活项借用播放器，非激活项归还（避免多实例并存抢音频焦点）
    DisposableEffect(item.displayId, isActive) {
        if (!isActive) {
            onDispose {}
        } else {
            val player = DiscoveryPlayerPool.acquire(context)
            exoPlayer = player
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
                override fun onPlaybackStateChanged(s: Int) { 
                    if (s == Player.STATE_READY) duration = player.duration 
                }
            }
            player.addListener(listener)
            onDispose { 
                player.removeListener(listener)
                DiscoveryPlayerPool.release(player)
                exoPlayer = null
            }
        }
    }

    LaunchedEffect(isActive, isScrolling) {
        if (isActive && !isScrolling) exoPlayer?.play() else exoPlayer?.pause()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply { 
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT 
                } 
            },
            update = { view ->
                // 核心优化：播放器动态绑定/解绑（非激活时显示缩略图，不绑定播放器）
                view.player = exoPlayer
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
                        onDoubleTap = { if (isPlaying) exoPlayer?.pause() else exoPlayer?.play() },
                        onLongPress = { onLongPress() }
                    )
                }
                .pointerInput(item.displayId) {
                    var dragTotalX = 0f
                    var startPosition = 0L
                    detectHorizontalDragGestures(
                        onDragStart = { 
                            onLockChange(true)
                            showControls = true
                            dragTotalX = 0f
                            startPosition = exoPlayer?.currentPosition ?: 0L
                        },
                        onDragEnd = { onLockChange(false); seekLabel = "" },
                        onDragCancel = { onLockChange(false); seekLabel = "" },
                        onHorizontalDrag = { change, dragAmount ->
                            dragTotalX += dragAmount
                            val screenWidth = size.width.toFloat()
                            val seekRatio = (dragTotalX / screenWidth) * 0.5f 
                            val seekOffsetMs = (duration * seekRatio).toLong()
                            val targetPosition = (startPosition + seekOffsetMs).coerceIn(0, duration)
                            
                            exoPlayer?.seekTo(targetPosition)
                            
                            val diffSec = (targetPosition - startPosition) / 1000
                            seekLabel = if (diffSec >= 0) "快进 +${diffSec}s" else "后退 ${diffSec}s"
                            
                            change.consume()
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
                onClick = { exoPlayer?.play() },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(
                    Lucide.Play,
                    null,
                    modifier = Modifier.size(80.dp),
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        /**
         * 进度条 + 时间文本独立子组件：
         * 500ms 轮询更新仅影响本组件，不波及播放器本体 / 手势层 → 显著减少重组丢帧。
         */
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            VideoProgressBar(
                exoPlayer = exoPlayer,
                bottomPadding = bottomPadding,
                isFullScreen = isFullScreen,
                onToggleFullScreen = onToggleFullScreen
            )
        }
    }
}

/**
 * 视频进度条 + 时间文本的独立子组件。
 * 单独持有高频（500ms）轮询的 currentTime / progress 状态，
 * 使进度更新仅重组本组件，不波及播放器本体（AndroidView）与手势层，减少丢帧。
 */
@Composable
private fun VideoProgressBar(
    exoPlayer: ExoPlayer?,
    bottomPadding: Dp,
    isFullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableLongStateOf(0L) }
    var currentTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(exoPlayer) {
        while (true) {
            val p = exoPlayer ?: break
            currentTime = p.currentPosition
            if (duration <= 0) duration = p.duration
            if (duration > 0) progress = currentTime.toFloat() / duration
            delay(500.milliseconds)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
            .padding(bottom = bottomPadding + (if (isFullScreen) 16.dp else 48.dp))
    ) {
        Slider(
            value = progress,
            onValueChange = {
                progress = it
                exoPlayer?.seekTo((it * duration).toLong())
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
                    imageVector = if (isFullScreen) Lucide.Minimize else Lucide.Maximize,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
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
private fun shareDiscoveryItem(context: Context, item: DiscoveryItem, chooserTitle: String) {
    val file = item.file
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (item is DiscoveryItem.Image) "image/*" else "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}
