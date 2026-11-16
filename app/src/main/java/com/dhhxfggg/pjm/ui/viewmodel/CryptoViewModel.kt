package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.service.VaultService
import com.dhhxfggg.pjm.domain.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for cryptographic operations.
 */
@Immutable
data class CryptoUiState(
    val isProcessing: Boolean = false,
)

/**
 * ViewModel for handling cryptographic operations like encryption, storage, and password requests.
 */
@HiltViewModel
class CryptoViewModel
    @Inject
    constructor(
        private val app: Application,
    ) : AndroidViewModel(app) {
        // 一次性消费的事件通道（不再用 replay=1 的 SharedFlow）：
        // · 未被消费的事件留在通道缓冲区里 —— 冷启动 handleIntent 早于 setContent 建立订阅也不会丢；
        // · 事件一旦被消费就出队 —— Activity 重建不会重放上一条删除/密码请求
        //   （replay=1 的旧实现在重建时会把上一条再弹一次）。
        private val _events = Channel<CryptoEvent>(capacity = Channel.UNLIMITED)

        /**
         * Flow of cryptographic events that require UI interaction（每个事件只会被消费一次）。
         */
        val events: Flow<CryptoEvent> = _events.receiveAsFlow()

        private val _uiState = MutableStateFlow(CryptoUiState())

        /**
         * UI state for cryptographic status.
         */
        val uiState = _uiState.asStateFlow()

        private var pendingUris: List<Uri>? = null

        /**
         * Events that can occur during cryptographic processes.
         */
        sealed class CryptoEvent {
            /**
             * Request the user to provide a password.
             */
            data class RequestPassword(
                val fileName: String,
            ) : CryptoEvent()

            /**
             * Request permission from the user to delete original files.
             */
            data class RequestDeletePermission(
                val uris: List<Uri>,
            ) : CryptoEvent()
        }

        init {
            observeOperationResults()
        }

        private fun observeOperationResults() {
            viewModelScope.launch {
                VaultManager.operationResults.collect { result ->
                    when (result) {
                        is OperationResult.Success -> {
                            when (result.action) {
                                VaultService.ACTION_STORE -> {
                                    // 操作结果汇总反馈
                                    if (result.failed > 0) {
                                        android.widget.Toast
                                            .makeText(
                                                app,
                                                app.getString(
                                                    R.string.toast_ingest_summary_failed,
                                                    result.imported,
                                                    result.skipped,
                                                    result.failed,
                                                ),
                                                android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                    } else {
                                        android.widget.Toast
                                            .makeText(
                                                app,
                                                app.getString(R.string.toast_ingest_summary, result.imported, result.skipped),
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                    // 只有真的入库成功才询问「是否删除原件」，两道护栏见 VaultService 与 IngestionEngine
                                    if (result.imported > 0 && result.uris.isNotEmpty()) {
                                        _events.send(CryptoEvent.RequestDeletePermission(result.uris))
                                    }
                                }
                                VaultService.ACTION_ENCRYPT -> {
                                    android.widget.Toast
                                        .makeText(
                                            app,
                                            app.getString(R.string.toast_encrypt_volumes, result.volumes),
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                        }
                        is OperationResult.Error -> {
                            android.widget.Toast
                                .makeText(app, result.message, android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }
                        is OperationResult.PasswordRequired -> {
                            pendingUris = result.uris
                            _events.send(CryptoEvent.RequestPassword(result.fileName))
                        }
                    }
                }
            }
        }

        /**
         * Initiates the storage (encryption and movement) of files into the vault.
         *
         * @param uris The URIs of the files to store.
         * @param password Optional password for encryption.
         */
        fun handleStore(
            uris: List<Uri>,
            password: String? = null,
        ) {
            pendingUris = uris
            VaultService.startStore(app, uris, password)
        }

        /**
         * Retries a pending storage operation with the provided password.
         *
         * @param password The password to use for encryption.
         */
        fun retryWithPassword(password: String) {
            pendingUris?.let { handleStore(it, password) }
        }

        /**
         * Initiates the packing and encryption of multiple files into a single PJM archive.
         *
         * @param uris The URIs of the files to pack and encrypt.
         */
        fun handlePackAndEncrypt(uris: List<Uri>) {
            VaultService.startEncrypt(app, uris)
        }
    }
