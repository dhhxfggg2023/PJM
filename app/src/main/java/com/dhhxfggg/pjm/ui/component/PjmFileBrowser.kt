package com.dhhxfggg.pjm.ui.component

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    // 核心优化：搜索延迟过滤 —— 输入框保持即时响应，列表只在停止输入 300ms 后重建一次，
    // 避免每敲一个字符就重新过滤/排序整个目录。
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedQuery = ""
        } else {
            delay(300)
            debouncedQuery = searchQuery
        }
    }

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
    // 核心修复：以「App 回到前台」作为刷新时机 —— 用户去系统设置授权后返回时重读一次即可。
    // 原来每次重组都直接调 Environment.isExternalStorageManager()（跨进程 IPC），
    // 而搜索框每敲一个字符都会触发重组 → 每敲一下就是一次 binder 调用。
    val isAppVisible = rememberIsAppVisible()
    val hasAllFilesAccess =
        remember(isAppVisible) {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
        }

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
    var installedApps by remember { mutableStateOf<List<Triple<String, String, ApplicationInfo?>>>(emptyList()) }
    LaunchedEffect(currentRawDir, currentDocDir) {
        installedApps =
            if (currentRawDir.absolutePath.endsWith("Android/data") && currentDocDir == null) {
                // 核心修复：getInstalledPackages 是 PackageManager 的跨进程调用，设备应用多时数百毫秒。
                // LaunchedEffect 默认跑在 Main，之前这里会直接卡住 UI；现在下移到 IO。
                withContext(Dispatchers.IO) { loadInstalledApps(context) }
            } else {
                emptyList()
            }
    }

    // 核心修复：目录内容加载与搜索关键词解耦。
    // 原实现把 searchQuery 作为 remember 的 key，导致搜索框【每敲一个字符】就在主线程
    // 重新执行 listFiles() / SAF 跨进程 query / DocumentFile 包装。现在：
    //   1) 目录内容只在目录变化时于 IO 线程加载一次；
    //   2) 搜索只在已加载的结果上做内存过滤。
    val dirEntries by produceState(
        initialValue = emptyList<DirEntry>(),
        currentRawDir,
        currentDocDir,
        currentTreeUri,
        installedApps,
        privilegedEntries,
        hasRootAuth,
    ) {
        value =
            withContext(Dispatchers.IO) {
                loadDirEntries(
                    context = context,
                    currentRawDir = currentRawDir,
                    docDir = currentDocDir,
                    currentTreeUri = currentTreeUri,
                    installedApps = installedApps,
                    privilegedEntries = privilegedEntries,
                    hasRootAuth = hasRootAuth,
                    persistedTreeUris = persistedPermissions.map { it.uri },
                )
            }
    }

    // 核心优化：只对已加载好的字段做内存过滤，不触碰任何 DocumentFile / 跨进程 query。
    val nodes =
        remember(dirEntries, debouncedQuery) {
            dirEntries
                .filter {
                    it.name.contains(debouncedQuery, ignoreCase = true) ||
                        (it.node as? File)?.name?.contains(debouncedQuery, ignoreCase = true) == true
                }.sortedBy { it.name.lowercase() }
        }

    // 文案在组合期取好：用 LocalContext 读资源不会跟随语言/配置变化（lint: LocalContextResources）
    val systemDirLabel = stringResource(R.string.filebrowser_system_dir)
    // 取 DocumentFile.name 是跨进程查询：只在目录变化时算一次，不能每次重组都查
    val currentPathDisplay =
        remember(currentDocDir, currentRawDir, systemDirLabel) {
            currentDocDir?.let { doc ->
                val path = doc.uri.path ?: ""
                if (path.contains("primary:Android/data")) {
                    "Data/" + path.substringAfterLast("/")
                } else {
                    doc.name ?: systemDirLabel
                }
            } ?: currentRawDir.absolutePath
        }

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
            // 核心优化：去掉常驻的 blur(20.dp) 离屏模糊（整块背景每帧都要离屏渲染），
            // 改为静态半透明渐变 —— 观感接近，开销几乎为零。
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                                ),
                            ),
                        ),
            )

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
                            val docDir = currentDocDir
                            if (docDir != null) {
                                val parent = docDir.parentFile
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

                        // node 是 Any（本地 File 与 SAF DocumentFile 两种），没有统一的 uri 可取；
                        // 同一目录内 name 唯一，拼上当前目录做 key，刷新时才不会整列表重绘。
                        items(nodes, key = { (currentDocDir?.uri?.toString() ?: currentRawDir.absolutePath) + "/" + it.name }) { entry ->
                            // 核心修复：isDirectory / length() 已在 loadDirEntries 的 IO 阶段算好 ——
                            // DocumentFile 的这两个属性是跨进程 query，绝不能在组合期调用。
                            val name = entry.name
                            val node = entry.node
                            val isDirectory = entry.isDirectory
                            FolderItem(name, entry.appInfo, isDirectory, entry.fileSize) {
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
                                val docDir = currentDocDir
                                val selectedUri =
                                    when {
                                        docDir != null -> docDir.uri
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

/**
 * 读取「只显示用户安装的应用」列表。
 *
 * 只显示用户安装的应用（过滤全部系统应用，含被商店更新过的系统应用）：
 * `FLAG_SYSTEM` 标记预装系统应用，`FLAG_UPDATED_SYSTEM_APP` 标记被 Play/商店更新过的
 * 系统应用（仍有系统签名），两者都应过滤。仅保留 B站/PJM 重要包例外。
 *
 * **必须在 IO 线程调用**：`getInstalledPackages` 是 PackageManager 跨进程调用。
 */
private fun loadInstalledApps(context: android.content.Context): List<Triple<String, String, ApplicationInfo?>> {
    val pm = context.packageManager
    return pm
        .getInstalledPackages(0)
        .mapNotNull { info ->
            try {
                val appInfo = info.applicationInfo ?: return@mapNotNull null
                val pkg = info.packageName
                val isSystemApp =
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                // 重要包例外：B站/PJM 即使预装也保留（核心功能）
                val isImportant = pkg in BiliBridge.BILI_PKGS || pkg == context.packageName
                if (isSystemApp && !isImportant) return@mapNotNull null
                val name = pm.getApplicationLabel(appInfo).toString()
                Triple(name, pkg, appInfo)
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.first.lowercase() }
}

/**
 * 目录条目（一次 IO 加载的结果）。
 *
 * `isDirectory` / `fileSize` 在 [loadDirEntries] 的 IO 线程里预先算好 ——
 * `DocumentFile.isDirectory` / `length()` 在 SAF 下是跨进程 query，
 * 绝不能在 LazyColumn 的 item 组合期调用（滚动时每行每次重组一次 binder）。
 */
private data class DirEntry(
    val name: String,
    val node: Any,
    val appInfo: ApplicationInfo?,
    val isDirectory: Boolean,
    val fileSize: Long?,
)

/**
 * 枚举当前目录内容。
 *
 * **必须在 IO 线程调用** —— 内含 `listFiles()`、SAF 跨进程 `contentResolver.query()`
 * 与 `DocumentFile` 包装；目录标志与文件大小也在这里一次性算好。
 *
 * @return 每项为 [DirEntry]（显示名 / File 或 DocumentFile / ApplicationInfo? / 是否目录 / 文件大小）
 */
@Suppress("LongParameterList")
private fun loadDirEntries(
    context: android.content.Context,
    currentRawDir: File,
    docDir: DocumentFile?,
    currentTreeUri: Uri?,
    installedApps: List<Triple<String, String, ApplicationInfo?>>,
    privilegedEntries: List<Pair<File, Boolean>>?,
    hasRootAuth: Boolean,
    persistedTreeUris: List<Uri>,
): List<DirEntry> {
    if (docDir != null) {
        var filesList = docDir.listFiles().toList()
        if (filesList.isEmpty()) {
            val manualFiles = mutableListOf<DocumentFile>()
            try {
                val treeForQuery = currentTreeUri ?: docDir.uri
                val childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeForQuery,
                        DocumentsContract.getDocumentId(docDir.uri),
                    )
                context.contentResolver
                    .query(
                        childrenUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0)
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeForQuery, id)
                            DocumentFile.fromSingleUri(context, fileUri)?.let { manualFiles.add(it) }
                        }
                    }
                filesList = manualFiles
            } catch (_: Exception) {
            }
        }
        // 核心修复：目录选择器同时展示文件与目录（文件带图标不可选，仅供确认目录内容）
        // SAF 下 isDirectory / length() 都是跨进程 query —— 在这里（IO 线程）各查一次。
        return filesList.map { doc ->
            val isDir = doc.isDirectory
            DirEntry(doc.name ?: "Unknown", doc, null, isDir, if (isDir) null else doc.length())
        }
    }

    if (currentRawDir.absolutePath.endsWith("Android/data")) {
        return installedApps.map { (name, pkg, appInfo) ->
            val appDir = File(currentRawDir, pkg)
            val isDir = appDir.isDirectory
            DirEntry(name, appDir, appInfo, isDir, if (isDir) null else appDir.length())
        }
    }

    // 核心修复：隐藏目录过滤不可靠（.data 会被误显示成 data）——
    // 用 isHidden + “以 . 开头”双重判断，彻底过滤 .xxx 隐藏目录。
    // 目录和文件都展示，文件仅供浏览确认，不可选择。
    // 特权模式可用时优先用 shell 身份枚举（Android 14 的 File API 读 Android/data 返回空）；
    // 特权不可用才用 File API。
    val fileNodes: List<DirEntry> =
        if (privilegedEntries != null) {
            privilegedEntries
                .filter { !it.first.name.startsWith(".") }
                .map { (file, isDir) ->
                    // 特权模式下必须用 shell 枚举的目录标志：Android 14 的 File.isDirectory
                    // 对 Android/data 下受保护目录恒为 false（会把 download 误判成文件）。
                    DirEntry(file.name, file, null, isDir, if (isDir) null else file.length().takeIf { it > 0 })
                }
        } else {
            currentRawDir
                .listFiles()
                ?.filter { !it.isHidden && !it.name.startsWith(".") }
                ?.map { file ->
                    val isDir = file.isDirectory
                    DirEntry(file.name, file, null, isDir, if (isDir) null else file.length())
                } ?: emptyList()
        }

    // 核心修复：浏览 Android 目录时，File API 在 Android 14+ 看不到受保护的 data 目录（只剩 .data）。
    // 若已有根授权（SAF），用 SAF 构建 data 入口，让用户能进入 Android/data。
    if (currentRawDir.absolutePath.endsWith("Android") && hasRootAuth) {
        val rootTree =
            persistedTreeUris.firstOrNull { tree ->
                val treeId =
                    try {
                        DocumentsContract.getTreeDocumentId(tree)
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
                    val subUri = DocumentsContract.buildDocumentUriUsingTree(rootTree, "primary:Android/data")
                    DocumentFile.fromSingleUri(context, subUri)
                } catch (_: Exception) {
                    null
                }
            // data 入口排在最前，优先展示（它受保护但 SAF 可达）
            if (dataDoc != null) return listOf(DirEntry("data", dataDoc, null, dataDoc.isDirectory, null)) + fileNodes
        }
    }
    return fileNodes
}
