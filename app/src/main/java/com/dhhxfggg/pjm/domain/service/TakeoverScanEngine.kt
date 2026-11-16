package com.dhhxfggg.pjm.domain.service

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.edit
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.db.ManagedAppDao
import com.dhhxfggg.pjm.data.db.TakeoverJobDao
import com.dhhxfggg.pjm.data.model.ManagedAppEntity
import com.dhhxfggg.pjm.data.model.TakeoverJobEntity
import com.dhhxfggg.pjm.data.model.TakeoverStatus
import com.dhhxfggg.pjm.domain.util.IngestionEngine
import com.dhhxfggg.pjm.domain.util.PjmLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用接管的扫描引擎（无状态单例，所有进度落 SharedPreferences）。
 *
 * 抽出来的原因：现在有**两条**执行路径都要跑同一套扫描 ——
 * 1. 常驻前台服务（App 在前台/未被冻结时，秒级响应）；
 * 2. JobScheduler 兜底任务（ROM 冻结后台进程时，15 分钟一次的唤醒机会）。
 *
 * 若把扫描逻辑留在 Service 里，兜底任务只能「尝试拉起 Service」——
 * 而国产 ROM 从后台拉起前台服务本身就可能被拒，等于兜底失效。现在兜底任务
 * 直接在自己进程里跑这套扫描，不依赖前台服务是否允许启动。
 */
object TakeoverScanEngine {
    private const val TAG = "TakeoverScan"

    // 核心修复：互斥必须在 Engine（object 单例）层，而不是前台 Service 的实例字段。
    // 两条执行路径 —— TakeoverService 的常驻观察者与 TakeoverJobService 的 JobScheduler 兜底 ——
    // 同处主进程却各自持有 Service 实例；旧实现只在 Service 内加 Mutex，兜底任务完全不共享，
    // 并发时双方读到同一水位、各自导入同一文件（两个 UUID 副本）并争抢删原文件。
    private val scanMutex = kotlinx.coroutines.sync.Mutex()

    const val PREFS_NAME = "pjm_takeover_state"
    const val KEY_LAST_ID = "last_seen_id"
    const val KEY_LAST_SWEEP = "last_sweep_ms"

    private const val SCAN_LIMIT = 50
    private const val SETTLE_MS = 2000L
    private const val PRUNE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    /** 幂等键前缀：`file://` + 绝对路径，与历史目录深扫写入的 `Uri.fromFile(file).toString()` 完全一致 */
    private const val FILE_KEY_PREFIX = "file://"

    /** PENDING 超过这个时长视为进程被杀的残留，允许重新入库 */
    private const val STALE_PENDING_MS = 10L * 60 * 1000

    /**
     * 可以被 7 天清理的收尾状态。
     *
     * 包含两种失败态：它们要留给用户看/重试，但也没必要永久占表
     * （旧实现只清 IMPORTED，失败记录只增不减）。
     */
    private val prunableStatuses =
        listOf(TakeoverStatus.IMPORTED, TakeoverStatus.IMPORT_FAILED, TakeoverStatus.DELETE_FAILED)

    /**
     * 跑一轮扫描。
     *
     * @param doSweep 是否同时扫「应用自己的保存目录」。
     *   媒体库通道可以高频（相册一有变化就回调），目录深扫只在定时兜底轮做 ——
     *   否则聊天软件、截图这类上万文件的目录会把存储 IO 拖满、整机变卡。
     * @return 本轮真正处理掉（入库 + 删除原文件）的数量。服务用它在「什么都没扫到」时
     *   把定时兜底的间隔逐步拉长 —— 空闲时没必要每 3 分钟唤醒一次存储。
     */
    suspend fun scanOnce(
        context: Context,
        managedAppDao: ManagedAppDao,
        takeoverJobDao: TakeoverJobDao,
        fileDao: FileDao,
        doSweep: Boolean,
    ): Int =
        scanMutex.withLock {
            doScanOnce(context, managedAppDao, takeoverJobDao, fileDao, doSweep)
        }

    /** 真正的一轮扫描；只能由 [scanOnce] 在互斥下调用。 */
    private suspend fun doScanOnce(
        context: Context,
        managedAppDao: ManagedAppDao,
        takeoverJobDao: TakeoverJobDao,
        fileDao: FileDao,
        doSweep: Boolean,
    ): Int {
        val managed = managedAppDao.getAll()
        if (managed.isEmpty()) return 0
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val packageAddedAtSec = managed.associate { it.packageName to it.addedAt / 1000 }

        // 水位线用 MediaStore 的 _id，而不是 date_added：
        // date_added 是「写入媒体库的时间」，系统/厂商可能延迟索引、甚至写 NULL；
        // 用时间做水位会把这期间保存的文件永久跳过。
        val storedId = prefs.getLong(KEY_LAST_ID, 0L)
        var lastId = storedId
        if (storedId <= 0L) {
            // 首次启用：水位定在当前最大 id，不做历史回填。
            // 核心修复：读失败时 **不写** 水位。旧实现把失败的 0 直接落盘，下一轮就从 0 开始，
            // 等于把用户整个相册的历史照片回扫一遍（全部搬进库）。读失败保持缺省，下轮重试。
            val maxId = currentMaxMediaId(context)
            if (maxId == null) {
                PjmLogger.w(TAG, "读取 MediaStore 最大 id 失败：本轮不初始化水位（下轮重试），不写 0")
                return if (doSweep) {
                    sweepManagedAppMediaDirs(context, managed, takeoverJobDao, fileDao, prefs)
                } else {
                    0
                }
            }
            lastId = maxId
            prefs.edit { putLong(KEY_LAST_ID, lastId) }
            PjmLogger.i(TAG, "接管水位初始化: lastId=" + lastId)
        }

        val found =
            queryNewMedia(context, lastId)
                ?: run {
                    PjmLogger.w(TAG, "MediaStore 查询失败，水位线保持不变")
                    TakeoverNotifications.notifyQueryFailure(context)
                    // 查询失败也要把目录深扫跑完，并如实回报它处理了几个
                    return if (doSweep) sweepManagedAppMediaDirs(context, managed, takeoverJobDao, fileDao, prefs) else 0
                }

        if (found.isNotEmpty()) PjmLogger.i(TAG, "媒体库新增 " + found.size + " 行")
        val candidates = if (found.size > SCAN_LIMIT) found.take(SCAN_LIMIT) else found
        var newWatermark = lastId
        val failedDeletes = mutableListOf<Uri>()
        var handled = 0
        for (item in candidates) {
            newWatermark = maxOf(newWatermark, item.id)
            val addedAtSec = packageAddedAtSec[item.owner] ?: continue
            // date_added 缺失时退回文件 mtime；两者都拿不到就按「刚插入」处理
            val effectiveSec =
                if (item.dateAdded > 0L) {
                    item.dateAdded
                } else {
                    item.dataPath?.let { File(it).lastModified() / 1000 } ?: (System.currentTimeMillis() / 1000)
                }
            if (effectiveSec + 2 < addedAtSec) continue
            // 幂等键统一成绝对路径（见 canonicalKey / idempotencyKeys），与目录深扫共用同一套键
            val keys = idempotencyKeys(item)
            val key = keys.first()
            val existing = takeoverJobDao.findByAnyUri(keys)
            if (existing != null) {
                if (existing.status == TakeoverStatus.PENDING) {
                    // 核心修复：PENDING 写入后进程被杀（或 onStopJob 打断）会永久残留。
                    // 旧实现下一轮直接改判 IMPORT_FAILED，之后所有轮次一直 continue ——
                    // 原文件既不再入库也不会被删，且这是一条误报的失败。
                    // 超过阈值视为崩溃残留：删掉记录，让它重新走完整入库流程。
                    if (System.currentTimeMillis() - existing.createdAt > STALE_PENDING_MS) {
                        takeoverJobDao.deleteByUri(existing.uri)
                        PjmLogger.i(TAG, "清理崩溃残留的 PENDING 记录，重新入库: " + existing.uri)
                    } else {
                        continue
                    }
                } else {
                    continue
                }
            }
            takeoverJobDao.upsert(TakeoverJobEntity(uri = key, status = TakeoverStatus.PENDING))
            if (!importOne(context, fileDao, item.uri)) {
                takeoverJobDao.updateStatus(key, TakeoverStatus.IMPORT_FAILED)
                PjmLogger.w(TAG, "入库失败，保留原文件: " + item.uri)
                continue
            }
            if (deleteOrTrash(context, item.uri)) {
                takeoverJobDao.updateStatus(key, TakeoverStatus.IMPORTED)
            } else {
                takeoverJobDao.updateStatus(key, TakeoverStatus.DELETE_FAILED)
                failedDeletes.add(item.uri)
            }
            handled++
        }
        prefs.edit { putLong(KEY_LAST_ID, newWatermark) }
        if (found.size > SCAN_LIMIT) {
            // 还有剩余：由服务侧再排一轮（兜底任务一次跑完为止也行，这里只推进水位）
            PjmLogger.i(TAG, "本轮命中上限，剩余文件留待下一轮")
        }

        if (failedDeletes.isNotEmpty()) TakeoverNotifications.notifyDeleteFailures(context, failedDeletes)
        if (handled > 0) {
            PjmLogger.i(TAG, "接管完成：处理 " + handled + " 个文件，删除失败 " + failedDeletes.size + " 个")
            runCatching {
                takeoverJobDao.pruneFinished(
                    finishedStatuses = prunableStatuses,
                    cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS,
                )
            }
        }

        return handled + (if (doSweep) sweepManagedAppMediaDirs(context, managed, takeoverJobDao, fileDao, prefs) else 0)
    }

    /**
     * 当前 MediaStore 表里的最大 _id（首次启用时用于定位水位，不做历史回填）。
     *
     * 读失败返回 null —— 调用方据此放弃本轮水位初始化，绝不把 0 当水位落盘。
     */
    private fun currentMaxMediaId(context: Context): Long? {
        val collection = mediaFilesCollection()
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val sortOrder = MediaStore.MediaColumns._ID + " DESC"
        return try {
            val cursor = context.contentResolver.query(collection, projection, null, null, sortOrder)
            if (cursor == null) {
                PjmLogger.w(TAG, "读取 MediaStore 最大 id 返回空游标")
                null
            } else {
                cursor.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            }
        } catch (e: Exception) {
            PjmLogger.w(TAG, "读取 MediaStore 最大 id 失败: " + e.message)
            null
        }
    }

    /**
     * 媒体库文件集合。
     *
     * `MediaStore.VOLUME_EXTERNAL` 是 API 29 才引入的卷名常量，minSdk 24 上必须退回
     * 旧的 `"external"` 字面量，避免旧系统上取到无效集合。
     */
    private fun mediaFilesCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

    private suspend fun sweepManagedAppMediaDirs(
        context: Context,
        managed: List<ManagedAppEntity>,
        takeoverJobDao: TakeoverJobDao,
        fileDao: FileDao,
        prefs: android.content.SharedPreferences,
    ): Int {
        val storageRoot = Environment.getExternalStorageDirectory() ?: return 0
        val now = System.currentTimeMillis()
        // 全局水位只用来「跳过没变化的目录」；真正的「哪些文件算新」要按每个应用各自的加入时间算，
        // 不能取所有应用的最大值 —— 新加进来的应用会把老应用的时间线一起抬高，漏掉老应用的保存。
        val cutoff = lastSweepMs(prefs)
        var handled = 0
        val failedDeletes = mutableListOf<Uri>()
        val sharedRoots = listOf("Pictures", "DCIM", "Movies", "Download")
        val rootChildren =
            sharedRoots.associateWith { root ->
                File(storageRoot, root)
                    .takeIf { it.isDirectory }
                    ?.listFiles()
                    ?.filter { it.isDirectory }
                    .orEmpty()
            }
        for (app in managed) {
            val perAppCutoff = maxOf(cutoff, app.addedAt)
            for (dir in appSaveDirectories(storageRoot, app, rootChildren)) {
                if (!dir.isDirectory) continue
                if (!dirHasRecentChanges(dir, perAppCutoff)) continue
                // 只在目录真的有变化时记一条：出问题时能直接看出这轮到底检查了哪些目录
                PjmLogger.i(TAG, "目录有变化: " + dir.absolutePath)
                sweepDirectory(context, dir, perAppCutoff, now, takeoverJobDao, fileDao, failedDeletes) { handled++ }
            }
        }
        prefs.edit { putLong(KEY_LAST_SWEEP, now) }
        if (failedDeletes.isNotEmpty()) TakeoverNotifications.notifyDeleteFailures(context, failedDeletes)
        if (handled > 0) PjmLogger.i(TAG, "目录接管完成：处理 " + handled + " 个文件，删除失败 " + failedDeletes.size + " 个")
        return handled
    }

    private fun lastSweepMs(prefs: android.content.SharedPreferences): Long = prefs.getLong(KEY_LAST_SWEEP, 0L)

    private fun appSaveDirectories(
        storageRoot: File,
        app: ManagedAppEntity,
        rootChildren: Map<String, List<File>>,
    ): List<File> {
        val result = mutableListOf(File(storageRoot, "Android/media/" + app.packageName))
        val genericTokens = setOf("com", "org", "net", "android", "app", "apps", "web", "mobile", "client")
        val label = app.displayName.trim()
        val labels =
            buildList {
                if (label.isNotEmpty()) add(label)
                app.packageName
                    .split('.')
                    .filter { it.length >= 4 && it.lowercase() !in genericTokens }
                    .forEach { add(it) }
            }.distinctBy { it.lowercase() }
        if (labels.isEmpty()) return result
        for ((_, children) in rootChildren) {
            if (children.isEmpty()) continue
            children.forEach { child ->
                val matched =
                    labels.any { label ->
                        child.name.equals(label, ignoreCase = true) ||
                            child.name.startsWith(label + "-", ignoreCase = true) ||
                            child.name.startsWith(label + " ", ignoreCase = true)
                    }
                if (matched) result.add(child)
            }
        }
        return result.distinctBy { it.absolutePath }
    }

    private fun dirHasRecentChanges(
        dir: File,
        cutoff: Long,
        depth: Int = 0,
    ): Boolean {
        if (dir.lastModified() > cutoff) return true
        if (depth >= 2) return false
        val children = dir.listFiles() ?: return false
        return children.any { it.isDirectory && dirHasRecentChanges(it, cutoff, depth + 1) }
    }

    private suspend fun sweepDirectory(
        context: Context,
        dir: File,
        cutoff: Long,
        now: Long,
        takeoverJobDao: TakeoverJobDao,
        fileDao: FileDao,
        failedDeletes: MutableList<Uri>,
        onHandled: () -> Unit,
    ) {
        dir
            .walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.length() > 0 &&
                    file.lastModified() > cutoff &&
                    file.lastModified() < now - SETTLE_MS &&
                    !file.name.startsWith(".") &&
                    !file.name.endsWith(".tmp") &&
                    !file.name.endsWith(".pending")
            }.forEach { file ->
                val uri = Uri.fromFile(file)
                // 幂等键与媒体库通道共用（见 [canonicalKey]）：同一文件两条通道只留一行记录
                val key = canonicalKey(uri, file.absolutePath)
                if (takeoverJobDao.findByUri(key) != null) return@forEach
                takeoverJobDao.upsert(TakeoverJobEntity(uri = key, status = TakeoverStatus.PENDING))
                if (!importOne(context, fileDao, uri)) {
                    takeoverJobDao.updateStatus(key, TakeoverStatus.IMPORT_FAILED)
                    PjmLogger.w(TAG, "目录接管入库失败，保留原文件: " + file.absolutePath)
                    return@forEach
                }
                if (file.delete()) {
                    takeoverJobDao.updateStatus(key, TakeoverStatus.IMPORTED)
                    onHandled()
                } else {
                    takeoverJobDao.updateStatus(key, TakeoverStatus.DELETE_FAILED)
                    failedDeletes.add(uri)
                }
            }
    }

    /**
     * 幂等键：同一个文件可能来自两条通道 —— 媒体库给 `content://media/...`，
     * 目录深扫给 `file:///storage/...`。旧实现两处各写各的键，删除失败时同一文件
     * 会被另一条通道当成新文件再入库一份（两个 UUID 副本）。
     *
     * 统一成「绝对路径」：能拿到真实路径就用路径键（与历史目录深扫写入的键完全一致），
     * 拿不到（媒体行没有 DATA）才退回 content uri。
     */
    private fun canonicalKey(
        uri: Uri,
        dataPath: String?,
    ): String {
        val path = dataPath ?: if (uri.scheme == "file") uri.path else null
        return if (!path.isNullOrBlank()) FILE_KEY_PREFIX + File(path).absolutePath else uri.toString()
    }

    /**
     * 媒体库候选行的查询键：路径键优先，同时带上 content uri 键 ——
     * 历史记录可能只存了 content uri，两个都查才不会重复入库。
     */
    private fun idempotencyKeys(item: MediaCandidate): List<String> {
        val canonical = canonicalKey(item.uri, item.dataPath)
        val raw = item.uri.toString()
        return if (canonical == raw) listOf(canonical) else listOf(canonical, raw)
    }

    private data class MediaCandidate(
        val id: Long,
        val uri: Uri,
        val owner: String,
        val dateAdded: Long,
        val dataPath: String?,
    )

    private fun queryNewMedia(
        context: Context,
        lastId: Long,
    ): List<MediaCandidate>? {
        // API 28 及以下没有 OWNER_PACKAGE_NAME：媒体库通道无法把文件归属到具体应用。
        // 这类系统上整体跳过媒体库通道（目录深扫仍按应用目录/label 捡文件）——
        // 若改成「跳过 owner 过滤」，用户自己拍的照片也会被搬进库，比漏扫更糟。
        // 返回空列表而不是 null：避免每轮都发一条「接管失效」通知。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            PjmLogger.d(TAG, "系统低于 Android 10（无 OWNER_PACKAGE_NAME），跳过媒体库通道，仅目录深扫")
            return emptyList()
        }
        val collection = mediaFilesCollection()
        val projection =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATA,
            )
        val selection = MediaStore.MediaColumns._ID + " > ?"
        val selectionArgs = arrayOf(lastId.toString())
        val sortOrder = MediaStore.MediaColumns._ID + " ASC"
        // 这里**只能**用普通查询。Bundle 的 QUERY_ARG_*（LIMIT）在部分机型上会静默返回
        // 空游标：不抛异常、也走不到回退分支，结果就是"相册里有 4 张新图，媒体库通道一次都没看到"，
        // 只能等 3 分钟一次的目录深扫兜底 —— 用户看到的就是"只有打开 App 才动"。
        // 普通查询本身不需要 LIMIT：readCandidates 收满 SCAN_LIMIT 就 break。
        return try {
            readCandidates(
                context = context,
                collection = collection,
                projection = projection,
                selection = selection,
                selectionArgs = selectionArgs,
                sortOrder = sortOrder,
            )
        } catch (e: Exception) {
            PjmLogger.w(TAG, "查询 MediaStore 失败: " + e.message)
            null
        }
    }

    private fun readCandidates(
        context: Context,
        collection: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = null,
    ): List<MediaCandidate> {
        val result = mutableListOf<MediaCandidate>()
        // 只用普通查询：Bundle 的 QUERY_ARG_* 重载是 API 26+，本文件 minSdk 24 ——
        // 低版本机型上连方法都找不到，高版本机型上又会静默返回空游标（见上面注释）。
        val cursor = context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
        cursor?.use { c ->
            val idColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val ownerColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val dateColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val dataColumn = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            while (c.moveToNext()) {
                val owner = c.getString(ownerColumn) ?: continue
                result.add(
                    MediaCandidate(
                        id = c.getLong(idColumn),
                        uri = ContentUris.withAppendedId(collection, c.getLong(idColumn)),
                        owner = owner,
                        dateAdded = if (c.isNull(dateColumn)) 0L else c.getLong(dateColumn),
                        dataPath = if (dataColumn >= 0 && !c.isNull(dataColumn)) c.getString(dataColumn) else null,
                    ),
                )
                if (result.size > SCAN_LIMIT) break
            }
        }
        return result
    }

    private suspend fun importOne(
        context: Context,
        fileDao: FileDao,
        uri: Uri,
    ): Boolean =
        runCatching {
            val summary =
                IngestionEngine.store(
                    context = context,
                    uris = listOf(uri),
                    fileDao = fileDao,
                )
            summary.imported > 0
        }.getOrElse { e ->
            PjmLogger.e(TAG, "接管入库异常: " + uri, e as? Exception)
            false
        }

    private suspend fun deleteOrTrash(
        context: Context,
        uri: Uri,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (context.contentResolver.delete(uri, null, null) > 0) return@withContext true
            } catch (e: Exception) {
                PjmLogger.w(TAG, "直接删除失败，尝试移入回收站: " + uri + " (" + e.message + ")")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
                    if (context.contentResolver.update(uri, values, null, null) > 0) return@withContext true
                } catch (e: Exception) {
                    PjmLogger.w(TAG, "移入回收站失败: " + uri + " (" + e.message + ")")
                }
            }
            false
        }
}
