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

    // 日志异步落盘：I 级进队列由单独线程批量写，W/E 仍然同步 hard-sync（崩溃也要留下）。
    // 之前每条日志都 open/write/close 一次文件，扫描/导入期间会把 IO 和主线程都拖慢。
    private val pendingLines = java.util.concurrent.ConcurrentLinkedQueue<String>()

    @Volatile
    private var writerStarted = false

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
        startLogWriter()

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
        // 核心修复：日志注入防护。
        // tag / 消息体里可能包含攻击者可控的文件名（分享进来的 DISPLAY_NAME、共享目录里的
        // 文件名），其中的换行会把一条日志伪装成多条、伪造出虚假记录。落盘/输出前统一折叠换行。
        val safeTag = tag.replace('\n', ' ').replace('\r', ' ')
        val tId = if (traceId != null) " [${traceId.replace('\n', ' ').replace('\r', ' ')}]" else ""

        val fullMsg = if (tr != null) "${sanitize(msg)}\n${getStackTrace(tr)}" else sanitize(msg)
        val logLine = "[$timestamp] [$level] [$threadName]$tId $safeTag: $fullMsg\n"

        // 1. Android Logcat (for development)
        // 本地 JVM 单测里 android.util.Log 是没有实现的桩，直接调用会抛
        // “not mocked”；日志失败不能反过来让调用方（如 BookLoader 的损坏文件分支）崩掉。
        runCatching {
            when (level) {
                "D" -> Log.d(safeTag, fullMsg)
                "I" -> Log.i(safeTag, fullMsg)
                "W" -> Log.w(safeTag, fullMsg)
                "E" -> Log.e(safeTag, fullMsg)
            }
        }

        // 2. 持久化：W/E 立即 hard-sync；I 交给后台线程批量写；D 只进 logcat（扫描心跳这类高频日志不落盘）
        when (level) {
            "W", "E" -> writeToDisk(businessLogFile, logLine, isCritical = true)
            "I" -> pendingLines.add(logLine)
            else -> Unit
        }

        // 3. Persist to Error Log with System Context if it's an error
        if (level == "E") {
            val contextInfo = captureSystemContext()
            val errorPayload = "--- ERROR CONTEXT SNAPSHOT ---\n$contextInfo\n$logLine\n"
            writeToDisk(errorLogFile, errorPayload, isCritical = true)
        }
    }

    /**
     * 安装全局未捕获异常处理器。
     * 崩溃时把完整堆栈写入 error 日志（落盘 hard-sync），再交回系统默认处理（不影响崩溃报告/重启）。
     * 配合“导出诊断日志”功能，可以在崩溃后从用户处拿到一线堆栈。
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e(
                    "CrashHandler",
                    "UNCAUGHT EXCEPTION on thread [${thread.name}]: ${throwable.javaClass.name}: ${throwable.message}",
                    throwable,
                )
            } catch (_: Throwable) {
                // 记录失败绝不能影响后续系统处理
            }
            // 交回原有处理器（保留系统默认崩溃行为）
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 折叠消息体中的换行/回车，防止攻击者可控的文件名伪造日志行（log injection）。
     * 堆栈由 [getStackTrace] 单独生成，已经过多行格式化，因此只清洗调用方传入的 msg。
     */
    private fun sanitize(message: String): String = message.replace('\n', ' ').replace('\r', ' ')

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

    private fun startLogWriter() {
        if (writerStarted) return
        writerStarted = true
        Thread(
            {
                while (true) {
                    try {
                        val first = pendingLines.poll()
                        if (first == null) {
                            Thread.sleep(80)
                            continue
                        }
                        val batch = StringBuilder(first)
                        var budget = 64 * 1024
                        while (budget > 0) {
                            val next = pendingLines.poll() ?: break
                            batch.append(next)
                            budget -= next.length
                        }
                        writeToDisk(businessLogFile, batch.toString(), isCritical = false)
                    } catch (_: InterruptedException) {
                        return@Thread
                    } catch (_: Throwable) {
                        // 写日志失败不能影响主流程
                    }
                }
            },
            "pjm-log-writer",
        ).apply {
            isDaemon = true
            start()
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
        pendingLines.clear()
        businessLogFile?.delete()
        errorLogFile?.delete()
        businessLogFile?.parentFile?.listFiles()?.forEach { if (it.name.endsWith(".old")) it.delete() }
    }
}
