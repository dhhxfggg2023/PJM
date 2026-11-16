package com.dhhxfggg.pjm.ui.screen

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.BookmarkCheck
import com.composables.icons.lucide.ListTodo
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SlidersHorizontal
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.reader.BookChapter
import com.dhhxfggg.pjm.domain.reader.BookDocument
import com.dhhxfggg.pjm.domain.reader.BookLoader
import com.dhhxfggg.pjm.domain.reader.Paginator
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.ui.component.PjmButtonCaption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// 小说 / 电子书阅读器。
//
// 与旧实现（塞在 MediaDetailScreen 里、只认 txt 和 epub）相比，这一版的关键变化：
// 1. 格式：TXT / EPUB / FB2 / HTML / Markdown 统一走 BookLoader，
//    解析结果都是「段落 + 章节」，排版与交互只有一份实现。
// 2. 编码：BOM + UTF-16 字节规律 + 多候选编码打分，中文 GBK 书不再整本乱码。
// 3. 章节：强特征（第 X 章 / Chapter N / 序章…）+ 弱特征兜底，且排除正文长句。
// 4. 分页：分页算法抽成纯函数 Paginator（可单测），量高仍按核数并行；
//    页边界缓存按排版参数组合存，换字号再换回来不用重排。
// 5. 阅读体验：书名/作者、章节进度、上一章/下一章、目录层级缩进、页内进度。

/** 阅读器配色方案 */
internal data class ReaderTheme(
    @androidx.annotation.StringRes val nameRes: Int,
    val background: Long,
    val foreground: Long,
)

/** 常用阅读配色：夜间、纸黄、护眼、纯白、深蓝、高对比 */
internal val READER_THEMES =
    listOf(
        ReaderTheme(R.string.reader_theme_night, 0xFF0D0D0F, 0xFFE7E2D6),
        ReaderTheme(R.string.reader_theme_paper, 0xFFF6EFE3, 0xFF3B332A),
        ReaderTheme(R.string.reader_theme_eye, 0xFFCCE8CF, 0xFF20301F),
        ReaderTheme(R.string.reader_theme_white, 0xFFFFFFFF, 0xFF1D1D1F),
        ReaderTheme(R.string.reader_theme_navy, 0xFF14202E, 0xFFC9D6E3),
        ReaderTheme(R.string.reader_theme_contrast, 0xFF000000, 0xFFFFFFFF),
    )

/** 阅读位置落盘的防抖窗口：翻页很密时不必每页写一次 SharedPreferences */
private const val POSITION_SAVE_DEBOUNCE_MS = 400L

/** 字号/行距/边距连点的合并窗口：等手停下来再整本重排 */
private const val LAYOUT_DEBOUNCE_MS = 150L

/** 工具条自动隐藏延迟 */
private const val CHROME_HIDE_DELAY_MS = 2800L

private const val TAG_READER = "Reader"

/**
 * @param file 书文件
 * @param title 库里显示的名字（解析出书名后优先显示书名）
 * @param onBack 返回
 */
@Suppress("WrongConstant")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    file: File,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val prefs = remember { context.getSharedPreferences("pjm_reader", android.content.Context.MODE_PRIVATE) }
    val positionKey = remember(file.absolutePath) { "page_" + file.absolutePath.hashCode() }
    // 书签的键带版本号：老版本存的是「页码」，现在存的是「段落下标」，语义完全不同。
    // 如果继续用同一个键，老库里第 50 页的书签会被当成第 50 段读出来，直接跳到书开头。
    // 换键等于让旧书签自然失效（用户重新加一次即可），比跳到错位置好。
    val bookmarkKey = remember(file.absolutePath) { "bm2_" + file.absolutePath.hashCode() }

    var book by remember(file.absolutePath) { mutableStateOf<BookDocument?>(null) }
    var loadFailed by remember(file.absolutePath) { mutableStateOf(false) }
    // 可用视口尺寸：直接量根容器，而不是读 Configuration（分屏/悬浮窗下 Configuration 会偏大）
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var pages by remember(file.absolutePath) { mutableStateOf<List<List<String>>?>(null) }
    // 每页起始段落下标：章节跳转、当前章判定都要靠它把「页」映射回「段落」
    var pageStarts by remember(file.absolutePath) { mutableStateOf<List<Int>>(emptyList()) }
    var layoutDone by remember(file.absolutePath) { mutableStateOf(false) }
    // 行数缓存跨重排复用：字号/页宽不变但行距变化时，断行结果完全一样，不必从头算
    val lineCache = remember { ReaderLineCache() }
    // 与正文渲染共用同一个测量器：分页高度必须来自真实行盒，不能用「行数 × 行距」的理想公式
    val textMeasurer = rememberTextMeasurer()
    var fontSize by remember { mutableFloatStateOf(prefs.getFloat("font_size", 17f)) }
    var lineSpacing by remember { mutableFloatStateOf(prefs.getFloat("line_spacing", 1.8f)) }
    var marginDp by remember { mutableIntStateOf(prefs.getInt("margin_dp", 18)) }
    var themeIndex by remember { mutableIntStateOf(prefs.getInt("theme_index", 0)) }
    var showChrome by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // 书签按「段落下标」存，不按页码：换字号/换行距后页数会变，页码书签会全部错位
    var bookmarks by remember(file.absolutePath) {
        mutableStateOf(
            prefs
                .getStringSet(bookmarkKey, emptySet())
                .orEmpty()
                .mapNotNull { it.toIntOrNull() }
                .toSet(),
        )
    }

    fun toggleBookmark(paragraph: Int) {
        bookmarks = if (paragraph in bookmarks) bookmarks - paragraph else bookmarks + paragraph
        prefs.edit { putStringSet(bookmarkKey, bookmarks.map { it.toString() }.toSet()) }
    }

    val scope = rememberCoroutineScope()
    val focusRequester =
        remember {
            androidx.compose.ui.focus
                .FocusRequester()
        }
    val pagerState = rememberPagerState(0) { (pages?.size ?: 1).coerceAtLeast(1) }
    val theme = READER_THEMES[themeIndex.coerceIn(0, READER_THEMES.size - 1)]
    val topInsetDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 当前页起始段落：章节判定、书签定位都以段落为准（页码会随字号变，段落不会）
    val currentParagraph by remember(pageStarts, pagerState) {
        derivedStateOf { pageStarts.getOrNull(pagerState.currentPage) ?: 0 }
    }

    // 1) 解析：格式识别 + 章节识别 + 编码判定都在 IO 线程完成
    LaunchedEffect(file.absolutePath) {
        cleanStalePageCaches(context)
        val loaded = withContext(Dispatchers.IO) { BookLoader.load(file) }
        book = loaded
        loadFailed = loaded == null
    }

    val currentBook = book

    // 正文样式：分页测量与实际渲染必须共用同一份，否则行盒高度对不上，页面底部会空出来
    val style =
        TextStyle(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineSpacing).sp,
            fontFamily = FontFamily.Serif,
            color = Color(theme.foreground),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )

    // 2) 分页：字号/行距/边距/屏幕尺寸/可用高度任一变化都重排（带防抖）
    LaunchedEffect(
        currentBook,
        fontSize,
        lineSpacing,
        marginDp,
        viewportWidthPx,
        viewportHeightPx,
        topInsetDp,
    ) {
        val document = currentBook ?: return@LaunchedEffect
        if (viewportHeightPx <= 0 || viewportWidthPx <= 0) return@LaunchedEffect
        delay(LAYOUT_DEBOUNCE_MS)
        pages = null
        layoutDone = false

        val paragraphs = document.paragraphs
        val pageWidth = (viewportWidthPx - with(density) { (marginDp * 2).dp.toPx() }).coerceAtLeast(1f)
        // 正文可用高度 = 实测高度 − 状态栏 − 上下 padding(8 + 28) − 2dp 余量
        val pageHeight =
            (
                viewportHeightPx.toFloat() - with(density) { topInsetDp.toPx() } -
                    with(density) { (8 + 28).dp.toPx() } - with(density) { 2.dp.toPx() }
            ).coerceAtLeast(1f)
        val gap = with(density) { 4.dp.toPx() }
        val measureWidth = pageWidth.toInt().coerceAtLeast(1)
        // 实测行盒高度：Compose 渲染的行高不是「行数 × lineHeight」——
        // 首行按字号算，后续行才按行距累加，行距越大理想公式高得越多，
        // 于是每页算出来的内容比实际多，底部空一截（行距越大越明显）。
        // 这里用与正文完全相同的 style 量一行和两行，得到首行高度与每行增量。
        val oneLineHeight =
            textMeasurer
                .measure(text = "测", style = style, constraints = Constraints(maxWidth = measureWidth))
                .size
                .height
        val twoLineHeight =
            textMeasurer
                .measure(text = "测\n测", style = style, constraints = Constraints(maxWidth = measureWidth))
                .size
                .height
        val lineStepPx = (twoLineHeight - oneLineHeight).coerceAtLeast(1)

        fun heightOf(lines: Int): Float = (oneLineHeight + (lines - 1).coerceAtLeast(0) * lineStepPx).toFloat() + gap

        val cacheFile =
            readerCacheFile(
                context,
                readerCacheKey(
                    file,
                    fontSize,
                    lineSpacing,
                    marginDp,
                    viewportWidthPx,
                    viewportHeightPx - with(density) { topInsetDp.toPx() }.toInt(),
                ),
            )
        val cached = withContext(Dispatchers.IO) { readPageCache(cacheFile, paragraphs.size) }
        if (cached != null) {
            val restored = buildPagesFromStarts(paragraphs, cached)
            pageStarts = cached
            pages = restored
            if (restored.isNotEmpty()) {
                val savedPage = prefs.getInt(positionKey, 0)
                val savedRatio = prefs.getFloat(positionKey + "_ratio", -1f)
                val target =
                    when {
                        savedPage in restored.indices -> savedPage
                        savedRatio in 0f..1f -> (savedRatio * (restored.size - 1).coerceAtLeast(1)).toInt()
                        else -> 0
                    }
                pagerState.scrollToPage(target.coerceIn(0, (restored.size - 1).coerceAtLeast(0)))
            }
            layoutDone = true
            return@LaunchedEffect
        }

        // 量高：改用轻量行数估算（等宽 CJK 只做一次 ceil，混合文本走 breakText，
        // 只有标点落在断行边界时才精确构建 StaticLayout）。整本行数按字号+页宽缓存，
        // 行距变化直接复用，不必从头排版。
        val textSizePx = with(density) { fontSize.sp.toPx() }
        // 缓存 key 只含影响断行的因素：字号与页宽。行距/边距/主题变化都不需要重算行数。
        val measureCacheKey = "s" + textSizePx.toInt() + "w" + measureWidth
        val heights = FloatArray(paragraphs.size)

        // 先查整本缓存：行距/主题切换时这里直接命中，零次断行
        val cachedLines = lineCache.lines(measureCacheKey, paragraphs.size)
        if (cachedLines != null) {
            for (i in paragraphs.indices) {
                heights[i] =
                    if (paragraphs[i].isEmpty()) {
                        gap * 1.5f
                    } else {
                        heightOf(cachedLines[i])
                    }
            }
        } else {
            val lines = IntArray(paragraphs.size)

            suspend fun measureSlice(
                from: Int,
                to: Int,
            ) {
                // Paint/estimator 不是线程安全的：每条协程自己一份，共享只读的行数缓存
                val estimator =
                    ReaderLineEstimator(
                        TextPaint().apply {
                            typeface = Typeface.SERIF
                            textSize = textSizePx
                            isAntiAlias = true
                        },
                        measureWidth,
                        lineCache,
                        measureCacheKey,
                    )
                for (index in from until to) {
                    lines[index] = estimator.measure(paragraphs[index])
                }
            }

            val sliceCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            var cursor = 0
            var chunk = 120 // 第一块小一点：点开就能看到字
            while (cursor < paragraphs.size) {
                val end = minOf(cursor + chunk, paragraphs.size)
                val step = ((end - cursor) + sliceCount - 1) / sliceCount
                coroutineScope {
                    (0 until sliceCount)
                        .mapNotNull { k ->
                            val from = cursor + k * step
                            if (from >= end) null else async(Dispatchers.Default) { measureSlice(from, minOf(from + step, end)) }
                        }.awaitAll()
                }
                cursor = end
                chunk = 600
            }
            lineCache.store(measureCacheKey, lines)
            for (i in paragraphs.indices) {
                heights[i] =
                    if (paragraphs[i].isEmpty()) {
                        gap * 1.5f
                    } else {
                        heightOf(lines[i])
                    }
            }
        }

        val starts = Paginator.paginate(heights, pageHeight)
        val computed = buildPagesFromStarts(paragraphs, starts)
        pageStarts = starts
        pages = computed
        layoutDone = true

        // 恢复阅读位置：优先按整本百分比（换字号后页数会变，百分比才不会跳错地方）
        run {
            val savedRatio = prefs.getFloat(positionKey + "_ratio", -1f)
            val savedPage = prefs.getInt(positionKey, 0)
            val target =
                when {
                    savedRatio in 0f..1f -> (savedRatio * (computed.size - 1).coerceAtLeast(1)).toInt()
                    savedPage in computed.indices -> savedPage
                    else -> 0
                }
            pagerState.scrollToPage(target.coerceIn(0, (computed.size - 1).coerceAtLeast(0)))
        }
        withContext(Dispatchers.IO) { writePageCache(cacheFile, paragraphs.size, starts) }
        PjmLogger.i(TAG_READER, "排版完成: " + file.name + " 段落 " + paragraphs.size + " 页 " + computed.size)
    }

    // 3) 阅读位置落盘（防抖）
    LaunchedEffect(pagerState, pages) {
        if (pages.isNullOrEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }.collectLatest { page ->
            delay(POSITION_SAVE_DEBOUNCE_MS)
            val total = (pages?.size ?: 0).coerceAtLeast(1)
            val ratio = (page.toFloat() / (total - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            prefs.edit {
                putInt(positionKey, page)
                putFloat(positionKey + "_ratio", ratio)
            }
        }
    }
    // 防抖期间退出会丢掉最后一次翻页：离开前补写一次
    DisposableEffect(pagerState) {
        onDispose {
            val total = pages?.size ?: 0
            if (total > 0) {
                val page = pagerState.currentPage
                val ratio = (page.toFloat() / (total - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                prefs.edit {
                    putInt(positionKey, page)
                    putFloat(positionKey + "_ratio", ratio)
                }
            }
        }
    }
    LaunchedEffect(showChrome) {
        if (showChrome) {
            delay(CHROME_HIDE_DELAY_MS)
            showChrome = false
        }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(theme.background))
                .onSizeChanged {
                    viewportHeightPx = it.height
                    viewportWidthPx = it.width
                },
    ) {
        val pageList = pages
        when {
            loadFailed ->
                ReaderMessage(
                    text = stringResource(R.string.reader_load_failed),
                    color = Color(theme.foreground),
                )
            currentBook == null || pageList == null ->
                ReaderLoading(
                    text =
                        if (currentBook == null) {
                            stringResource(R.string.reader_loading)
                        } else {
                            stringResource(R.string.viewer_text_paginating)
                        },
                    color = Color(theme.foreground),
                )
            else -> {
                // 章节信息：给顶栏副标题和「上一章/下一章」用
                val chapterIndex by remember(currentBook, currentParagraph) {
                    derivedStateOf { currentBook.chapterIndexAt(currentParagraph) }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.VolumeDown -> {
                                        scope.launch {
                                            pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                                        }
                                        true
                                    }
                                    Key.VolumeUp -> {
                                        scope.launch {
                                            pagerState.animateScrollToPage(
                                                (pagerState.currentPage + 1).coerceAtMost(pagerState.pageCount - 1),
                                            )
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }.pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    when {
                                        offset.x < size.width * 0.3f ->
                                            scope.launch {
                                                pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                                            }
                                        offset.x > size.width * 0.7f ->
                                            scope.launch {
                                                pagerState.animateScrollToPage(
                                                    (pagerState.currentPage + 1).coerceAtMost(pagerState.pageCount - 1),
                                                )
                                            }
                                        // 点中间 = 显示/隐藏工具条
                                        else -> showChrome = !showChrome
                                    }
                                }
                            },
                ) { index ->
                    val page = pageList.getOrNull(index).orEmpty()
                    // 包一层 SelectionContainer：长按可以选中文字复制
                    SelectionContainer {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .padding(start = marginDp.dp, end = marginDp.dp, top = 8.dp, bottom = 28.dp),
                        ) {
                            page.forEach { paragraph ->
                                Text(
                                    text = paragraph,
                                    style = style,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = showChrome, enter = fadeIn(), exit = fadeOut()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ReaderTopBar(
                            title = currentBook.title?.takeIf { it.isNotBlank() } ?: title,
                            subtitle =
                                currentBook.chapters.getOrNull(chapterIndex)?.title
                                    ?: currentBook.author,
                            bookmarked = currentParagraph in bookmarks,
                            onBack = onBack,
                            onToggleBookmark = { toggleBookmark(currentParagraph) },
                            onOpenToc = { showToc = true },
                            onOpenSettings = { showSettings = true },
                        )
                        Spacer(Modifier.weight(1f))
                        // 底部章节切换：读到长书中段时，翻几百页才能到下一章不现实
                        if (currentBook.chapters.isNotEmpty()) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.4f))
                                        .navigationBarsPadding()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    enabled = chapterIndex > 0,
                                    onClick = {
                                        val prev = currentBook.chapters.getOrNull(chapterIndex - 1) ?: return@TextButton
                                        scope.launch {
                                            pagerState.scrollToPage(paragraphToPage(pageStarts, prev.startParagraph))
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.reader_prev_chapter), color = Color.White.copy(alpha = 0.9f))
                                }
                                TextButton(
                                    enabled = chapterIndex < currentBook.chapters.lastIndex,
                                    onClick = {
                                        val next = currentBook.chapters.getOrNull(chapterIndex + 1) ?: return@TextButton
                                        scope.launch {
                                            pagerState.scrollToPage(paragraphToPage(pageStarts, next.startParagraph))
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.reader_next_chapter), color = Color.White.copy(alpha = 0.9f))
                                }
                            }
                        }
                    }
                }

                // 页码/百分比常显：不用唤出工具条也能看到读到哪
                Text(
                    text =
                        (pagerState.currentPage + 1).toString() + " / " + pageList.size +
                            "  ·  " + percentOf(pagerState.currentPage, pageList.size) + "%" +
                            if (currentBook.truncated) "  ·  " + stringResource(R.string.reader_truncated) else "",
                    color = Color(theme.foreground).copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp),
                )
            }
        }
    }

    if (!layoutDone && currentBook != null && pages == null) {
        // 排版进行中：页面底部给一句明确提示，避免只看到页码不动以为卡住
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                text = stringResource(R.string.viewer_text_paginating),
                color = Color(theme.foreground).copy(alpha = 0.55f),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 40.dp),
            )
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            fontSize = fontSize,
            lineSpacing = lineSpacing,
            marginDp = marginDp,
            themeIndex = themeIndex,
            onFontSize = {
                fontSize = it
                prefs.edit { putFloat("font_size", it) }
            },
            onLineSpacing = {
                lineSpacing = it
                prefs.edit { putFloat("line_spacing", it) }
            },
            onMargin = {
                marginDp = it
                prefs.edit { putInt("margin_dp", it) }
            },
            onTheme = {
                themeIndex = it
                prefs.edit { putInt("theme_index", it) }
            },
            onReset = {
                fontSize = 17f
                lineSpacing = 1.8f
                marginDp = 18
                themeIndex = 0
                prefs.edit {
                    putFloat("font_size", 17f)
                    putFloat("line_spacing", 1.8f)
                    putInt("margin_dp", 18)
                    putInt("theme_index", 0)
                }
            },
            onDismiss = { showSettings = false },
        )
    }

    if (showToc && currentBook != null) {
        ReaderTocSheet(
            chapters = currentBook.chapters,
            bookmarks = bookmarks.sorted(),
            currentParagraph = currentParagraph,
            pageStarts = pageStarts,
            pageCount = (pages?.size ?: 1).coerceAtLeast(1),
            onJumpToParagraph = { paragraph ->
                showToc = false
                scope.launch { pagerState.scrollToPage(paragraphToPage(pageStarts, paragraph)) }
            },
            onToggleBookmark = { toggleBookmark(it) },
            onDismiss = { showToc = false },
        )
    }
}
// ---------------- 子组件 ----------------

@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String?,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 返回是通用图标，不再加小字；其余功能按钮保留一行 10sp 小字。
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onBack) {
                Icon(Lucide.ArrowLeft, stringResource(R.string.action_back), tint = Color.White)
            }
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onOpenToc) {
                Icon(Lucide.ListTodo, stringResource(R.string.reader_cd_toc), tint = Color.White)
            }
            PjmButtonCaption(caption = stringResource(R.string.hint_toc), color = Color.White.copy(alpha = 0.7f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onOpenSettings) {
                Icon(Lucide.SlidersHorizontal, stringResource(R.string.reader_cd_settings), tint = Color.White)
            }
            PjmButtonCaption(caption = stringResource(R.string.hint_settings), color = Color.White.copy(alpha = 0.7f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = if (bookmarked) Lucide.BookmarkCheck else Lucide.Bookmark,
                    contentDescription =
                        stringResource(
                            if (bookmarked) R.string.reader_remove_bookmark else R.string.reader_add_bookmark,
                        ),
                    tint = if (bookmarked) Color(0xFFF2C14E) else Color.White,
                )
            }
            PjmButtonCaption(
                caption = stringResource(if (bookmarked) R.string.hint_bookmarked else R.string.hint_bookmark),
                color = if (bookmarked) Color(0xFFF2C14E).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ReaderLoading(
    text: String,
    color: Color,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = color)
            Spacer(Modifier.height(12.dp))
            Text(text = text, color = color.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReaderMessage(
    text: String,
    color: Color,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = color.copy(alpha = 0.75f), fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTocSheet(
    chapters: List<BookChapter>,
    bookmarks: List<Int>,
    currentParagraph: Int,
    pageStarts: List<Int>,
    pageCount: Int,
    onJumpToParagraph: (Int) -> Unit,
    onToggleBookmark: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        var tab by remember { mutableIntStateOf(0) }
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SheetTab(
                    label = stringResource(R.string.reader_toc_title, chapters.size),
                    selected = tab == 0,
                    modifier = Modifier.weight(1f),
                ) { tab = 0 }
                SheetTab(
                    label = stringResource(R.string.reader_bookmarks_count, bookmarks.size),
                    selected = tab == 1,
                    modifier = Modifier.weight(1f),
                ) { tab = 1 }
            }
            if (tab == 0) {
                if (chapters.isEmpty()) {
                    Text(
                        text = stringResource(R.string.reader_no_chapters),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                } else {
                    val keys = remember(chapters) { uniqueKeys(chapters.map { it.startParagraph.toString() }) }
                    // 当前章只在列表外算一次（写在 item 里会 O(n²)）
                    val currentChapter by remember(chapters, currentParagraph) {
                        derivedStateOf {
                            chapters.indexOfLast { it.startParagraph <= currentParagraph }.coerceAtLeast(-1)
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                        items(chapters.size, key = { keys[it] }) { index ->
                            val chapter = chapters[index]
                            val isCurrent = index == currentChapter
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                        )
                                        // 卷/部一级（level 0）多缩进一点，目录层次一眼能看出来
                                        .padding(start = (16 + chapter.level * 8).dp, end = 20.dp, top = 12.dp, bottom = 12.dp)
                                        .clickable { onJumpToParagraph(chapter.startParagraph) },
                            )
                        }
                    }
                }
            } else if (bookmarks.isEmpty()) {
                Text(
                    text = stringResource(R.string.reader_bookmarks_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    // bookmarks 是段落下标（不随字号变）；显示时换算成「第几页 + 百分比」
                    items(bookmarks, key = { it }) { paragraph ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val page = Paginator.pageOf(pageStarts, paragraph)
                            TextButton(
                                onClick = { onJumpToParagraph(paragraph) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text =
                                        stringResource(R.string.reader_page_label, page + 1) +
                                            "  ·  " + percentOf(page, pageCount) + "%",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            TextButton(onClick = { onToggleBookmark(paragraph) }) {
                                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    fontSize: Float,
    lineSpacing: Float,
    marginDp: Int,
    themeIndex: Int,
    onFontSize: (Float) -> Unit,
    onLineSpacing: (Float) -> Unit,
    onMargin: (Int) -> Unit,
    onTheme: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.reader_settings_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            StepperRow(
                stringResource(R.string.reader_font_size),
                fontSize.toInt().toString(),
                { onFontSize((fontSize - 1f).coerceAtLeast(14f)) },
                { onFontSize((fontSize + 1f).coerceAtMost(30f)) },
            )
            StepperRow(
                stringResource(R.string.reader_line_height),
                String.format(Locale.US, "%.1f×", lineSpacing),
                { onLineSpacing((lineSpacing - 0.1f).coerceAtLeast(1.2f)) },
                { onLineSpacing((lineSpacing + 0.1f).coerceAtMost(2.6f)) },
            )
            StepperRow(
                stringResource(R.string.reader_margins),
                marginDp.toString(),
                { onMargin((marginDp - 2).coerceAtLeast(8)) },
                { onMargin((marginDp + 2).coerceAtMost(40)) },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.reader_colors),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                READER_THEMES.forEachIndexed { index, item ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(item.background))
                                    .clickable { onTheme(index) },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(item.nameRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (index == themeIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onReset) { Text(stringResource(R.string.reader_reset_layout)) }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onPlus) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

// ---------------- 分页缓存 ----------------

/** 段落起始下标 → 每页段落切片 */
private fun buildPagesFromStarts(
    paragraphs: List<String>,
    starts: List<Int>,
): List<List<String>> {
    if (starts.isEmpty()) return listOf(emptyList())
    val result = ArrayList<List<String>>(starts.size)
    starts.forEachIndexed { i, start ->
        val end = if (i + 1 < starts.size) starts[i + 1] else paragraphs.size
        val from = start.coerceIn(0, paragraphs.size)
        val to = end.coerceIn(from, paragraphs.size)
        result.add(paragraphs.subList(from, to).toList())
    }
    if (result.isEmpty()) result.add(emptyList())
    return result
}

/** 段落下标 → 页下标（章节跳转用）：直接二分页起始点，O(log n) */
private fun paragraphToPage(
    starts: List<Int>,
    paragraphIndex: Int,
): Int = Paginator.pageOf(starts, paragraphIndex)

private fun percentOf(
    page: Int,
    total: Int,
): Int = if (total <= 0) 0 else ((page + 1) * 100 / total).coerceIn(0, 100)

private fun uniqueKeys(bases: List<String>): List<String> {
    val seen = HashMap<String, Int>(bases.size)
    return bases.map { base ->
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        if (count == 1) base else base + "#" + count
    }
}

private fun readerCacheKey(
    file: File,
    fontSize: Float,
    lineSpacing: Float,
    marginDp: Int,
    widthDp: Int,
    heightDp: Int,
): String = listOf("v7", file.length(), file.lastModified(), fontSize, lineSpacing, marginDp, widthDp, heightDp).joinToString("-")

private fun readerCacheFile(
    context: android.content.Context,
    key: String,
): File {
    val dir = File(context.cacheDir, "reader_pages").apply { mkdirs() }
    return File(dir, "v7-" + key.hashCode().toString() + ".pag")
}

/** 排版算法升级后，旧版本的 .pag 直接删掉：留着只占空间，也不会再被读到 */
private fun cleanStalePageCaches(context: android.content.Context) {
    val dir = File(context.cacheDir, "reader_pages")
    if (!dir.isDirectory) return
    dir.listFiles()?.forEach { file ->
        if (!file.name.startsWith("v7-")) runCatching { file.delete() }
    }
}

private fun readPageCache(
    file: File,
    paragraphCount: Int,
): List<Int>? {
    if (!file.exists()) return null
    return runCatching {
        val lines = file.readLines()
        if (lines.size < 2) return null
        if (lines[0].toIntOrNull() != paragraphCount) return null
        if (lines[1].isBlank()) return null
        lines[1].split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

private fun writePageCache(
    file: File,
    paragraphCount: Int,
    starts: List<Int>,
) {
    runCatching {
        val dir = file.parentFile ?: return@runCatching
        // 只留最近 40 本，避免缓存无限增长
        dir.listFiles()?.let { files ->
            if (files.size > 40) files.sortedBy { it.lastModified() }.take(files.size - 40).forEach { it.delete() }
        }
        file.writeText(paragraphCount.toString() + "\n" + starts.joinToString(",") + "\n")
    }
}
