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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for cryptographic operations.
 */
@Immutable
data class CryptoUiState(
    val isProcessing: Boolean = false
)

/**
 * ViewModel for handling cryptographic operations like encryption, storage, and password requests.
 */
@HiltViewModel
class CryptoViewModel @Inject constructor(
    private val app: Application,
) : AndroidViewModel(app) {

    private val _events = MutableSharedFlow<CryptoEvent>()
    /**
     * Shared flow for cryptographic events that require UI interaction.
     */
    val events = _events.asSharedFlow()

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
         * Request to open a file with the system app.
         */
        data class RequestSystemOpen(val uri: Uri, val fileName: String) : CryptoEvent()
        /**
         * Request the user to provide a password.
         */
        data class RequestPassword(val fileName: String) : CryptoEvent()
        /**
         * Request permission from the user to delete original files.
         */
        data class RequestDeletePermission(val uris: List<Uri>) : CryptoEvent()
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
                                    android.widget.Toast.makeText(
                                        app,
                                        app.getString(R.string.toast_ingest_summary_failed, result.imported, result.skipped, result.failed),
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    android.widget.Toast.makeText(
                                        app,
                                        app.getString(R.string.toast_ingest_summary, result.imported, result.skipped),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                if (result.uris.isNotEmpty()) {
                                    _events.emit(CryptoEvent.RequestDeletePermission(result.uris))
                                }
                            }
                            VaultService.ACTION_ENCRYPT -> {
                                android.widget.Toast.makeText(
                                    app,
                                    app.getString(R.string.toast_encrypt_volumes, result.volumes),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    is OperationResult.PasswordRequired -> {
                        pendingUris = result.uris
                        _events.emit(CryptoEvent.RequestPassword(result.fileName))
                    }
                    else -> {}
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
    fun handleStore(uris: List<Uri>, password: String? = null) {
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
