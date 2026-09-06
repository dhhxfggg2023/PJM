package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * PJM High-Fidelity Diagnostic Logger.
 * Features:
 * 1. Synchronous hard-sync to disk for CRITICAL logs.
 * 2. Automatic system context capture (Memory, Storage, Threading).
 * 3. Separate error log file for persistent issue tracking.
 * 4. Trace ID support for end-to-end task debugging.
 */
object PjmLogger {
    private const val LOG_TAG = "PjmLogger"
    private var businessLogFile: File? = null
    private var errorLogFile: File? = null

    private val logDateFormatter =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }

    /**
     * Initializes the diagnostic engine.
     */
    fun init(context: Context) {
        val logDir = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
        businessLogFile = File(logDir, "pjm_business.log")
        errorLogFile = File(logDir, "pjm_error.log")

        i(LOG_TAG, "==================== DIAGNOSTIC ENGINE INITIALIZED ====================")
        i(LOG_TAG, "Model: ${android.os.Build.MODEL}, OS: ${android.os.Build.VERSION.RELEASE}, API: ${android.os.Build.VERSION.SDK_INT}")
    }

    /**
     * Generates a new Trace ID for a specific task workflow.
     */
    fun generateTraceId(): String =
        UUID
            .randomUUID()
            .toString()
            .substring(0, 8)
            .uppercase()

    @Synchronized
    private fun log(
        level: String,
        tag: String,
        msg: String,
        tr: Throwable? = null,
        traceId: String? = null,
    ) {
        val timestamp = logDateFormatter.get()?.format(Date()) ?: "UNKNOWN"
        val threadName = Thread.currentThread().name
        val tId = if (traceId != null) " [$traceId]" else ""

        val fullMsg = if (tr != null) "$msg\n${getStackTrace(tr)}" else msg
        val logLine = "[$timestamp] [$level] [$threadName]$tId $tag: $fullMsg\n"

        // 1. Android Logcat (for development)
        when (level) {
            "D" -> Log.d(tag, fullMsg)
            "I" -> Log.i(tag, fullMsg)
            "W" -> Log.w(tag, fullMsg)
            "E" -> Log.e(tag, fullMsg)
        }

        // 2. Persist to Business Log
        writeToDisk(businessLogFile, logLine, isCritical = (level == "E" || level == "W"))

        // 3. Persist to Error Log with System Context if it's an error
        if (level == "E") {
            val contextInfo = captureSystemContext()
            val errorPayload = "--- ERROR CONTEXT SNAPSHOT ---\n$contextInfo\n$logLine\n"
            writeToDisk(errorLogFile, errorPayload, isCritical = true)
        }
    }

    private fun writeToDisk(
        file: File?,
        content: String,
        isCritical: Boolean,
    ) {
        val target = file ?: return
        try {
            // Rotation: Move to .old if too large (10MB limit for business, 20MB for error)
            val limit = if (target.name.contains("error")) 20 * 1024 * 1024L else 10 * 1024 * 1024L
            if (target.exists() && target.length() > limit) {
                target.renameTo(File(target.parent, "${target.name}.old"))
            }

            FileOutputStream(target, true).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                if (isCritical) {
                    fos.flush()
                    fos.fd.sync() // Hard-sync to disk
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to write log to disk: ${e.message}")
        }
    }

    private fun captureSystemContext(): String {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMem = runtime.maxMemory() / 1024 / 1024
        return "Memory: ${usedMem}MB / ${maxMem}MB"
    }

    fun d(
        tag: String,
        msg: String,
        traceId: String? = null,
    ) = log("D", tag, msg, traceId = traceId)

    fun i(
        tag: String,
        msg: String,
        traceId: String? = null,
    ) = log("I", tag, msg, traceId = traceId)

    fun w(
        tag: String,
        msg: String,
        tr: Throwable? = null,
        traceId: String? = null,
    ) = log("W", tag, msg, tr, traceId)

    fun e(
        tag: String,
        msg: String,
        tr: Throwable? = null,
        traceId: String? = null,
    ) = log("E", tag, msg, tr, traceId)

    /**
     * Enhanced Error Logger with code support.
     */
    fun e(
        tag: String,
        code: Int,
        msg: String,
        tr: Throwable? = null,
        traceId: String? = null,
    ) = log("E", tag, "[Code $code] $msg", tr, traceId)

    private fun getStackTrace(tr: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        tr.printStackTrace(pw)
        return sw.toString()
    }

    fun getLogFile(): File? = businessLogFile

    fun getErrorLogFile(): File? = errorLogFile

    fun clear() {
        businessLogFile?.delete()
        errorLogFile?.delete()
        businessLogFile?.parentFile?.listFiles()?.forEach { if (it.name.endsWith(".old")) it.delete() }
    }
}
