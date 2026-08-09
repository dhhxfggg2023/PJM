package com.dhhxfggg.pjm.domain.util

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.dhhxfggg.pjm.R
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object ErrorHandler {
    private val _errors = MutableSharedFlow<UiText>()
    val errors: SharedFlow<UiText> = _errors.asSharedFlow()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        handleError(throwable)
    }

    fun getExceptionHandler() = exceptionHandler

    fun handleError(error: Throwable) {
        val uiText = when (error) {
            is java.io.FileNotFoundException -> UiText.StringResource(R.string.error_read_failed)
            is java.io.IOException -> UiText.StringResource(R.string.error_read_failed)
            is SecurityException -> UiText.StringResource(R.string.error_read_failed)
            is OutOfMemoryError -> UiText.StringResource(R.string.error_read_failed)
            is IllegalArgumentException -> UiText.DynamicString(error.message ?: "Error")
            else -> UiText.DynamicString(error.message ?: "Unknown Error")
        }
        
        PjmLogger.e("ErrorHandler", error.message ?: "Unknown error", error)

        scope.launch {
            _errors.emit(uiText)
        }
    }

    fun showMessage(uiText: UiText) {
        scope.launch {
            _errors.emit(uiText)
        }
    }
}

@Composable
fun ErrorListener(
    onError: (String) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        ErrorHandler.errors.collect { uiText ->
            val message = uiText.asString(context)
            onError(message)
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
