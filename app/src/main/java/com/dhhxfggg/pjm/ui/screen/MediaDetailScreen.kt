package com.dhhxfggg.pjm.ui.screen

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.ui.component.VideoSurface
import com.dhhxfggg.pjm.ui.component.rememberAudioInfo
import com.dhhxfggg.pjm.ui.component.rememberIsAppVisible
import com.dhhxfggg.pjm.ui.theme.rememberIconPack
import com.dhhxfggg.pjm.ui.viewmodel.MediaDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File

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
    val context = LocalContext.current
    val density = LocalDensity.current
    // 只读文件头取像素尺寸（不解码整张图），用来算"最大放大到 1:1 是几倍"
    val dims by
        produceState<IntArray?>(initialValue = null, file.absolutePath) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, options)
                        if (options.outWidth > 0 && options.outHeight > 0) {
                            intArrayOf(options.outWidth, options.outHeight)
                        } else {
                            null
                        }
                    }.getOrNull()
                }
        }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val viewHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val maxZoomFactor =
            remember(dims, viewWidthPx, viewHeightPx) {
                maxZoomFor(dims, viewWidthPx, viewHeightPx)
            }
        key(file.absolutePath) {
            // 缩放状态按「文件」隔离：Telephoto 用 rememberSaveable，不隔离会把上一张的缩放带到下一张
            val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = maxZoomFactor))
            ZoomableAsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(file)
                        .size(2560)
                        .precision(Precision.INEXACT)
                        // 本地文件不需要 Coil 的磁盘缓存副本（解码结果它也不缓存）
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                contentDescription = null,
                state = rememberZoomableImageState(zoomableState),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 最大缩放倍率 = 解码目标（2560px）与 Fit 缩放之比。
 * 比如 4000x3000 的图在 1080 宽的屏上，Fit 缩放是 0.27、解码缩放是 0.64 → 能放到约 2.4 倍正好 1:1。
 */
private fun maxZoomFor(
    dims: IntArray?,
    viewWidthPx: Float,
    viewHeightPx: Float,
): Float {
    if (dims == null || dims[0] <= 0 || dims[1] <= 0) return 3f
    val fileWidth = dims[0].toFloat()
    val fileHeight = dims[1].toFloat()
    val fitScale = minOf(viewWidthPx / fileWidth, viewHeightPx / fileHeight)
    if (fitScale <= 0f) return 3f
    val decodeScale = minOf(1f, 2560f / maxOf(fileWidth, fileHeight))
    return (decodeScale / fitScale).coerceIn(2f, 12f)
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
    var showControls by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }
    var lastScrubSeekAt by remember { mutableLongStateOf(0L) }
    // 双击跳转的一次性提示（"+10s" / "-10s"），700ms 后自动消失
    var seekHint by remember { mutableStateOf<String?>(null) }
    var videoError by remember { mutableStateOf<String?>(null) }

    // Telegram 式「长按快进/快退」：按住开始 2x，按住的同时左右滑动调整倍速（0.5x~8x）。
    // 左 1/3 为快退（暂停 + 定时回跳），右 1/3（短视频里是 1/3 右侧）为快进；中间 1/3 长视频不响应。
    var turboSpeed by remember { mutableFloatStateOf(0f) }
    var turboForward by remember { mutableStateOf(true) }
    var wasPlayingBeforeTurbo by remember { mutableStateOf(false) }
    var reverseJob by remember { mutableStateOf<Job?>(null) }
    val turboScope = rememberCoroutineScope()

    fun stopTurbo() {
        reverseJob?.cancel()
        reverseJob = null
        player.setPlaybackSpeed(1f)
        turboSpeed = 0f
        // 用 playWhenReady（用户是否想让它在播）而不是 isPlaying（此刻是否真的在播）：
        // seek / 换速的瞬间 playbackState 可能短暂离开 READY，isPlaying 会瞬间变 false，
        // 于是松手时把正在播放的视频停掉 —— 这就是「快进之后自动暂停」的根因。
        player.playWhenReady = wasPlayingBeforeTurbo
    }

    fun startTurbo(forward: Boolean) {
        wasPlayingBeforeTurbo = player.playWhenReady
        turboForward = forward
        turboSpeed = 2f
        if (forward) {
            player.setPlaybackSpeed(2f)
        } else {
            player.pause()
            reverseJob =
                turboScope.launch {
                    while (true) {
                        val step = (400f * turboSpeed).toLong()
                        player.seekTo((player.currentPosition - step).coerceAtLeast(0L))
                        delay(200)
                    }
                }
        }
    }

    fun adjustTurbo(deltaX: Float) {
        if (turboSpeed <= 0f) return
        // 前进：往右拖倍数变大；后退：往左拖倍数变大（方向跟着手走才直觉）
        val delta = if (turboForward) deltaX else -deltaX
        turboSpeed = (turboSpeed + delta / 220f).coerceIn(0.5f, 8f)
        if (turboForward) player.setPlaybackSpeed(turboSpeed)
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
                            onDoubleTap = { offset ->
                                // 按 Telegram PhotoViewer 的分区：左 1/3 后退 10 秒、右 1/3 前进 10 秒，
                                // 中间 1/3 不跳（避免误触），且要求总时长 > 15 秒、目标位置有效
                                val width = size.width.toFloat()
                                val position = player.currentPosition.coerceAtLeast(0L)
                                val target =
                                    when {
                                        durationMs <= 15_000L -> position
                                        offset.x < width / 3f -> (position - 10_000L).coerceAtLeast(0L)
                                        offset.x > width * 2f / 3f -> (position + 10_000L).coerceAtMost(durationMs)
                                        else -> position
                                    }
                                if (target != position) {
                                    player.seekTo(target)
                                    seekHint = if (target > position) "+10 秒" else "-10 秒"
                                    turboScope.launch {
                                        delay(700)
                                        seekHint = null
                                    }
                                }
                                showControls = true
                            },
                        )
                    }.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var active = false
                            var forward = true
                            val holdJob =
                                turboScope.launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    // 明确分区：屏幕右半边按住 = 加速，左半边按住 = 快退。
                                    // 不再按 1/3 切，中间往往刚好落在大拇指位置，反而容易误判。
                                    if (durationMs >= 5_000L) {
                                        forward = down.position.x > size.width / 2f
                                        active = true
                                        startTurbo(forward)
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
        // 双击跳转的一次性提示（像 YouTube / Telegram 那样给个即时反馈）
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
                    text = (if (turboForward) "▶ " else "◀ ") + String.format("%.1fx", turboSpeed),
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
                            Modifier
                                .size(72.dp)
                                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Lucide.Pause else Lucide.Play,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(38.dp),
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
                    Slider(
                        value = shownMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                        onValueChange = {
                            scrubbing = true
                            scrubMs = it.toLong()
                        },
                        onValueChangeFinished = {
                            player.seekTo(scrubMs)
                            scrubbing = false
                        },
                        colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = 0.35f)),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDuration(shownMs), color = Color.White, fontSize = 12.sp)
                        Text(formatDuration(durationMs), color = Color.White, fontSize = 12.sp)
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

    // 整个音频分类就是播放队列：上一首/下一首/随机都能用，不用用户自己挑
    val queueFiles = remember(queue) { queue.map { VaultManager.getFileFromEntity(context, it) } }
    val start = startIndex.coerceIn(0, (queueFiles.size - 1).coerceAtLeast(0))
    val currentFile = queueFiles.getOrNull(currentIndex) ?: file
    val info = rememberAudioInfo(currentFile)

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

    val iconPack = rememberIconPack()
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
            Slider(
                value = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    dragging = true
                    positionMs = (fraction * durationMs).toLong()
                },
                onValueChangeFinished = {
                    controller?.seekTo(positionMs)
                    dragging = false
                },
            )
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
                                if (sleepLeftMs > 0L) {
                                    "定时 " + formatDuration(sleepLeftMs)
                                } else {
                                    "定时关闭"
                                },
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                        )
                    }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        listOf(0, 15, 30, 45, 60, 90).forEach { minutes ->
                            val current = sleepMinutes == minutes
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (minutes == 0) "关闭定时" else minutes.toString() + " 分钟",
                                        color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    )
                                },
                                onClick = {
                                    sleepMinutes = minutes
                                    sleepMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }
        ViewerTopBar(title = "", onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
    }
}

// ---------------- 文本 ----------------

/** 阅读器配色：夜间 / 米黄 / 护眼 */
private data class ReaderTheme(
    val background: Long,
    val foreground: Long,
)

private val READER_THEMES =
    listOf(
        ReaderTheme(0xFF0D0D0F, 0xFFE7E2D6),
        ReaderTheme(0xFFF6EFE3, 0xFF3B332A),
        ReaderTheme(0xFFCCE8CF, 0xFF20301F),
    )

/** 常见网文章节标题（第 X 章/节/回/卷… 或 Chapter N，以及卷首语） */
private val CHAPTER_PATTERN =
    Regex("^\\s*(第[0-9零一二三四五六七八九十百千两]{1,10}[章节回卷集部篇].*|Chapter\\s+\\d+.*|序章|楔子|前言|后记)$", RegexOption.IGNORE_CASE)

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
    val measurer = rememberTextMeasurer()
    val prefs = remember { context.getSharedPreferences("pjm_reader", Context.MODE_PRIVATE) }
    val positionKey = remember(file.absolutePath) { "page_" + file.absolutePath.hashCode() }

    var rawText by remember { mutableStateOf<String?>(null) }
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
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(0) { (pages?.size ?: 1).coerceAtLeast(1) }
    val theme = READER_THEMES[themeIndex.coerceIn(0, READER_THEMES.size - 1)]

    LaunchedEffect(file.absolutePath) {
        rawText =
            withContext(Dispatchers.IO) {
                runCatching { decodeText(readLimitedBytes(file, 3L * 1024 * 1024)) }.getOrNull()
            }
    }

    LaunchedEffect(rawText, fontSize, lineSpacing, marginDp, configuration.screenWidthDp, configuration.screenHeightDp) {
        val body = rawText ?: return@LaunchedEffect
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
            )
        val pageWidth = with(density) { (configuration.screenWidthDp.dp - (marginDp * 2).dp).toPx() }
        // 底部工具条（进度 + 按钮）比原来高，正文可用的高度相应减少
        val pageHeight = with(density) { (configuration.screenHeightDp.dp - 176.dp).toPx() }
        val constraints = Constraints(maxWidth = pageWidth.toInt().coerceAtLeast(1))
        val gap = with(density) { 6.dp.toPx() }

        // 分页缓存：同一本书 + 同一套排版参数（字号/行距/边距/屏幕尺寸）直接复用上次算好的页边界，
        // 再打开就是瞬间出页，不用重新量上万段。缓存在 cacheDir，"清除缓存"会一并清掉。
        val cacheFile =
            readerCacheFile(
                context,
                readerCacheKey(file, fontSize, lineSpacing, marginDp, configuration.screenWidthDp, configuration.screenHeightDp),
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
            if (savedPage in cachedPages.indices) pagerState.scrollToPage(savedPage)
            layoutDone = true
            return@LaunchedEffect
        }

        val result = mutableListOf<List<String>>()
        val chapterEntries = mutableListOf<Pair<String, Int>>()
        val chapterParagraphs = mutableListOf<Pair<Int, String>>()
        val pageStarts = mutableListOf(0)
        var current = mutableListOf<String>()
        var used = 0f
        var publishedPages = 0
        val saved = prefs.getInt(positionKey, 0)
        var restored = false

        // 关键：排版是逐段量高，一本 3MB 的书要量上万段。旧实现一次性量完，
        // 中间只 yield()（yield 不会等下一帧），主线程被连续占用好几秒 = 卡死。
        // 现在每帧只给排版 6ms，剩下的时间留给绘制/手势，并且边排版边把结果交给界面，
        // 第一页几十毫秒就能看，后面随翻随补。
        val frameBudgetNanos = 6_000_000L
        var sliceStart = System.nanoTime()

        for ((index, paragraph) in paragraphs.withIndex()) {
            val isChapter = CHAPTER_PATTERN.matches(paragraph)
            val height =
                if (paragraph.isEmpty()) {
                    gap * 1.5f
                } else {
                    measurer.measure(AnnotatedString(paragraph), style, constraints = constraints).size.height + gap
                }
            if (used + height > pageHeight && current.isNotEmpty()) {
                result.add(current)
                current = mutableListOf()
                used = 0f
                pageStarts.add(index)
            }
            if (isChapter) {
                chapterEntries.add(paragraph.trim() to result.size)
                chapterParagraphs.add(index to paragraph.trim())
            }
            current.add(paragraph)
            used += height

            if (!restored && saved in result.indices) {
                restored = true
                pagerState.scrollToPage(saved)
            }
            // 每排完一页就交给界面：原来攒够 4 页才发布，第一屏要等四页的量，
            // 长文/大字号时体感就是"点了半天才出字"
            if (result.size != publishedPages) {
                publishedPages = result.size
                pages = result.toList()
                chapters = chapterEntries.toList()
            }
            if (System.nanoTime() - sliceStart > frameBudgetNanos) {
                // 让出一帧：等下一次 vsync 再继续，保证拖动/翻页不掉帧
                withFrameNanos { }
                sliceStart = System.nanoTime()
            }
        }
        if (current.isNotEmpty()) result.add(current)
        if (result.isEmpty()) result.add(emptyList())
        pages = result
        chapters = chapterEntries
        writePageCache(cacheFile, paragraphs.size, pageStarts, chapterParagraphs)
        layoutDone = true
        if (!restored && saved in result.indices) pagerState.scrollToPage(saved)
    }

    LaunchedEffect(pagerState, pages) {
        if (pages.isNullOrEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }.collect { page ->
            prefs.edit().putInt(positionKey, page).apply()
        }
    }
    LaunchedEffect(showChrome) {
        if (showChrome) {
            delay(2600)
            showChrome = false
        }
    }

    val style =
        TextStyle(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.8f).sp,
            fontFamily = FontFamily.Serif,
            color = Color(theme.foreground),
        )

    Box(modifier = Modifier.fillMaxSize().background(Color(theme.background))) {
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
                            .pointerInput(Unit) {
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
                                        else -> showChrome = !showChrome
                                    }
                                }
                            },
                ) { index ->
                    val page = pageList.getOrNull(index).orEmpty()
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = marginDp.dp, vertical = 18.dp)) {
                        page.forEach { paragraph ->
                            Text(
                                text = paragraph,
                                style = style,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            )
                        }
                    }
                }
                AnimatedVisibility(visible = showChrome, enter = fadeIn(), exit = fadeOut()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ViewerTopBar(title = title, onBack = onBack)
                        Spacer(Modifier.weight(1f))
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            // 进度条：任意位置都能直接跳过去，不用一页页翻
                            Slider(
                                value = pagerState.currentPage.toFloat(),
                                valueRange = 0f..(pageList.size - 1).coerceAtLeast(1).toFloat(),
                                onValueChange = { value ->
                                    scope.launch {
                                        pagerState.scrollToPage(value.toInt().coerceIn(0, pageList.size - 1))
                                    }
                                },
                                colors =
                                    SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color.White.copy(alpha = 0.85f),
                                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                                    ),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    fontSize = (fontSize - 1f).coerceAtLeast(12f)
                                    prefs.edit().putFloat("font_size", fontSize).apply()
                                }) { Text("A-", color = Color.White) }
                                TextButton(onClick = {
                                    fontSize = (fontSize + 1f).coerceAtMost(30f)
                                    prefs.edit().putFloat("font_size", fontSize).apply()
                                }) { Text("A+", color = Color.White) }
                                // 行距：1.4 / 1.8 / 2.2 三挡轮换
                                TextButton(onClick = {
                                    lineSpacing =
                                        when {
                                            lineSpacing < 1.6f -> 1.8f
                                            lineSpacing < 2.0f -> 2.2f
                                            else -> 1.4f
                                        }
                                    prefs.edit().putFloat("line_spacing", lineSpacing).apply()
                                }) { Text("行距", color = Color.White) }
                                // 边距：12 / 18 / 24dp 三挡轮换
                                TextButton(onClick = {
                                    marginDp =
                                        when (marginDp) {
                                            12 -> 18
                                            18 -> 24
                                            else -> 12
                                        }
                                    prefs.edit().putInt("margin_dp", marginDp).apply()
                                }) { Text("边距", color = Color.White) }
                                TextButton(onClick = {
                                    themeIndex = (themeIndex + 1) % READER_THEMES.size
                                    prefs.edit().putInt("theme_index", themeIndex).apply()
                                }) { Text("配色", color = Color.White) }
                                if (chapters.isNotEmpty()) {
                                    TextButton(onClick = { showChapters = true }) { Text("目录", color = Color.White) }
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text =
                                        (pagerState.currentPage + 1).toString() + "/" + pageList.size +
                                            "  " + ((pagerState.currentPage + 1) * 100 / pageList.size) + "%" +
                                            (if (layoutDone) "" else " · 排版中"),
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(chapters.size) { index ->
                    val (chapterTitle, page) = chapters[index]
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier
                                .fillMaxWidth()
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

private fun readerCacheKey(
    file: File,
    fontSize: Float,
    lineSpacing: Float,
    marginDp: Int,
    widthDp: Int,
    heightDp: Int,
): String {
    val parts = listOf(file.length(), file.lastModified(), fontSize, lineSpacing, marginDp, widthDp, heightDp)
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
