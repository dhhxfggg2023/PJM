package com.dhhxfggg.pjm.ui.screen

import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.LayoutList
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.X
import com.dhhxfggg.pjm.MainApplication
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.ShareUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.ui.component.FileCard
import com.dhhxfggg.pjm.ui.component.PjmAeroDialog
import com.dhhxfggg.pjm.ui.component.PjmDeleteConfirmDialog
import com.dhhxfggg.pjm.ui.component.PjmIconButton
import com.dhhxfggg.pjm.ui.component.PjmRenameDialog
import com.dhhxfggg.pjm.ui.component.SelectableFileCard
import com.dhhxfggg.pjm.ui.theme.rememberIconPack
import com.dhhxfggg.pjm.ui.viewmodel.CryptoViewModel
import com.dhhxfggg.pjm.ui.viewmodel.FileListItem
import com.dhhxfggg.pjm.ui.viewmodel.FileViewerViewModel
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Screen for viewing and managing files within a specific vault category.
 * Supports batch operations, different view modes, and shared element transitions.
 *
 * 注：本页**没有搜索功能**（原注释有误）。保险库内文件统一以 UUID 命名，
 * 按文件名搜索没有意义；需要按名字查找请使用 `PjmFileBrowser`（浏览外部目录时文件名才可读）。
 *
 * @param category The category key (e.g., "images", "videos").
 * @param bottomPadding Padding to be applied at the bottom of the screen.
 * @param settingsViewModel ViewModel for UI settings.
 * @param fileViewModel ViewModel for file operations.
 * @param cryptoViewModel ViewModel for cryptographic operations.
 * @param onNavigateToMediaDetail Callback to navigate to the media detail screen.
 * @param onBack Callback to navigate back.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileViewerScreen(
    category: String,
    bottomPadding: Dp = 0.dp,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    fileViewModel: FileViewerViewModel = hiltViewModel(),
    cryptoViewModel: CryptoViewModel = hiltViewModel(LocalActivity.current as ComponentActivity),
    onNavigateToMediaDetail: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportSuccessMsg = stringResource(R.string.status_export_success)
    // Compose lint 禁止在组合作用域用 LocalContext.current 取资源（LocalContextGetResourceValueCall），
    // 这里提前取好文案/模板：模板不带参数取回的是原始 "%1$d/%2$d" 字符串，运行时再格式化。
    val exportFailedMsg = stringResource(R.string.error_export_failed_simple)
    val exportPartialTemplate = stringResource(R.string.status_export_partial)
    val shareFilesMsg = stringResource(R.string.chooser_title_share_files)

    // 长按卡片重命名（只改库里的显示名）；库里不放「⋮」，那个入口只属于发现页
    var renameTarget by remember { mutableStateOf<FileEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntity?>(null) }

    val iconPack = rememberIconPack()

    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val settings = settingsState.settings

    val uiState by fileViewModel.uiState.collectAsStateWithLifecycle()
    val flattenedItemsFlow by remember(category) { fileViewModel.getFlattenedFiles(category) }.collectAsStateWithLifecycle()
    // null = 查询还没回来（显示加载圈）；empty = 真的没有文件（显示空状态）
    val filesStillLoading = flattenedItemsFlow == null
    val flattenedItems = flattenedItemsFlow ?: emptyList()

    // 自动找回逻辑：进入分类时如果为空，尝试从物理路径同步
    LaunchedEffect(category) {
        fileViewModel.syncIfEmpty(category)
    }

    // 选区只存路径集合：拖选时一次赋值（原来每帧先 clear 再 addAll，还要重建一整份 HashSet）
    val selectionSaver =
        remember {
            listSaver<Set<String>, String>(
                save = { it.toList() },
                restore = { it.toSet() },
            )
        }
    var selected by rememberSaveable(stateSaver = selectionSaver) { mutableStateOf(emptySet<String>()) }
    // 真正需要实体时（分享/导出/删除/重命名）再按当前列表解析；卡片判定直接用 selected.contains（O(1)）
    val selectedFiles: List<FileEntity> by remember(flattenedItems) {
        derivedStateOf {
            if (selected.isEmpty()) {
                emptyList<FileEntity>()
            } else {
                flattenedItems.mapNotNull { (it as? FileListItem.FileItem)?.entity }.filter { it.relativePath in selected }
            }
        }
    }

    var fileForDetails by remember { mutableStateOf<FileEntity?>(null) }
    var showBatchDeleteConfirmDialog by remember { mutableStateOf(value = false) }

    // 相册式「长按后滑动连续多选」：用最新列表做命中测试（列表变化时不重启手势）
    val itemsState = rememberUpdatedState(flattenedItems)
    // 选区 = 「起点行 → 当前触点的整段」（含中间每一行、跳过日期表头），
    // 而不是「手指碰巧路过的那些」：快速滑动时指针事件会跳行，逐项 add 必漏。
    val applySelectionRange =
        remember(itemsState) {
            { anchor: Int, current: Int, base: Set<String> ->
                if (anchor < 0 || current < 0) {
                    Unit
                } else {
                    val items = itemsState.value
                    val lo = minOf(anchor, current).coerceAtLeast(0)
                    val hi = maxOf(anchor, current).coerceAtMost(items.lastIndex)
                    // 拖拽前已勾选的保持勾选；区间内的补进来；区间外的旧勾选不动。
                    // 组装好新集合后一次赋值（拖选每帧都会走到这里）
                    val next = HashSet<String>(hi - lo + 1 + base.size)
                    next.addAll(base)
                    for (i in lo..hi) {
                        (items.getOrNull(i) as? FileListItem.FileItem)?.let { next.add(it.entity.relativePath) }
                    }
                    selected = next
                }
            }
        }

    // 长按松手补发的那次点击：记录"这一项在这个时间点之前不要反选"
    var suppressLongPressClick by remember { mutableStateOf<Pair<String, Long>?>(null) }

    val toggleSelection =
        remember {
            { file: FileEntity ->
                val pending = suppressLongPressClick
                if (pending != null && pending.first == file.relativePath && System.currentTimeMillis() <= pending.second) {
                    // 长按已经把它选中了，这次点击什么都不做
                } else {
                    selected =
                        if (file.relativePath in selected) {
                            selected - file.relativePath
                        } else {
                            selected + file.relativePath
                        }
                    if (selected.isEmpty()) {
                        fileViewModel.setBatchMode(false)
                    }
                }
            }
        }

    val categoryDisplayName =
        when (category) {
            VaultManager.CAT_PJM -> stringResource(R.string.cat_pjm_display)
            VaultManager.CAT_BILI_VIDEOS -> stringResource(R.string.mode_bili_videos)
            VaultManager.CAT_IMAGES -> stringResource(R.string.cat_images_display)
            VaultManager.CAT_VIDEOS -> stringResource(R.string.cat_videos_display)
            VaultManager.CAT_TEXTS -> stringResource(R.string.cat_texts_display)
            VaultManager.CAT_AUDIOS -> stringResource(R.string.cat_audios_display)
            else -> stringResource(R.string.cat_others_display)
        }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (uiState.isBatchMode) stringResource(R.string.title_batch_mode) else categoryDisplayName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (uiState.isBatchMode) {
                            Text(
                                text = stringResource(R.string.label_selected_count, selected.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.isBatchMode) {
                                fileViewModel.setBatchMode(false)
                                selected = emptySet()
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        Icon(
                            if (uiState.isBatchMode) Lucide.X else Lucide.ArrowLeft,
                            stringResource(if (uiState.isBatchMode) R.string.action_close else R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (uiState.isBatchMode) {
                        val allFilteredFiles =
                            remember(flattenedItems) {
                                flattenedItems
                                    .asSequence()
                                    .filterIsInstance<FileListItem.FileItem>()
                                    .map { it.entity }
                                    .toList()
                            }
                        val isAllSelected = (selected.size == allFilteredFiles.size) && allFilteredFiles.isNotEmpty()
                        PjmIconButton(
                            icon = if (isAllSelected) Lucide.Square else Lucide.CheckCheck,
                            contentDescription = stringResource(R.string.action_select_all),
                            onClick = {
                                if (isAllSelected) {
                                    selected = emptySet()
                                    fileViewModel.setBatchMode(false)
                                } else {
                                    selected = allFilteredFiles.mapTo(HashSet()) { it.relativePath }
                                }
                            },
                        )
                        Spacer(Modifier.width(6.dp))

                        // 重命名只对「单选 + 非图片」出现：图片显示名固定是入库时间，改了也没意义
                        if (selected.size == 1 && !selectedFiles[0].isImage) {
                            PjmIconButton(
                                icon = Lucide.Pencil,
                                contentDescription = stringResource(R.string.action_rename),
                                onClick = { renameTarget = selectedFiles[0] },
                            )
                            Spacer(Modifier.width(6.dp))
                        }

                        PjmIconButton(
                            icon = iconPack.actionShare,
                            contentDescription = stringResource(R.string.action_share),
                            onClick = {
                                // 分享到外部：用规范化显示名（PJM_入库时间.ext）生成可分享文件，磁盘原件不动
                                scope.launch {
                                    val namedFiles =
                                        withContext(Dispatchers.IO) {
                                            selectedFiles.map { FileUtils.obtainNamedShareFile(context, it) }
                                        }
                                    shareFiles(context, namedFiles, shareFilesMsg)
                                }
                            },
                        )
                        Spacer(Modifier.width(6.dp))

                        if ((category == VaultManager.CAT_IMAGES) ||
                            (category == VaultManager.CAT_VIDEOS) ||
                            (category == VaultManager.CAT_AUDIOS) ||
                            (category == VaultManager.CAT_BILI_VIDEOS)
                        ) {
                            PjmIconButton(
                                icon = Lucide.Download,
                                contentDescription = stringResource(R.string.action_export_to_gallery),
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        var count = 0
                                        selectedFiles.forEach {
                                            // 导出到相册：文件名统一为规范显示名（PJM_入库时间.ext）
                                            val named = FileUtils.obtainNamedShareFile(context, it)
                                            if (FileUtils.exportToPublicDirectory(
                                                    context,
                                                    named,
                                                    FileUtils.normalizedDisplayName(it),
                                                )
                                            ) {
                                                count++
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            // 核心修复：旧实现统计了成功数却从未使用 —— 全部失败也提示"成功导出"。
                                            val message =
                                                when {
                                                    count == 0 -> exportFailedMsg
                                                    count < selectedFiles.size ->
                                                        String.format(java.util.Locale.US, exportPartialTemplate, count, selectedFiles.size)
                                                    else -> exportSuccessMsg
                                                }
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                            if (count > 0) {
                                                selected = emptySet()
                                                fileViewModel.setBatchMode(false)
                                            }
                                        }
                                    }
                                },
                            )
                            Spacer(Modifier.width(6.dp))
                        }

                        PjmIconButton(
                            icon = iconPack.actionDelete,
                            contentDescription = stringResource(R.string.action_delete),
                            danger = true,
                            onClick = { showBatchDeleteConfirmDialog = true },
                        )
                    } else {
                        PjmIconButton(
                            icon = if (settings.fileViewMode == "grid") Lucide.LayoutList else Lucide.LayoutGrid,
                            contentDescription =
                                stringResource(
                                    if (settings.fileViewMode == "grid") {
                                        R.string.cd_switch_to_list
                                    } else {
                                        R.string.cd_switch_to_grid
                                    },
                                ),
                            onClick = { settingsViewModel.toggleViewMode() },
                        )
                        // 多选统一由「长按」进入；这里不再放一个重复的按钮。
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).padding(horizontal = 16.dp)) {
            // 快速滚动条状态（列表/网格各自独立，切换模式不重置位置）
            val listState = rememberLazyListState()
            val gridState = rememberLazyGridState()
            if (flattenedItems.isEmpty()) {
                if (filesStillLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                        )
                    }
                } else {
                    // 空分类只留背景：不再显示图标与「暂无加密资源 / 把文件分享到 PJM」这类文字，
                    // 页面上没有内容时就是干净的背景，不要任何提示块。
                    Box(modifier = Modifier.fillMaxSize())
                }
            } else if (settings.fileViewMode == "grid") {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(settings.gridSpanCount),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    var dragAnchor = -1
                                    var dragBase = emptySet<String>()
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            val anchor = gridIndexAt(gridState, offset) ?: -1
                                            if (anchor >= 0) {
                                                dragAnchor = anchor
                                                dragBase = selected
                                                fileViewModel.setBatchMode(true)
                                                applySelectionRange(anchor, anchor, dragBase)
                                                // 长按后原地松手：combinedClickable 还会补一次 click，
                                                // 那次 click 会把刚选中的这一项反选掉。记 600ms 把它吞掉。
                                                (itemsState.value.getOrNull(anchor) as? FileListItem.FileItem)?.let {
                                                    suppressLongPressClick = it.entity.relativePath to (System.currentTimeMillis() + 600L)
                                                }
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            if (dragAnchor >= 0) {
                                                gridIndexAt(gridState, change.position)?.let { current ->
                                                    applySelectionRange(dragAnchor, current, dragBase)
                                                    change.consume()
                                                }
                                            }
                                        },
                                    )
                                },
                    ) {
                        itemsIndexed(
                            items = flattenedItems,
                            key = { _, item ->
                                when (item) {
                                    is FileListItem.Header -> item.date
                                    is FileListItem.FileItem -> item.entity.relativePath
                                }
                            },
                            span = { _, item ->
                                when (item) {
                                    is FileListItem.Header -> GridItemSpan(maxLineSpan)
                                    is FileListItem.FileItem -> GridItemSpan(1)
                                }
                            },
                            contentType = { _, item -> item::class.java },
                        ) { _, item ->
                            when (item) {
                                is FileListItem.Header -> DateHeader(item.date)
                                is FileListItem.FileItem -> {
                                    val isSelected = item.entity.relativePath in selected
                                    if (uiState.isBatchMode) {
                                        SelectableFileCard(
                                            fileEntity = item.entity,
                                            isSelected = isSelected,
                                            imageOnly = true,
                                            gridSpanCount = settings.gridSpanCount,
                                            onClick = { toggleSelection(item.entity) },
                                            modifier = Modifier.aspectRatio(1f),
                                        )
                                    } else {
                                        FileCard(
                                            fileEntity = item.entity,
                                            imageOnly = true,
                                            gridSpanCount = settings.gridSpanCount,
                                            onClick = {
                                                if (item.entity.extension == "pjm") {
                                                    fileForDetails = item.entity
                                                } else if (isViewerSupported(item.entity)) {
                                                    onNavigateToMediaDetail(item.entity.relativePath)
                                                } else {
                                                    scope.launch { openFile(context, item.entity) }
                                                }
                                            },
                                            isSelected = false,
                                            modifier = Modifier.aspectRatio(1f),
                                            // 长按 = 选中（进入多选），不再是直接弹重命名；重命名改成多选工具条上的按钮
                                            onLongClick = {
                                                if (!uiState.isBatchMode) {
                                                    fileViewModel.setBatchMode(true)
                                                    if (item.entity.relativePath !in selected) {
                                                        selected = selected + item.entity.relativePath
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(bottomPadding + 32.dp)) }
                    }
                    QuickScrollScrubber(
                        totalItems = flattenedItems.size,
                        scrollActive = gridState.isScrollInProgress,
                        scrollFraction = {
                            // 旧算法用「首个可见项 / 最后一项」：滑到底时首个可见项还差一屏，
                            // 进度条永远到不了底。改成按实际可滚动距离算，到底就是 1f。
                            val visible =
                                gridState.layoutInfo.visibleItemsInfo.size
                                    .coerceAtLeast(1)
                            when {
                                flattenedItems.size <= 1 -> 0f
                                !gridState.canScrollForward -> 1f
                                else ->
                                    (
                                        gridState.firstVisibleItemIndex.toFloat() /
                                            (flattenedItems.size - visible).coerceAtLeast(1)
                                    ).coerceIn(0f, 1f)
                            }
                        },
                        onScrubToFraction = { f ->
                            val target = ((flattenedItems.size - 1) * f).toInt().coerceAtLeast(0)
                            scope.launch { gridState.scrollToItem(target) }
                        },
                        scrubLabel = { f -> scrubLabelAt(flattenedItems, f) },
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    var dragAnchor = -1
                                    var dragBase = emptySet<String>()
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            val anchor = listIndexAt(listState, offset) ?: -1
                                            if (anchor >= 0) {
                                                dragAnchor = anchor
                                                dragBase = selected
                                                fileViewModel.setBatchMode(true)
                                                applySelectionRange(anchor, anchor, dragBase)
                                                // 长按后原地松手：combinedClickable 还会补一次 click，
                                                // 那次 click 会把刚选中的这一项反选掉。记 600ms 把它吞掉。
                                                (itemsState.value.getOrNull(anchor) as? FileListItem.FileItem)?.let {
                                                    suppressLongPressClick = it.entity.relativePath to (System.currentTimeMillis() + 600L)
                                                }
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            if (dragAnchor >= 0) {
                                                listIndexAt(listState, change.position)?.let { current ->
                                                    applySelectionRange(dragAnchor, current, dragBase)
                                                    change.consume()
                                                }
                                            }
                                        },
                                    )
                                },
                    ) {
                        itemsIndexed(
                            items = flattenedItems,
                            key = { _, item ->
                                when (item) {
                                    is FileListItem.Header -> item.date
                                    is FileListItem.FileItem -> item.entity.relativePath
                                }
                            },
                            contentType = { _, item -> item::class.java },
                        ) { _, item ->
                            when (item) {
                                is FileListItem.Header -> DateHeader(item.date)
                                is FileListItem.FileItem -> {
                                    val isSelected = item.entity.relativePath in selected
                                    FileCard(
                                        fileEntity = item.entity,
                                        isSelected = isSelected,
                                        // 长按 = 选中（进入多选），不再是直接弹重命名；重命名改成多选工具条上的按钮
                                        onLongClick = {
                                            if (!uiState.isBatchMode) {
                                                fileViewModel.setBatchMode(true)
                                                if (item.entity.relativePath !in selected) {
                                                    selected = selected + item.entity.relativePath
                                                }
                                            }
                                        },
                                        onClick = {
                                            if (uiState.isBatchMode) {
                                                toggleSelection(item.entity)
                                            } else if (item.entity.extension == "pjm") {
                                                fileForDetails = item.entity
                                            } else if (isViewerSupported(item.entity)) {
                                                onNavigateToMediaDetail(item.entity.relativePath)
                                            } else {
                                                scope.launch { openFile(context, item.entity) }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(bottomPadding + 32.dp)) }
                    }
                    QuickScrollScrubber(
                        totalItems = flattenedItems.size,
                        scrollActive = listState.isScrollInProgress,
                        scrollFraction = {
                            val visible =
                                listState.layoutInfo.visibleItemsInfo.size
                                    .coerceAtLeast(1)
                            when {
                                flattenedItems.size <= 1 -> 0f
                                !listState.canScrollForward -> 1f
                                else ->
                                    (
                                        listState.firstVisibleItemIndex.toFloat() /
                                            (flattenedItems.size - visible).coerceAtLeast(1)
                                    ).coerceIn(0f, 1f)
                            }
                        },
                        onScrubToFraction = { f ->
                            val target = ((flattenedItems.size - 1) * f).toInt().coerceAtLeast(0)
                            scope.launch { listState.scrollToItem(target) }
                        },
                        scrubLabel = { f -> scrubLabelAt(flattenedItems, f) },
                    )
                }
            }
        }
    }

    fileForDetails?.let { entity ->
        // 归一化：全 App 只有这里曾用系统 AlertDialog，磨砂风格与其它弹窗不一致
        PjmAeroDialog(
            onDismissRequest = { fileForDetails = null },
            title = stringResource(R.string.dialog_title_details),
            confirmButton = {
                if (entity.extension == "pjm") {
                    Button(onClick = {
                        fileForDetails = null
                        fileViewModel.extractPjmToVault(entity)
                    }) { Text(stringResource(R.string.action_extract_now)) }
                } else {
                    Button(onClick = {
                        if (isViewerSupported(entity)) {
                            onNavigateToMediaDetail(entity.relativePath)
                        } else {
                            scope.launch { openFile(context, entity) }
                        }
                        fileForDetails = null
                    }) { Text(stringResource(R.string.action_open)) }
                }
            },
            dismissButton = { TextButton(onClick = { fileForDetails = null }) { Text(stringResource(R.string.action_close)) } },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.label_file_name_prefix, FileUtils.normalizedDisplayName(entity)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.label_file_size, FileUtils.formatFileSize(entity.size)),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (entity.extension == "pjm") {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.msg_pjm_archive_info),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }

    renameTarget?.let { entity ->
        PjmRenameDialog(
            // 预填界面上看到的显示名（图片是入库时间），而不是数据库里可能是 UUID 的 name
            initialName = FileUtils.normalizedDisplayName(entity),
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                fileViewModel.renameDisplayName(entity, newName)
                renameTarget = null
                // 改完名就退出多选，省得再按一次返回
                selected = emptySet()
                fileViewModel.setBatchMode(false)
            },
        )
    }

    deleteTarget?.let { entity ->
        PjmDeleteConfirmDialog(
            title = stringResource(R.string.dialog_title_shred_confirm),
            candidates = listOf(entity),
            message = stringResource(R.string.msg_delete_select_hint),
            onDismiss = { deleteTarget = null },
            onConfirm = { selected ->
                deleteTarget = null
                MainApplication.applicationScope.launch { fileViewModel.deleteFiles(selected) }
            },
        )
    }

    // 多选状态下，系统返回（手势/按键）先退出多选，而不是直接退出页面
    BackHandler(enabled = uiState.isBatchMode) {
        selected = emptySet()
        fileViewModel.setBatchMode(false)
    }

    if (showBatchDeleteConfirmDialog) {
        val candidates = selectedFiles.toList()
        // 核心修复：归一化批量删除确认弹窗 —— 可勾选要删除的文件，杜绝误删
        PjmDeleteConfirmDialog(
            title = stringResource(R.string.dialog_title_shred_confirm),
            candidates = candidates,
            message = stringResource(R.string.msg_delete_select_hint),
            onDismiss = { showBatchDeleteConfirmDialog = false },
            // 参数改名为 checked：外层有同名 state（selected 选区），避免遮蔽后误写
            onConfirm = { checked ->
                showBatchDeleteConfirmDialog = false
                fileViewModel.setBatchMode(false)
                selected = emptySet()
                MainApplication.applicationScope.launch { fileViewModel.deleteFiles(checked) }
            },
        )
    }
}

/**
 * 拖动滑块时显示的日期标签：取目标位置所在文件（或所在日期分组）的日期。
 */
private fun scrubLabelAt(
    items: List<FileListItem>,
    fraction: Float,
): String {
    if (items.isEmpty()) return ""
    val index = ((items.size - 1) * fraction).toInt().coerceIn(0, items.lastIndex)
    for (i in index downTo 0) {
        when (val item = items[i]) {
            is FileListItem.FileItem -> return FileUtils.formatFileTime(item.entity.lastModified).substringBefore(' ')
            is FileListItem.Header -> return item.date.substringBefore(' ')
        }
    }
    return ""
}

/** 哪些类型交给应用内查看器（其余走系统应用） */
private fun isViewerSupported(entity: FileEntity): Boolean =
    entity.isImage ||
        FileUtils.isVideoFile(entity.name) ||
        FileUtils.isAudioFile(entity.name) ||
        FileUtils.isTextFile(entity.name)

/**
 * Displays a header for a group of files associated with a specific date.
 */
@Composable
fun DateHeader(date: String) {
    Text(
        text = date,
        modifier =
            Modifier
                .padding(vertical = 12.dp, horizontal = 4.dp)
                .fillMaxWidth(),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
    )
}

/**
 * 快速滚动滑条：右侧细轨道 + 可拖动滑块。
 *
 * 三段式行为：
 * 1. 平时完全隐藏（alpha 0、不接管触摸），不挡列表、不误触；
 * 2. 列表一开始滚动就淡入，停手约 0.9 秒后淡出；
 * 3. 只有从滑块本身开始拖才会移动 —— 点轨道其它位置不会把进度「跳」过去。
 */
@Composable
private fun BoxScope.QuickScrollScrubber(
    totalItems: Int,
    scrollActive: Boolean,
    scrollFraction: () -> Float,
    onScrubToFraction: (Float) -> Unit,
    scrubLabel: (Float) -> String = { "" },
) {
    if (totalItems <= 1) return
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scrollActive, dragging) {
        if (scrollActive || dragging) {
            shown = true
        } else {
            delay(900)
            shown = false
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(160),
        label = "quickScrollAlpha",
    )
    // 组合期不在 lambda 里直读 layoutInfo：滚动时它每帧都变，会把滑轨每帧重组。
    // 用 derivedStateOf 按「算出来的比例」去重，只有比例真的变了才重组；
    // scrollFraction 由调用方每帧重建 lambda，所以用 rememberUpdatedState 保持最新。
    val currentScrollFraction = rememberUpdatedState(scrollFraction)
    val liveFraction by remember {
        derivedStateOf { currentScrollFraction.value().coerceIn(0f, 1f) }
    }
    val thumbHeight = 48.dp
    // 隐藏时彻底不挂手势，触摸直接落到下面的列表上
    val gestureModifier =
        if (!shown) {
            Modifier
        } else {
            Modifier.pointerInput(totalItems) {
                val thumbPx = thumbHeight.toPx()
                val slopPx = 16.dp.toPx()
                detectDragGestures(
                    onDragStart = { offset ->
                        val current = scrollFraction().coerceIn(0f, 1f)
                        val top = (size.height - thumbPx) * current
                        // 命中滑块（含容差）才进入拖动
                        if (offset.y >= top - slopPx && offset.y <= top + thumbPx + slopPx) {
                            dragging = true
                            dragFraction = current
                        }
                    },
                    onDrag = { change, _ ->
                        if (dragging) {
                            change.consume()
                            dragFraction = (change.position.y / size.height).coerceIn(0f, 1f)
                            onScrubToFraction(dragFraction)
                        }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                )
            }
        }
    BoxWithConstraints(
        modifier =
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(26.dp)
                .alpha(alpha)
                .then(gestureModifier),
    ) {
        val fraction = if (dragging) dragFraction else liveFraction
        Box(
            Modifier
                .fillMaxHeight()
                .width(3.dp)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = if (dragging) 0.45f else 0.28f)),
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 5.dp)
                .offset(y = (maxHeight - thumbHeight) * fraction)
                .width(16.dp)
                .height(thumbHeight)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
        )
        // 拖动时旁边冒一个小气泡，告诉你现在滑到哪一段时间了；松手立刻消失
        if (dragging) {
            val label = scrubLabel(fraction)
            if (label.isNotEmpty()) {
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(
                                // 气泡贴到滑条左边一点（原来 -128dp 离得太远），字号也放大到 14sp
                                x = (-40).dp,
                                y =
                                    ((maxHeight - thumbHeight) * fraction)
                                        .coerceIn(0.dp, (maxHeight - 40.dp).coerceAtLeast(0.dp)),
                            )
                            // requiredWidthIn：气泡挂在 26dp 宽的滑条里，普通 widthIn 会被父约束压成
                            // 几十 dp，只剩一个字（之前一直显示"2"就是这个原因）
                            .requiredWidthIn(max = 160.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Opens a file using a system-provided viewer app.
 * 先用规范化显示名生成可分享文件（硬链接零拷贝/小文件复制），外部 App 看到规范名，磁盘原件不动。
 */
private suspend fun openFile(
    context: Context,
    fileEntity: FileEntity,
) {
    // 核心修复：obtainNamedShareFile 内含遍历 cacheDir + 最多 256MB 的复制兜底，
    // 旧实现在 Compose onClick（主线程）直接调用，大文件会 ANR。
    val file =
        withContext(Dispatchers.IO) { FileUtils.obtainNamedShareFile(context, fileEntity) }
    withContext(Dispatchers.Main) { FileUtils.openFileWithSystemApp(context, file) }
}

/**
 * Shares files (already resolved to named share files) using the system share sheet.
 *
 * MIME 由 [ShareUtils] 依据真实文件类型推导（图片 → `image/&#42;`）。
 * 此前这里硬编码全通配类型，导致 QQ / 微信把多选图片当作「文件」而不是图片发送。
 */
private fun shareFiles(
    context: Context,
    files: List<File>,
    chooserTitle: String,
) {
    if (files.isEmpty()) return
    val (uris, failed) = ShareUtils.toShareUris(context, files)
    if (failed.isNotEmpty()) {
        val msg = context.getString(R.string.toast_share_partial_failed, failed.size)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
    val chooser = ShareUtils.createShareChooser(context, uris, chooserTitle)
    if (chooser == null) {
        Toast.makeText(context, context.getString(R.string.toast_share_nothing_available), Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { context.startActivity(chooser) }
        .onFailure {
            PjmLogger.e("FileViewer", "No activity to handle share", it)
            Toast.makeText(context, context.getString(R.string.toast_share_nothing_available), Toast.LENGTH_SHORT).show()
        }
}

/** 网格模式：触点坐标 → 条目下标（跨表头/空白返回 null） */
private fun gridIndexAt(
    state: LazyGridState,
    position: Offset,
): Int? =
    state.layoutInfo.visibleItemsInfo
        .firstOrNull { item ->
            position.x >= item.offset.x &&
                position.x <= item.offset.x + item.size.width &&
                position.y >= item.offset.y &&
                position.y <= item.offset.y + item.size.height
        }?.index

/** 列表模式：触点坐标 → 条目下标（LazyListItemInfo 只有纵向偏移） */
private fun listIndexAt(
    state: androidx.compose.foundation.lazy.LazyListState,
    position: Offset,
): Int? =
    state.layoutInfo.visibleItemsInfo
        .firstOrNull { item ->
            position.y >= item.offset && position.y <= item.offset + item.size
        }?.index
