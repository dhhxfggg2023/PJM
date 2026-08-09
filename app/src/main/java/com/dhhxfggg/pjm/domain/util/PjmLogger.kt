package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
 * Provides persistent logging to the device's internal storage using a non-blocking Channel.
 */
object PjmLogger {
    private const val LOG_TAG = "PjmLogger"
    private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024L // 10MB
    private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var internalLogFile: File? = null
    private val logChannel = Channel<String>(capacity = Channel.BUFFERED)
    
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
        
        startLogConsumer()
        
        i(LOG_TAG, "==================== PJM ENGINE STARTED ====================")
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("CRASH", "Uncaught exception on thread ${thread.name}", throwable)
            dumpLogsToCrashFile(context, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Dumps the recent logs and stack trace to a .crash file.
     */
    fun dumpLogsToCrashFile(context: Context, tr: Throwable) {
        try {
            val crashDir = File(context.filesDir, "crashes").apply { if (!exists()) mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val crashFile = File(crashDir, "crash_$timestamp.crash")
            
            FileOutputStream(crashFile).use { fos ->
                val writer = fos.bufferedWriter()
                writer.write("CRASH REPORT\n")
                writer.write("Timestamp: $timestamp\n")
                writer.write("Stack Trace:\n${getStackTrace(tr)}\n\n")
                writer.write("Recent Logs:\n")
                internalLogFile?.let { logFile ->
                    if (logFile.exists()) {
                        // Read last 100KB of logs
                        val length = logFile.length()
                        val start = maxOf(0L, length - 100 * 1024)
                        logFile.inputStream().use { input ->
                            input.skip(start)
                            input.bufferedReader().use { logReader ->
                                logReader.forEachLine { line ->
                                    writer.write(line)
                                    writer.write("\n")
                                }
                            }
                        }
                    }
                }
                writer.flush()
            }
            Log.i(LOG_TAG, "Crash dump saved to ${crashFile.name}")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to dump crash logs", e)
        }
    }

    private fun startLogConsumer() {
        loggerScope.launch {
            for (logLine in logChannel) {
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
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        enqueueLog("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        enqueueLog("I", tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        val fullMsg = if (tr != null) "$msg\n${getStackTrace(tr)}" else msg
        enqueueLog("W", tag, fullMsg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        val fullMsg = if (tr != null) "$msg\n${getStackTrace(tr)}" else msg
        enqueueLog("E", tag, fullMsg)
    }

    private fun enqueueLog(level: String, tag: String, msg: String) {
        val formatter = logDateFormatter.get()
        val timestamp = formatter?.format(Date()) ?: "UNKNOWN"
        val logLine = "[$timestamp] $level/$tag: $msg\n"
        logChannel.trySend(logLine)
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
