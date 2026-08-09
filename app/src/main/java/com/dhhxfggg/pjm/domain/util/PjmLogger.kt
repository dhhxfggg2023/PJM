package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PJM Private Business Logger Engine.
 * Provides persistent logging to the device's internal storage.
 */
object PjmLogger {
    private const val LOG_TAG = "PjmLogger"
    private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024L // 10MB
    private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var internalLogFile: File? = null
    
    private val logDateFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = 
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * Initializes the logger engine.
     */
    fun init(context: Context) {
        val logDir = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
        internalLogFile = File(logDir, "pjm_business.log")
        
        i(LOG_TAG, "==================== PJM ENGINE STARTED ====================")
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("CRASH", "Uncaught exception on thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        writeToLogFile("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        writeToLogFile("I", tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        val fullMsg = if (tr != null) "$msg\n${getStackTrace(tr)}" else msg
        writeToLogFile("W", tag, fullMsg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        val fullMsg = if (tr != null) "$msg\n${getStackTrace(tr)}" else msg
        writeToLogFile("E", tag, fullMsg)
    }

    private fun writeToLogFile(level: String, tag: String, msg: String) {
        val formatter = logDateFormatter.get()
        val timestamp = formatter?.format(Date()) ?: "UNKNOWN"
        val logLine = "[$timestamp] $level/$tag: $msg\n"
        
        loggerScope.launch {
            try {
                internalLogFile?.let { file ->
                    if (file.exists() && file.length() > MAX_LOG_FILE_SIZE) {
                        val backup = File(file.parent, "pjm_business_old.log")
                        if (backup.exists()) backup.delete()
                        file.renameTo(backup)
                    }
                    FileOutputStream(file, true).use { fos ->
                        fos.write(logLine.toByteArray(Charsets.UTF_8))
                    }
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to write to log file", e)
            }
        }
    }

    private fun getStackTrace(tr: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        tr.printStackTrace(pw)
        return sw.toString()
    }

    fun getLogFile(): File? = internalLogFile

    fun clear() {
        loggerScope.launch {
            runCatching {
                internalLogFile?.parentFile?.listFiles()?.forEach { file ->
                    if (file.name.contains("pjm_business")) {
                        file.delete()
                    }
                }
            }
        }
    }
}
