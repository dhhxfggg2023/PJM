package com.dhhxfggg.pjm.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.transfer.VaultTransferServer
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.domain.util.VaultPaths
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

data class TransferUiState(
    val running: Boolean = false,
    val port: Int = 0,
    val addresses: List<String> = emptyList(),
    val fileCount: Int = 0,
    val totalSize: Long = 0L,
    val starting: Boolean = false,
    val error: String? = null,
)

/** 「传到电脑」页面的状态：手机自己起 HTTP 服务，电脑浏览器直接拉文件。 */
@HiltViewModel
class TransferViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val fileDao: FileDao,
    ) : ViewModel() {
        private var files: List<FileEntity> = emptyList()

        private val server =
            VaultTransferServer(
                vaultRoot = VaultPaths.vaultRoot(context),
                filesProvider = { files },
                displayName = { FileUtils.normalizedDisplayName(it) },
            )

        private val _state = MutableStateFlow(TransferUiState())
        val state: StateFlow<TransferUiState> = _state.asStateFlow()

        fun toggle() {
            if (_state.value.running) {
                server.stop()
                _state.value = TransferUiState()
                return
            }
            _state.value = _state.value.copy(starting = true, error = null)
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
                    _state.value = TransferUiState(error = "端口被占用，换个时间再试")
                    return@launch
                }
                _state.value =
                    TransferUiState(
                        running = true,
                        port = server.port,
                        addresses = localAddresses(),
                        fileCount = loaded.size,
                        totalSize = loaded.sumOf { it.size },
                    )
            }
        }

        override fun onCleared() {
            server.stop()
        }

        private fun localAddresses(): List<String> =
            runCatching {
                NetworkInterface
                    .getNetworkInterfaces()
                    .toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .distinct()
            }.getOrDefault(emptyList())
    }
