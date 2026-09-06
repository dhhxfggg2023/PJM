package com.dhhxfggg.pjm.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.LayoutList
import com.composables.icons.lucide.ListTodo
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquareCheck
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.dhhxfggg.pjm.MainApplication
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.ui.component.FileCard
import com.dhhxfggg.pjm.ui.component.PjmDeleteConfirmDialog
import com.dhhxfggg.pjm.ui.component.SelectableFileCard
import com.dhhxfggg.pjm.ui.theme.rememberIconPack
import com.dhhxfggg.pjm.ui.viewmodel.CryptoViewModel
import com.dhhxfggg.pjm.ui.viewmodel.FileListItem
import com.dhhxfggg.pjm.ui.viewmodel.FileViewerViewModel
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayList

/**
 * Screen for viewing and managing files within a specific vault category.
 * Supports searching, batch operations, different view modes, and shared element transitions.
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
    val shareFilesMsg = stringResource(R.string.chooser_title_share_files)

    val iconPack = rememberIconPack()

    val settingsState by settingsViewModel.uiState.collectAsState()
    val settings = settingsState.settings

    val uiState by fileViewModel.uiState.collectAsState()
    val flattenedItems by remember(category) { fileViewModel.getFlattenedFiles(category) }.collectAsState()

    // 自动找回逻辑：进入分类时如果为空，尝试从物理路径同步
    LaunchedEffect(category) {
        fileViewModel.syncIfEmpty(category)
    }

    val selectedFiles = remember { mutableStateListOf<FileEntity>() }

    // O(1) 选中判定：用集合查询替代线性扫描，避免多选操作时对全列表反复遍历
    val selectedPaths by remember {
        derivedStateOf { selectedFiles.mapTo(HashSet()) { it.relativePath } }
    }

    var fileForDetails by remember { mutableStateOf<FileEntity?>(null) }
    var showBatchDeleteConfirmDialog by remember { mutableStateOf(value = false) }

    val toggleSelection =
        remember {
            { file: FileEntity ->
                val index = selectedFiles.indexOfFirst { it.relativePath == file.relativePath }
                if (index != -1) {
                    selectedFiles.removeAt(index)
                } else {
                    selectedFiles.add(file)
                }
                if (selectedFiles.isEmpty()) {
                    fileViewModel.setBatchMode(false)
                }
            }
        }

    val categoryDisplayName =
        when (category) {
            VaultManager.CAT_PJM -> stringResource(R.string.cat_pjm_display)
            VaultManager.CAT_BILI_VIDEOS -> stringResource(R.string.mode_bili_videos)
            VaultManager.CAT_IMAGES -> stringResource(R.string.cat_images_display)
            VaultManager.CAT_VIDEOS -> stringResource(R.string.cat_videos_display)
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
                                text = stringResource(R.string.label_selected_count, selectedFiles.size),
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
                                selectedFiles.clear()
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        Icon(if (uiState.isBatchMode) Lucide.X else Lucide.ArrowLeft, null)
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
                        val isAllSelected = (selectedFiles.size == allFilteredFiles.size) && allFilteredFiles.isNotEmpty()
                        IconButton(onClick = {
                            if (isAllSelected) {
                                selectedFiles.clear()
                                fileViewModel.setBatchMode(false)
                            } else {
                                selectedFiles.clear()
                                selectedFiles.addAll(allFilteredFiles)
                            }
                        }) {
                            Icon(if (isAllSelected) Lucide.Square else Lucide.SquareCheck, null)
                        }
                        IconButton(onClick = {
                            // 分享到外部：用规范化显示名（PJM_入库时间.ext）生成可分享文件，磁盘原件不动
                            scope.launch {
                                val namedFiles =
                                    withContext(Dispatchers.IO) {
                                        selectedFiles.map { FileUtils.obtainNamedShareFile(context, it) }
                                    }
                                shareFiles(context, namedFiles, shareFilesMsg)
                            }
                        }) { Icon(iconPack.actionShare, null) }

                        if ((category == VaultManager.CAT_IMAGES) ||
                            (category == VaultManager.CAT_VIDEOS) ||
                            (category == VaultManager.CAT_AUDIOS) ||
                            (category == VaultManager.CAT_BILI_VIDEOS)
                        ) {
                            IconButton(onClick = {
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
                                        Toast.makeText(context, exportSuccessMsg, Toast.LENGTH_SHORT).show()
                                        selectedFiles.clear()
                                        fileViewModel.setBatchMode(false)
                                    }
                                }
                            }) { Icon(Lucide.Save, stringResource(R.string.action_export_to_gallery)) }
                        }

                        IconButton(onClick = { showBatchDeleteConfirmDialog = true }) {
                            Icon(Lucide.Trash2, null, tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(onClick = { settingsViewModel.toggleViewMode() }) {
                            Icon(if (settings.fileViewMode == "grid") Lucide.LayoutList else Lucide.LayoutGrid, null)
                        }
                        if (flattenedItems.isNotEmpty()) {
                            IconButton(onClick = { fileViewModel.setBatchMode(true) }) {
                                Icon(Lucide.ListTodo, null)
                            }
                        }
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
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.empty_vault_msg),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (settings.fileViewMode == "grid") {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(settings.gridSpanCount),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
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
                                    val isSelected = item.entity.relativePath in selectedPaths
                                    if (uiState.isBatchMode) {
                                        SelectableFileCard(
                                            fileEntity = item.entity,
                                            isSelected = isSelected,
                                            imageOnly = true,
                                            gridSpanCount = settings.gridSpanCount,
                                            onClick = { toggleSelection(item.entity) },
                                            onLongPress = { toggleSelection(item.entity) },
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
                                                } else if (item.entity.isImage || FileUtils.isVideoFile(item.entity.name)) {
                                                    onNavigateToMediaDetail(item.entity.relativePath)
                                                } else {
                                                    openFile(context, item.entity)
                                                }
                                            },
                                            onLongClick = {
                                                fileViewModel.setBatchMode(true)
                                                toggleSelection(item.entity)
                                            },
                                            isSelected = false,
                                            modifier = Modifier.aspectRatio(1f),
                                        )
                                    }
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(bottomPadding + 32.dp)) }
                    }
                    QuickScrollScrubber(
                        totalItems = flattenedItems.size,
                        onScrubToFraction = { f ->
                            val target = ((flattenedItems.size - 1) * f).toInt().coerceAtLeast(0)
                            scope.launch { gridState.scrollToItem(target) }
                        },
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
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
                                    val isSelected = item.entity.relativePath in selectedPaths
                                    FileCard(
                                        fileEntity = item.entity,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (uiState.isBatchMode) {
                                                toggleSelection(item.entity)
                                            } else if (item.entity.extension == "pjm") {
                                                fileForDetails = item.entity
                                            } else if (item.entity.isImage || FileUtils.isVideoFile(item.entity.name)) {
                                                onNavigateToMediaDetail(item.entity.relativePath)
                                            } else {
                                                openFile(context, item.entity)
                                            }
                                        },
                                        onLongClick = {
                                            if (!uiState.isBatchMode) fileViewModel.setBatchMode(true)
                                            toggleSelection(item.entity)
                                        },
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(bottomPadding + 32.dp)) }
                    }
                    QuickScrollScrubber(
                        totalItems = flattenedItems.size,
                        onScrubToFraction = { f ->
                            val target = ((flattenedItems.size - 1) * f).toInt().coerceAtLeast(0)
                            scope.launch { listState.scrollToItem(target) }
                        },
                    )
                }
            }
        }
    }

    fileForDetails?.let { entity ->
        AlertDialog(
            onDismissRequest = { fileForDetails = null },
            title = { Text(stringResource(R.string.dialog_title_details), fontWeight = FontWeight.Bold) },
            text = {
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
            },
            confirmButton = {
                if (entity.extension == "pjm") {
                    Button(onClick = {
                        fileForDetails = null
                        fileViewModel.extractPjmToVault(entity)
                    }) { Text(stringResource(R.string.action_extract_now)) }
                } else {
                    Button(onClick = {
                        if (entity.isImage || FileUtils.isVideoFile(entity.name)) {
                            onNavigateToMediaDetail(entity.relativePath)
                        } else {
                            openFile(context, entity)
                        }
                        fileForDetails = null
                    }) { Text(stringResource(R.string.action_open)) }
                }
            },
            dismissButton = { TextButton(onClick = { fileForDetails = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }

    if (showBatchDeleteConfirmDialog) {
        val candidates = selectedFiles.toList()
        // 核心修复：归一化批量删除确认弹窗 —— 可勾选要删除的文件，杜绝误删
        PjmDeleteConfirmDialog(
            title = stringResource(R.string.dialog_title_shred_confirm),
            candidates = candidates,
            message = stringResource(R.string.msg_delete_select_hint),
            onDismiss = { showBatchDeleteConfirmDialog = false },
            onConfirm = { selected ->
                showBatchDeleteConfirmDialog = false
                fileViewModel.setBatchMode(false)
                selectedFiles.clear()
                MainApplication.applicationScope.launch { fileViewModel.deleteFiles(selected) }
            },
        )
    }
}

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
 * 快速滚动滑条：右侧细轨道 + 可拖动滑块，按住拖动即可按占比瞬间跳到列表/网格任意位置。
 * 独立实现，避免依赖随 Compose 版本变动的 Scrollbar API。
 */
@Composable
private fun BoxScope.QuickScrollScrubber(
    totalItems: Int,
    onScrubToFraction: (Float) -> Unit,
) {
    if (totalItems <= 1) return
    var active by remember { mutableStateOf(false) }
    var fraction by remember { mutableStateOf(0f) }
    BoxWithConstraints(
        modifier =
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(26.dp)
                .pointerInput(totalItems) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            active = true
                            fraction = (offset.y / size.height).coerceIn(0f, 1f)
                            onScrubToFraction(fraction)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            fraction = (change.position.y / size.height).coerceIn(0f, 1f)
                            onScrubToFraction(fraction)
                        },
                        onDragEnd = { active = false },
                        onDragCancel = { active = false },
                    )
                },
    ) {
        // 细轨道：平时隐约可见，拖动时高亮
        Box(
            Modifier
                .fillMaxHeight()
                .width(3.dp)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = if (active) 0.45f else 0.22f)),
        )
        if (active) {
            // 滑块（拖动时显示在对应位置）
            val travel = maxHeight - 48.dp
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 5.dp)
                    .offset(y = travel * fraction)
                    .width(16.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
            )
        }
    }
}

/**
 * Opens a file using a system-provided viewer app.
 * 先用规范化显示名生成可分享文件（硬链接零拷贝/小文件复制），外部 App 看到规范名，磁盘原件不动。
 */
private fun openFile(
    context: Context,
    fileEntity: FileEntity,
) {
    val file = FileUtils.obtainNamedShareFile(context, fileEntity)
    FileUtils.openFileWithSystemApp(context, file)
}

/**
 * Shares files (already resolved to named share files) using the system share sheet.
 */
private fun shareFiles(
    context: Context,
    files: List<File>,
    chooserTitle: String,
) {
    val uris = ArrayList<Uri>()
    files.forEach { file ->
        try {
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        } catch (_: Exception) {
            // Log error or ignore invalid files
        }
    }
    if (uris.isNotEmpty()) {
        val intent =
            Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = "*/*"
                if (uris.size > 1) {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                } else {
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}
