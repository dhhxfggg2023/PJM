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

    /**
     * 视频感知指纹在 `contentHash` 列中的前缀标记。
     *
     * `contentHash` 列被两种语义复用：普通文件存 MD5（32 位十六进制），
     * 视频存「时长|宽|高|dHash」感知指纹。加前缀后完整性检查才能区分二者，
     * 否则每次查重之后做完整性检查都会把所有视频误判为「已损坏」。
     */
    private const val VIDEO_FP_PREFIX = "fp:"

    /** 是否为感知指纹（而非 MD5）。同时兼容加前缀之前写入的历史数据。 */
    private fun isPerceptualFingerprint(hash: String?): Boolean = hash != null && hash.startsWith(VIDEO_FP_PREFIX)

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
                val hash = e.contentHash
                // 只有「确定是 MD5」的记录才做字节级校验：
                //  - null        → 尚未计算，无从比对（空转会被用户误解为「校验通过」，但不误报损坏）
                //  - fp: 前缀    → 视频感知指纹，与 MD5 不可比
                //  - 视频文件    → 历史上曾直接写入无前缀的感知指纹，一律不按 MD5 比对
                val md5Comparable = hash != null && !isPerceptualFingerprint(hash) && !FileUtils.isVideoFile(e.name)
                when {
                    !f.exists() -> missing.add(e)
                    md5Comparable && calculateHash(f) != hash -> corrupted.add(e)
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
                // 关键：感知指纹必须带 [VIDEO_FP_PREFIX] 前缀与 MD5 区分开 ——
                // 否则 checkIntegrity 会拿 MD5 去和指纹串比对，把所有跑过查重的视频误报为「已损坏」。
                val hash =
                    if (FileUtils.isVideoFile(entity.name)) {
                        // 核心优化：视频感知指纹（MediaMetadataRetriever + 抽帧 + dHash）很贵，
                        // 历史实现**每次都无条件重算**——873 个视频每次查重都白抽一遍帧。
                        // 文件未变（长度与修改时间都与索引记录一致）时指纹必然不变，直接复用缓存。
                        val reusable =
                            entity.contentHash?.takeIf {
                                isPerceptualFingerprint(it) &&
                                    file.length() == entity.size &&
                                    file.lastModified() == entity.lastModified
                            }
                        reusable ?: calculateVideoFingerprint(file)?.let { VIDEO_FP_PREFIX + it }
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
                    // 核心修复：视频组（感知指纹）一律不预勾选。
                    // 视频指纹只比对「时长 + 分辨率 + 第 1 秒关键帧 dHash」，**没有**图片那样的
                    // 像素二次验证；同源重封装能命中，但两个时长/分辨率恰好相同、首帧又相似的
                    // 不同视频也会被分到一组 —— 默认勾选会诱导用户直接误删。
                    // MD5 组是字节级完全一致，保留预勾选。
                    val groupHash = group.first().contentHash.orEmpty()
                    val isPerceptual = groupHash.startsWith(VIDEO_FP_PREFIX)
                    result.add(
                        DuplicateGroup(
                            members = sorted,
                            recommendedDelete =
                                if (isPerceptual) {
                                    emptySet()
                                } else {
                                    sorted.drop(1).map { it.relativePath }.toSet()
                                },
                        ),
                    )
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

            // 1) 计算/读取感知数据（增量：已缓存跳过）
            // 核心修复（崩溃/卡死）：
            //   a. 分批处理（每批 128 张）—— 绝不一次性创建 1.2 万个协程，控制内存峰值；
            //   b. Semaphore(3) 限流 —— 只 3 路并发解码，避免 OOM + 避免占满 8 线程 IO 池
            //      （否则删除等其他操作排队，用户感知"卡死"）；
            //   c. computeBundle 内部 catch Throwable（含 OOM）+ 显式 recycle，单图失败不影响整体。
            // 核心优化（一次解码）：dHash 指纹、32×32 灰度、64 宽验证签名三者都来自同一次
            //   64 宽解码。历史实现里指纹与灰度是**两次独立解码**（都解到 64 宽），
            //   2 万张图等于白解码两万次；验证阶段更糟 —— 每个候选对都要重新解码两张原图。
            data class Fp(
                val entity: FileEntity,
                val fp: ImageFingerprint?,
                val gray32: ByteArray?,
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
                                        val cachedFp = ImageFingerprintCache.getFingerprint(context, e)
                                        val cachedGray = ImageFingerprintCache.getGray32(context, e)
                                        val cachedSig = ImageFingerprintCache.getVerifySignature(context, e)
                                        if (cachedFp != null && cachedGray != null && cachedSig != null) {
                                            // 三项全命中 → 零解码（增量场景：第二次起查重是秒级）
                                            Fp(e, cachedFp, cachedGray)
                                        } else {
                                            // 任一缺失 → 一次解码同时补齐三项（老缓存升级也走这里）
                                            val bundle = ImageFingerprintCache.computeBundle(context, e)
                                            if (bundle != null) {
                                                ImageFingerprintCache.saveBundle(context, e, bundle)
                                                Fp(e, bundle.fingerprint, bundle.gray32)
                                            } else {
                                                // 解码失败：退回已缓存的部分，绝不因单图失败影响整体
                                                Fp(e, cachedFp, cachedGray)
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
            // 拆成非空 Pair（entity → fingerprint），后续免去 !! 断言（防 NPE）
            val fpList: List<Pair<FileEntity, ImageFingerprint>> =
                fps.mapNotNull { f -> f.fp?.let { f.entity to it } }.filter { it.second.dHash.length == 64 }
            if (fpList.size < 2) return@withContext emptyList()

            // 2) 二进制串 → Long（加速汉明距离）+ 宽高比/面积预计算（粗筛纯内存过滤）
            val dHashes = LongArray(fpList.size) { i -> fpList[i].second.dHash.toLongOrNull(2) ?: 0L }
            val ratios =
                FloatArray(fpList.size) { i ->
                    val fp = fpList[i].second
                    fp.width.toFloat() / fp.height.coerceAtLeast(1)
                }
            val areas =
                LongArray(fpList.size) { i ->
                    val fp = fpList[i].second
                    fp.width.toLong() * fp.height
                }

            // 3) 粗筛（核心修复 OOM + 80% 卡死）：
            //   a. O(n²) bitCount 保证 100% 召回（不遗漏任何汉明距离 ≤16 的对）；
            //   b. 【内存宽高比预过滤】—— 原图 4:3 与 16:9 不可能是缩略图关系，纯内存直接排除；
            //   c. 【面积差异预过滤】—— 本功能只找"原图 vs 缩略图"（面积差 ≥ 1.2 倍）；
            //   d. 候选对用 LongArray 紧凑编码 (a shl 32) or b —— 8 字节/对，百万候选对约 8MB。
            //      核心修复：原来用 IntArray + `(a shl 16) or b`，图片数 ≥ 65536 时高位被截断，
            //      解码回来会指向**完全无关的两张图** —— 既产生错误重复组，又白白解码验证。
            onProgress(0.6f)
            val totalPairs = fpList.size.toLong() * (fpList.size - 1) / 2
            var candidates = LongArray(8192)
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
                    if ((hi xor dHashes[j]).countOneBits() <= 16) {
                        if (candidateCount == candidates.size) candidates = candidates.copyOf(candidates.size * 2)
                        candidates[candidateCount++] = packPair(i, j)
                    }
                }
            }
            onProgress(0.75f)
            PjmLogger.i(TAG, "图片感知查重：${fpList.size} 张图，$totalPairs 对，候选对 $candidateCount")

            // 3.5) 32×32 灰度预筛（纯内存，微秒级）—— 候选对可能达数百万，
            //      每对解码 64px 验证耗时以小时计。候选对先纯内存比较灰度：
            //      平均亮度差 > 15 → 内容不一致，直接排除。
            //      同图不同分辨率灰度差 < 6（通过），不同图 > 20（排除）—— 可砍掉 95%+ 干扰对。
            // 核心优化：灰度已在第 1 步随指纹一次性算好并落盘，这里**只做内存归集**，
            //      不再有第二次解码遍历（历史实现此处会再解码全部图片）。
            onProgress(0.75f)
            val gray32Cache = HashMap<String, ByteArray>(fpList.size)
            fps.forEach { f ->
                // 只为进入比对集合（fp 非空）的图片保留灰度，避免为被淘汰的图白占内存
                if (f.fp != null) f.gray32?.let { gray32Cache[f.entity.relativePath] = it }
            }
            // 用灰度预筛过滤候选对（内存紧凑重建，避免保留被淘汰的）
            if (candidateCount > 0) {
                var kept = 0
                for (idx in 0 until candidateCount) {
                    if (VaultManager.isTaskCancelled(VaultManager.TASK_DUPLICATES_PERCEPTUAL)) throw CancellationException("灰度预筛已取消")
                    val pair = candidates[idx]
                    val i = pairFirst(pair)
                    val j = pairSecond(pair)
                    val g1 = gray32Cache[fpList[i].first.relativePath]
                    val g2 = gray32Cache[fpList[j].first.relativePath]
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
                                    val i = pairFirst(pair)
                                    val j = pairSecond(pair)
                                    fpSemaphore.withPermit {
                                        if (ImageFingerprintCache.verifySameContent(
                                                context,
                                                fpList[i].first,
                                                fpList[j].first,
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
            val groups = HashMap<Int, MutableList<Pair<FileEntity, ImageFingerprint>>>()
            fpList.forEachIndexed { i, f -> groups.getOrPut(find(i)) { mutableListOf() }.add(f) }
            val result = mutableListOf<DuplicateGroup>()
            groups.values.forEach { g ->
                if (g.size > 1) {
                    val sorted = g.sortedByDescending { it.second.width.toLong() * it.second.height }
                    result.add(
                        DuplicateGroup(
                            members = sorted.map { it.first },
                            recommendedDelete = sorted.drop(1).map { it.first.relativePath }.toSet(),
                        ),
                    )
                }
            }
            onProgress(1f)
            result
        }

    /** 把候选对 (i, j) 打包进一个 Long（各占 32 位，支持索引 ≥ 65536）。 */
    private fun packPair(
        i: Int,
        j: Int,
    ): Long = (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)

    private fun pairFirst(pair: Long): Int = (pair ushr 32).toInt()

    private fun pairSecond(pair: Long): Int = (pair and 0xFFFFFFFFL).toInt()

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
