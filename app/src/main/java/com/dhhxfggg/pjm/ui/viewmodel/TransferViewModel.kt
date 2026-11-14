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
    val task: PcTask? = null,
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
 * 两个方向都由手机发起：发到电脑 = 给 PC 下发拉取任务并看进度；取回手机 = 手机直接拉 PC 文件夹。
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
        private val importedKeys =
            context.getSharedPreferences("pjm_transfer", Context.MODE_PRIVATE).getStringSet("imported", emptySet())!!.toMutableSet()

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

        fun startPull(
            category: String,
            fromMs: Long,
            toMs: Long,
        ) {
            viewModelScope.launch {
                val ok = withContext(Dispatchers.IO) { client.startPull(category, fromMs, toMs) }
                _state.value =
                    _state.value.copy(
                        message = if (ok) "已通知电脑端开始接收" else "电脑端没有响应，确认工具已打开",
                    )
            }
        }

        fun stopPull() {
            viewModelScope.launch { withContext(Dispatchers.IO) { client.stopPull() } }
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
