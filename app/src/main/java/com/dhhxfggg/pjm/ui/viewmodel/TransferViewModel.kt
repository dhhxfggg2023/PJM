package com.dhhxfggg.pjm.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.transfer.PcFolder
import com.dhhxfggg.pjm.domain.transfer.PcLinkClient
import com.dhhxfggg.pjm.domain.transfer.PcTask
import com.dhhxfggg.pjm.domain.transfer.VaultTransferServer
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.domain.util.VaultPaths
import com.dhhxfggg.pjm.domain.util.upsertLocked
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

/**
 * 本批推送失败的单个文件。
 *
 * 带 [entity] 是为了在「失败文件」弹窗里给图片/视频显示缩略图 —— 只给文件名，
 * 用户没法判断失败的是哪一张。文本等出不了缩略图的类型只显示文件名与原因。
 */
data class TransferFailedItem(
    val name: String,
    val reason: String,
    val entity: FileEntity?,
)

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
    /** 本批推送失败的文件（显示名 + 原因 + 库内实体，供弹窗显示缩略图） */
    val pushFailedItems: List<TransferFailedItem> = emptyList(),
    val importing: Boolean = false,
    /** 取回时用户点了「停止」：当前文件写完就退出，不再继续后面的文件 */
    val importCancelled: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val importBytes: Long = 0L,
    val importSpeed: Long = 0L,
    /** 取回（电脑→手机）的总平均速度 */
    val importAvgSpeed: Long = 0L,
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
        // 服务线程会并发读取；整体替换为不可变 List，标记 volatile 保证可见性
        @Volatile
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

        /** 「从电脑取回」的取消标志：与 pushCancel 对称，按批重置 */
        @Volatile
        private var importCancel = false

        // 传输令牌：每次开启服务随机生成，写入外部私有目录供 PC 经 adb 读取。
        // 不写应用私有目录（shell 进程读不到），也绝不硬编码。
        @Volatile
        private var transferToken: String? = null

        private val server =
            VaultTransferServer(
                vaultRoot = VaultPaths.vaultRoot(context),
                filesProvider = { files },
                displayName = { FileUtils.normalizedDisplayName(it) },
                authTokenProvider = { transferToken },
            )

        private val _state = MutableStateFlow(TransferUiState())
        val state: StateFlow<TransferUiState> = _state.asStateFlow()

        fun toggleServer() {
            if (_state.value.serverRunning) {
                pollJob?.cancel()
                server.stop()
                transferToken = null
                deleteTransferToken()
                _state.value = TransferUiState()
                return
            }
            _state.value = _state.value.copy(starting = true, message = null)
            viewModelScope.launch {
                files = withContext(Dispatchers.IO) { loadAllFiles() }
                // 先落令牌再启动服务：PC 连上后先读令牌，再发第一个数据请求
                val token =
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                transferToken = token
                writeTransferToken(token)
                val ok = withContext(Dispatchers.IO) { server.start() }
                if (!ok) {
                    _state.value = TransferUiState(message = context.getString(R.string.transfer_start_port_busy))
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
                        // 电脑端 PJM-Link 是单线程：手机正在推文件时，state() 请求在对端排队，
                        // 超过 8s 读超时 → state() 返回 null → 被误判「电脑端离线」。
                        // 推送期间进度完全由手机本地算（不等电脑端回报），所以这几轮直接跳过，
                        // 推完（push.active 变 false）立刻恢复轮询。
                        if (_state.value.push?.active == true) {
                            delay(1200)
                            continue
                        }
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

        private fun tokenFile(): File? = context.getExternalFilesDir(null)?.let { File(it, TOKEN_FILE_NAME) }

        /** 原子写令牌文件：PC 可能在服务启动瞬间读取，避免读到半截内容 */
        private fun writeTransferToken(token: String) {
            runCatching {
                val dir = context.getExternalFilesDir(null) ?: return
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, TOKEN_FILE_NAME)
                val tmp = File(dir, "$TOKEN_FILE_NAME.tmp")
                tmp.writeText(token)
                if (!tmp.renameTo(target)) {
                    target.writeText(token)
                    tmp.delete()
                }
            }.onFailure { PjmLogger.w("Transfer", context.getString(R.string.transfer_token_write_failed), it) }
        }

        private fun deleteTransferToken() {
            runCatching { tokenFile()?.delete() }
        }

        /** 重新读一遍库内清单：服务启动后新入库/删除的文件，PC 端与网页必须能看到 */
        private suspend fun loadAllFiles(): List<FileEntity> =
            VaultManager.CATEGORIES.flatMap { category ->
                runCatching { fileDao.getFilesByCategoryOnce(category) }.getOrDefault(emptyList())
            }

        /** 重新读一遍可选日期（入库后日期会变多，回页面时刷新） */
        fun refreshDates() {
            viewModelScope.launch {
                // 核心修复：原来只刷新日期不刷新文件清单，服务开启后新建的文件 PC 端永远看不到
                files = withContext(Dispatchers.IO) { loadAllFiles() }
                val groups = withContext(Dispatchers.IO) { runCatching { fileDao.getImportCountsByDay() }.getOrDefault(emptyList()) }
                val recent = groups.takeLast(40).reversed()
                val list = recent.map { it.day }
                _state.value =
                    _state.value.copy(
                        dates = list,
                        dateCounts = recent.associate { it.day to it.count },
                        selectedDates = _state.value.selectedDates.intersect(list.toSet()),
                        message = if (list.isEmpty()) context.getString(R.string.transfer_dates_all) else context.getString(R.string.transfer_dates_refreshed, list.size),
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
                _state.value = _state.value.copy(checking = true, message = context.getString(R.string.transfer_checking_connection))
                val snapshot = withContext(Dispatchers.IO) { client.state() }
                if (snapshot == null) {
                    _state.value =
                        _state.value.copy(
                            checking = false,
                            pcOnline = false,
                            message = context.getString(R.string.transfer_connect_fail),
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
                            message = context.getString(R.string.transfer_check_ok, context.getString(if (snapshot.writable) R.string.transfer_writable else R.string.transfer_readonly), FileUtils.formatFileSize(snapshot.free)),
                        )
                }
                toast(_state.value.message ?: "")
            }
        }

        fun toggleDate(day: String) {
            val cur = _state.value.selectedDates
            _state.value = _state.value.copy(selectedDates = if (day in cur) cur - day else cur + day)
        }

        /**
         * 整月批量选中/取消：该月已全选则整月取消，否则整月补齐。
         * 逐个 toggleDate 会触发 N 次状态更新与重组，这里一次到位。
         */
        fun toggleDates(days: List<String>) {
            if (days.isEmpty()) return
            val cur = _state.value.selectedDates
            val allSelected = days.all { it in cur }
            _state.value =
                _state.value.copy(
                    selectedDates = if (allSelected) cur - days.toSet() else cur + days,
                )
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
                _state.value = _state.value.copy(checking = true, message = context.getString(R.string.transfer_checking_pc))
                val probe = withContext(Dispatchers.IO) { client.state() }
                if (probe == null) {
                    _state.value =
                        _state.value.copy(checking = false, pcOnline = false, push = null, message = context.getString(R.string.transfer_pc_unreachable))
                    return@launch
                }
                if (!probe.writable) {
                    _state.value =
                        _state.value.copy(checking = false, pcOnline = true, push = null, message = context.getString(R.string.transfer_pc_not_writable, probe.root))
                    return@launch
                }
                val fmt = PcLinkClient.DATE_FORMAT
                val all =
                    withContext(Dispatchers.IO) {
                        VaultManager.CATEGORIES.flatMap { category ->
                            runCatching { fileDao.getFilesByCategoryOnce(category) }.getOrDefault(emptyList())
                        }
                    }
                // 核心修复：4 万条实体逐条日期格式化原本跑在主线程（明显掉帧/ANR 风险），移到 IO
                val chosen =
                    withContext(Dispatchers.IO) {
                        all.filter { entity ->
                            val stamp = entity.importedAt.takeIf { it > 0 } ?: entity.lastModified
                            dates.isEmpty() || dates.contains(fmt.format(java.util.Date(stamp)))
                        }
                    }
                // 存在性/大小校验依赖磁盘 stat，同样在主线程执行过一次；这里一次性在 IO 里算好
                val existsOnDisk =
                    withContext(Dispatchers.IO) {
                        chosen.associateWith { entity ->
                            val file = File(VaultPaths.vaultRoot(context), entity.relativePath)
                            file.exists() && file.isFile && file.length() == entity.size
                        }
                    }
                if (chosen.isEmpty()) {
                    _state.value = _state.value.copy(checking = false, pcOnline = true, push = null, message = context.getString(R.string.transfer_no_files))
                    return@launch
                }
                val planBytes = chosen.sumOf { it.size }
                val total = chosen.size
                var done = 0
                var failed = 0
                var skipped = 0
                var bytes = 0L
                val failedItems = mutableListOf<TransferFailedItem>()
                var lastUiAt = 0L
                // 速率改为 1 秒时间窗平均值：150ms 瞬时差值跳得太快，肉眼读不出来
                var speedWindowAt = System.currentTimeMillis()
                var speedWindowBytes = 0L
                var shownSpeed = 0L
                // 总平均速度：只统计真正传输的部分 —— 从第一个实际传出的字节开始计时，
                // 跳过的文件既不进分子也不进分母（否则"上次已传"的体量会摊进平均速度）。
                var avgStartedAt = 0L
                var avgSpeed = 0L
                pushCancel = false
                _state.value =
                    _state.value.copy(
                        checking = false,
                        pcOnline = true,
                        message = context.getString(R.string.transfer_preparing, total, FileUtils.formatFileSize(planBytes)),
                        push = PcTask(active = true, done = 0, total = total, bytes = 0, speed = 0, skipped = 0, failed = 0, error = null),
                        pushFailedItems = emptyList(),
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
                        // 跳过的文件（上次已传过）不累计字节：它们没有真正经过网络，
                        // 计进去会让"已传大小 / 瞬时速度 / 平均速度"全部虚高。
                        skipped++
                        done++
                        continue
                    }
                    val file = File(VaultPaths.vaultRoot(context), entity.relativePath)
                    if (existsOnDisk[entity] != true) {
                        failed++
                        done++
                        failedItems.add(
                            TransferFailedItem(name, context.getString(R.string.transfer_file_mismatch, name), entity),
                        )
                        _state.value =
                            _state.value.copy(
                                push = PcTask(true, done, total, bytes, 0, skipped, failed, context.getString(R.string.transfer_file_mismatch, name)),
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
                                        if (now - speedWindowAt >= SPEED_WINDOW_MS) {
                                            val elapsed = (now - speedWindowAt).coerceAtLeast(1L)
                                            shownSpeed = ((bytes - speedWindowBytes) * 1000L / elapsed).coerceAtLeast(0L)
                                            speedWindowAt = now
                                            speedWindowBytes = bytes
                                        }
                                        if (avgStartedAt == 0L) avgStartedAt = now
                                        val elapsedAvg = (now - avgStartedAt).coerceAtLeast(1L)
                                        avgSpeed = (bytes * 1000L / elapsedAvg).coerceAtLeast(0L)
                                        _state.value =
                                            _state.value.copy(
                                                push = PcTask(true, done, total, bytes, shownSpeed, skipped, failed, null, avgSpeed),
                                            )
                                    }
                                }
                            }
                        pushedKeys.add(key)
                        // 不再每个文件都写 SharedPreferences：pushedKeys 会涨到几千个，
                        // 每次全量序列化写入是 O(n)，实测速度从 51 文件/秒一路掉到 20。
                        // 改为整批结束后统一写一次（见下方 uploadDone 之后）。
                        if (skippedOnPc) skipped++
                        done++
                    } catch (e: Exception) {
                        if (pushCancel) break
                        failed++
                        done++
                        failedItems.add(TransferFailedItem(name, e.message.orEmpty(), entity))
                        _state.value =
                            _state.value.copy(
                                push = PcTask(true, done, total, bytes, 0, skipped, failed, e.message),
                            )
                    }
                    val now = System.currentTimeMillis()
                    if (now - speedWindowAt >= SPEED_WINDOW_MS) {
                        val elapsed = (now - speedWindowAt).coerceAtLeast(1L)
                        shownSpeed = ((bytes - speedWindowBytes) * 1000L / elapsed).coerceAtLeast(0L)
                        speedWindowAt = now
                        speedWindowBytes = bytes
                    }
                    lastUiAt = now
                    if (avgStartedAt == 0L && bytes > 0L) avgStartedAt = now
                    if (avgStartedAt > 0L) {
                        val elapsedAvg = (now - avgStartedAt).coerceAtLeast(1L)
                        avgSpeed = (bytes * 1000L / elapsedAvg).coerceAtLeast(0L)
                    }
                    _state.value =
                        _state.value.copy(
                            push = PcTask(true, done, total, bytes, shownSpeed, skipped, failed, _state.value.push?.error, avgSpeed),
                        )
                }
                val cancelled = pushCancel
                // 整批只写一次账本：避免上面每个文件都全量写入导致越传越慢
                withContext(Dispatchers.IO) {
                    context
                        .getSharedPreferences("pjm_transfer", Context.MODE_PRIVATE)
                        .edit()
                        .putStringSet("pushed", pushedKeys)
                        .apply()
                }
                val closed = withContext(Dispatchers.IO) { client.uploadDone() }
                _state.value =
                    _state.value.copy(
                        push = PcTask(false, done, total, bytes, 0, skipped, failed, null, avgSpeed),
                        pushFailedItems = failedItems.toList(),
                        message =
                            when {
                                cancelled -> context.getString(R.string.transfer_push_cancelled, done, total)
                                failed > 0 -> context.getString(R.string.transfer_push_failed, done, total, failed)
                                skipped > 0 -> context.getString(R.string.transfer_push_skipped, done - skipped, skipped)
                                !closed && done == total -> context.getString(R.string.transfer_push_done_all, total, FileUtils.formatFileSize(bytes))
                                else -> context.getString(R.string.transfer_push_done, done, total, FileUtils.formatFileSize(bytes))
                            },
                    )
            }
        }

        fun stopPush() {
            if (_state.value.push?.active != true) return
            pushCancel = true
        }

        /** 停止「从电脑取回」：当前文件写到一半就中断，已入库的文件保留 */
        fun stopImport() {
            if (!_state.value.importing) return
            importCancel = true
        }

        fun toggleFolder(key: String) {
            val cur = _state.value.selected
            _state.value = _state.value.copy(selected = if (key in cur) cur - key else cur + key)
        }

        /** 批量勾选/取消（「取回」页按「分类 + 本月」一次勾一批） */
        fun toggleFolders(keys: List<String>) {
            if (keys.isEmpty()) return
            val cur = _state.value.selected
            val target = keys.toSet()
            val next = if (target.all { it in cur }) cur - target else cur + target
            _state.value = _state.value.copy(selected = next)
        }

        fun importSelected() {
            val cs = _state.value
            if (cs.importing) return
            val chosen = cs.folders.filter { it.key in cs.selected }
            if (chosen.isEmpty()) {
                _state.value = cs.copy(message = context.getString(R.string.transfer_no_folder))
                return
            }
            importCancel = false
            viewModelScope.launch {
                var done = 0
                var failed = 0
                var totalFiles = chosen.sumOf { it.count }
                var bytes = 0L
                var speedWindowAt = System.currentTimeMillis()
                var speedWindowBytes = 0L
                var shownSpeed = 0L
                // 平均速度只在真正开始接收字节后起算，不含遍历文件夹/查询清单的准备时间
                var importAvgStartedAt = 0L
                var importAvg = 0L
                _state.value =
                    cs.copy(importing = true, importCancelled = false, importDone = 0, importTotal = totalFiles, importBytes = 0, importSpeed = 0, importAvgSpeed = 0, importError = null, message = null)
                for (folder in chosen) {
                    if (importCancel) break
                    val list = withContext(Dispatchers.IO) { client.files(folder.category, folder.date) }
                    if (list.isEmpty()) continue
                    totalFiles = totalFiles.coerceAtLeast(done + list.size)
                    for (item in list) {
                        if (importCancel) break
                        val key = folder.category + "|" + folder.date + "|" + item.name + "|" + item.size
                        if (importedKeys.contains(key)) {
                            done++
                            _state.value = _state.value.copy(importDone = done)
                            continue
                        }
                        try {
                            withContext(Dispatchers.IO) {
                                saveOne(folder.category, folder.date, item.name, { read -> bytes += read }, { importCancel })
                            }
                            importedKeys.add(key)
                            context
                                .getSharedPreferences("pjm_transfer", Context.MODE_PRIVATE)
                                .edit()
                                .putStringSet("imported", importedKeys)
                                .apply()
                            done++
                        } catch (e: Exception) {
                            done++
                            // 用户主动停止不算失败：不弹红字，只在结尾给一句「已停止」
                            if (!importCancel) {
                                failed++
                                _state.value = _state.value.copy(importError = e.message)
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (now - speedWindowAt >= SPEED_WINDOW_MS) {
                            val elapsed = (now - speedWindowAt).coerceAtLeast(1L)
                            shownSpeed = ((bytes - speedWindowBytes) * 1000L / elapsed).coerceAtLeast(0L)
                            speedWindowAt = now
                            speedWindowBytes = bytes
                        }
                        if (importAvgStartedAt == 0L && bytes > 0L) importAvgStartedAt = System.currentTimeMillis()
                        if (importAvgStartedAt > 0L) {
                            val elapsedAvg = (System.currentTimeMillis() - importAvgStartedAt).coerceAtLeast(1L)
                            importAvg = (bytes * 1000L / elapsedAvg).coerceAtLeast(0L)
                        }
                        _state.value = _state.value.copy(importDone = done, importBytes = bytes, importSpeed = shownSpeed, importAvgSpeed = importAvg)
                    }
                }
                VaultManager.triggerRefresh()
                val cancelled = importCancel
                _state.value =
                    _state.value.copy(
                        importing = false,
                        importCancelled = cancelled,
                        importTotal = done,
                        importError = if (failed > 0) context.getString(R.string.transfer_import_failed, failed) else null,
                        message =
                            if (cancelled) {
                                context.getString(R.string.transfer_import_stopped, done - failed)
                            } else {
                                context.getString(R.string.transfer_import_summary, done - failed, failed)
                            },
                    )
            }
        }

        /**
         * 把电脑端的 yyyy-MM-dd 目录名解析成本地时区的入库时刻（当天 00:00）。
         *
         * 用 SimpleDateFormat 而不是 java.time：minSdk 24，java.time 在 API 26 以下没有脱糖支持会崩。
         * 解析失败（目录名异常）时返回 null，调用方退回「当前时间」。
         */
        private fun folderDateMillis(date: String): Long? =
            runCatching {
                val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                format.isLenient = false
                format.parse(date)?.time
            }.getOrNull()

        private companion object {
            /** 令牌文件名（PC 端通过 adb 读取外部私有目录下的同名文件） */
            const val TOKEN_FILE_NAME = "pjm_transfer_token"

            /** 速率显示的时间窗：1 秒平均值，避免瞬时抖动 */
            const val SPEED_WINDOW_MS = 1000L
        }

        private suspend fun saveOne(
            category: String,
            date: String,
            name: String,
            onBytes: (Long) -> Unit,
            isCancelled: () -> Boolean,
        ) {
            val root = VaultPaths.vaultRoot(context)
            val dir = File(root, category).apply { mkdirs() }
            val ext = FileUtils.getFileExtension(name)
            val uuid = UUID.randomUUID().toString()
            val part = File(dir, uuid + ".part")
            val final = File(dir, if (ext.isEmpty()) uuid else uuid + "." + ext)
            try {
                client.download(category, date, name, part, onBytes, isCancelled)
            } catch (e: Exception) {
                // 核心修复：下载失败/被取消时 .part 残留会一直堆在分类目录里（既不进库也不清理）
                part.delete()
                throw e
            }
            if (!part.renameTo(final)) {
                part.delete()
                error(context.getString(R.string.transfer_write_vault_failed))
            }
            // 取回的文件沿用电脑上那个日期目录的日期，不记成「今天入库」。
            // 否则从电脑搬回 80 GB 老文件，热力图会在同一天堆出一个假峰值，
            // 历史分布全被压平 —— 那些文件本来就是那天进来的。
            val stamp = folderDateMillis(date) ?: System.currentTimeMillis()
            fileDao.upsertLocked(
                FileEntity(
                    relativePath = category + "/" + final.name,
                    name = name,
                    size = final.length(),
                    category = category,
                    lastModified = stamp,
                    isImage = FileUtils.isImageFile(name),
                    extension = ext,
                    contentHash = null,
                    importedAt = stamp,
                ),
            )
        }

        override fun onCleared() {
            pollJob?.cancel()
            server.stop()
        }
    }
