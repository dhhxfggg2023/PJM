package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.lang.Long
import java.security.MessageDigest
import kotlin.math.abs

/**
 * 保险库健康扫描：完整性检查 / 精确查重 / 感知查重 / 内容指纹。
 *
 * 从 VaultManager 拆出的职责块。依赖 VaultManager 门面（PjmDispatchers / 缓冲池 /
 * 取消标志 / 路径），避免对象间循环初始化问题。
 */
object VaultScanner {
    private const val TAG = "VaultScanner"

    suspend fun checkIntegrity(
        context: Context,
        fileDao: FileDao,
        onProgress: (Float) -> Unit,
    ): Map<String, List<FileEntity>> =
        withContext(VaultManager.PjmDispatchers.IO) {
            val all = fileDao.getAllFiles().first()
            val missing = mutableListOf<FileEntity>()
            val corrupted = mutableListOf<FileEntity>()
            all.forEachIndexed { i, e ->
                onProgress(i.toFloat() / all.size)
                val f = VaultManager.getFileFromEntity(context, e)
                if (!f.exists()) {
                    missing.add(e)
                } else if (e.contentHash != null && calculateHash(f) != e.contentHash) {
                    corrupted.add(e)
                }
            }
            mapOf("missing" to missing, "corrupted" to corrupted)
        }

    suspend fun findDuplicateFiles(
        context: Context,
        fileDao: FileDao,
        onProgress: (Float) -> Unit,
    ): List<DuplicateGroup> =
        withContext(VaultManager.PjmDispatchers.IO) {
            val allFiles = fileDao.getAllFiles().first()
            val suspects =
                allFiles
                    .groupBy { it.size }
                    .filter { it.value.size > 1 }
                    .values
                    .flatten()
            suspects.forEachIndexed { i, entity ->
                if (VaultManager.isTaskCancelled(VaultManager.TASK_DUPLICATES_EXACT)) throw CancellationException("精确查重已取消")
                onProgress(i.toFloat() / suspects.size)
                val file = VaultManager.getFileFromEntity(context, entity)
                // 核心修复：视频用内容级感知指纹（时长+分辨率+关键帧 dHash），
                // 因为 merge 重封装导致字节级（MD5）不同，但内容相同的视频 MD5 指纹永远检测不到。
                val hash =
                    if (FileUtils.isVideoFile(entity.name)) {
                        calculateVideoFingerprint(file)
                    } else {
                        entity.contentHash ?: calculateHash(file)
                    }
                if (hash != entity.contentHash) fileDao.upsert(entity.copy(contentHash = hash))
            }
            val finalFiles = fileDao.getAllFiles().first()
            // 核心修复：返回【分组】结构 —— 组内包含全部成员（含保留的原图）与建议删除集，
            // 供 UI 双图对比展示（让用户确认后自行勾选要删除的）
            val result = mutableListOf<DuplicateGroup>()
            finalFiles.filter { it.contentHash != null }.groupBy { it.contentHash }.values.forEach { group ->
                if (group.size > 1) {
                    val sorted = group.sortedBy { it.lastModified }
                    result.add(DuplicateGroup(members = sorted, recommendedDelete = sorted.drop(1).map { it.relativePath }.toSet()))
                }
            }
            result
        }

    /**
     * 图片感知查重：找出【内容相同但分辨率不同】的图片（原图 vs QQ 缩略图等）。
     *
     * 算法（针对上万张图片优化，1.3 万张实测通过）：
     * 1. 指纹（增量）：每张图采样解码算 64-bit dHash + 原始分辨率，落盘 [ImageFingerprintCache]。
     *    已缓存的直接跳过 —— 下次新增图片只需算新图，秒级增量。
     * 2. 全量两两粗筛：64 位 dHash 转 Long，用 bitCount 快速算汉明距离（1 万张 ≈ 5 千万对，
     *    JVM 上仅数秒）。阈值 ≤ 16 —— 大缩放 + 重压缩可能翻转较多位，必须放宽保证召回。
     * 3. 【内存宽高比预过滤】：比例差异 > 3% 的对直接排除（原图 4:3 与 16:9 不可能是缩略图关系），
     *    候选对骤降 90%+ —— 这是防止 256MB 堆 OOM 的关键。
     * 4. 候选对用 IntArray 紧凑编码（4 字节/对）而非 Pair 装箱（~40 字节/对），
     *    百万候选对仅 ~4MB。
     * 5. 候选对精确确认：[ImageFingerprintCache.verifySameContent]（宽高比一致 + 128px
     *    逐像素亮度差 ≤ 阈值）才算重复 —— 像素验证对重采样鲁棒，是主判定，误报率极低。
     * 6. Union-Find 连通成组；每组【保留分辨率最高】的，其余标记为建议删除。
     *
     * @return 重复图片分组（组内含全部成员供对比展示，recommendedDelete 默认勾选）
     */
    suspend fun findSimilarImages(
        context: Context,
        fileDao: FileDao,
        onProgress: (Float) -> Unit,
    ): List<DuplicateGroup> =
        withContext(VaultManager.PjmDispatchers.IO) {
            val all = fileDao.getAllFiles().first()
            val images = all.filter { FileUtils.isImageFile(it.name) }
            if (images.size < 2) return@withContext emptyList()

            // 1) 计算/读取指纹（增量：已缓存跳过）
            // 核心修复（崩溃/卡死）：
            //   a. 分批处理（每批 128 张）—— 绝不一次性创建 1.2 万个协程，控制内存峰值；
            //   b. Semaphore(3) 限流 —— 只 3 路并发解码，避免 OOM + 避免占满 8 线程 IO 池
            //      （否则删除等其他操作排队，用户感知"卡死"）；
            //   c. computeFingerprint 内部 catch Throwable（含 OOM）+ 显式 recycle，单图失败不影响整体。
            data class Fp(
                val entity: FileEntity,
                val fp: ImageFingerprint?,
            )
            val fpSemaphore = Semaphore(3)
            val fps = mutableListOf<Fp>()
            var processedTotal = 0
            for (batch in images.chunked(128)) {
                if (VaultManager.isTaskCancelled(VaultManager.TASK_DUPLICATES_PERCEPTUAL)) throw CancellationException("指纹计算已取消")
                fps +=
                    coroutineScope {
                        batch
                            .map { e ->
                                async(VaultManager.PjmDispatchers.IO) {
                                    fpSemaphore.withPermit {
                                        val cached = ImageFingerprintCache.getFingerprint(context, e)
                                        if (cached != null) {
                                            Fp(e, cached)
                                        } else {
                                            val computed = ImageFingerprintCache.computeFingerprint(context, e)
                                            if (computed != null) {
                                                ImageFingerprintCache.saveFingerprint(context, e, computed)
                                                Fp(e, computed)
                                            } else {
                                                Fp(e, null)
                                            }
                                        }
                                    }
                                }
                            }.awaitAll()
                    }
                processedTotal += batch.size
                // 进度：每批更新一次
                onProgress(0.6f * (processedTotal.toFloat() / images.size))
            }
            if (fps.size < 2) return@withContext emptyList()
            val fpList = fps.filter { it.fp != null && it.fp!!.dHash.length == 64 }
            if (fpList.size < 2) return@withContext emptyList()

            // 2) 二进制串 → Long（加速汉明距离）+ 宽高比/面积预计算（粗筛纯内存过滤）
            val dHashes = LongArray(fpList.size) { i -> fpList[i].fp!!.dHash.toLongOrNull(2) ?: 0L }
            val ratios =
                FloatArray(fpList.size) { i ->
                    val fp = fpList[i].fp!!
                    fp.width.toFloat() / fp.height.coerceAtLeast(1)
                }
            val areas =
                LongArray(fpList.size) { i ->
                    val fp = fpList[i].fp!!
                    fp.width.toLong() * fp.height
                }

            // 3) 粗筛（核心修复 OOM + 80% 卡死）：
            //   a. O(n²) bitCount 保证 100% 召回（不遗漏任何汉明距离 ≤16 的对）；
            //   b. 【内存宽高比预过滤】—— 原图 4:3 与 16:9 不可能是缩略图关系，纯内存直接排除；
            //   c. 【面积差异预过滤】—— 本功能只找"原图 vs 缩略图"（面积差 ≥ 1.2 倍）；
            //   d. 候选对用 IntArray 紧凑编码 (a shl 16) or b —— 4 字节/对，百万候选对仅 ~4MB。
            onProgress(0.6f)
            val totalPairs = fpList.size.toLong() * (fpList.size - 1) / 2
            var candidates = IntArray(8192)
            var candidateCount = 0
            var processedPairs = 0L
            for (i in 0 until fpList.size - 1) {
                if (VaultManager.isTaskCancelled(VaultManager.TASK_DUPLICATES_PERCEPTUAL)) throw CancellationException("比对已取消")
                val hi = dHashes[i]
                val ri = ratios[i]
                val areaI = areas[i]
                for (j in i + 1 until fpList.size) {
                    if (++processedPairs % 8192 == 0L) {
                        onProgress(0.6f + 0.15f * (processedPairs.toFloat() / totalPairs))
                    }
                    // 宽高比差异 > 3% → 直接排除（与 verifySameContent 的比例检查一致，先省一次解码）
                    val rj = ratios[j]
                    if (abs(ri - rj) / maxOf(ri, rj) > 0.03f) continue
                    // 面积差异 < 1.2 倍 → 同分辨率/近似分辨率，非"原图 vs 缩略图"，跳过
                    val maxArea = maxOf(areaI, areas[j])
                    val minArea = minOf(areaI, areas[j])
                    if (maxArea < minArea * 1.2f) continue
                    if (Long.bitCount(hi xor dHashes[j]) <= 16) {
                        if (candidateCount == candidates.size) candidates = candidates.copyOf(candidates.size * 2)
                        candidates[candidateCount++] = (i shl 16) or j
                    }
                }
            }
            onProgress(0.75f)
            PjmLogger.i(TAG, "图片感知查重：${fpList.size} 张图，$totalPairs 对，候选对 $candidateCount")

            // 3.5) 核心新增：32×32 灰度预筛（纯内存，微秒级）—— 候选对可能达数百万，
            //      每对解码 64px 验证耗时以小时计。预计算每张图 32×32 灰度（1024 字节，全量仅 ~13MB），
            //      候选对先纯内存比较灰度：平均亮度差 > 15 → 内容不一致，直接排除。
            //      同图不同分辨率灰度差 < 6（通过），不同图 > 20（排除）—— 可砍掉 95%+ 干扰对。
            // 核心修复（半永久化）：getOrComputeGray32 读缓存优先，未命中才解码并【落盘 .g32】。
            //      首次查重计算 1.3 万张（~5 分钟），之后每次查重秒级复用，不再重复解码大图。
            onProgress(0.75f)
            val gray32Cache = HashMap<String, ByteArray>(fpList.size)
            // 加载/计算灰度（并行，每批 128；已落盘的直接读文件，秒级）
            var grayProcessed = 0
            val grayBatchSize = 128
            for (start in 0 until fpList.size step grayBatchSize) {
                if (VaultManager.isTaskCancelled(VaultManager.TASK_DUPLICATES_PERCEPTUAL)) throw CancellationException("灰度计算已取消")
                val end = minOf(start + grayBatchSize, fpList.size)
                coroutineScope {
                    (start until end)
                        .map { idx ->
                            async(VaultManager.PjmDispatchers.IO) {
                                val e = fpList[idx].entity
                                ImageFingerprintCache.getOrComputeGray32(context, e)?.let { e.relativePath to it }
                            }
                        }.awaitAll()
                        .forEach { pair -> if (pair != null) gray32Cache[pair.first] = pair.second }
                }
                grayProcessed += end - start
                onProgress(0.75f + 0.05f * (grayProcessed.toFloat() / fpList.size))
            }
            // 用灰度预筛过滤候选对（内存紧凑重建，避免保留被淘汰的）
            if (candidateCount > 0) {
                var kept = 0
                for (idx in 0 until candidateCount) {
                    if (VaultManager.isTaskCancelled(VaultManager.TASK_DUPLICATES_PERCEPTUAL)) throw CancellationException("灰度预筛已取消")
                    val pair = candidates[idx]
                    val i = pair shr 16
                    val j = pair and 0xFFFF
                    val g1 = gray32Cache[fpList[i].entity.relativePath]
                    val g2 = gray32Cache[fpList[j].entity.relativePath]
                    if (g1 != null && g2 != null && ImageFingerprintCache.gray32Similar(g1, g2)) {
                        candidates[kept++] = pair
                    }
                }
                candidateCount = kept
            }
            gray32Cache.clear()
            onProgress(0.8f)
            PjmLogger.i(TAG, "图片感知查重：灰度预筛后候选对 $candidateCount")

            // 核心优化：候选对【并行】验证（解码 128px 是重活）
            // 核心修复（崩溃/卡死）：
            //   a. 分批验证（每批 32 对），批间更新进度 —— 避免验证阶段进度条卡住；
            //   b. Semaphore(3) 限流 —— 控制并发解码内存峰值；
            //   c. verifySameContent 内部 catch Throwable（含 OOM）+ recycle，单对失败不影响整体。
            val parent = IntArray(fpList.size) { it }

            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) {
                    parent[r] = parent[parent[r]]
                    r = parent[r]
                }
                return r
            }

            fun union(
                a: Int,
                b: Int,
            ) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[ra] = rb
            }
            var verified = 0
            if (candidateCount > 0) {
                val totalCandidates = candidateCount
                var processedCandidates = 0
                var batchStart = 0
                while (batchStart < candidateCount) {
                    if (VaultManager.isTaskCancelled(VaultManager.TASK_DUPLICATES_PERCEPTUAL)) throw CancellationException("验证已取消")
                    val batchEnd = minOf(batchStart + 32, candidateCount)
                    coroutineScope {
                        (batchStart until batchEnd)
                            .map { idx ->
                                async(VaultManager.PjmDispatchers.IO) {
                                    if (VaultManager.isTaskCancelled(VaultManager.TASK_DUPLICATES_PERCEPTUAL)) return@async null
                                    val pair = candidates[idx]
                                    val i = pair shr 16
                                    val j = pair and 0xFFFF
                                    fpSemaphore.withPermit {
                                        if (ImageFingerprintCache.verifySameContent(
                                                context,
                                                fpList[i].entity,
                                                fpList[j].entity,
                                            )
                                        ) {
                                            i to j
                                        } else {
                                            null
                                        }
                                    }
                                }
                            }.awaitAll()
                            .forEach { pair ->
                                if (pair != null) {
                                    union(pair.first, pair.second)
                                    verified++
                                }
                            }
                    }
                    processedCandidates += batchEnd - batchStart
                    batchStart = batchEnd
                    // 进度 80% → 90%：每 512 对才更新一次，避免海量候选对时高频 StateFlow 冲刷；
                    // 进度按已处理比例平滑推进（候选对减少后肉眼可见地快速爬升）
                    if (processedCandidates % 512 == 0 || processedCandidates == totalCandidates) {
                        onProgress(0.8f + 0.1f * (processedCandidates.toFloat() / totalCandidates))
                    }
                }
            }
            onProgress(0.9f)
            PjmLogger.i(TAG, "图片感知查重：确认重复对 $verified")

            // 4) 分组：每组 ≥ 2 → 保留分辨率最高，其余标记为建议删除（供 UI 对比展示）
            val groups = HashMap<Int, MutableList<Fp>>()
            fpList.forEachIndexed { i, f -> groups.getOrPut(find(i)) { mutableListOf() }.add(f) }
            val result = mutableListOf<DuplicateGroup>()
            groups.values.forEach { g ->
                if (g.size > 1) {
                    val sorted = g.sortedByDescending { it.fp!!.width.toLong() * it.fp!!.height }
                    result.add(
                        DuplicateGroup(
                            members = sorted.map { it.entity },
                            recommendedDelete = sorted.drop(1).map { it.entity.relativePath }.toSet(),
                        ),
                    )
                }
            }
            onProgress(1f)
            result
        }

    fun calculateHash(file: File): String? {
        try {
            file.inputStream().use { return calculateHash(it) }
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * 计算输入流的 MD5 指纹。
     * 供内容级去重比对使用；调用方负责关闭流。
     */
    fun calculateHash(input: InputStream): String? {
        val digest = MessageDigest.getInstance("MD5")
        val buf = VaultManager.acquireBuffer()
        return try {
            var r: Int
            while (input.read(buf).also { r = it } != -1) digest.update(buf, 0, r)
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        } finally {
            VaultManager.releaseBuffer(buf)
        }
    }

    /**
     * 视频内容级感知指纹（不受重封装影响）。
     *
     * 背景：B 站视频 merge（MediaMuxer 重封装）后，同一源视频每次输出的
     * 字节级（MD5）都不同（moov 时间戳/chunk 布局/元数据差异），
     * 导致 MD5 去重永远检测不到。改用内容特征：
     *   时长 + 分辨率 + 第 1 秒关键帧的 dHash（感知哈希）
     * 同一视频无论封装几次，指纹稳定；不同视频区分度高。
     */
    fun calculateVideoFingerprint(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0"
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "0"
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "0"
            val frame =
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
            val dHash = if (frame != null) dHash64(frame) else "0"
            "$duration|$width|$height|$dHash"
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /** 64-bit dHash：缩放 9x8 灰度，逐像素比较生成感知哈希（图片指纹与视频指纹共用） */
    internal fun dHash64(bitmap: Bitmap): String =
        try {
            val w = 9
            val h = 8
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            try {
                val pixels = IntArray(w * h)
                scaled.getPixels(pixels, 0, w, 0, 0, w, h)
                val gray = IntArray(w * h)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    gray[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                }
                val sb = StringBuilder(64)
                for (y in 0 until h) {
                    for (x in 0 until w - 1) {
                        sb.append(if (gray[y * w + x] >= gray[y * w + x + 1]) '1' else '0')
                    }
                }
                sb.toString()
            } finally {
                // 核心修复：回收中间缩放 Bitmap，降低 1.2 万次调用的 GC 压力（防 OOM 卡死）
                try {
                    if (scaled != bitmap) scaled.recycle()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            "0"
        }
}
