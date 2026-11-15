package com.dhhxfggg.pjm.ui.screen

import com.dhhxfggg.pjm.ui.component.PjmSlider
import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import com.composables.icons.lucide.*
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.service.AudioPlaybackService
import com.dhhxfggg.pjm.domain.util.AudioSettings
import com.dhhxfggg.pjm.domain.util.EpubExtractor
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.ui.component.PlayerProgressBar
import com.dhhxfggg.pjm.ui.component.VideoSurface
import com.dhhxfggg.pjm.ui.component.ZoomableVaultImage
import com.dhhxfggg.pjm.ui.component.rememberAudioInfo
import com.dhhxfggg.pjm.ui.component.rememberIsAppVisible
import com.dhhxfggg.pjm.ui.theme.rememberIconPack
import com.dhhxfggg.pjm.ui.viewmodel.MediaDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File
import java.util.Locale

/**
 * 统一的文件查看器：图片 / 视频 / 音频 / 文本。
 *
 * 设计目标（按使用反馈走）：
 * 1. 手势一致：缩放、平移、亮度/音量、拖动进度都在同一套坐标里，不再各页各写一套；
 * 2. 所有渲染都在应用内完成，不再把文件甩给系统应用（除了确实不支持的类型）；
 * 3. 图片按屏幕需要的尺寸解码，避免一次性吃进原图导致卡顿/OOM。
 *
 * 不支持的类型只给一句说明 + 一个「用系统应用打开」，不再假装能预览。
 */
@OptIn(UnstableApi::class)
@Composable
fun MediaDetailScreen(
    relativePath: String,
    onBack: () -> Unit,
    viewModel: MediaDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val fileEntity by viewModel.fileEntity.collectAsState()
    val categoryFiles by viewModel.categoryFiles.collectAsState()

    LaunchedEffect(relativePath) {
        viewModel.loadFile(relativePath)
    }

    val entity = fileEntity
    val file =
        remember(entity) {
            entity?.let { VaultManager.getFileFromEntity(context, it) }
        }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            entity == null || file == null -> LoadingHint()
            entity.isImage -> {
                // 列表没加载完就先别建 pager：否则初始页固定在第 0 张，点开任何图都会显示错的那张
                if (categoryFiles.isEmpty()) {
                    ImageListLoading()
                } else {
                    val startIndex =
                        categoryFiles.indexOfFirst { it.relativePath == entity.relativePath }.coerceAtLeast(0)
                    ImagePager(files = categoryFiles, startIndex = startIndex, onBack = onBack)
                }
            }
            FileUtils.isVideoFile(entity.name) -> {
                val videos =
                    if (categoryFiles.isEmpty()) {
                        listOf(entity)
                    } else {
                        categoryFiles.filter { FileUtils.isVideoFile(it.name) }
                    }
                if (videos.size <= 1 && videos.firstOrNull()?.relativePath == entity.relativePath) {
                    VideoPager(listOf(entity), 0, onBack)
                } else {
                    val startIndex = videos.indexOfFirst { it.relativePath == entity.relativePath }.coerceAtLeast(0)
                    VideoPager(videos, startIndex, onBack)
                }
            }
            FileUtils.isAudioFile(entity.name) -> {
                val audios =
                    if (categoryFiles.isEmpty()) {
                        listOf(entity)
                    } else {
                        categoryFiles.filter { FileUtils.isAudioFile(it.name) }
                    }
                val startIndex = audios.indexOfFirst { it.relativePath == entity.relativePath }.coerceAtLeast(0)
                AudioViewer(
                    file = file,
                    title = FileUtils.normalizedDisplayName(entity),
                    queue = audios,
                    startIndex = startIndex,
                    onBack = onBack,
                )
            }
            FileUtils.isTextFile(entity.name) -> TextViewer(file = file, title = FileUtils.normalizedDisplayName(entity), onBack = onBack)
            else -> UnsupportedViewer(file = file, onBack = onBack)
        }
    }
}

@Composable
private fun ImageListLoading() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun LoadingHint() {
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(2500)
        failed = true
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failed) {
            Text(stringResource(R.string.error_media_load_failed), color = Color.White)
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

// ---------------- 图片 ----------------

/**
 * 图片查看：纵向 pager（上滑下一张、下滑上一张，按库里的顺序连续看）。
 *
 * 缩放/平移交给开源库 me.saket.telephoto:zoomable：双指缩放、双击缩放、惯性滑动、
 * 边界回弹以及与 pager 的嵌套滚动（放大时拖图、缩到 1x 才翻页）都由它处理。
 */
@Composable
private fun ImagePager(
    files: List<FileEntity>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val pagerState =
        rememberPagerState(
            initialPage = startIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0)),
        ) { files.size }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            val entity = files.getOrNull(page) ?: return@VerticalPager
            val file = remember(entity.relativePath) { VaultManager.getFileFromEntity(context, entity) }
            ZoomableImagePage(file)
        }
        val currentTitle =
            files
                .getOrNull(pagerState.currentPage)
                ?.let { FileUtils.normalizedDisplayName(it) }
                .orEmpty()
        ViewerTopBar(title = currentTitle, onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/**
 * 单张图片：直接用 Telephoto 官方的 ZoomableAsyncImage（Coil3 版）。
 *
 * 之前是自己拿 `Modifier.zoomable` 配 `Image(contentScale = Fit)`，两边各算各的内容边界：
 * Telephoto 以为内容只有 scaledInside 的大小，Image 又按 Fit 画了一遍，缩放/平移的边界就对不上，
 * 一拖就把画面顶出屏幕。官方组件内部把 contentScale / contentLocation 统一交给 zoomableState，
 * 整张图始终完整可见，最大倍率按解码分辨率给。
 */
@Composable
private fun ZoomableImagePage(file: File) {
    // 缩放/边界/双击全部交给共用组件，库内查看器与发现页行为一致
    ZoomableVaultImage(file = file, modifier = Modifier.fillMaxSize())
}

// ---------------- 视频 ----------------

/**
 * 视频查看：纵向 pager（上滑下一个、下滑上一个），页面内横向拖动拖进度。
 * 控制条只放播放/暂停 + 进度 + 时间，上下滑动交给 pager，互不抢手势。
 */
@Composable
private fun VideoPager(
    files: List<FileEntity>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val pagerState =
        rememberPagerState(
            initialPage = startIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0)),
        ) { files.size }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 0,
        ) { page ->
            val entity = files.getOrNull(page) ?: return@VerticalPager
            val file = remember(entity.relativePath) { VaultManager.getFileFromEntity(context, entity) }
            VideoPage(file = file, isActive = pagerState.settledPage == page)
        }
        val title =
            files
                .getOrNull(pagerState.currentPage)
                ?.let { FileUtils.normalizedDisplayName(it) }
                .orEmpty()
        ViewerTopBar(title = title, onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    file: File,
    isActive: Boolean,
) {
    val context = LocalContext.current
    val player =
        remember(file.absolutePath) {
            ExoPlayer.Builder(context.applicationContext).build().apply {
                setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                volume = AudioSettings.appVolume(context)
                prepare()
                playWhenReady = false
            }
        }
    DisposableEffect(file.absolutePath) {
        onDispose { player.release() }
    }
    val isAppVisible = rememberIsAppVisible()
    LaunchedEffect(isActive, isAppVisible) {
        player.playWhenReady = isActive && isAppVisible
        if (!isActive) player.pause()
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // 进入播放页不自动弹控制条：画面干净，需要时点一下再出现
    var showControls by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }
    var lastScrubSeekAt by remember { mutableLongStateOf(0L) }
    // 双击跳转的一次性提示（"+10s" / "-10s"），700ms 后自动消失
    var seekHint by remember { mutableStateOf<String?>(null) }
    var videoError by remember { mutableStateOf<String?>(null) }

    // 长按 = 加速（整个画面统一）：按住 2x，按住的同时左右滑动调整倍速（0.5x~8x）。
    // 左半边不再有「快速倒退」——那个手势没有实用价值，还容易误触。
    var turboSpeed by remember { mutableFloatStateOf(0f) }
    var wasPlayingBeforeTurbo by remember { mutableStateOf(false) }
    val turboScope = rememberCoroutineScope()

    fun stopTurbo() {
        player.setPlaybackSpeed(1f)
        turboSpeed = 0f
        // 用 playWhenReady（用户是否想让它在播）而不是 isPlaying（此刻是否真的在播）：
        // seek / 换速的瞬间 playbackState 可能短暂离开 READY，isPlaying 会瞬间变 false，
        // 于是松手时把正在播放的视频停掉 —— 这就是「快进之后自动暂停」的根因。
        player.playWhenReady = wasPlayingBeforeTurbo
    }

    fun startTurbo() {
        wasPlayingBeforeTurbo = player.playWhenReady
        turboSpeed = 2f
        player.setPlaybackSpeed(2f)
    }

    fun adjustTurbo(deltaX: Float) {
        if (turboSpeed <= 0f) return
        // 往右拖倍数变大、往左拖变小：方向跟着手走才直觉
        turboSpeed = (turboSpeed + deltaX / 220f).coerceIn(0.5f, 8f)
        player.setPlaybackSpeed(turboSpeed)
    }

    // 全屏 / 竖屏由用户自己选：全屏 = 横屏 + 收起系统栏；竖屏 = 保持正常方向和系统栏。
    // 默认竖屏，想全屏再点一下，不再一进播放页就强行全屏。
    var fullscreen by remember { mutableStateOf(false) }
    DisposableEffect(fullscreen) {
        val activity = context as? android.app.Activity
        val window = activity?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            delay(200)
        }
    }
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(2600)
            showControls = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VideoSurface(player = player, modifier = Modifier.fillMaxSize())
        // 手势层：纵向拖动不消费，留给 pager 换视频；横向拖动消费，用来拖进度
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(durationMs) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = doubleTap@{ offset ->
                                // 按左右三分区：左 1/3 后退 10 秒、右 1/3 前进 10 秒，
                                // 中间 1/3 不跳（避免误触），且要求总时长 > 15 秒、目标位置有效
                                val width = size.width.toFloat()
                                val position = player.currentPosition.coerceAtLeast(0L)
                                if (offset.x >= width / 3f && offset.x <= width * 2f / 3f) {
                                    // 中间双击 = 播放/暂停（最常见的手势，之前被吞了）
                                    // 双击只做播放/暂停，不弹控制条（一快进就蹦出按钮和进度条很烦）
                                    if (player.isPlaying) player.pause() else player.play()
                                    return@doubleTap
                                }
                                val target =
                                    when {
                                        durationMs <= 15_000L -> position
                                        offset.x < width / 3f -> (position - 10_000L).coerceAtLeast(0L)
                                        else -> (position + 10_000L).coerceAtMost(durationMs)
                                    }
                                if (target != position) {
                                    player.seekTo(target)
                                    seekHint = if (target > position) "+10 秒" else "-10 秒"
                                    turboScope.launch {
                                        delay(700)
                                        seekHint = null
                                    }
                                }
                                // 双击只做跳转：不弹进度条、不弹中间按钮，画面保持干净
                            },
                        )
                    }.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var active = false
                            val holdJob =
                                turboScope.launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    // 长按任意位置都是加速；几秒钟的短视频没必要（一下子就播完了）
                                    if (durationMs >= 5_000L) {
                                        active = true
                                        startTurbo()
                                    }
                                }
                            var lastX = down.position.x
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (active) {
                                    adjustTurbo(change.position.x - lastX)
                                    lastX = change.position.x
                                    change.consume()
                                }
                            }
                            holdJob.cancel()
                            if (active) stopTurbo()
                        }
                    }.pointerInput(durationMs) {
                        if (durationMs <= 0L) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = {
                                if (turboSpeed > 0f) return@detectHorizontalDragGestures
                                scrubbing = true
                                scrubMs = player.currentPosition.coerceAtLeast(0L)
                            },
                            onDragEnd = {
                                if (!scrubbing) return@detectHorizontalDragGestures
                                player.seekTo(scrubMs)
                                scrubbing = false
                            },
                            onDragCancel = { scrubbing = false },
                        ) { change, dragAmount ->
                            if (turboSpeed > 0f || !scrubbing) return@detectHorizontalDragGestures
                            change.consume()
                            // 横向拖动灵敏度调低（全屏宽 = 时长的 35%），避免一拖就飞过头
                            val deltaMs = (dragAmount / size.width * durationMs * 0.35f).toLong()
                            scrubMs = (scrubMs + deltaMs).coerceIn(0L, durationMs)
                            // 画面跟着手指走：节流 seek（80ms 一次），边拖边看到拖到了哪一帧
                            val now = System.currentTimeMillis()
                            if (now - lastScrubSeekAt > 80L) {
                                lastScrubSeekAt = now
                                player.seekTo(scrubMs)
                            }
                        }
                    },
        )
        // 双击跳转的一次性提示（给个即时反馈）
        seekHint?.let { hint ->
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(hint, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        // 长按加速时的倍速角标
        if (turboSpeed > 0f) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 72.dp, end = 16.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = turboSpeed.toString().take(3) + "x",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        // 拖动进度：只显示一个时间提示，不唤起整条控制栏
        if (scrubbing) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = formatDuration(scrubMs) + " / " + formatDuration(durationMs),
                    color = Color.White,
                )
            }
        }
        if (turboSpeed > 0f) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 88.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = String.format("%.1fx", turboSpeed),
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }
        }
        if (showControls) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            showControls = true
                        },
                        modifier =
                            // 中间按钮改成实心白圆 + 深色图标：原来半透明黑圆压在画面上，
                            // 遇到暗场景几乎看不见按钮边界。
                            Modifier
                                .size(54.dp)
                                .background(Color.White.copy(alpha = 0.92f), CircleShape),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Lucide.Pause else Lucide.Play,
                            contentDescription = null,
                            tint = Color.Black.copy(alpha = 0.8f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val shownMs = if (scrubbing) scrubMs else positionMs
                    PlayerProgressBar(
                        progress = if (durationMs > 0L) shownMs.toFloat() / durationMs else 0f,
                        onScrub = {
                            scrubbing = true
                            scrubMs = (it * durationMs).toLong()
                        },
                        onSeek = {
                            scrubMs = (it * durationMs).toLong()
                            player.seekTo(scrubMs)
                            scrubbing = false
                        },
                        activeColor = Color.White,
                        inactiveColor = Color.White.copy(alpha = 0.3f),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDuration(shownMs), color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(formatDuration(durationMs), color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        // 全屏 / 竖屏切换：想躺着看就全屏，想竖着刷就保持竖屏
                        IconButton(
                            onClick = {
                                fullscreen = !fullscreen
                                showControls = true
                            },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                imageVector = if (fullscreen) Lucide.Minimize else Lucide.Maximize,
                                contentDescription = if (fullscreen) "切换竖屏" else "切换全屏",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 音频 ----------------

/**
 * 音频播放器（助眠/日常听歌）。
 *
 * 播放本体仍在后台的 MediaSessionService 里（锁屏、通知栏、退后台都继续），
 * 这里只是控制端：封面、进度拖动、上一首/下一首、播放模式、倍速、队列、定时关闭。
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AudioViewer(
    file: File,
    title: String,
    queue: List<FileEntity>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentIndex by remember { mutableIntStateOf(startIndex) }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var sleepLeftMs by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatOne by remember { mutableStateOf(false) }
    // 封面点一下 = 播放列表；定时支持「播完 N 首」
    var showPlaylist by remember { mutableStateOf(false) }
    var sleepSongs by remember { mutableIntStateOf(0) }
    var playedSinceSleep by remember { mutableIntStateOf(0) }
    var showLyrics by remember { mutableStateOf(false) }

    // 整个音频分类就是播放队列：上一首/下一首/随机都能用，不用用户自己挑
    val queueFiles = remember(queue) { queue.map { VaultManager.getFileFromEntity(context, it) } }
    val start = startIndex.coerceIn(0, (queueFiles.size - 1).coerceAtLeast(0))
    val currentFile = queueFiles.getOrNull(currentIndex) ?: file
    val info = rememberAudioInfo(currentFile)
    // 歌词：优先内嵌（ID3 USLT 等）。库里的文件都是 UUID 名，旁边不会有同名 .lrc
    val lyrics by
        androidx.compose.runtime.produceState(emptyList<Pair<Long, String>>(), currentFile) {
            value =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        // 内嵌 ID3 的 USLT 没有系统 API 可读；这里读同目录同名的 .lrc（自己放进库里的歌词）
                        val lrc = java.io.File(currentFile.parentFile, currentFile.nameWithoutExtension + ".lrc")
                        if (lrc.isFile) parseLrc(lrc.readText()) else emptyList()
                    }.getOrDefault(emptyList())
                }
        }

    DisposableEffect(Unit) {
        val token =
            SessionToken(
                context,
                ComponentName(context, AudioPlaybackService::class.java),
            )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }.getOrNull()?.let { c ->
                    controller = c
                    val items =
                        if (queueFiles.isEmpty()) {
                            listOf(MediaItem.fromUri(file.toURI().toString()))
                        } else {
                            queueFiles.map { MediaItem.fromUri(it.toURI().toString()) }
                        }
                    c.setMediaItems(items, start, 0L)
                    c.prepare()
                    c.play()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { MediaController.releaseFuture(future) }
    }

    // 进度/状态轮询：400ms 一次，只更新这几个标量，不碰 AndroidView
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            isPlaying = c.isPlaying
            durationMs = c.duration.coerceAtLeast(0L)
            if (!dragging) positionMs = c.currentPosition.coerceAtLeast(0L)
            currentIndex = c.currentMediaItemIndex.coerceAtLeast(0)
            shuffle = c.shuffleModeEnabled
            repeatOne = c.repeatMode == Player.REPEAT_MODE_ONE
            playbackSpeed = c.playbackParameters.speed
            delay(400)
        }
    }

    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes <= 0) {
            sleepLeftMs = 0L
            return@LaunchedEffect
        }
        sleepLeftMs = sleepMinutes * 60_000L
        while (sleepLeftMs > 0L) {
            delay(1000)
            sleepLeftMs -= 1000
        }
        controller?.pause()
        sleepMinutes = 0
    }

    // 睡眠定时（按首数）：只在「自动切到下一首」时计数，手动切歌不算
    DisposableEffect(controller) {
        val c = controller
        if (c == null) return@DisposableEffect onDispose { }
        val listener =
            object : Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    if (sleepSongs > 0 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        playedSinceSleep += 1
                        if (playedSinceSleep >= sleepSongs) {
                            c.pause()
                            sleepSongs = 0
                            playedSinceSleep = 0
                        }
                    }
                }
            }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    val iconPack = rememberIconPack()
    val backdrop = info?.artwork
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 封面做整页背景：高斯模糊（API 31+）+ 纵向压暗，Poweramp 那种感觉
        if (backdrop != null) {
            Image(
                bitmap = backdrop.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.55f,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Modifier.blur(56.dp)
                            } else {
                                Modifier
                            },
                        ),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.45f),
                                    Color.Black.copy(alpha = 0.75f),
                                    Color.Black.copy(alpha = 0.94f),
                                ),
                            ),
                        ),
            )
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(top = 88.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 封面：内嵌封面优先，没有就用类型图标
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF17171A))
                        // 点封面 = 打开播放列表（不额外占按钮位）
                        .pointerInput(Unit) { detectTapGestures { showPlaylist = true } }
                        // 上下滑切上/下一首：图片、视频、音频三个查看器统一"上下翻页"
                        .pointerInput(currentIndex) {
                            var dy = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dy = 0f },
                                onDragEnd = {
                                    val threshold = 48.dp.toPx()
                                    if (dy < -threshold) controller?.seekToNextMediaItem()
                                    if (dy > threshold) controller?.seekToPreviousMediaItem()
                                    dy = 0f
                                },
                                onDragCancel = { dy = 0f },
                            ) { _, amount -> dy += amount }
                        },
                contentAlignment = Alignment.Center,
            ) {
                val artwork = info?.artwork
                if (artwork != null) {
                    Image(
                        bitmap = artwork.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = iconPack.fileAudio,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(120.dp),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = info?.title?.takeIf { it.isNotBlank() } ?: title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text =
                    listOfNotNull(info?.artist, info?.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { "PJM 音频" },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxWidth()) {
                PlayerProgressBar(
                    progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    onScrub = { fraction ->
                        dragging = true
                        positionMs = (fraction * durationMs).toLong()
                    },
                    onSeek = {
                        positionMs = (it * durationMs).toLong()
                        controller?.seekTo(positionMs)
                        dragging = false
                    },
                    activeColor = MaterialTheme.colorScheme.primary,
                )
                // 拖动时在上方冒一个时间气泡，松手才真正跳转
                if (dragging) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                        shadowElevation = 3.dp,
                    ) {
                        Text(
                            text = formatDuration(positionMs),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(positionMs), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                Text(formatDuration(durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                // 图标按钮光看图认不出来，补一行小字
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = {
                            val c = controller ?: return@IconButton
                            c.shuffleModeEnabled = !c.shuffleModeEnabled
                        },
                    ) {
                        Icon(
                            imageVector = Lucide.Shuffle,
                            contentDescription = "随机播放",
                            tint = if (shuffle) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                        )
                    }
                    Text("随机", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                }
                IconButton(onClick = { controller?.seekToPreviousMediaItem() }) {
                    Icon(Lucide.SkipBack, contentDescription = "上一首", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                FilledIconButton(
                    onClick = { if (isPlaying) controller?.pause() else controller?.play() },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Lucide.Pause else Lucide.Play,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                    Icon(Lucide.SkipForward, contentDescription = "下一首", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = {
                            val c = controller ?: return@IconButton
                            c.repeatMode = if (c.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_ONE
                        },
                    ) {
                        Icon(
                            imageVector = if (repeatOne) Lucide.Repeat1 else Lucide.Repeat,
                            contentDescription = if (repeatOne) "单曲循环" else "列表循环",
                            tint = if (repeatOne) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                        )
                    }
                    Text(if (repeatOne) "单曲" else "循环", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (lyrics.isNotEmpty()) {
                    TextButton(onClick = { showLyrics = true }) {
                        Text("歌词", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                }
                // 倍速：弹菜单一次看到全部档位，点哪个是哪个（原来只能一个个轮换）
                Box {
                    var speedMenu by remember { mutableStateOf(false) }
                    TextButton(onClick = { speedMenu = true }) {
                        Text(String.format("%.2fx", playbackSpeed), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { speed ->
                            val current = kotlin.math.abs(speed - playbackSpeed) < 0.01f
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = String.format("%.2fx", speed),
                                        color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    )
                                },
                                onClick = {
                                    controller?.setPlaybackSpeed(speed)
                                    playbackSpeed = speed
                                    speedMenu = false
                                },
                            )
                        }
                    }
                }
                // 定时关闭：一次列出全部时长，点哪个是哪个
                Box {
                    var sleepMenu by remember { mutableStateOf(false) }
                    TextButton(onClick = { sleepMenu = true }) {
                        Text(
                            text =
                                when {
                                    sleepSongs > 0 -> "定时 " + sleepSongs + " 首"
                                    sleepLeftMs > 0L -> "定时 " + formatDuration(sleepLeftMs)
                                    else -> "定时关闭"
                                },
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                        )
                    }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        listOf(0, 15, 30, 45, 60, 90).forEach { minutes ->
                            val current = sleepMinutes == minutes && sleepSongs == 0
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (minutes == 0) "关闭定时" else minutes.toString() + " 分钟",
                                        color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    )
                                },
                                onClick = {
                                    sleepMinutes = minutes
                                    sleepSongs = 0
                                    playedSinceSleep = 0
                                    sleepMenu = false
                                },
                            )
                        }
                        listOf(1, 3, 5, 10).forEach { songs ->
                            val current = sleepSongs == songs
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "播完 " + songs + " 首后停",
                                        color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    )
                                },
                                onClick = {
                                    sleepMinutes = 0
                                    sleepSongs = songs
                                    playedSinceSleep = 0
                                    sleepMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }
        ViewerTopBar(title = "", onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
        // 播放列表：整个音频分类都在队列里，点任意一首就切过去
        if (showPlaylist) {
            ModalBottomSheet(onDismissRequest = { showPlaylist = false }) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "播放列表 · 共 " + queue.size + " 首",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                        items(queue.size) { index ->
                            val entity = queue[index]
                            val isCurrent = index == currentIndex
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showPlaylist = false
                                            controller?.seekTo(index, 0L)
                                            controller?.play()
                                        }.background(
                                            if (isCurrent) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            } else {
                                                Color.Transparent
                                            },
                                        ).padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = (index + 1).toString(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.width(32.dp),
                                )
                                Text(
                                    text = FileUtils.normalizedDisplayName(entity),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isCurrent) {
                                    Icon(
                                        imageVector = Lucide.Play,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 歌词：内嵌歌词（LRC 格式）逐行显示，当前行高亮，点一句跳过去
        if (showLyrics) {
            ModalBottomSheet(onDismissRequest = { showLyrics = false }) {
                val listState =
                    androidx.compose.foundation.lazy
                        .rememberLazyListState()
                val activeIndex = lyrics.indexOfLast { it.first <= positionMs }
                LaunchedEffect(activeIndex) {
                    if (activeIndex > 0) {
                        runCatching { listState.animateScrollToItem(activeIndex) }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(bottom = 24.dp),
                ) {
                    items(lyrics.size) { index ->
                        val (startMs, text) = lyrics[index]
                        val active = index == activeIndex
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { controller?.seekTo(startMs) }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** LRC 歌词解析：[mm:ss.xx] / [mm:ss:xx] 都可，同一行多个时间标签按每个时间各算一行 */
private val LRC_TAG = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

private fun parseLrc(raw: String): List<Pair<Long, String>> {
    val out = mutableListOf<Pair<Long, String>>()
    raw.lineSequence().forEach { line ->
        val matches = LRC_TAG.findAll(line).toList()
        if (matches.isEmpty()) return@forEach
        val text = line.replace(LRC_TAG, "").trim()
        if (text.isEmpty()) return@forEach
        matches.forEach { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            val seconds = match.groupValues[2].toLongOrNull() ?: 0L
            val frac = match.groupValues[3]
            val millis =
                when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLongOrNull()?.times(100) ?: 0L
                    2 -> frac.toLongOrNull()?.times(10) ?: 0L
                    else -> frac.toLongOrNull() ?: 0L
                }
            out.add((minutes * 60_000L + seconds * 1000L + millis) to text)
        }
    }
    return out.sortedBy { it.first }
}

// ---------------- 文本 ----------------

/** 阅读器配色方案 */
private data class ReaderTheme(
    val name: String,
    val background: Long,
    val foreground: Long,
)

// 常用阅读配色：夜间、纸黄、护眼、纯白、深蓝、高对比
private val READER_THEMES =
    listOf(
        ReaderTheme("夜间", 0xFF0D0D0F, 0xFFE7E2D6),
        ReaderTheme("纸黄", 0xFFF6EFE3, 0xFF3B332A),
        ReaderTheme("护眼", 0xFFCCE8CF, 0xFF20301F),
        ReaderTheme("纯白", 0xFFFFFFFF, 0xFF1D1D1F),
        ReaderTheme("深蓝", 0xFF14202E, 0xFFC9D6E3),
        ReaderTheme("高对比", 0xFF000000, 0xFFFFFFFF),
    )

/** 常见网文章节标题（第 X 章/节/回/卷… 或 Chapter N，以及卷首语） */
private val CHAPTER_PATTERN =
    Regex(
        // 只认「第 X 章/节/回…」开头、后面可以跟冒号/空格/间隔号再接标题的行；
        // 原来用 .* 吞掉整行，正文里以「第十一章」开头的句子也会被当成章节，
        // 于是目录里出现几十个字的长标题、章节顺序看着像乱的。
        "^\\s*(第[0-9零一二三四五六七八九十百千两]{1,10}[章节回卷集部篇](\\s*[:：、.·—\\-]\\s*.{0,30})?|Chapter\\s+[0-9]{1,4}(\\s*[:：.\\-]\\s*.{0,30})?|序章|楔子|前言|后记)\\s*$",
        RegexOption.IGNORE_CASE,
    )

/**
 * 文本阅读器（分页小说模式，参考主流开源阅读器）：章节识别、目录跳转、三套配色、翻页记忆。
 *
 * 排版仍是逐段量高铺页（这一点没有现成库能替：txt 没有版式信息），
 * 交互（翻页、点击、目录、字号、配色）对齐常见阅读器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextViewer(
    file: File,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val prefs = remember { context.getSharedPreferences("pjm_reader", Context.MODE_PRIVATE) }
    val positionKey = remember(file.absolutePath) { "page_" + file.absolutePath.hashCode() }

    var rawText by remember { mutableStateOf<String?>(null) }
    // 实际可用高度（px）：由 pager 测量得到，分页就按这个真值算，不再靠屏幕尺寸猜
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    // EPUB 自带目录：段落序号 → 章节标题
    var epubChapters by remember(file.absolutePath) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var pages by remember { mutableStateOf<List<List<String>>?>(null) }
    // 整本还没排完时给个明确提示：不然用户只看到页码在涨，以为卡住了
    var layoutDone by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var fontSize by remember { mutableFloatStateOf(prefs.getFloat("font_size", 17f)) }
    var lineSpacing by remember { mutableFloatStateOf(prefs.getFloat("line_spacing", 1.8f)) }
    var marginDp by remember { mutableIntStateOf(prefs.getInt("margin_dp", 18)) }
    var themeIndex by remember { mutableIntStateOf(prefs.getInt("theme_index", 0)) }
    var showChrome by remember { mutableStateOf(true) }
    var showChapters by remember { mutableStateOf(false) }
    // 排版/配色等设置全部收进独立浮窗，工具条只留导航与进度
    var showSettings by remember { mutableStateOf(false) }
    val focusRequester =
        remember {
            androidx.compose.ui.focus
                .FocusRequester()
        }
    val bookmarkKey = "bm_" + file.absolutePath.hashCode()
    var bookmarks by remember(file.absolutePath) {
        mutableStateOf(
            prefs
                .getStringSet(bookmarkKey, emptySet())!!
                .map { it.toIntOrNull() }
                .filterNotNull()
                .toSet(),
        )
    }

    fun toggleBookmark(page: Int) {
        bookmarks = if (page in bookmarks) bookmarks - page else bookmarks + page
        prefs.edit().putStringSet(bookmarkKey, bookmarks.map { it.toString() }.toSet()).apply()
    }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(0) { (pages?.size ?: 1).coerceAtLeast(1) }
    val theme = READER_THEMES[themeIndex.coerceIn(0, READER_THEMES.size - 1)]
    // 状态栏高度（dp）：页面上下留白要和分页时算的一致
    val topInsetDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LaunchedEffect(file.absolutePath) {
        val isEpub = file.extension.equals("epub", ignoreCase = true)
        rawText =
            withContext(Dispatchers.IO) {
                if (isEpub) {
                    val book = runCatching { EpubExtractor.extract(file) }.getOrNull()
                    epubChapters = book?.chapters ?: emptyMap()
                    book?.text
                } else {
                    runCatching { decodeText(readLimitedBytes(file, 3L * 1024 * 1024)) }.getOrNull()
                }
            }
    }

    LaunchedEffect(rawText, epubChapters, fontSize, lineSpacing, marginDp, configuration.screenWidthDp, viewportHeightPx, topInsetDp) {
        val body = rawText ?: return@LaunchedEffect
        // 还没测量到可用高度就先不排：否则会按 0 高度算出满屏一页
        if (viewportHeightPx <= 0) return@LaunchedEffect
        pages = null
        layoutDone = false
        val paragraphs =
            body
                .lineSequence()
                .flatMap { line ->
                    if (line.length <= 1000) sequenceOf(line) else line.chunked(1000).asSequence()
                }.toList()
        val style =
            TextStyle(
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineSpacing).sp,
                fontFamily = FontFamily.Serif,
                color = Color(theme.foreground),
                // 关掉字体额外内边距：否则每行上下各多几像素，行高越大偏差累加越多，
                // 分页按「行数 × 行高」算，实际渲染更高，文字就会被顶到页面外。
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            )
        val pageWidth = with(density) { (configuration.screenWidthDp.dp - (marginDp * 2).dp).toPx() }
        // 正文可用高度 = pager 实测高度 − 状态栏 − 上下 padding（8 + 28）− 2dp 余量
        // 用实测值而不是屏幕尺寸推算：差几 dp 就会表现为「底部空一块」或「最后一行被切掉」
        val pageHeight =
            (viewportHeightPx.toFloat() - with(density) { topInsetDp.toPx() } - with(density) { (8 + 28).dp.toPx() } - with(density) { 2.dp.toPx() })
                .coerceAtLeast(1f)
        // 必须和实际渲染的 padding(bottom = 4.dp) 一致，否则每段多算 2dp，段落一多就溢出
        val gap = with(density) { 4.dp.toPx() }
        // 量行数改用 android.text.StaticLayout：和 Compose 用的是同一套 Minikin 断行，
        // 但可以在后台线程跑、不受「每帧只排几毫秒」的限制。行高用 style 里定死的 lineHeight。
        val lineHeightPx = with(density) { (fontSize * lineSpacing).sp.toPx() }
        val measureWidth = pageWidth.toInt().coerceAtLeast(1)

        // 分页缓存：同一本书 + 同一套排版参数（字号/行距/边距/屏幕尺寸）直接复用上次算好的页边界，
        // 再打开就是瞬间出页，不用重新量上万段。缓存在 cacheDir，"清除缓存"会一并清掉。
        val cacheFile =
            readerCacheFile(
                context,
                readerCacheKey(
                    file,
                    fontSize,
                    lineSpacing,
                    marginDp,
                    configuration.screenWidthDp,
                    (viewportHeightPx - topInsetDp.value.toInt()).toInt(),
                ),
            )
        readPageCache(cacheFile, paragraphs.size)?.let { cache ->
            val cachedPages = ArrayList<List<String>>(cache.starts.size)
            cache.starts.forEachIndexed { i, start ->
                val end = if (i + 1 < cache.starts.size) cache.starts[i + 1] else paragraphs.size
                cachedPages.add(paragraphs.subList(start.coerceIn(0, paragraphs.size), end.coerceIn(0, paragraphs.size)))
            }
            if (cachedPages.isEmpty()) cachedPages.add(emptyList())
            pages = cachedPages
            chapters = cache.chapters.map { (paragraphIndex, title) -> title to cache.pageOf(paragraphIndex) }
            val savedPage = prefs.getInt(positionKey, 0)
            val savedRatio = prefs.getFloat(positionKey + "_ratio", -1f)
            val resumePage =
                when {
                    savedPage in cachedPages.indices -> savedPage
                    savedRatio in 0f..1f -> (savedRatio * (cachedPages.size - 1).coerceAtLeast(1)).toInt()
                    else -> 0
                }
            if (resumePage in cachedPages.indices) pagerState.scrollToPage(resumePage)
            layoutDone = true
            return@LaunchedEffect
        }

        val result = mutableListOf<List<String>>()
        val chapterEntries = mutableListOf<Pair<String, Int>>()
        val chapterParagraphs = mutableListOf<Pair<Int, String>>()
        val pageStarts = mutableListOf(0)
        var current = mutableListOf<String>()
        var used = 0f
        val saved = prefs.getInt(positionKey, 0)

        // 量行是整个排版里最贵的一步：每段一次 StaticLayout，一本大书就是几万次。
        // 段落高度只取决于「这一段 + 页面宽度」，彼此独立 —— 所以按核数切片并行量，
        // 算完再顺序铺页；主线程只负责贴结果，不再一段一段等。
        val heights = FloatArray(paragraphs.size)
        suspend fun measureSlice(
            from: Int,
            to: Int,
        ) {
            // Paint 不是线程安全的：每条协程自己一份
            val paint =
                TextPaint().apply {
                    typeface = Typeface.SERIF
                    textSize = with(density) { fontSize.sp.toPx() }
                    isAntiAlias = true
                }
            for (index in from until to) {
                val paragraph = paragraphs[index]
                heights[index] =
                    if (paragraph.isEmpty()) {
                        gap * 1.5f
                    } else {
                        val lines =
                            StaticLayout
                                .Builder
                                .obtain(paragraph, 0, paragraph.length, paint, measureWidth)
                                .setIncludePad(false)
                                .setLineSpacing(0f, 1f)
                                // 和 Compose 侧一致的简单断行；不做连字符/高质量断行，省掉多余开销
                                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
                                .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
                                .build()
                                .lineCount
                        lines * lineHeightPx + gap
                    }
            }
        }

        suspend fun publishChunk() {
            withContext(Dispatchers.Main) {
                pages = result.toList()
                chapters = chapterEntries.toList()
            }
        }

        val sliceCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        var cursor = 0
        var chunk = 120 // 第一块小一点：点开就能看到字
        while (cursor < paragraphs.size) {
            val end = minOf(cursor + chunk, paragraphs.size)
            val step = ((end - cursor) + sliceCount - 1) / sliceCount
            coroutineScope {
                (0 until sliceCount)
                    .mapNotNull { k ->
                        val from = cursor + k * step
                        if (from >= end) null else async(Dispatchers.Default) { measureSlice(from, minOf(from + step, end)) }
                    }.awaitAll()
            }
            for (index in cursor until end) {
                val height = heights[index]
                if (used + height > pageHeight && current.isNotEmpty()) {
                    result.add(current)
                    current = mutableListOf()
                    used = 0f
                    pageStarts.add(index)
                }
                val paragraph = paragraphs[index]
                // EPUB 用书内自带目录；txt 才靠标题正则识别（过长的不算，正文里「第 X 章」太多）
                val explicitTitle = epubChapters[index]
                val chapterTitle =
                    explicitTitle
                        ?: paragraph.trim().takeIf { it.length <= 48 && CHAPTER_PATTERN.matches(it) }
                if (chapterTitle != null) {
                    val clean = chapterTitle.replace(Regex("\\s+"), " ").trim()
                    chapterEntries.add(clean to result.size)
                    chapterParagraphs.add(index to clean)
                }
                current.add(paragraph)
                used += height
            }
            cursor = end
            chunk = 600
            publishChunk()
        }
        if (current.isNotEmpty()) result.add(current)
        if (result.isEmpty()) result.add(emptyList())
        pages = result
        chapters = chapterEntries
        layoutDone = true
        // 恢复阅读位置：优先按「整本百分比」——换字号/行距后页数会变，百分比才不会跳错地方
        run {
            val savedRatio = prefs.getFloat(positionKey + "_ratio", -1f)
            val target =
                when {
                    savedRatio in 0f..1f -> (savedRatio * (result.size - 1).coerceAtLeast(1)).toInt()
                    saved in result.indices -> saved
                    else -> 0
                }
            if (target in result.indices) pagerState.scrollToPage(target)
        }
        withContext(Dispatchers.IO) { writePageCache(cacheFile, paragraphs.size, pageStarts, chapterParagraphs) }
    }

    LaunchedEffect(pagerState, pages) {
        if (pages.isNullOrEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val total = (pages?.size ?: 0).coerceAtLeast(1)
            val ratio = (page.toFloat() / (total - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            prefs
                .edit()
                .putInt(positionKey, page)
                .putFloat(positionKey + "_ratio", ratio)
                .apply()
        }
    }
    LaunchedEffect(showChrome) {
        if (showChrome) {
            delay(2600)
            showChrome = false
        }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? android.app.Activity
            activity?.window?.let { window ->
                val attrs = window.attributes
                attrs.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
            }
        }
    }

    val style =
        TextStyle(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.8f).sp,
            fontFamily = FontFamily.Serif,
            color = Color(theme.foreground),
        )

    // 视口高度在根容器上量：它一直都在，分页不必等 pager 组合出来才开始
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(theme.background))
                .onSizeChanged { viewportHeightPx = it.height },
    ) {
        val pageList = pages
        when {
            rawText == null || pageList == null ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(theme.foreground))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (rawText == null) stringResource(R.string.viewer_text_empty) else stringResource(R.string.viewer_text_paginating),
                            color = Color(theme.foreground).copy(alpha = 0.7f),
                            fontSize = 12.sp,
                        )
                    }
                }
            else -> {
                HorizontalPager(
                    state = pagerState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    androidx.compose.ui.input.key.Key.VolumeDown -> {
                                        scope.launch {
                                            pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                                        }
                                        true
                                    }
                                    androidx.compose.ui.input.key.Key.VolumeUp -> {
                                        scope.launch {
                                            pagerState.animateScrollToPage(
                                                (pagerState.currentPage + 1).coerceAtMost(pagerState.pageCount - 1),
                                            )
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }.pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    when {
                                        offset.x < size.width * 0.3f ->
                                            scope.launch {
                                                pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                                            }
                                        offset.x > size.width * 0.7f ->
                                            scope.launch {
                                                pagerState.animateScrollToPage(
                                                    (pagerState.currentPage + 1).coerceAtMost(pagerState.pageCount - 1),
                                                )
                                            }
                                        else -> {
                                            // 点中间 = 唤出底部阅读设置（不再只是开关工具条）
                                            showChrome = false
                                            showSettings = true
                                        }
                                    }
                                }
                            },
                ) { index ->
                    val page = pageList.getOrNull(index).orEmpty()
                    // 包一层 SelectionContainer：长按可以选中文字复制（之前完全不能选）
                    SelectionContainer {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    // 顶部让开状态栏/挖孔，底部让开手势区：正文不贴边
                                    .statusBarsPadding()
                                    .padding(start = marginDp.dp, end = marginDp.dp, top = 8.dp, bottom = 28.dp),
                        ) {
                            page.forEach { paragraph ->
                                Text(
                                    text = paragraph,
                                    style = style,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = showChrome, enter = fadeIn(), exit = fadeOut()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 顶栏只放导航：返回 / 标题 / 目录 / 书签（设置改成点屏幕中间唤出底部面板）
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.4f))
                                    .statusBarsPadding()
                                    .padding(horizontal = 2.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, null, tint = Color.White) }
                            Text(
                                text = title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // 目录按钮常驻：即使这本书没识别到章节，面板里还有书签列表
                            IconButton(onClick = { showChapters = true }) {
                                Icon(Lucide.ListTodo, "目录与书签", tint = Color.White)
                            }
                            val bookmarked = pagerState.currentPage in bookmarks
                            TextButton(onClick = { toggleBookmark(pagerState.currentPage) }) {
                                Text(
                                    text = if (bookmarked) "已书签" else "书签",
                                    color = if (bookmarked) Color(0xFFF2C14E) else Color.White,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            // 阅读页不放进度条：翻页用点击左右区域/目录/音量键，页码常显在右下角
                        }
                    }
                }
                // 页码/百分比常显：不用唤出工具条也能看到读到哪
                Text(
                    text =
                        (pagerState.currentPage + 1).toString() + " / " + pageList.size +
                            "  ·  " + ((pagerState.currentPage + 1) * 100 / pageList.size) + "%",
                    color = Color(theme.foreground).copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp),
                )
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            fontSize = fontSize,
            lineSpacing = lineSpacing,
            marginDp = marginDp,
            themeIndex = themeIndex,
            onOpenToc = {
                showSettings = false
                showChapters = true
            },
            onReset = {
                fontSize = 17f
                lineSpacing = 1.8f
                marginDp = 18
                themeIndex = 0
                prefs.edit().putFloat("font_size", 17f).putFloat("line_spacing", 1.8f).putInt("margin_dp", 18).putInt("theme_index", 0).apply()
            },
            onFontSize = {
                fontSize = it
                prefs.edit().putFloat("font_size", it).apply()
            },
            onLineSpacing = {
                lineSpacing = it
                prefs.edit().putFloat("line_spacing", it).apply()
            },
            onMargin = {
                marginDp = it
                prefs.edit().putInt("margin_dp", it).apply()
            },
            onTheme = {
                themeIndex = it
                prefs.edit().putInt("theme_index", it).apply()
            },
            onDismiss = { showSettings = false },
        )
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                if (bookmarks.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                text = "书签（" + bookmarks.size + "）",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                            bookmarks.sorted().forEach { page ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showChapters = false
                                                scope.launch { pagerState.scrollToPage(page.coerceIn(0, (pages?.size ?: 1) - 1)) }
                                            }.padding(horizontal = 20.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "第 " + (page + 1) + " 页",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { toggleBookmark(page) }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                item {
                    Text(
                        text = "目录（" + chapters.size + " 章）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(chapters.size) { index ->
                    val (chapterTitle, page) = chapters[index]
                    val current = chapters.indexOfLast { it.second <= pagerState.currentPage } == index
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    showChapters = false
                                    scope.launch { pagerState.scrollToPage(page.coerceIn(0, (pages?.size ?: 1) - 1)) }
                                }.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

// ---------------- 阅读器分页缓存 ----------------

/**
 * 一本书按某套排版参数算出来的页边界：每页从第几段开始（段落序号）。
 * 章节只存段落序号，页序号由 [pageOf] 现算，换字号也不会错位。
 */
private class ReaderPageCache(
    val starts: List<Int>,
    val chapters: List<Pair<Int, String>>,
) {
    fun pageOf(paragraphIndex: Int): Int {
        val pos = starts.binarySearch(paragraphIndex)
        return if (pos >= 0) pos else (-pos - 2).coerceAtLeast(0)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    fontSize: Float,
    lineSpacing: Float,
    marginDp: Int,
    themeIndex: Int,
    onFontSize: (Float) -> Unit,
    onLineSpacing: (Float) -> Unit,
    onMargin: (Int) -> Unit,
    onTheme: (Int) -> Unit,
    onOpenToc: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            // 常用设置放在最外层：字号 / 行高 / 左右边距
            StepperRow("字号", fontSize.toInt().toString(), { onFontSize((fontSize - 1f).coerceAtLeast(14f)) }, { onFontSize((fontSize + 1f).coerceAtMost(30f)) })
            StepperRow(
                "行高",
                String.format(Locale.US, "%.1f×", lineSpacing),
                { onLineSpacing((lineSpacing - 0.1f).coerceAtLeast(1.2f)) },
                { onLineSpacing((lineSpacing + 0.1f).coerceAtMost(2.6f)) },
            )
            StepperRow("左右边距", marginDp.toString(), { onMargin((marginDp - 2).coerceAtLeast(8)) }, { onMargin((marginDp + 2).coerceAtMost(40)) })
            Spacer(Modifier.height(8.dp))
            Text("配色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                READER_THEMES.forEachIndexed { index, item ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(item.background))
                                    .border(
                                        width = if (index == themeIndex) 2.dp else 1.dp,
                                        color =
                                            if (index == themeIndex) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                Color(item.foreground).copy(alpha = 0.5f)
                                            },
                                        shape = CircleShape,
                                    ).clickable { onTheme(index) },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (index == themeIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onReset) { Text("恢复默认排版") }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(60.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        TextButton(onClick = onPlus) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
        // 用应用内统一的细轨滑条，不用 Material 默认那个又厚又碎的样式
        PjmSlider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (label == "行距") String.format(Locale.US, "%.1f", value) else value.toInt().toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(42.dp),
        )
    }
}

private fun readerCacheKey(
    file: File,
    fontSize: Float,
    lineSpacing: Float,
    marginDp: Int,
    widthDp: Int,
    heightDp: Int,
): String {
    // v3：分页算法从「Compose 逐段量高」换成 StaticLayout 后台量行，旧缓存的页边界不能再用
    val parts = listOf("v3", file.length(), file.lastModified(), fontSize, lineSpacing, marginDp, widthDp, heightDp)
    return parts.joinToString("-")
}

private fun readerCacheFile(
    context: Context,
    key: String,
): File {
    val dir = File(context.cacheDir, "reader_pages").apply { mkdirs() }
    return File(dir, key.hashCode().toString() + ".pag")
}

private fun readPageCache(
    file: File,
    paragraphCount: Int,
): ReaderPageCache? {
    if (!file.exists()) return null
    return runCatching {
        val lines = file.readLines()
        if (lines.size < 2) return null
        if (lines[0].toIntOrNull() != paragraphCount) return null
        val starts = if (lines[1].isBlank()) emptyList() else lines[1].split(',').map { it.toInt() }
        if (starts.isEmpty()) return null
        val chapters =
            lines.drop(2).mapNotNull { line ->
                val sep = line.indexOf('|')
                if (sep <= 0) {
                    null
                } else {
                    line.substring(0, sep).toIntOrNull()?.let { it to line.substring(sep + 1) }
                }
            }
        ReaderPageCache(starts, chapters)
    }.getOrNull()
}

private fun writePageCache(
    file: File,
    paragraphCount: Int,
    starts: List<Int>,
    chapters: List<Pair<Int, String>>,
) {
    runCatching {
        val dir = file.parentFile ?: return@runCatching
        // 只留最近 40 本，避免缓存无限增长
        dir.listFiles()?.let { files ->
            if (files.size > 40) files.sortedBy { it.lastModified() }.take(files.size - 40).forEach { it.delete() }
        }
        file.writeText(
            paragraphCount.toString() + "\n" +
                starts.joinToString(",") + "\n" +
                chapters.joinToString("\n") { (index, title) -> index.toString() + "|" + title.replace('\n', ' ') } + "\n",
        )
    }
}

/** 只读取前 limit 字节，避免大文件直接把内存吃满 */
private fun readLimitedBytes(
    file: File,
    limit: Long,
): ByteArray {
    val length = file.length()
    if (length <= limit) return file.readBytes()
    val buffer = ByteArray(limit.toInt())
    var read = 0
    file.inputStream().use { input ->
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
    }
    return if (read == buffer.size) buffer else buffer.copyOf(read)
}

/** UTF-8 优先，出现替换字符时回退 GBK（大量日志/网页下载文本是 GBK） */
private fun decodeText(bytes: ByteArray): String {
    val utf8 = String(bytes, Charsets.UTF_8)
    return if (utf8.contains('\uFFFD')) {
        runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8)
    } else {
        utf8
    }
}

// ---------------- 兜底 ----------------

@Composable
private fun UnsupportedViewer(
    file: File,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.error_unsupported_preview), color = Color.White)
            Spacer(Modifier.height(14.dp))
            Button(onClick = { FileUtils.openFileWithSystemApp(context, file) }) {
                Text(stringResource(R.string.action_open_with_system))
            }
        }
        ViewerTopBar(title = "", onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
    }
}

// ---------------- 公共组件 ----------------

@Composable
private fun ViewerTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Lucide.ArrowLeft, contentDescription = null, tint = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
