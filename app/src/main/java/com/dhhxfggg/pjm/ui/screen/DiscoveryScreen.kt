package com.dhhxfggg.pjm.ui.screen

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.util.DiscoveryPlayerPool
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.ShareUtils
import com.dhhxfggg.pjm.domain.util.VaultPaths
import com.dhhxfggg.pjm.ui.component.PjmDeleteConfirmDialog
import com.dhhxfggg.pjm.ui.component.PjmEmptyState
import com.dhhxfggg.pjm.ui.component.PlayerProgressBar
import com.dhhxfggg.pjm.ui.component.VideoControlBar
import com.dhhxfggg.pjm.ui.component.VideoSurface
import com.dhhxfggg.pjm.ui.component.ZoomableVaultImage
import com.dhhxfggg.pjm.ui.component.formatPlaybackTime
import com.dhhxfggg.pjm.ui.component.rememberAudioInfo
import com.dhhxfggg.pjm.ui.component.rememberIsAppVisible
import com.dhhxfggg.pjm.ui.component.rememberPlayerProgress
import com.dhhxfggg.pjm.ui.theme.rememberIconPack
import com.dhhxfggg.pjm.ui.viewmodel.DiscoveryItem
import com.dhhxfggg.pjm.ui.viewmodel.DiscoveryMode
import com.dhhxfggg.pjm.ui.viewmodel.DiscoveryViewModel
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

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
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    // 每次导航回发现页都按设置里的默认分类重新定一次（VM 会跨导航存活，必须显式重放）
    LaunchedEffect(Unit) { viewModel.applyDefaultMode() }
    val settingsState by settingsViewModel.uiState.collectAsState()
    val predictiveBackEnabled = settingsState.settings.predictiveBackEnabled

    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val activity = context as? Activity

    val iconPack = rememberIconPack()

    val shareContentTitle = stringResource(R.string.chooser_title_share_content)

    var isInteractionLocked by remember { mutableStateOf(value = false) }
    val scope = rememberCoroutineScope()
    val pagerState =
        rememberPagerState(
            pageCount = { uiState.items.size },
        )
    var showDeleteConfirm by remember { mutableStateOf<DiscoveryItem?>(null) }

    // 右上角常驻「⋮」菜单的开合状态。
    // 分享/删除以前挂在长按上，和"长按加速"抢同一个手势，两个功能都半残；
    // 现在常驻入口，长按专心做加速。
    var itemMenuOpen by remember { mutableStateOf(value = false) }

    // 预测性返回：全屏观感下拖动返回手势时画面跟着缩小，松手才退出全屏
    var backProgress by remember { mutableFloatStateOf(0f) }
    if (predictiveBackEnabled) {
        PredictiveBackHandler(enabled = isFullScreen) { progress ->
            try {
                progress.collect { event -> backProgress = event.progress }
                onFullScreenChange(false)
            } finally {
                backProgress = 0f
            }
        }
    } else {
        BackHandler(enabled = isFullScreen) {
            backProgress = 0f
            onFullScreenChange(false)
        }
    }

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

    // 核心修复：用「已停稳的页」而不是「当前页」来驱动副作用与播放器激活状态。
    //
    // 旧写法在两处直接读 `pagerState.currentPage`：
    //  1) 顶层 `LaunchedEffect(pagerState.currentPage, ...)` —— 顶层读取快照状态意味着
    //     **每次翻页都会重组整个 DiscoveryScreen**（含模式 Tab 与所有已组合页面）；
    //  2) 每页的 `isActive = (currentPage == page)` —— 滑动过半阈值时旧页立刻 release、
    //     新页 setMediaItem + prepare()，来回抖动还会反复重建解码器。
    //
    // `settledPage` 只在页面真正停稳后变化（derivedStateOf 会缓存，滑动过程中不触发重组），
    // 两个问题一并解决；`LaunchedEffect` 的读取发生在 effect 体内，不构成组合期读取。
    val settledPage by remember { derivedStateOf { pagerState.settledPage } }
    val isPagerScrolling by remember { derivedStateOf { pagerState.isScrollInProgress } }

    LaunchedEffect(settledPage, uiState.items.size) {
        isInteractionLocked = false
        if (uiState.items.isNotEmpty() && (settledPage >= (uiState.items.size - 2))) {
            viewModel.loadMoreItems()
        }
    }

    // 逐张记录"真的看过"：只有翻到并停稳的那一张才算，未翻到的条目不受牵连。
    //
    // 用 settledPage（页面停稳）而不是 currentPage：翻页动画期间被划过的那几张
    // 并没有真正停留观看，过早计入等于重复了"取批即算看过"的老毛病。
    // 去重集合按 mode 重建（切分类时才丢），同一个路径只写一次库。
    val viewedPaths = remember(uiState.mode) { mutableSetOf<String>() }
    LaunchedEffect(settledPage, uiState.items.size) {
        val shown = uiState.items.getOrNull(settledPage) ?: return@LaunchedEffect
        val path = shown.entity.relativePath
        if (viewedPaths.add(path)) viewModel.markViewed(path)
    }

    val finalBottomPadding = if (isFullScreen) 0.dp else bottomPadding

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color = if (isFullScreen) Color.Black else Color.Transparent)
                .graphicsLayer {
                    if (isFullScreen) {
                        translationX = size.width * 0.18f * backProgress
                        val s = 1f - 0.05f * backProgress
                        scaleX = s
                        scaleY = s
                    }
                },
    ) {
        if (uiState.items.isEmpty() && uiState.isLoading) {
            // 还在加载：显示转圈
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
            }
        } else if (uiState.items.isEmpty()) {
            // 核心修复：加载完成但确实没有内容。
            // 原实现只有上面那个分支 —— 库为空时转圈会**永远转下去**，
            // 用户会以为卡住了，实际是根本没东西可看。
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                PjmEmptyState(
                    icon = iconPack.stateEmpty,
                    title = stringResource(R.string.discovery_empty_title),
                    description = stringResource(R.string.discovery_empty_hint),
                )
            }
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = !isInteractionLocked,
                key = { index -> uiState.items.getOrNull(index)?.displayId ?: index },
            ) { page ->
                val item = uiState.items.getOrNull(page)
                if (item != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        DiscoveryItemRenderer(
                            item = item,
                            // 用 settledPage：页面停稳后才成为激活项，避免滑动过半时
                            // 播放器在两个页面之间来回抢占用/释放、反复 prepare 解码器。
                            isActive = (settledPage == page),
                            isScrolling = isPagerScrolling,
                            isFullScreen = isFullScreen,
                            bottomPadding = finalBottomPadding,
                            onToggleFullScreen = { onFullScreenChange(!isFullScreen) },
                            onLockChange = { isInteractionLocked = it },
                            onLongPress = {
                                // 长按只做触感反馈：播放/查看器内部用它做"加速"
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onVideoEnded = {
                                // 自动连播：播完就滚到下一项（跨 视频 / B站视频 两种模式都适用）
                                val next = (pagerState.currentPage + 1).coerceAtMost(uiState.items.lastIndex)
                                if (next > pagerState.currentPage) {
                                    scope.launch { pagerState.animateScrollToPage(next) }
                                }
                                viewModel.loadMoreItems()
                            },
                        )
                    }
                }
            }
        }

        // 操作入口固定在右上角，并且整屏只渲染这一个菜单：
        // 之前菜单写在每个 pager 页面里，翻页时前后几页各弹一个，看起来就是"冒出好几个分享/删除"
        val currentItem = uiState.items.getOrNull(settledPage)
        if (!isFullScreen && currentItem != null) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        // 往下挪：顶部有进度条/模式切换时会盖住这颗按钮，点不到
                        .padding(top = 56.dp, end = 12.dp),
            ) {
                IconButton(
                    onClick = { itemMenuOpen = true },
                    modifier = Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape),
                ) {
                    Icon(iconPack.actionMore, contentDescription = stringResource(R.string.action_more), tint = Color.White)
                }
                DropdownMenu(expanded = itemMenuOpen, onDismissRequest = { itemMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share)) },
                        leadingIcon = { Icon(iconPack.actionShare, contentDescription = null) },
                        onClick = {
                            itemMenuOpen = false
                            shareDiscoveryItem(context, currentItem, shareContentTitle)
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = Color.Red.copy(alpha = 0.9f)) },
                        leadingIcon = {
                            Icon(iconPack.actionDelete, contentDescription = null, tint = Color.Red.copy(alpha = 0.9f))
                        },
                        onClick = {
                            itemMenuOpen = false
                            showDeleteConfirm = currentItem
                        },
                    )
                }
            }
        }

        if (!isFullScreen) {
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .statusBarsPadding(),
                color = Color.Black.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                    DiscoveryModeTab(stringResource(R.string.mode_bili_videos), uiState.mode == DiscoveryMode.BILI_VIDEOS) {
                        viewModel.setMode(DiscoveryMode.BILI_VIDEOS)
                    }
                    DiscoveryModeTab(
                        stringResource(R.string.mode_images),
                        uiState.mode == DiscoveryMode.IMAGES,
                    ) { viewModel.setMode(DiscoveryMode.IMAGES) }
                    DiscoveryModeTab(
                        stringResource(R.string.mode_videos),
                        uiState.mode == DiscoveryMode.VIDEOS,
                    ) { viewModel.setMode(DiscoveryMode.VIDEOS) }
                    DiscoveryModeTab(
                        stringResource(R.string.cat_short_audios),
                        uiState.mode == DiscoveryMode.AUDIOS,
                    ) { viewModel.setMode(DiscoveryMode.AUDIOS) }
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
            },
        )
    }
}

/**
 * 音频发现页：封面 + 标题/艺术家 + 官方 PlayerView 控制条。
 * 播放器仍从复用池借，切页即释放，和视频页一致；播完自动连播由 onAudioEnded 驱动。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun DiscoveryAudioRenderer(
    item: DiscoveryItem.Audio,
    isActive: Boolean,
    isScrolling: Boolean,
    onLongPress: () -> Unit,
    onAudioEnded: () -> Unit,
    fallbackCoverPath: String? = null,
) {
    val context = LocalContext.current
    val iconPack = rememberIconPack()
    // 没有内嵌封面时用一张随机图片当封面（尺寸交给 Coil 采样，不会整张原图进内存）
    val fallbackCover = fallbackCoverPath?.let { remember(it) { VaultPaths.getFileByRelativePath(context, it) } }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    val info = rememberAudioInfo(item.file)
    val isAppVisible = rememberIsAppVisible()

    DisposableEffect(item.displayId, isActive) {
        if (!isActive) {
            onDispose {}
        } else {
            val player = DiscoveryPlayerPool.acquire(context)
            exoPlayer = player
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", item.file)
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            val listener =
                object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) onAudioEnded()
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

    LaunchedEffect(isActive, isScrolling, isAppVisible) {
        if (isActive && !isScrolling && isAppVisible) exoPlayer?.play() else exoPlayer?.pause()
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(item.displayId) {
                    detectTapGestures(onLongPress = { onLongPress() })
                },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.5f))
            val artwork = info?.artwork
            if (artwork != null) {
                Image(
                    bitmap = artwork.asImageBitmap(),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .fillMaxWidth(0.72f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else if (fallbackCover != null) {
                AsyncImage(
                    model = fallbackCover,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .fillMaxWidth(0.72f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.72f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = iconPack.fileAudio,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(96.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = info?.title ?: FileUtils.normalizedDisplayName(item.entity),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text =
                    listOfNotNull(info?.artist, info?.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { stringResource(R.string.label_unknown_artist) },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(0.4f))
            DiscoveryAudioControls(player = exoPlayer)
        }
    }
}

/** 音频播放控制条：进度 + 时间 + 播放/暂停（自己画，避免 PlayerView 再叠一层封面） */
@Composable
private fun DiscoveryAudioControls(player: ExoPlayer?) {
    // 轮询/拖动锁定逻辑和视频页共用一份，这里只管排布
    val progress = rememberPlayerProgress(player)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        PlayerProgressBar(
            progress = progress.fraction,
            onScrub = { fraction -> progress.scrubTo((fraction * progress.durationMs).toLong()) },
            onSeek = {
                val target = (it * progress.durationMs).toLong()
                player?.seekTo(target)
                progress.endScrub(target)
            },
            activeColor = Color.White,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatPlaybackTime(progress.shownPositionMs), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            Text(formatPlaybackTime(progress.durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        }
        IconButton(
            onClick = { if (progress.isPlaying) player?.pause() else player?.play() },
            modifier = Modifier.align(Alignment.CenterHorizontally).size(64.dp),
        ) {
            Icon(
                imageVector = if (progress.isPlaying) Lucide.Pause else Lucide.Play,
                contentDescription = stringResource(if (progress.isPlaying) R.string.action_pause else R.string.action_play),
                tint = Color.White,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}

/**
 * Renders an individual discovery item, choosing between image, video and audio display.
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
    onLongPress: () -> Unit,
    onVideoEnded: () -> Unit,
    audioCoverPath: String? = null,
) {
    key(item.displayId) {
        when (item) {
            is DiscoveryItem.Image -> DiscoveryImageRenderer(item, onLockChange, onLongPress)
            is DiscoveryItem.Audio ->
                DiscoveryAudioRenderer(
                    item = item,
                    isActive = isActive,
                    isScrolling = isScrolling,
                    onLongPress = onLongPress,
                    onAudioEnded = onVideoEnded,
                    fallbackCoverPath = audioCoverPath,
                )
            is DiscoveryItem.Video ->
                DiscoveryVideoRenderer(
                    item,
                    isActive,
                    isScrolling,
                    isFullScreen,
                    bottomPadding,
                    onToggleFullScreen,
                    onLockChange,
                    onLongPress,
                    onVideoEnded,
                )
        }
    }
}

/**
 * Handles rendering and interactive gestures for image discovery items.
 */
@Composable
fun DiscoveryImageRenderer(
    item: DiscoveryItem.Image,
    onLockChange: (Boolean) -> Unit,
    onLongPress: () -> Unit,
) {
    // 和库内查看器共用同一个缩放组件：边界由组件统一算，放大后不会把图拖出屏幕
    ZoomableVaultImage(
        file = item.file,
        onZoomedChange = onLockChange,
        onLongPress = onLongPress,
    )
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
    onLongPress: () -> Unit,
    onVideoEnded: () -> Unit,
) {
    val context = LocalContext.current
    // 手势回调不是组合作用域，用 LocalResources（跟随语言/配置变化）而不是 LocalContext 读资源
    val resources = LocalResources.current
    // 核心优化：从复用池借播放器（避免滑动时反复创建/销毁 MediaCodec 实例）
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    // 进度/时长/播放状态统一交给共享状态轮询，本层不再自己缓存一份 duration：
    // 之前父层（READY 时更新一次）和子组件（500ms 轮询）各存一份，切集后会短暂对不上。
    // 注意：本层只在手势回调里读它（手势回调不属于重组作用域，读它不会引起重组）。
    val progress = rememberPlayerProgress(exoPlayer)
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
            val listener =
                object : Player.Listener {
                    override fun onPlaybackStateChanged(s: Int) {
                        // 播完自动连播下一项（发现页两种视频模式共用）
                        if (s == Player.STATE_ENDED) onVideoEnded()
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

    // 核心修复：播放条件必须并入「App 是否在前台」。
    // 原来只看 isActive/isScrolling，按 Home 键或熄屏后这些条件依然成立，
    // 视频音频会继续播放（耗电，隐私场景下也很尴尬）。
    val isAppVisible = rememberIsAppVisible()
    LaunchedEffect(isActive, isScrolling, isAppVisible) {
        if (isActive && !isScrolling && isAppVisible) exoPlayer?.play() else exoPlayer?.pause()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VideoSurface(
            player = exoPlayer,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .pointerInput(item.displayId) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = { offset ->
                                // 左右分区跳转：左 1/3 后退、右 1/3 前进、中间不动作
                                val w = size.width.toFloat()
                                val pos = exoPlayer?.currentPosition ?: 0L
                                val total = progress.durationMs
                                val target =
                                    when {
                                        total <= 15_000L -> pos
                                        offset.x < w / 3f -> (pos - 10_000L).coerceAtLeast(0L)
                                        offset.x > w * 2f / 3f -> (pos + 10_000L).coerceAtMost(total)
                                        else -> pos
                                    }
                                if (target != pos) exoPlayer?.seekTo(target)
                            },
                            onLongPress = { onLongPress() },
                        )
                    }.pointerInput(item.displayId) {
                        var dragTotalX = 0f
                        var startPosition = 0L
                        // 松手要对齐到这个位置；拖到哪就算哪（取消也一样）
                        var lastPosition = 0L
                        detectHorizontalDragGestures(
                            onDragStart = {
                                onLockChange(true)
                                // 拖动进度时不弹控制条：只显示中间的秒数提示
                                dragTotalX = 0f
                                startPosition = exoPlayer?.currentPosition ?: 0L
                                lastPosition = startPosition
                                progress.scrubTo(startPosition)
                            },
                            onDragEnd = {
                                onLockChange(false)
                                seekLabel = ""
                                progress.endScrub(lastPosition)
                            },
                            onDragCancel = {
                                onLockChange(false)
                                seekLabel = ""
                                progress.endScrub(lastPosition)
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                val total = progress.durationMs
                                // 总时长还没就绪（未 READY / 时长未知）：这次拖动不处理，
                                // 否则 coerceIn(0, 0) 会把进度直接拖回开头
                                if (total <= 0L) return@detectHorizontalDragGestures
                                dragTotalX += dragAmount
                                val screenWidth = size.width.toFloat()
                                val seekRatio = (dragTotalX / screenWidth) * 0.5f
                                val seekOffsetMs = (total * seekRatio).toLong()
                                val targetPosition = (startPosition + seekOffsetMs).coerceIn(0L, total)

                                exoPlayer?.seekTo(targetPosition)
                                lastPosition = targetPosition
                                progress.scrubTo(targetPosition)

                                val diffSec = (targetPosition - startPosition) / 1000
                                seekLabel =
                                    if (diffSec >= 0) {
                                        resources.getString(R.string.fast_seek_forward, diffSec)
                                    } else {
                                        resources.getString(R.string.fast_seek_backward, diffSec)
                                    }

                                change.consume()
                            },
                        )
                    },
        )

        if (seekLabel.isNotEmpty()) {
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 60.dp),
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(seekLabel, color = Color.White, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
            }
        }

        if (showControls && !progress.isPlaying) {
            IconButton(
                onClick = { exoPlayer?.play() },
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(
                    Lucide.Play,
                    null,
                    modifier = Modifier.size(80.dp),
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
        }

        // 控制条和查看器共用一份：进度更新只重组它自己，不波及播放器本体与手势层
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VideoControlBar(
                player = exoPlayer,
                progress = progress,
                bottomPadding = bottomPadding + (if (isFullScreen) 16.dp else 48.dp),
                fullscreen = isFullScreen,
                onToggleFullscreen = onToggleFullScreen,
            )
        }
    }
}

/**
 * A circular action icon for sharing or deleting items.
 */
@Composable
fun DiscoveryActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                .size(48.dp),
    ) {
        Icon(icon, label, tint = tint)
    }
}

/**
 * A tab button for switching between discovery modes.
 */
@Composable
fun DiscoveryModeTab(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
        )
    }
}

/**
 * Shares a discovery item using a system share intent.
 *
 * 统一走 [ShareUtils]：MIME 由真实文件类型推导，并写入 ClipData 保证读权限随 Intent 授予。
 */
private fun shareDiscoveryItem(
    context: Context,
    item: DiscoveryItem,
    chooserTitle: String,
) {
    val mimeType = if (item is DiscoveryItem.Image) "image/*" else "video/*"
    val chooser = ShareUtils.createShareChooser(context, item.file, chooserTitle, mimeType)
    if (chooser == null) {
        Toast.makeText(context, context.getString(R.string.toast_share_nothing_available), Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { context.startActivity(chooser) }
        .onFailure { Toast.makeText(context, context.getString(R.string.toast_share_nothing_available), Toast.LENGTH_SHORT).show() }
}
