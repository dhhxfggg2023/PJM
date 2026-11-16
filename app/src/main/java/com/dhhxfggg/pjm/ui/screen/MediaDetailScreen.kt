package com.dhhxfggg.pjm.ui.screen

import android.content.ComponentName
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.composables.icons.lucide.*
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.service.AudioPlaybackService
import com.dhhxfggg.pjm.domain.util.DiscoveryPlayerPool
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.PlayMode
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.ui.component.PjmButtonCaption
import com.dhhxfggg.pjm.ui.component.PjmIconWithCaption
import com.dhhxfggg.pjm.ui.component.PlayerProgressBar
import com.dhhxfggg.pjm.ui.component.PlayerProgressState
import com.dhhxfggg.pjm.ui.component.VideoControlBar
import com.dhhxfggg.pjm.ui.component.VideoSurface
import com.dhhxfggg.pjm.ui.component.ZoomableVaultImage
import com.dhhxfggg.pjm.ui.component.formatPlaybackTime
import com.dhhxfggg.pjm.ui.component.rememberAudioInfo
import com.dhhxfggg.pjm.ui.component.rememberIsAppVisible
import com.dhhxfggg.pjm.ui.component.rememberPlayerProgress
import com.dhhxfggg.pjm.ui.theme.rememberIconPack
import com.dhhxfggg.pjm.ui.viewmodel.MediaDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 断行策略：最简单的按词断行，不做连字符/高质量断行（几万段排版省掉多余开销）。
 *
 * 值就是 0：`LineBreaker.BREAK_STRATEGY_SIMPLE` 是 API 29 才有的类，本应用 minSdk 24 引用不了，
 * 而 `Layout.BREAK_STRATEGY_SIMPLE` 与它同值 —— lint 的 WrongConstant 只认前者，这里显式忽略。
 */
@Suppress("WrongConstant")
private const val SIMPLE_BREAK_STRATEGY = android.text.Layout.BREAK_STRATEGY_SIMPLE

/** 阅读位置落盘的防抖窗口：翻页很密时不必每页写一次 SharedPreferences */
private const val POSITION_SAVE_DEBOUNCE_MS = 400L

/** 字号/行距/边距连点的合并窗口：等手停下来再整本重排 */
private const val LAYOUT_DEBOUNCE_MS = 150L

/** 查看器日志 tag */
private const val TAG_MEDIA_VIEWER = "MediaDetail"

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
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaDetailScreen(
    relativePath: String,
    onBack: () -> Unit,
    viewModel: MediaDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val fileEntity by viewModel.fileEntity.collectAsStateWithLifecycle()
    val categoryFiles by viewModel.categoryFiles.collectAsStateWithLifecycle()

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
                    // 列表里没有当前这张时退回「只看这一张」：以前 clamp 到 0 会变成点 A 看 B
                    val foundIndex = categoryFiles.indexOfFirst { it.relativePath == entity.relativePath }
                    val startIndex =
                        if (foundIndex < 0) {
                            PjmLogger.w(TAG_MEDIA_VIEWER, "image not in category list, fallback to single: " + entity.relativePath)
                            0
                        } else {
                            foundIndex
                        }
                    ImagePager(
                        files = if (foundIndex < 0) listOf(entity) else categoryFiles,
                        startIndex = startIndex,
                        onBack = onBack,
                    )
                }
            }
            FileUtils.isVideoFile(entity.name) -> {
                // remember：整个音频/视频列表的过滤只在 categoryFiles 变化时做一次。
                // 旧写法每次重组（进度更新、控制条显隐）都会把整份分类列表重新扫一遍。
                val videos =
                    if (categoryFiles.isEmpty()) {
                        listOf(entity)
                    } else {
                        remember(categoryFiles) { categoryFiles.filter { FileUtils.isVideoFile(it.name) } }
                    }
                // 列表里没有当前这条时退回「只播这一条」：以前 clamp 到 0 会变成点 A 播 B
                val foundIndex = videos.indexOfFirst { it.relativePath == entity.relativePath }
                val startIndex =
                    if (foundIndex < 0) {
                        PjmLogger.w(TAG_MEDIA_VIEWER, "video not in category list, fallback to single: " + entity.relativePath)
                        0
                    } else {
                        foundIndex
                    }
                VideoPager(
                    files = if (foundIndex < 0) listOf(entity) else videos,
                    startIndex = startIndex,
                    onBack = onBack,
                )
            }
            FileUtils.isAudioFile(entity.name) -> {
                val audios =
                    if (categoryFiles.isEmpty()) {
                        listOf(entity)
                    } else {
                        remember(categoryFiles) { categoryFiles.filter { FileUtils.isAudioFile(it.name) } }
                    }
                // 队列里没有当前这首时退回「只播这一首」：以前 clamp 到 0 会变成点这首放那首
                val foundIndex = audios.indexOfFirst { it.relativePath == entity.relativePath }
                val startIndex =
                    if (foundIndex < 0) {
                        PjmLogger.w(TAG_MEDIA_VIEWER, "audio not in category list, fallback to single: " + entity.relativePath)
                        0
                    } else {
                        foundIndex
                    }
                AudioViewer(
                    file = file,
                    title = FileUtils.normalizedDisplayName(entity),
                    queue = if (foundIndex < 0) listOf(entity) else audios,
                    startIndex = startIndex,
                    onBack = onBack,
                )
            }
            FileUtils.isTextFile(entity.name) ->
                ReaderScreen(
                    file = file,
                    title = FileUtils.normalizedDisplayName(entity),
                    onBack = onBack,
                )
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

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    file: File,
    isActive: Boolean,
) {
    val context = LocalContext.current
    // 文案在组合期取好（跟随语言/配置变化），双击手势回调里直接用
    val seekForward10Label = stringResource(R.string.video_seek_forward_10)
    val seekBack10Label = stringResource(R.string.video_seek_back_10)
    // 和发现页共用播放器复用池：翻页时不再每页新建/销毁 ExoPlayer（MediaCodec 反复初始化很贵）。
    // 借/还必须成对：文件切走后 DisposableEffect 归还，池里最多留 2 个实例。
    val player =
        remember(file.absolutePath) {
            DiscoveryPlayerPool.acquire(context).apply {
                setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                prepare()
                playWhenReady = false
            }
        }
    DisposableEffect(file.absolutePath) {
        onDispose { DiscoveryPlayerPool.release(player) }
    }
    val isAppVisible = rememberIsAppVisible()
    LaunchedEffect(player, isActive, isAppVisible) {
        val p = player ?: return@LaunchedEffect
        p.playWhenReady = isActive && isAppVisible
        if (!isActive) p.pause()
    }

    // 进度/时长/播放状态和发现页共用同一份轮询。以前这里是 200ms 轮询 + 把位置写在本层，
    // 控制条一显示就每 200ms 重组整页（含播放器 AndroidView）。
    val progress = rememberPlayerProgress(player)
    // 进入播放页不自动弹控制条：画面干净，需要时点一下再出现
    var showControls by remember { mutableStateOf(false) }
    var lastScrubSeekAt by remember { mutableLongStateOf(0L) }
    // 双击跳转的一次性提示（"+10s" / "-10s"），700ms 后自动消失
    var seekHint by remember { mutableStateOf<String?>(null) }

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
    //
    // 拆成两个 effect：恢复动作只挂在「离开本页」上（DisposableEffect(Unit)），切换全屏只走
    // LaunchedEffect(fullscreen)。原来两者共用一个 DisposableEffect(fullscreen)，每次切换都会
    // 先拆（恢复方向 + 弹出系统栏）再装（设回全屏），屏幕会闪一下。
    var fullscreen by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? android.app.Activity
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val window = activity?.window
            window
                ?.let {
                    androidx.core.view.WindowCompat
                        .getInsetsController(it, it.decorView)
                }?.show(
                    androidx.core.view.WindowInsetsCompat.Type
                        .systemBars(),
                )
        }
    }
    LaunchedEffect(fullscreen) {
        val activity = context as? android.app.Activity
        val window = activity?.window
        val controller =
            window?.let {
                androidx.core.view.WindowCompat
                    .getInsetsController(it, it.decorView)
            }
        controller?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.hide(
                androidx.core.view.WindowInsetsCompat.Type
                    .systemBars(),
            )
        } else {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(
                androidx.core.view.WindowInsetsCompat.Type
                    .systemBars(),
            )
        }
    }

    LaunchedEffect(showControls, progress.isPlaying) {
        if (showControls && progress.isPlaying) {
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
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = doubleTap@{ offset ->
                                // 按左右三分区：左 1/3 后退 10 秒、右 1/3 前进 10 秒，
                                // 中间 1/3 不跳（避免误触），且要求总时长 > 15 秒、目标位置有效
                                val width = size.width.toFloat()
                                val position = player.currentPosition.coerceAtLeast(0L)
                                // 手势开始的一刻直接问播放器，不用等轮询（也就不会用到过期时长）
                                val total = player.duration.coerceAtLeast(0L)
                                if (offset.x >= width / 3f && offset.x <= width * 2f / 3f) {
                                    // 中间双击 = 播放/暂停（最常见的手势，之前被吞了）
                                    // 双击只做播放/暂停，不弹控制条（一快进就蹦出按钮和进度条很烦）
                                    if (player.isPlaying) player.pause() else player.play()
                                    return@doubleTap
                                }
                                val target =
                                    when {
                                        total <= 15_000L -> position
                                        offset.x < width / 3f -> (position - 10_000L).coerceAtLeast(0L)
                                        else -> (position + 10_000L).coerceAtMost(total)
                                    }
                                if (target != position) {
                                    player.seekTo(target)
                                    seekHint = if (target > position) seekForward10Label else seekBack10Label
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
                                    if (player.duration >= 5_000L) {
                                        active = true
                                        startTurbo()
                                    }
                                }
                            var lastX = down.position.x
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (active) {
                                    adjustTurbo(change.position.x - lastX)
                                    lastX = change.position.x
                                    change.consume()
                                } else {
                                    // 分界：手指先动 → 交给横向拖动去快进；按住不动到长按时长才进加速。
                                    // 之前长按计时和拖动同时跑，往右拖快进时会被判成长按加速，两个一起动。
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    if (dx * dx + dy * dy > slop * slop) {
                                        holdJob.cancel()
                                        break
                                    }
                                }
                            }
                            holdJob.cancel()
                            if (active) stopTurbo()
                        }
                    }.pointerInput(Unit) {
                        // 时长在每次拖动开始时取一次；播放器还没就绪就忽略这次拖动。
                        // 不把时长塞进 pointerInput 的 key —— 那会让手势层随进度更新反复重启。
                        var dragDuration = 0L
                        detectHorizontalDragGestures(
                            onDragStart = {
                                if (turboSpeed > 0f) return@detectHorizontalDragGestures
                                dragDuration = player.duration.takeIf { it > 0L } ?: 0L
                                if (dragDuration <= 0L) return@detectHorizontalDragGestures
                                progress.scrubTo(player.currentPosition.coerceAtLeast(0L))
                            },
                            onDragEnd = {
                                if (!progress.scrubbing) return@detectHorizontalDragGestures
                                val target = progress.shownPositionMs
                                player.seekTo(target)
                                progress.endScrub(target)
                            },
                            onDragCancel = {
                                // 节流 seek 可能没跟上最后一段距离，抬手时补一次
                                val target = progress.shownPositionMs
                                player.seekTo(target)
                                progress.endScrub(target)
                            },
                        ) { change, dragAmount ->
                            if (turboSpeed > 0f || !progress.scrubbing || dragDuration <= 0L) {
                                return@detectHorizontalDragGestures
                            }
                            change.consume()
                            // 横向拖动灵敏度调低（全屏宽 = 时长的 35%），避免一拖就飞过头
                            val deltaMs = (dragAmount / size.width * dragDuration * 0.35f).toLong()
                            val target = (progress.shownPositionMs + deltaMs).coerceIn(0L, dragDuration)
                            progress.scrubTo(target)
                            // 画面跟着手指走：节流 seek（80ms 一次），边拖边看到拖到了哪一帧
                            val now = System.currentTimeMillis()
                            if (now - lastScrubSeekAt > 80L) {
                                lastScrubSeekAt = now
                                player.seekTo(target)
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
        // 倍速角标只在顶部居中显示一处：之前右上角也画了一份，两处不同字号/格式
        // （一个 12sp 截断字符串、一个 14sp 一位小数）看着像两个读数，留居中这个更清晰。
        // 全屏拖进度时不弹控制栏，只给一个中间的时间提示（控制栏可见时它自己会显示位置）。
        // 读 progress 的部分收进叶子 composable：拖动时位置每帧都在变，整页不该跟着重组。
        VideoScrubHint(progress = progress, visible = !showControls, modifier = Modifier.align(Alignment.Center))
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                if (player.isPlaying) player.pause() else player.play()
                                showControls = true
                            },
                            // 中间按钮改成实心白圆 + 深色图标：原来半透明黑圆压在画面上，
                            // 遇到暗场景几乎看不见按钮边界。
                            modifier =
                                Modifier
                                    .size(54.dp)
                                    .background(Color.White.copy(alpha = 0.92f), CircleShape),
                        ) {
                            Icon(
                                imageVector = if (progress.isPlaying) Lucide.Pause else Lucide.Play,
                                contentDescription =
                                    stringResource(if (progress.isPlaying) R.string.action_pause else R.string.action_play),
                                tint = Color.Black.copy(alpha = 0.8f),
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        PjmButtonCaption(
                            caption = stringResource(if (progress.isPlaying) R.string.hint_pause else R.string.hint_play),
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
                // 控制条和发现页共用一份；全屏 / 竖屏切换：想躺着看就全屏，想竖着刷就保持竖屏
                VideoControlBar(
                    player = player,
                    progress = progress,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    bottomPadding = if (fullscreen) 16.dp else 8.dp,
                    fullscreen = fullscreen,
                    onToggleFullscreen = {
                        fullscreen = !fullscreen
                        showControls = true
                    },
                )
            }
        }
    }
}

// ---------------- 音频 ----------------

/** 文案资源留在 UI 层：领域层枚举保持纯 Kotlin，不认识 R.string */
@androidx.annotation.StringRes
private fun playModeLabelRes(mode: PlayMode): Int =
    when (mode) {
        PlayMode.SEQUENTIAL -> R.string.play_mode_sequential
        PlayMode.REPEAT_ALL -> R.string.play_mode_repeat_all
        PlayMode.REPEAT_ONE -> R.string.play_mode_repeat_one
        PlayMode.SHUFFLE -> R.string.play_mode_shuffle
    }

/** 把模式成对写回播放器：保证不存在「随机 + 单曲循环」这种自相矛盾的组合 */
private fun applyPlayMode(
    controller: MediaController?,
    mode: PlayMode,
) {
    val c = controller ?: return
    // 先关 shuffle 再设 repeat，最后按模式打开 shuffle：中间态不会出现随机 + 单曲循环
    c.shuffleModeEnabled = false
    c.repeatMode = mode.repeat
    if (mode.shuffle) c.shuffleModeEnabled = true
}

/** 模式图标：四个模式一眼能认出来 */
private fun playModeIcon(mode: PlayMode) =
    when (mode) {
        PlayMode.SEQUENTIAL -> Lucide.ArrowRight
        PlayMode.REPEAT_ALL -> Lucide.Repeat
        PlayMode.REPEAT_ONE -> Lucide.Repeat1
        PlayMode.SHUFFLE -> Lucide.Shuffle
    }

/**
 * 定时关闭状态。分钟制与按首数制严格互斥：设置其一时另一个清零，
 * 对应的计时协程也会因为 key 变化被取消，不会出现「两个都在跑」。
 */
private data class SleepTimer(
    /** 分钟制：剩余分钟数（0 = 未启用） */
    val minutes: Int = 0,
    /** 按首数制：还要再播几首（0 = 未启用） */
    val songs: Int = 0,
    /** 分钟制剩余毫秒，只用于 UI 显示 */
    val leftMs: Long = 0L,
)

/** 队列内容变化的重查间隔：ViewModel 重发列表时不用等太久，也不会每次重组都查 */
private const val QUEUE_WATCH_INTERVAL_MS = 500L

/** 单曲循环回绕判定：新位置在这以内、旧位置不小于它，才算「播完一遍」而不是 seek */
private const val REPEAT_WRAP_TOLERANCE_MS = 1500L

/** 「播完一首」的去重窗口：同一次回绕/切歌最多只算一首 */
private const val SONG_COUNT_DEBOUNCE_MS = 2000L

/** 按首数定时到点后，等下一首起播再暂停的延迟 */
private const val AFTER_PLAY_DELAY_MS = 250L

/** 点过播放模式后，多久内不按播放器回读覆盖 UI（等 MediaController 的异步写入落地） */
private const val MODE_SETTLE_MS = 600L

/**
 * 音频播放器（助眠/日常听歌）。
 *
 * 播放本体仍在后台的 MediaSessionService 里（锁屏、通知栏、退后台都继续），
 * 这里只是控制端：封面、进度拖动、上一首/下一首、播放模式、队列、定时关闭。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
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
    // 进度/时长/播放状态交给共享状态（和发现页、视频页同一份实现），
    // 本层只留队列、播放模式这些低频标量
    val progress = rememberPlayerProgress(controller)
    // 播放模式：单一四态枚举（顺序 / 列表循环 / 单曲循环 / 随机）。
    // 以前是「随机开关 + 循环按钮」两个独立状态，shuffle 与 REPEAT_MODE_ONE 同时为真时
    // ExoPlayer 的实际行为是「随机队列但永远重复当前这一首」，UI 和听到的完全对不上。
    var playMode by remember { mutableStateOf(PlayMode.SEQUENTIAL) }
    // 本地点按播放模式的时间戳，用来屏蔽随后 600ms 内轮询回读的旧值
    var lastModeSetAt by remember { mutableLongStateOf(0L) }
    // 定时关闭：分钟制与按首数制合并成一个状态，设置其一时另一个清零（严格互斥）
    var sleepTimer by remember { mutableStateOf(SleepTimer()) }
    // 封面点一下 = 播放列表
    var showPlaylist by remember { mutableStateOf(false) }
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

    // 核心修复：队列不能只在控制器首次连上时设置一次。
    // categoryFiles 初始是 emptyList()（ViewModel 异步填充），旧实现把首帧的单首队列固定到
    // MediaController，UI 却按完整队列渲染 —— 点第 N 首/上一首/下一首/随机全部失效。
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
                    // 连上就先同步一次播放模式：播放器里的成对开关是唯一事实来源，
                    // 避免首帧 UI 显示「顺序播放」而播放器其实还是上次的循环模式
                    playMode = PlayMode.fromPlayerFlags(c.shuffleModeEnabled, c.repeatMode)
                    controller = c
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { MediaController.releaseFuture(future) }
    }

    // 队列（绝对路径，顺序敏感）决定队列内容；内容快照在下面的协程里算，只有真的变了才重排
    val queueKey = remember(queueFiles) { queueFiles.map { it.absolutePath } }

    // 队列随 categoryFiles 到达 / 变化而更新。
    // 旧实现用 LaunchedEffect(controller, queueFiles, start)：ViewModel 每次重新发一遍列表
    // （哪怕只是删了库里另一首音频）都会把整条队列 setMediaItems + prepare + play 重来一次，
    // 正在听的歌被打断、跳回 start。这里改成：只在「内容真的变了」时更新，
    // 且更新后按原来的相对路径与播放位置把当前曲目接回去。
    val latestQueueKey by rememberUpdatedState(queueKey)
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        var appliedContent: String? = null
        while (true) {
            val keys = latestQueueKey
            val content = keys.joinToString("\n")
            if (content != appliedContent) {
                val firstApply = appliedContent == null
                val items =
                    if (keys.isEmpty()) {
                        listOf(MediaItem.fromUri(file.toURI().toString()))
                    } else {
                        keys.map { MediaItem.fromUri(File(it).toURI().toString()) }
                    }
                // 内容变了也要接着放：记住当前音频与进度，重排后按 relativePath 找回同一首
                val playingUri =
                    c.currentMediaItem
                        ?.localConfiguration
                        ?.uri
                        ?.toString()
                val resumePosition = c.currentPosition.coerceAtLeast(0L)
                // 当前这首还在队列里就接着放它、接着原来的位置；被删掉了才退回第一首开头
                val found =
                    if (firstApply) {
                        -1
                    } else {
                        keys.indexOfFirst { path -> File(path).toURI().toString() == playingUri }
                    }
                val resumeIndex =
                    if (found >= 0) {
                        found
                    } else {
                        start.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                    }
                val resumeMs = if (found >= 0) resumePosition else 0L
                // 用户暂停着就不替他续播：只有本来就该播（或首次进入）才 play
                val wasPlaying = c.playWhenReady
                c.setMediaItems(items, resumeIndex, resumeMs)
                c.prepare()
                if (firstApply || wasPlaying) c.play()
                appliedContent = content
            }
            delay(QUEUE_WATCH_INTERVAL_MS)
        }
    }

    // 播放模式 / 当前曲目轮询：400ms 一次。进度和时长不在这里读 —— 那是共享状态的活，
    // 之前两处各存一份 positionMs/durationMs，拖动时还要互相绕开。
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            currentIndex = c.currentMediaItemIndex.coerceAtLeast(0)
            // 锁屏/通知栏切换播放模式也会经过这里：把成对的两个开关还原成同一个四态枚举。
            // 刚点过按钮的 600ms 内不回读，等 MediaController 的异步写入落到会话播放器再说，
            // 否则会先闪回旧模式再跳回来
            if (SystemClock.elapsedRealtime() - lastModeSetAt > MODE_SETTLE_MS) {
                playMode = PlayMode.fromPlayerFlags(c.shuffleModeEnabled, c.repeatMode)
            }
            delay(400)
        }
    }

    // 定时关闭（分钟制）：剩余时间按墙钟算，后台被挂起再回来也不会多跑/少跑。
    // 切歌不重建这个协程（key 只有分钟数），所以上一首/下一首/列表点选不会重置定时。
    LaunchedEffect(sleepTimer.minutes) {
        val minutes = sleepTimer.minutes
        if (minutes <= 0) {
            sleepTimer = sleepTimer.copy(leftMs = 0L)
            return@LaunchedEffect
        }
        val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        sleepTimer = sleepTimer.copy(leftMs = minutes * 60_000L)
        // 播放中每 250ms 看一次：到点即停，不用等满 1 秒；但只在「秒数变了」时才写状态，
        // 免得整屏（封面模糊底、按钮行）跟着 4Hz 重组 —— 暂停时退化成 1 秒一次省电
        var lastShownSecond = -1L
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0L) break
            val second = left / 1000L
            if (second != lastShownSecond) {
                lastShownSecond = second
                sleepTimer = sleepTimer.copy(leftMs = left)
            }
            delay(if (controller?.isPlaying == true) 250L else 1000L)
        }
        controller?.pause()
        sleepTimer = SleepTimer()
    }

    // 按首数定时：每「自然播完一首」计数加一（含单曲循环的原地回绕），手动切歌不计。
    // listener 回调在播放器线程，用 rememberUpdatedState 拿最新的定时状态，避免闭包读到旧值。
    var playedCount by remember { mutableIntStateOf(0) }
    val latestSleepSongs by rememberUpdatedState(sleepTimer.songs)
    // 定时到点时的「稍后再停」：回绕/切歌瞬间 pause 会被随后的 prepare+play 覆盖，延后一帧再停
    val uiScope = rememberCoroutineScope()
    val afterPlayOne: (() -> Unit) -> Unit = { block ->
        uiScope.launch {
            delay(AFTER_PLAY_DELAY_MS)
            block()
        }
    }
    // 同一次「播完一首」可能同时来 onMediaItemTransition 和 onPositionDiscontinuity，
    // 用时间戳去重：一首歌不可能 2 秒内播完两遍
    var lastCountAt by remember { mutableLongStateOf(0L) }
    val countPlayedSong = {
        val target = latestSleepSongs
        val now = SystemClock.elapsedRealtime()
        if (target > 0 && now - lastCountAt >= SONG_COUNT_DEBOUNCE_MS) {
            lastCountAt = now
            playedCount += 1
            if (playedCount >= target) {
                // 暂停放在「下一首已经就绪」之后：回绕/切歌的瞬间 setMediaItems 会先清空曲目，
                // 直接 pause 会被随后的 prepare+play 覆盖，这里下一帧再停
                afterPlayOne {
                    controller?.pause()
                    sleepTimer = SleepTimer()
                }
                playedCount = 0
            }
        }
    }
    // 重新选择歌数时从头计数
    LaunchedEffect(sleepTimer.songs) { playedCount = 0 }

    // 定时关闭（按首数）：只在「自然播完一首」时计数，手动切歌不算。
    // 旧实现只认 onMediaItemTransition(AUTO)，单曲循环不会有 item transition（只会原地回绕），
    // 于是「播完 N 首暂停」永远不会触发。这里两个信号都算一次：
    // 1. 列表里自动切到下一首：MEDIA_ITEM_TRANSITION_REASON_AUTO；
    // 2. 单曲循环原地回绕：onPositionDiscontinuity(AUTO_TRANSITION) 且曲目没变，并做位置回绕校验，
    //    seek / 拖动进度条 / 手动切歌（REASON_SEEK、REASON_SKIP）都不计数。
    DisposableEffect(controller) {
        val c = controller
        if (c == null) return@DisposableEffect onDispose { }
        val listener =
            object : Player.Listener {
                /** 上一次见过的曲目下标：只有下标真的变了才算「切歌」，防止与回绕重复计数 */
                private var lastIndex = c.currentMediaItemIndex

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    val index = c.currentMediaItemIndex
                    // AUTO 且下标变了 = 列表里自然切到下一首；REASON_REPEAT 且下标没变
                    // = 单曲循环原地回绕（重复计数由 countPlayedSong 内部去重兜住）
                    val autoAdvance = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && index != lastIndex
                    val singleRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && index == lastIndex
                    if (autoAdvance || singleRepeat) countPlayedSong()
                    lastIndex = index
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // 顺序播放放到最后一首自然结束：没有下一首可切，但这一首确实播完了，
                    // 「播完 N 首暂停」要把它算进去（循环模式不会进入 ENDED，不会重复计数）
                    if (playbackState == Player.STATE_ENDED) countPlayedSong()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    val repeatWrap =
                        reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                            oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
                            newPosition.positionMs <= REPEAT_WRAP_TOLERANCE_MS &&
                            oldPosition.positionMs >= newPosition.positionMs
                    if (repeatWrap) countPlayedSong()
                }
            }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    val iconPack = rememberIconPack()
    val backdrop = info?.artwork
    // 封面位图与压暗渐变只在封面变化时重建：以前每次重组都 asImageBitmap() + new Brush
    val backdropBitmap = remember(backdrop) { backdrop?.asImageBitmap() }
    val scrimBrush =
        remember {
            Brush.verticalGradient(
                listOf(
                    Color.Black.copy(alpha = 0.45f),
                    Color.Black.copy(alpha = 0.75f),
                    Color.Black.copy(alpha = 0.94f),
                ),
            )
        }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 封面做整页背景：高斯模糊（API 31+）+ 纵向压暗，Poweramp 那种感觉
        if (backdropBitmap != null) {
            Image(
                bitmap = backdropBitmap,
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
            Box(modifier = Modifier.fillMaxSize().background(scrimBrush))
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
                        .ifBlank { stringResource(R.string.audio_default_title) },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            // 进度条 + 时间文本单独成叶子：只有它读每 500ms 变的 progress，
            // 整屏（封面 Image / blur(56.dp) / 渐变底 / 按钮行）不会跟着一起重组。
            AudioProgressLeaf(progress = progress, controller = controller)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                // 播放模式：一个按钮在四态里轮换（顺序 → 列表循环 → 单曲循环 → 随机）。
                // 以前是「随机开关 + 循环按钮」两个独立开关，随机 + 单曲循环同时打开时
                // ExoPlayer 会在随机队列里死循环同一首 —— 现在两个开关永远成对设置。
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = {
                            // 先更新 UI 再写播放器：轮询 400ms 后才回读，先改状态按钮不会闪一下旧模式
                            val next = playMode.next()
                            playMode = next
                            lastModeSetAt = SystemClock.elapsedRealtime()
                            applyPlayMode(controller, next)
                        },
                    ) {
                        Icon(
                            imageVector = playModeIcon(playMode),
                            contentDescription = stringResource(R.string.play_mode_next),
                            tint =
                                if (playMode == PlayMode.SEQUENTIAL) {
                                    Color.White.copy(alpha = 0.6f)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                        )
                    }
                    // 大字号下模式名可能变长，限一行省略，避免把控制条撑破
                    Text(
                        stringResource(playModeLabelRes(playMode)),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 跳过/队列这三个按钮只有图标，补一行小字说明它们各是做什么的
                PjmIconWithCaption(
                    icon = Lucide.SkipBack,
                    contentDescription = stringResource(R.string.cd_previous),
                    caption = stringResource(R.string.hint_previous),
                    onClick = { controller?.seekToPreviousMediaItem() },
                    iconTint = Color.White,
                    captionColor = Color.White.copy(alpha = 0.7f),
                    iconSize = 32.dp,
                )
                AudioPlayPauseButton(progress = progress, controller = controller)
                PjmIconWithCaption(
                    icon = Lucide.SkipForward,
                    contentDescription = stringResource(R.string.cd_next),
                    caption = stringResource(R.string.hint_next),
                    onClick = { controller?.seekToNextMediaItem() },
                    iconTint = Color.White,
                    captionColor = Color.White.copy(alpha = 0.7f),
                    iconSize = 32.dp,
                )
                // 队列入口：音频播放器不再有倍速按钮，这里补回一个同样大小的占位，
                // 让上一首/播放/下一首保持居中；右侧按钮是定时关闭的入口对齐位
                PjmIconWithCaption(
                    icon = Lucide.ListMusic,
                    contentDescription = stringResource(R.string.playlist_title, queue.size),
                    caption = stringResource(R.string.hint_queue),
                    onClick = { showPlaylist = true },
                    iconTint = Color.White.copy(alpha = 0.6f),
                    captionColor = Color.White.copy(alpha = 0.5f),
                    iconSize = 24.dp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (lyrics.isNotEmpty()) {
                    TextButton(onClick = { showLyrics = true }) {
                        Text(stringResource(R.string.player_lyrics), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                }
                // 定时关闭：分钟制与按首数制共用一个入口，点哪个是哪个（两者严格互斥）
                Box {
                    var sleepMenu by remember { mutableStateOf(false) }
                    TextButton(onClick = { sleepMenu = true }) {
                        Text(
                            text =
                                when {
                                    sleepTimer.songs > 0 -> stringResource(R.string.sleep_timer_songs, sleepTimer.songs)
                                    sleepTimer.leftMs > 0L -> stringResource(R.string.sleep_timer_left, formatPlaybackTime(sleepTimer.leftMs))
                                    else -> stringResource(R.string.audio_sleep_timer)
                                },
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                        )
                    }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        listOf(0, 15, 30, 45, 60, 90).forEach { minutes ->
                            val current = sleepTimer.minutes == minutes && sleepTimer.songs == 0
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text =
                                            if (minutes == 0) {
                                                stringResource(R.string.sleep_timer_off)
                                            } else {
                                                stringResource(R.string.sleep_timer_minutes, minutes)
                                            },
                                        color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    )
                                },
                                onClick = {
                                    // 选分钟制 = 清掉按首数制（另一个计时协程会被 key 变化取消）
                                    sleepTimer = SleepTimer(minutes = minutes)
                                    sleepMenu = false
                                },
                            )
                        }
                        listOf(1, 3, 5, 10).forEach { songs ->
                            val current = sleepTimer.songs == songs
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.sleep_timer_after_songs, songs),
                                        color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    )
                                },
                                onClick = {
                                    // 选按首数制 = 清掉分钟制，保证同一时刻只有一条路径在跑
                                    sleepTimer = SleepTimer(songs = songs)
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
                        text = stringResource(R.string.playlist_title, queue.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                        // 稳定 key：队列里插入/删除时，行状态不会按 index 错位
                        itemsIndexed(queue, key = { _, entity -> entity.relativePath }) { index, entity ->
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
                // 列表单独成叶子：只有它读每 500ms 变的 progress，弹窗本身不跟着重组
                LyricsList(
                    lyrics = lyrics,
                    progress = progress,
                    onSeekTo = { startMs -> controller?.seekTo(startMs) },
                )
            }
        }
    }
}

/**
 * 音频页的进度区（进度条 + 拖动气泡 + 两端时间）。
 *
 * 之所以单独成一个 composable：progress 每 500ms 变一次，这些读取如果留在 [AudioViewer] 顶层，
 * 整屏（封面 Image、blur(56.dp)、渐变底、按钮行）都会跟着每秒重组两次。
 */
@Composable
private fun AudioProgressLeaf(
    progress: PlayerProgressState,
    controller: MediaController?,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        PlayerProgressBar(
            progress = progress.fraction,
            onScrub = { fraction -> progress.scrubTo((fraction * progress.durationMs).toLong()) },
            onSeek = {
                val target = (it * progress.durationMs).toLong()
                controller?.seekTo(target)
                progress.endScrub(target)
            },
            activeColor = MaterialTheme.colorScheme.primary,
        )
        // 拖动时在上方冒一个时间气泡，松手才真正跳转
        if (progress.scrubbing) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                shadowElevation = 3.dp,
            ) {
                Text(
                    text = formatPlaybackTime(progress.shownPositionMs),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatPlaybackTime(progress.shownPositionMs), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        Text(formatPlaybackTime(progress.durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

/** 播放/暂停按钮：只读 isPlaying（低频变化），不牵动整屏 */
@Composable
private fun AudioPlayPauseButton(
    progress: PlayerProgressState,
    controller: MediaController?,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = { if (progress.isPlaying) controller?.pause() else controller?.play() },
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                imageVector = if (progress.isPlaying) Lucide.Pause else Lucide.Play,
                contentDescription = stringResource(if (progress.isPlaying) R.string.action_pause else R.string.action_play),
                modifier = Modifier.size(32.dp),
            )
        }
        PjmButtonCaption(
            caption = stringResource(if (progress.isPlaying) R.string.hint_pause else R.string.hint_play),
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

/** 拖动进度时中间的「当前 / 总长」提示（控制栏不可见时才出现） */
@Composable
private fun VideoScrubHint(
    progress: PlayerProgressState,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible || !progress.scrubbing) return
    Box(
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = formatPlaybackTime(progress.shownPositionMs) + " / " + formatPlaybackTime(progress.durationMs),
            color = Color.White,
        )
    }
}

/**
 * 歌词面板：当前行高亮 + 自动滚动 + 点一句跳过去。
 *
 * 单独成 composable，是为了把「每 500ms 变的 progress」的读取关在这一层；
 * 当前行下标用 derivedStateOf 缓存，item 内只做一次下标比较（不再是每项一遍 indexOfLast）。
 */
@Composable
private fun LyricsList(
    lyrics: List<Pair<Long, String>>,
    progress: PlayerProgressState,
    onSeekTo: (Long) -> Unit,
) {
    val listState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    val activeIndex by remember(lyrics) {
        derivedStateOf { lyrics.indexOfLast { it.first <= progress.positionMs } }
    }
    // 稳定 key：时间戳 + 文字（重复行由 uniqueKeys 补序号，避免 LazyColumn 因 key 重复崩溃）
    val lyricKeys = remember(lyrics) { uniqueKeys(lyrics.map { it.first.toString() + "|" + it.second }) }
    LaunchedEffect(activeIndex) {
        if (activeIndex > 0) {
            runCatching { listState.animateScrollToItem(activeIndex) }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(bottom = 24.dp),
    ) {
        items(lyrics.size, key = { lyricKeys[it] }) { index ->
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
                        .clickable { onSeekTo(startMs) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
            )
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

/**
 * Lazy 列表的稳定 key：内容重复时（同一时间戳的重复歌词行、同名同页章节）补序号，
 * 避免 LazyColumn 因 key 重复直接抛 IllegalArgumentException。
 */
private fun uniqueKeys(bases: List<String>): List<String> {
    val seen = HashMap<String, Int>(bases.size)
    return bases.map { base ->
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        if (count == 1) base else base + "#" + count
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
        // 顶栏只有一颗返回箭头：图标本身够直观，但按用户要求统一补一行小字，
        // 顶栏高度由状态栏内边距兜底，不会把标题挤掉（标题仍是 weight(1f)）。
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onBack) {
                Icon(Lucide.ArrowLeft, stringResource(R.string.action_back), tint = Color.White)
            }
            PjmButtonCaption(
                caption = stringResource(R.string.hint_back),
                color = Color.White.copy(alpha = 0.7f),
            )
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
