package com.dhhxfggg.pjm.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.transfer.PcFolder
import com.dhhxfggg.pjm.domain.transfer.PcLinkClient
import com.dhhxfggg.pjm.domain.transfer.PcTask
import com.dhhxfggg.pjm.domain.transfer.VaultTransferServer
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.domain.util.VaultPaths
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class TransferUiState(
    val serverRunning: Boolean = false,
    val starting: Boolean = false,
    val port: Int = 0,
    val pcOnline: Boolean = false,
    val pcRoot: String = "",
    val pcFs: String = "",
    val pcFree: Long = 0L,
    val pcTotal: Long = 0L,
    val folders: List<PcFolder> = emptyList(),
    val selected: Set<String> = emptySet(),
    /** 库里出现过的入库日期，新的在前（yyyy-MM-dd） */
    val dates: List<String> = emptyList(),
    /** 每个日期对应多少条记录，用于日期列表右侧的数量 */
    val dateCounts: Map<String, Int> = emptyMap(),
    /** 选中要传的日期；空 = 全部 */
    val selectedDates: Set<String> = emptySet(),
    val checking: Boolean = false,
    /** 电脑端回报的拉取任务状态（电脑上主动点「接收手机文件」时才用） */
    val task: PcTask? = null,
    /** 手机自己推送时本地算出来的进度：不依赖电脑端回报 */
    val push: PcTask? = null,
    val importing: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val importBytes: Long = 0L,
    val importSpeed: Long = 0L,
    val importError: String? = null,
    val message: String? = null,
)

/**
 * 「传到电脑」页：手机起服务（只绑回环），PC 工具经 adb 隧道连接。
 * 两个方向都由手机发起：发到电脑 = 手机逐个文件推给电脑（进度在本地算）；
 * 取回手机 = 手机直接拉电脑的文件夹。
 */
@HiltViewModel
class TransferViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val fileDao: FileDao,
    ) : ViewModel() {
        private var files: List<FileEntity> = emptyList()
        private val client = PcLinkClient()
        private var pollJob: Job? = null

        // 短时间连点只留最后一条提示：Toast 排队会连弹好几次
        private var lastToast: android.widget.Toast? = null
        private val importedKeys =
            context
                .getSharedPreferences("pjm_transfer", Context.MODE_PRIVATE)
                .getStringSet("imported", emptySet())
                .orEmpty()
                .toMutableSet()

        // 已经推到电脑上的文件（路径+大小）：重复点「开始」不会在电脑上堆出「(2)(3)」
        private val pushedKeys =
            context
                .getSharedPreferences("pjm_transfer", Context.MODE_PRIVATE)
                .getStringSet("pushed", emptySet())
                .orEmpty()
                .toMutableSet()

        @Volatile
        private var pushCancel = false

        private val server =
            VaultTransferServer(
                vaultRoot = VaultPaths.vaultRoot(context),
                filesProvider = { files },
                displayName = { FileUtils.normalizedDisplayName(it) },
            )

        private val _state = MutableStateFlow(TransferUiState())
        val state: StateFlow<TransferUiState> = _state.asStateFlow()

        fun toggleServer() {
            if (_state.value.serverRunning) {
                pollJob?.cancel()
                server.stop()
                _state.value = TransferUiState()
                return
            }
            _state.value = _state.value.copy(starting = true, message = null)
            viewModelScope.launch {
                val loaded =
                    withContext(Dispatchers.IO) {
                        VaultManager.CATEGORIES.flatMap { category ->
                            runCatching { fileDao.getFilesByCategoryOnce(category) }.getOrDefault(emptyList())
                        }
                    }
                files = loaded
                val ok = withContext(Dispatchers.IO) { server.start() }
                if (!ok) {
                    _state.value = TransferUiState(message = "服务启动失败：端口被占用")
                    return@launch
                }
                _state.value =
                    _state.value.copy(serverRunning = true, starting = false, port = server.port, message = null)
                startPolling()
                refreshDates()
            }
        }

        private fun startPolling() {
            pollJob?.cancel()
            pollJob =
                viewModelScope.launch {
                    while (isActive) {
                        val snapshot = withContext(Dispatchers.IO) { client.state() }
                        val cur = _state.value
                        _state.value =
                            if (snapshot == null) {
                                cur.copy(pcOnline = false, task = null)
                            } else {
                                cur.copy(
                                    pcOnline = true,
                                    pcRoot = snapshot.root,
                                    pcFs = snapshot.fs,
                                    pcFree = snapshot.free,
                                    pcTotal = snapshot.total,
                                    folders = snapshot.folders,
                                    task = snapshot.task,
                                    selected = cur.selected.filter { key -> snapshot.folders.any { it.key == key } }.toSet(),
                                )
                            }
                        delay(1200)
                    }
                }
        }

        /** 重新读一遍可选日期（入库后日期会变多，回页面时刷新） */
        fun refreshDates() {
            viewModelScope.launch {
                val groups = withContext(Dispatchers.IO) { runCatching { fileDao.getImportCountsByDay() }.getOrDefault(emptyList()) }
                val recent = groups.takeLast(40).reversed()
                val list = recent.map { it.day }
                _state.value =
                    _state.value.copy(
                        dates = list,
                        dateCounts = recent.associate { it.day to it.count },
                        selectedDates = _state.value.selectedDates.intersect(list.toSet()),
                        message = if (list.isEmpty()) "库里还没有入库记录，暂时只能传全部文件" else "已刷新：" + list.size + " 个日期可选",
                    )
                toast(_state.value.message ?: "")
            }
        }

        /** 立即弹一条提示：按钮点下去必须有反应，不能只更新一个页面底部的状态文本 */
        private fun toast(text: String) {
            if (text.isBlank()) return
            viewModelScope.launch {
                withContext(Dispatchers.Main) {
                    runCatching { lastToast?.cancel() }
                    val t = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT)
                    lastToast = t
                    t.show()
                }
            }
        }

        /** 手动测试与电脑端的连通性（「测试连接」按钮） */
        fun probe() {
            // 连点时只认第一次，避免重复发起探测
            if (_state.value.checking) return
            viewModelScope.launch {
                _state.value = _state.value.copy(checking = true, message = "正在测试连接…")
                val snapshot = withContext(Dispatchers.IO) { client.state() }
                if (snapshot == null) {
                    _state.value =
                        _state.value.copy(
                            checking = false,
                            pcOnline = false,
                            message = "连不上电脑端：确认 PJM-Link 已打开、USB 已插好，并在手机上允许了调试",
                        )
                    toast(_state.value.message ?: "")
                } else {
                    _state.value =
                        _state.value.copy(
                            checking = false,
                            pcOnline = true,
                            pcRoot = snapshot.root,
                            pcFs = snapshot.fs,
                            pcFree = snapshot.free,
                            pcTotal = snapshot.total,
                            folders = snapshot.folders,
                            message = "连接正常 · " + (if (snapshot.writable) "可写" else "不可写") + " · 剩余 " + FileUtils.formatFileSize(snapshot.free),
                        )
                }
                toast(_state.value.message ?: "")
            }
        }

        fun toggleDate(day: String) {
            val cur = _state.value.selectedDates
            _state.value = _state.value.copy(selectedDates = if (day in cur) cur - day else cur + day)
        }

        fun clearDates() {
            _state.value = _state.value.copy(selectedDates = emptySet())
        }

        /**
         * 发到电脑：手机自己把文件推过去。
         *
         * 进度、速度、剩余全部在手机本地算——传多快取决于手机读盘和 USB 隧道，
         * 不该等电脑端回报一个任务状态才知道走到哪一步。
         */
        fun startPush(dates: List<String>) {
            val snapshot = _state.value
            if (snapshot.checking || snapshot.push?.active == true) return
            viewModelScope.launch {
                _state.value = _state.value.copy(checking = true, message = "正在检查电脑端…")
                val probe = withContext(Dispatchers.IO) { client.state() }
                if (probe == null) {
                    _state.value =
                        _state.value.copy(checking = false, pcOnline = false, push = null, message = "电脑端连不上：先打开 PJM-Link，插好 USB 并允许调试")
                    return@launch
                }
                if (!probe.writable) {
                    _state.value =
                        _state.value.copy(checking = false, pcOnline = true, push = null, message = "电脑接收目录不可写：" + probe.root)
                    return@launch
                }
                val fmt = PcLinkClient.DATE_FORMAT
                val all =
                    withContext(Dispatchers.IO) {
                        VaultManager.CATEGORIES.flatMap { category ->
                            runCatching { fileDao.getFilesByCategoryOnce(category) }.getOrDefault(emptyList())
                        }
                    }
                val chosen =
                    all.filter { entity ->
                        val stamp = entity.importedAt.takeIf { it > 0 } ?: entity.lastModified
                        dates.isEmpty() || dates.contains(fmt.format(java.util.Date(stamp)))
                    }
                if (chosen.isEmpty()) {
                    _state.value = _state.value.copy(checking = false, pcOnline = true, push = null, message = "没有要发送的文件")
                    return@launch
                }
                val planBytes = chosen.sumOf { it.size }
                val total = chosen.size
                var done = 0
                var failed = 0
                var skipped = 0
                var bytes = 0L
                var lastAt = System.currentTimeMillis()
                var lastBytes = 0L
                var lastUiAt = 0L
                pushCancel = false
                _state.value =
                    _state.value.copy(
                        checking = false,
                        pcOnline = true,
                        message = "准备发送 " + total + " 个 · " + FileUtils.formatFileSize(planBytes),
                        push = PcTask(active = true, done = 0, total = total, bytes = 0, speed = 0, skipped = 0, failed = 0, error = null),
                    )
                for (entity in chosen) {
                    if (pushCancel) break
                    val stamp = entity.importedAt.takeIf { it > 0 } ?: entity.lastModified
                    val day = fmt.format(java.util.Date(stamp))
                    val name = FileUtils.normalizedDisplayName(entity)
                    // 身份以库里文件的 uuid 为准：改名、改入库时间都不影响「是不是同一个文件」
                    val uuid = entity.relativePath.substringAfterLast('/').substringBeforeLast('.')
                    val key = uuid
                    val base = bytes
                    if (pushedKeys.contains(key)) {
                        skipped++
                        done++
                        bytes = base + entity.size
                        continue
                    }
                    val file = File(VaultPaths.vaultRoot(context), entity.relativePath)
                    if (!file.exists() || !file.isFile || file.length() != entity.size) {
                        failed++
                        done++
                        _state.value =
                            _state.value.copy(
                                push = PcTask(true, done, total, bytes, 0, skipped, failed, "文件缺失或大小不一致：" + name),
                            )
                        continue
                    }
                    try {
                        val skippedOnPc =
                            withContext(Dispatchers.IO) {
                                client.upload(entity.category, day, name, uuid, file, done + 1, total, { pushCancel }) { sent ->
                                    bytes = base + sent
                                    val now = System.currentTimeMillis()
                                    if (now - lastUiAt >= 150) {
                                        lastUiAt = now
                                        val elapsed = (now - lastAt).coerceAtLeast(1L)
                                        val speed = (bytes - lastBytes) * 1000L / elapsed
                                        lastAt = now
                                        lastBytes = bytes
                                        _state.value =
                                            _state.value.copy(
                                                push = PcTask(true, done, total, bytes, speed, skipped, failed, null),
                                            )
                                    }
                                }
                            }
                        pushedKeys.add(key)
                        context
                            .getSharedPreferences("pjm_transfer", Context.MODE_PRIVATE)
                            .edit()
                            .putStringSet("pushed", pushedKeys)
                            .apply()
                        if (skippedOnPc) skipped++
                        done++
                    } catch (e: Exception) {
                        if (pushCancel) break
                        failed++
                        done++
                        _state.value =
                            _state.value.copy(
                                push = PcTask(true, done, total, bytes, 0, skipped, failed, e.message),
                            )
                    }
                    val now = System.currentTimeMillis()
                    val elapsed = (now - lastAt).coerceAtLeast(1L)
                    val speed = (bytes - lastBytes) * 1000L / elapsed
                    lastAt = now
                    lastBytes = bytes
                    lastUiAt = now
                    _state.value =
                        _state.value.copy(
                            push = PcTask(true, done, total, bytes, speed, skipped, failed, _state.value.push?.error),
                        )
                }
                val cancelled = pushCancel
                val closed = withContext(Dispatchers.IO) { client.uploadDone() }
                _state.value =
                    _state.value.copy(
                        push = PcTask(false, done, total, bytes, 0, skipped, failed, null),
                        message =
                            when {
                                cancelled -> "已停止 · " + done + "/" + total
                                failed > 0 -> "完成 " + done + "/" + total + " · 失败 " + failed
                                skipped > 0 -> "已发送 " + (done - skipped) + " 个 · 跳过 " + skipped + " 个（之前传过）"
                                !closed && done == total -> "已发送 " + total + " 个 · " + FileUtils.formatFileSize(bytes)
                                else -> "已发送 " + done + "/" + total + " · " + FileUtils.formatFileSize(bytes)
                            },
                    )
            }
        }

        fun stopPush() {
            if (_state.value.push?.active != true) return
            pushCancel = true
        }

        fun toggleFolder(key: String) {
            val cur = _state.value.selected
            _state.value = _state.value.copy(selected = if (key in cur) cur - key else cur + key)
        }

        fun selectAllFolders() {
            val all =
                _state.value.folders
                    .map { it.key }
                    .toSet()
            _state.value = _state.value.copy(selected = if (all == _state.value.selected) emptySet() else all)
        }

        fun importSelected() {
            val cs = _state.value
            if (cs.importing) return
            val chosen = cs.folders.filter { it.key in cs.selected }
            if (chosen.isEmpty()) {
                _state.value = cs.copy(message = "先选一个文件夹")
                return
            }
            viewModelScope.launch {
                var done = 0
                var failed = 0
                var totalFiles = chosen.sumOf { it.count }
                var bytes = 0L
                var lastAt = System.currentTimeMillis()
                var lastBytes = 0L
                _state.value =
                    cs.copy(importing = true, importDone = 0, importTotal = totalFiles, importBytes = 0, importSpeed = 0, importError = null, message = null)
                for (folder in chosen) {
                    val list = withContext(Dispatchers.IO) { client.files(folder.category, folder.date) }
                    if (list.isEmpty()) continue
                    totalFiles = totalFiles.coerceAtLeast(done + list.size)
                    for (item in list) {
                        val key = folder.category + "|" + folder.date + "|" + item.name + "|" + item.size
                        if (importedKeys.contains(key)) {
                            done++
                            _state.value = _state.value.copy(importDone = done)
                            continue
                        }
                        try {
                            withContext(Dispatchers.IO) { saveOne(folder.category, folder.date, item.name) { read -> bytes += read } }
                            importedKeys.add(key)
                            context
                                .getSharedPreferences("pjm_transfer", Context.MODE_PRIVATE)
                                .edit()
                                .putStringSet("imported", importedKeys)
                                .apply()
                            done++
                        } catch (e: Exception) {
                            failed++
                            done++
                            _state.value = _state.value.copy(importError = e.message)
                        }
                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastAt).coerceAtLeast(1L)
                        val speed = ((bytes - lastBytes) * 1000L / elapsed)
                        lastAt = now
                        lastBytes = bytes
                        _state.value = _state.value.copy(importDone = done, importBytes = bytes, importSpeed = speed)
                    }
                }
                VaultManager.triggerRefresh()
                _state.value =
                    _state.value.copy(importing = false, importTotal = done, importError = if (failed > 0) "失败 " + failed + " 个" else null, message = "新增 " + (done - failed) + " · 失败 " + failed)
            }
        }

        private suspend fun saveOne(
            category: String,
            date: String,
            name: String,
            onBytes: (Long) -> Unit,
        ) {
            val root = VaultPaths.vaultRoot(context)
            val dir = File(root, category).apply { mkdirs() }
            val ext = FileUtils.getFileExtension(name)
            val uuid = UUID.randomUUID().toString()
            val part = File(dir, uuid + ".part")
            val final = File(dir, if (ext.isEmpty()) uuid else uuid + "." + ext)
            client.download(category, date, name, part, onBytes)
            if (!part.renameTo(final)) {
                part.delete()
                error("写入保险库失败")
            }
            val now = System.currentTimeMillis()
            fileDao.upsert(
                FileEntity(
                    relativePath = category + "/" + final.name,
                    name = name,
                    size = final.length(),
                    category = category,
                    lastModified = now,
                    isImage = FileUtils.isImageFile(name),
                    extension = ext,
                    contentHash = null,
                    importedAt = now,
                ),
            )
        }

        override fun onCleared() {
            pollJob?.cancel()
            server.stop()
        }
    }
