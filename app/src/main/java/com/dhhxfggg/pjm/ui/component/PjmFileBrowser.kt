package com.dhhxfggg.pjm.ui.component

import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.composables.icons.lucide.*
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.shizuku.EmbeddedPrivilegedIo
import com.dhhxfggg.pjm.domain.util.BiliBridge
import java.io.File

/**
 * PJM 工业级文件浏览器 - 极致精简版
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PjmFolderPickerDialog(
    onDismiss: () -> Unit,
    onFolderSelected: (Uri) -> Unit,
    onRequestSafAuth: (String?) -> Unit, // 核心修复：重新引入授权请求回调
    extraTreeUri: Uri? = null, // 核心修复：刚授权（可能未持久化）的树 URI，会话内穿透用
) {
    val context = LocalContext.current
    var currentRawDir by remember { mutableStateOf(Environment.getExternalStorageDirectory()) }
    var currentDocDir by remember { mutableStateOf<DocumentFile?>(null) }
    // 核心修复：SAF 穿透进入应用目录时记录真正授权的 tree URI，
    // 枚举子目录（download 等）的 manual query 必须用 tree URI，
    // 传 document URI 会被 ExternalStorageProvider 拒绝。
    var currentTreeUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val persistedPermissions = remember { context.contentResolver.persistedUriPermissions }
    // 核心修复：授权目标改为整个内部存储根 (primary:)，Android 13+ 无法直接授权 Android/data。
    // 同时兼容历史版本中已授权的 Android/data 树。
    // 刚授权（可能未持久化）的树 URI 也纳入判断 —— 解决"授权后打不开应用内部文件夹"。
    val hasRootAuth =
        persistedPermissions.any {
            val u = it.uri.toString().lowercase()
            u.contains("tree/primary%3a") || u.endsWith("/tree/primary") || u.contains("primary%3aandroid%2fdata")
        } ||
            extraTreeUri?.toString()?.lowercase()?.contains("tree/") == true
    // 核心修复：特权模式（内置服务/Shizuku，shell 身份）可用时，无需 SAF 授权即可访问 Android/data。
    // 日志证实模拟器上特权服务已生效且扫描成功，UI 不应再反复提示授权。
    // isAvailable 是 suspend（需 ping 特权进程），用 produceState 在后台协程检测，不阻塞 UI。
    val privilegedAvailable by produceState(initialValue = false) {
        value = EmbeddedPrivilegedIo.isAvailable(context)
    }
    // MT 管理器同款：所有文件访问权限（MANAGE_EXTERNAL_STORAGE）可浏览公共目录全部文件。
    // 非 remember：每次重组实时读取，用户去系统设置授权返回后立即生效，无需重启选择器。
    val hasAllFilesAccess =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            android.os.Environment.isExternalStorageManager()

    // 核心修复：特权模式下 Android/data 下任意目录（含应用内部 download 等）必须用特权 IO 枚举，
    // File API 在 Android 14 读 Android/data/<pkg> 返回空 → 之前看不到 download 的根因。
    // 值为 null 表示未枚举/不可用（此时回退 File API）。
    var privilegedEntries by remember { mutableStateOf<List<Pair<File, Boolean>>?>(null) }
    LaunchedEffect(currentRawDir, currentDocDir, privilegedAvailable) {
        if (privilegedAvailable && currentDocDir == null && !currentRawDir.absolutePath.endsWith("Android/data")) {
            privilegedEntries =
                EmbeddedPrivilegedIo
                    .listFiles(context, currentRawDir.absolutePath)
                    ?.map { File(currentRawDir, it.name) to it.isDirectory }
        } else {
            privilegedEntries = null
        }
    }

    // 核心修复：只显示用户安装的应用（过滤全部系统应用，含更新过的系统应用）。
    // FLAG_SYSTEM 标记预装系统应用，FLAG_UPDATED_SYSTEM_APP 标记被 Play/商店更新过的
    // 系统应用（仍有系统签名），两者都应过滤。仅保留 B站/PJM 重要包例外。
    var installedApps by remember { mutableStateOf<List<Triple<String, String, android.content.pm.ApplicationInfo?>>>(emptyList()) }
    LaunchedEffect(currentRawDir, currentDocDir) {
        if (currentRawDir.absolutePath.endsWith("Android/data") && currentDocDir == null) {
            val pm = context.packageManager
            installedApps =
                pm
                    .getInstalledPackages(0)
                    .mapNotNull { info ->
                        try {
                            val appInfo = info.applicationInfo ?: return@mapNotNull null
                            val pkg = info.packageName
                            val isSystemApp =
                                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                    (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                            // 重要包例外：B站/PJM 即使预装也保留（核心功能）
                            val isImportant = pkg in BiliBridge.BILI_PKGS || pkg == context.packageName
                            if (isSystemApp && !isImportant) return@mapNotNull null
                            val name = pm.getApplicationLabel(appInfo).toString()
                            Triple(name, pkg, appInfo)
                        } catch (_: Exception) {
                            null
                        }
                    }.sortedBy { it.first.lowercase() }
        } else {
            installedApps = emptyList()
        }
    }

    val nodes =
        remember(currentRawDir, currentDocDir, searchQuery, installedApps, privilegedEntries) {
            val baseNodes: List<Triple<String, Any, android.content.pm.ApplicationInfo?>> =
                if (currentDocDir != null) {
                    var filesList = currentDocDir!!.listFiles().toList()
                    if (filesList.isEmpty()) {
                        val manualFiles = mutableListOf<DocumentFile>()
                        try {
                            val treeForQuery = currentTreeUri ?: currentDocDir!!.uri
                            val childrenUri =
                                android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                                    treeForQuery,
                                    android.provider.DocumentsContract.getDocumentId(currentDocDir!!.uri),
                                )
                            context.contentResolver
                                .query(
                                    childrenUri,
                                    arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                                    null,
                                    null,
                                    null,
                                )?.use { cursor ->
                                    while (cursor.moveToNext()) {
                                        val id = cursor.getString(0)
                                        val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeForQuery, id)
                                        DocumentFile.fromSingleUri(context, fileUri)?.let { manualFiles.add(it) }
                                    }
                                }
                            filesList = manualFiles
                        } catch (_: Exception) {
                        }
                    }
                    // 核心修复：目录选择器同时展示文件与目录（文件带图标不可选，仅供确认目录内容）
                    filesList.map { Triple(it.name ?: "Unknown", it, null) }
                } else {
                    if (currentRawDir.absolutePath.endsWith("Android/data")) {
                        installedApps.map { Triple(it.first, File(currentRawDir, it.second), it.third) }
                    } else {
                        // 核心修复：隐藏目录过滤不可靠（.data 会被误显示成 data）——
                        // 用 isHidden + “以 . 开头”双重判断，彻底过滤 .xxx 隐藏目录。
                        // 目录和文件都展示，文件仅供浏览确认，不可选择。
                        // 特权模式可用时优先用 shell 身份枚举（Android 14 的 File API 读 Android/data 返回空）；
                        // 特权不可用才用 File API。
                        val fileNodes =
                            if (privilegedAvailable && privilegedEntries != null) {
                                privilegedEntries!!
                                    .filter { !it.first.name.startsWith(".") }
                                    .map { Triple(it.first.name, it.first, null) }
                            } else {
                                currentRawDir
                                    .listFiles()
                                    ?.filter { !it.isHidden && !it.name.startsWith(".") }
                                    ?.map { Triple(it.name, it, null) } ?: emptyList()
                            }
                        // 核心修复：浏览 Android 目录时，File API 在 Android 14+ 看不到受保护的 data 目录（只剩 .data）。
                        // 若已有根授权（SAF），用 SAF 构建 data 入口，让用户能进入 Android/data。
                        if (currentRawDir.absolutePath.endsWith("Android") && hasRootAuth) {
                            val rootTree =
                                persistedPermissions.map { it.uri }.firstOrNull { tree ->
                                    val treeId =
                                        try {
                                            android.provider.DocumentsContract.getTreeDocumentId(tree)
                                        } catch (_: Exception) {
                                            ""
                                        }
                                    treeId == "primary:" ||
                                        treeId.contains("primary:Android/data") ||
                                        treeId.contains("primary%3AAndroid%2Fdata")
                                }
                            if (rootTree != null) {
                                val dataDoc =
                                    try {
                                        val subUri =
                                            android.provider.DocumentsContract.buildDocumentUriUsingTree(
                                                rootTree,
                                                "primary:Android/data",
                                            )
                                        DocumentFile.fromSingleUri(context, subUri)
                                    } catch (_: Exception) {
                                        null
                                    }
                                if (dataDoc != null) {
                                    // data 入口排在最前，优先展示（它受保护但 SAF 可达）
                                    listOf(Triple("data", dataDoc, null)) + fileNodes
                                } else {
                                    fileNodes
                                }
                            } else {
                                fileNodes
                            }
                        } else {
                            fileNodes
                        }
                    }
                }
            baseNodes
                .filter {
                    it.first.contains(searchQuery, ignoreCase = true) ||
                        (it.second as? File)?.name?.contains(searchQuery, ignoreCase = true) == true
                }.sortedBy { it.first.lowercase() }
        }

    val currentPathDisplay =
        currentDocDir?.let { doc ->
            val path = doc.uri.path ?: ""
            if (path.contains("primary:Android/data")) {
                "Data/" + path.substringAfterLast("/")
            } else {
                doc.name ?: context.getString(R.string.filebrowser_system_dir)
            }
        } ?: currentRawDir.absolutePath

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.95f)
                    .clip(MaterialTheme.shapes.extraLarge),
        ) {
            Box(modifier = Modifier.fillMaxSize().blur(radius = 20.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)))

            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                stringResource(R.string.filebrowser_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                currentPathDisplay,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (currentDocDir != null) {
                                val parent = currentDocDir!!.parentFile
                                if (parent != null) {
                                    currentDocDir = parent
                                } else {
                                    currentDocDir = null
                                    currentTreeUri = null
                                    currentRawDir =
                                        File(Environment.getExternalStorageDirectory(), "Android/data")
                                }
                            } else if (currentRawDir.absolutePath != "/storage/emulated/0") {
                                currentRawDir = currentRawDir.parentFile ?: currentRawDir
                            }
                        }) { Icon(Lucide.ChevronLeft, stringResource(R.string.action_go_back)) }
                    },
                    actions = {
                        IconButton(onClick = {
                            currentDocDir = null
                            currentTreeUri = null
                            currentRawDir =
                                Environment.getExternalStorageDirectory()
                        }) { Icon(Lucide.House, stringResource(R.string.filebrowser_go_home)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text(stringResource(R.string.filebrowser_search_hint)) },
                    leadingIcon = { Icon(Lucide.Search, null, modifier = Modifier.size(18.dp)) },
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.Transparent),
                )

                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // 核心修复：工业级一键穿透入口
                        if (currentRawDir.absolutePath.endsWith("Android/data") && currentDocDir == null) {
                            // 特权模式可用 → 已就绪；否则提示授权（SAF 单目录 或 所有文件访问）
                            val dataAccessReady = privilegedAvailable || hasRootAuth
                            item {
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            if (dataAccessReady) {
                                                stringResource(
                                                    R.string.filebrowser_status_data_ready,
                                                )
                                            } else {
                                                stringResource(R.string.filebrowser_title_data_auth)
                                            },
                                            fontWeight = FontWeight.Bold,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            when {
                                                privilegedAvailable -> stringResource(R.string.filebrowser_desc_privileged)
                                                hasRootAuth -> stringResource(R.string.filebrowser_desc_saf_locked)
                                                else -> stringResource(R.string.filebrowser_desc_manual_auth)
                                            },
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            Lucide.ShieldCheck,
                                            null,
                                            tint = if (dataAccessReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    modifier = Modifier.clickable { if (!dataAccessReady) onRequestSafAuth(null) },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f),
                                )
                            }
                            // MT 同款：所有文件访问（MANAGE_EXTERNAL_STORAGE）——未开启时给出入口
                            if (!hasAllFilesAccess && !privilegedAvailable) {
                                item {
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                stringResource(R.string.filebrowser_title_all_files_access),
                                                fontWeight = FontWeight.Bold,
                                            )
                                        },
                                        supportingContent = { Text(stringResource(R.string.filebrowser_desc_all_files_access)) },
                                        leadingContent = { Icon(Lucide.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) },
                                        modifier = Modifier.clickable { onRequestSafAuth(null) },
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f),
                                    )
                                }
                            }
                        }

                        items(nodes) { (name, node, appInfo) ->
                            // 判断是否为目录（目录可进入，文件仅展示不可点）。
                            // 核心修复：特权模式下必须用特权枚举的 isDirectory 标志——
                            // Android 14 的 File API 对 Android/data 下受保护目录 stat 失败，
                            // File.isDirectory 恒为 false，导致 download 等目录被误识别成文件。
                            val privilegedFlag =
                                if (currentDocDir == null && privilegedAvailable && privilegedEntries != null) {
                                    privilegedEntries!!.firstOrNull { it.first.name == name }?.second
                                } else {
                                    null
                                }
                            val isDirectory =
                                privilegedFlag ?: when (node) {
                                    is DocumentFile -> node.isDirectory
                                    is File -> node.isDirectory
                                    else -> true
                                }
                            val fileSize =
                                if (privilegedFlag != null) {
                                    if (privilegedFlag) {
                                        null
                                    } else {
                                        when (node) {
                                            is DocumentFile -> node.length().takeIf { it > 0 }
                                            is File -> node.length().takeIf { it > 0 }
                                            else -> null
                                        }
                                    }
                                } else {
                                    when (node) {
                                        is DocumentFile -> if (node.isDirectory) null else node.length()
                                        is File -> if (node.isDirectory) null else node.length()
                                        else -> null
                                    }
                                }
                            FolderItem(name, appInfo, isDirectory, fileSize) {
                                if (!isDirectory) return@FolderItem
                                if (node is DocumentFile) {
                                    // data 入口（SAF 根授权构建）：点击直接进入 Android/data 的 File 模式，
                                    // 显示已安装应用列表（含 B站），再点应用走穿透/授权
                                    if (currentRawDir.absolutePath.endsWith("Android") && name == "data") {
                                        currentRawDir = File(Environment.getExternalStorageDirectory(), "Android/data")
                                    } else {
                                        currentDocDir = node
                                    }
                                } else if (node is File) {
                                    val parentPath = currentRawDir.absolutePath
                                    if (parentPath.endsWith("Android/data") && node.name != null) {
                                        val relPath = "primary:Android/data/${node.name}"
                                        // 核心修复：真机 Android 14 无 root —— File API 读 Android/data/<pkg> 被系统限制
                                        // （即使有 MANAGE_EXTERNAL_STORAGE，listFiles 也返回空，看不到 download 等子目录）。
                                        // 必须优先用 SAF 根授权(tree/primary:) 穿透构造 DocumentFile —— MT 管理器同款方案。
                                        // 候选树 = 已持久化权限 + 刚授权（会话内有效，可能未持久化）的 extraTreeUri。
                                        val candidateTrees =
                                            buildList {
                                                addAll(persistedPermissions.map { it.uri })
                                                if (extraTreeUri != null) add(extraTreeUri)
                                            }
                                        val rootTree =
                                            candidateTrees.firstOrNull { tree ->
                                                val treeId =
                                                    try {
                                                        android.provider.DocumentsContract.getTreeDocumentId(tree)
                                                    } catch (
                                                        _: Exception,
                                                    ) {
                                                        ""
                                                    }
                                                treeId == "primary:" || relPath.startsWith(treeId + "/") || treeId.startsWith(relPath + "/")
                                            }
                                        if (rootTree != null) {
                                            try {
                                                val subUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(rootTree, relPath)
                                                val doc = DocumentFile.fromSingleUri(context, subUri)
                                                if (doc != null && doc.exists()) {
                                                    // 进入 SAF 模式，能看到 download 等真实子目录
                                                    currentDocDir = doc
                                                    currentTreeUri = rootTree
                                                    return@FolderItem
                                                }
                                            } catch (_: Exception) {
                                            }
                                        }
                                        // 无根授权：仅特权模式可用时 File 模式（有 shell 枚举可读）。
                                        // 核心修复：真机有全盘访问但无特权时，File API 依然读不了 Android/data/<pkg>
                                        // （Android 14 限制），进 File 模式只会看到空目录 → 选到 file:// 路径 → 扫描失败。
                                        // 必须触发 SAF 授权（授权即扫描），而非进空目录。
                                        if (privilegedAvailable) {
                                            currentRawDir = node
                                            return@FolderItem
                                        }
                                        // 触发精确定位授权（传入包名）
                                        onRequestSafAuth(node.name)
                                        return@FolderItem
                                    }
                                    currentRawDir = node
                                }
                            }
                        }
                    }
                }

                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), tonalElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                        Button(
                            onClick = {
                                val rawUri = Uri.fromFile(currentRawDir)
                                val selectedUri =
                                    when {
                                        currentDocDir != null -> currentDocDir!!.uri
                                        rawUri.path?.contains("Android/data") == true -> {
                                            // 核心修复：有全盘访问/特权时直接返回当前精确目录的 file URI，
                                            // 扫描器经 File API 只读该应用目录（不再有其它应用干扰）。
                                            // 无全盘访问时退回 SAF 树 URI（若已授权根/Android/data）。
                                            if (hasAllFilesAccess || privilegedAvailable) {
                                                rawUri
                                            } else {
                                                val candidateTrees =
                                                    buildList {
                                                        addAll(persistedPermissions.map { it.uri })
                                                        if (extraTreeUri != null) add(extraTreeUri)
                                                    }
                                                val rootTree =
                                                    candidateTrees.firstOrNull { tree ->
                                                        val treeId =
                                                            try {
                                                                android.provider.DocumentsContract.getTreeDocumentId(tree)
                                                            } catch (
                                                                _: Exception,
                                                            ) {
                                                                ""
                                                            }
                                                        treeId == "primary:" ||
                                                            treeId.contains("primary:Android/data") ||
                                                            treeId.contains("primary%3AAndroid%2Fdata")
                                                    }
                                                rootTree ?: rawUri
                                            }
                                        }
                                        else -> rawUri
                                    }
                                onFolderSelected(selectedUri)
                            },
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Icon(Lucide.FolderCheck, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_confirm_select))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderItem(
    name: String,
    appInfo: android.content.pm.ApplicationInfo? = null,
    isDirectory: Boolean = true,
    fileSize: Long? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (appInfo != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(appInfo).build(),
                contentDescription = null,
                modifier = Modifier.size(32.dp).clip(MaterialTheme.shapes.extraSmall),
            )
        } else if (isDirectory) {
            Icon(Lucide.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        } else {
            Icon(Lucide.File, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!isDirectory && fileSize != null) {
                Text(
                    com.dhhxfggg.pjm.domain.util.FileUtils
                        .formatFileSize(fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
