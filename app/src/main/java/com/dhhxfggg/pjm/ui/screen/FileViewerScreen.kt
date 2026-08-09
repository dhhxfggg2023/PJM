package com.dhhxfggg.pjm.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.LayoutList
import com.composables.icons.lucide.ListTodo
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Share2
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
import com.dhhxfggg.pjm.ui.component.SelectableFileCard
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
 * @param sharedTransitionScope Optional scope for shared element transitions.
 * @param animatedVisibilityScope Optional scope for animated visibility during transitions.
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val settings = settingsState.settings
    
    val uiState by fileViewModel.uiState.collectAsState()
    val flattenedItems by remember(category) { fileViewModel.getFlattenedFiles(category) }.collectAsState()

    // 自动找回逻辑：进入分类时如果为空，尝试从物理路径同步
    LaunchedEffect(category) {
        fileViewModel.syncIfEmpty(category)
    }

    val selectedFiles = remember { mutableStateListOf<FileEntity>() }
    
    var fileForDetails by remember { mutableStateOf<FileEntity?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<FileEntity?>(null) }
    var showBatchDeleteConfirmDialog by remember { mutableStateOf(false) }

    val toggleSelection = remember {
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

    val categoryDisplayName = remember(category) {
        when(category) {
            VaultManager.CAT_PJM -> "PJM 加密包"
            VaultManager.CAT_IMAGES -> "图片库"
            VaultManager.CAT_VIDEOS -> "视频库"
            VaultManager.CAT_AUDIOS -> "音频库"
            else -> "资源柜"
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (uiState.isBatchMode) "多选操作" else categoryDisplayName, style = MaterialTheme.typography.titleMedium)
                        if (uiState.isBatchMode) {
                            Text(
                                text = "已选中 ${selectedFiles.size} 项", 
                                style = MaterialTheme.typography.labelSmall, 
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = { 
                    IconButton(onClick = {
                        if (uiState.isBatchMode) {
                            fileViewModel.setBatchMode(false)
                            selectedFiles.clear()
                        } else {
                            onBack()
                        }
                    }) { 
                        Icon(if (uiState.isBatchMode) Lucide.X else Lucide.ArrowLeft, null) 
                    } 
                },
                actions = {
                    if (uiState.isBatchMode) {
                        val allFilteredFiles = remember(flattenedItems) { 
                            flattenedItems.filterIsInstance<FileListItem.FileItem>().map { it.entity } 
                        }
                        val isAllSelected = selectedFiles.size == allFilteredFiles.size && allFilteredFiles.isNotEmpty()
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
                        IconButton(onClick = { shareFiles(context, selectedFiles.toList()) }) { Icon(Lucide.Share2, null) }
                        
                        if (category == VaultManager.CAT_IMAGES || category == VaultManager.CAT_VIDEOS || category == VaultManager.CAT_AUDIOS) {
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    var count = 0
                                    selectedFiles.forEach { 
                                        if (FileUtils.exportToPublicDirectory(context, VaultManager.getFileFromEntity(context, it), it.name)) count++ 
                                    }
                                    withContext(Dispatchers.Main) { 
                                        Toast.makeText(context, "成功导出 $count 个资源", Toast.LENGTH_SHORT).show()
                                        selectedFiles.clear()
                                        fileViewModel.setBatchMode(false) 
                                    }
                                }
                            }) { Icon(Lucide.Save, "导出到系统库") }
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
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).padding(horizontal = 16.dp)) {
            if (flattenedItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
                    Text(
                        text = "暂无加密资源", 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ) 
                }
            } else if (settings.fileViewMode == "grid") {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(settings.gridSpanCount), 
                    verticalArrangement = Arrangement.spacedBy(8.dp), 
                    horizontalArrangement = Arrangement.spacedBy(8.dp), 
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = flattenedItems,
                        key = { _, item -> when(item) {
                            is FileListItem.Header -> item.date
                            is FileListItem.FileItem -> item.entity.relativePath
                        }},
                        span = { _, item -> when(item) {
                            is FileListItem.Header -> GridItemSpan(maxLineSpan)
                            is FileListItem.FileItem -> GridItemSpan(1)
                        }},
                        contentType = { _, item -> item::class.java }
                    ) { _, item ->
                        when(item) {
                            is FileListItem.Header -> DateHeader(item.date)
                            is FileListItem.FileItem -> {
                                val isSelected = selectedFiles.any { it.relativePath == item.entity.relativePath }
                                if (uiState.isBatchMode) {
                                    SelectableFileCard(
                                        fileEntity = item.entity, 
                                        isSelected = isSelected, 
                                        imageOnly = true, 
                                        onClick = { toggleSelection(item.entity) }, 
                                        onLongPress = { toggleSelection(item.entity) }, 
                                        modifier = Modifier.aspectRatio(1f)
                                    )
                                } else {
                                    FileCard(
                                        fileEntity = item.entity, 
                                        imageOnly = true, 
                                        gridSpanCount = settings.gridSpanCount, 
                                        thumbnail = item.thumbnail,
                                        onClick = { 
                                            if (item.entity.extension == "pjm") {
                                                fileForDetails = item.entity 
                                            } else if (item.entity.isImage || FileUtils.isVideoFile(item.entity.name)) {
                                                onNavigateToMediaDetail(item.entity.relativePath)
                                            } else {
                                                openFile(context, item.entity)
                                            }
                                        }, 
                                        onDelete = { showDeleteConfirmDialog = item.entity }, 
                                        onLongClick = { 
                                            fileViewModel.setBatchMode(true)
                                            toggleSelection(item.entity) 
                                        }, 
                                        isSelected = false, 
                                        modifier = Modifier.aspectRatio(1f)
                                    )
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(bottomPadding + 32.dp)) }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(
                        items = flattenedItems,
                        key = { _, item -> when(item) {
                            is FileListItem.Header -> item.date
                            is FileListItem.FileItem -> item.entity.relativePath
                        }},
                        contentType = { _, item -> item::class.java }
                    ) { _, item ->
                        when(item) {
                            is FileListItem.Header -> DateHeader(item.date)
                            is FileListItem.FileItem -> {
                                val isSelected = selectedFiles.any { it.relativePath == item.entity.relativePath }
                                FileCard(
                                    fileEntity = item.entity, 
                                    isSelected = isSelected, 
                                    thumbnail = item.thumbnail,
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
                                    onDelete = { if (!uiState.isBatchMode) showDeleteConfirmDialog = item.entity },
                                    onLongClick = { 
                                        if (!uiState.isBatchMode) fileViewModel.setBatchMode(true)
                                        toggleSelection(item.entity) 
                                    }
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(bottomPadding + 32.dp)) }
                }
            }
        }
    }

    fileForDetails?.let { entity ->
        AlertDialog(
            onDismissRequest = { fileForDetails = null },
            title = { Text("详情", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("大小: ${FileUtils.formatFileSize(entity.size)}", style = MaterialTheme.typography.bodySmall)
                    if (entity.extension == "pjm") {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "这是加密归档，您可以将其内容提取。", 
                            color = MaterialTheme.colorScheme.primary, 
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            confirmButton = {
                if (entity.extension == "pjm") {
                    Button(onClick = { 
                        fileForDetails = null
                        fileViewModel.extractPjmToVault(entity) 
                    }) { Text("立即提取") }
                } else {
                    Button(onClick = { 
                        if (entity.isImage || FileUtils.isVideoFile(entity.name)) {
                            onNavigateToMediaDetail(entity.relativePath)
                        } else {
                            openFile(context, entity)
                        }
                        fileForDetails = null 
                    }) { Text("打开") }
                }
            },
            dismissButton = { TextButton(onClick = { fileForDetails = null }) { Text("关闭") } }
        )
    }

    if (showDeleteConfirmDialog != null) {
        val entity = showDeleteConfirmDialog ?: return
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text("确定要永久抹除此项资源吗？") },
            confirmButton = {
                Button(
                    onClick = { 
                        showDeleteConfirmDialog = null
                        MainApplication.applicationScope.launch { fileViewModel.deleteFile(entity) } 
                    }, 
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { 
                TextButton(onClick = { showDeleteConfirmDialog = null }) { 
                    Text(stringResource(R.string.action_cancel)) 
                } 
            }
        )
    }

    if (showBatchDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirmDialog = false },
            title = { Text("安全粉碎确认") },
            text = { Text("确定要抹除选中的 ${selectedFiles.size} 个文件吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        val list = selectedFiles.toList()
                        showBatchDeleteConfirmDialog = false
                        fileViewModel.setBatchMode(false)
                        selectedFiles.clear()
                        MainApplication.applicationScope.launch { fileViewModel.deleteFiles(list) }
                    }, 
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("立即粉碎") }
            },
            dismissButton = { TextButton(onClick = { showBatchDeleteConfirmDialog = false }) { Text("取消") } }
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
        modifier = Modifier
            .padding(vertical = 12.dp, horizontal = 4.dp)
            .fillMaxWidth(), 
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp), 
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    )
}

/**
 * Opens a file using a system-provided viewer app.
 */
private fun openFile(context: Context, fileEntity: FileEntity) {
    val file = VaultManager.getFileFromEntity(context, fileEntity)
    FileUtils.openFileWithSystemApp(context, file)
}

/**
 * Shares one or more files using the system share sheet.
 */
private fun shareFiles(context: Context, files: List<FileEntity>) {
    val uris = ArrayList<Uri>()
    files.forEach { entity ->
        val file = VaultManager.getFileFromEntity(context, entity)
        try { 
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)) 
        } catch (e: Exception) {
            // Log error or ignore invalid files
        }
    }
    if (uris.isNotEmpty()) {
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = "*/*"
            if (uris.size > 1) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) 
            } else {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享文件"))
    }
}
