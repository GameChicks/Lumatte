@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.lumixfilmmirror.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts
import com.lumixfilmmirror.media.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

private data class MirrorLutCard(val local: LutAsset? = null, val official: LumixLabLut? = null) {
    val key: String get() = official?.let { "official:${it.code}" }
        ?: local?.lumixLabSource?.let { "official:${it.code}" } ?: requireNotNull(local).id
    val title: String get() = official?.title ?: requireNotNull(local).name
    val creator: String? get() = official?.creator?.name ?: local?.lumixLabSource?.creatorName
}
private data class GalleryCategory(val key: String, val title: String, val colour: Color)

private fun LutAsset.galleryCard(): MirrorLutCard {
    val source = lumixLabSource
    val official = source?.thumbnailPath?.let { path -> LumixLabLut(source.code, source.title,
        LumixLabCreator(source.creatorCode, source.creatorName,
            source.creatorAvatarPath?.let { LumixLabImageRef(it, null, null, null) }),
        LumixLabImageRef(path, null, null, null), null) }
    return MirrorLutCard(this, official)
}

@Composable
internal fun MirrorLuts(state: WorkspaceState, workspace: WorkspaceViewModel, personal: MirrorPersonalState,
    onImportPhoto: () -> Unit, onImportLut: () -> Unit, onChoose: (LutAsset, Boolean) -> Unit,
    initialSecond: Boolean = false,
    onFullscreen: (Boolean) -> Unit) {
    val browser by workspace.officialLuts.state.collectAsState()
    val tones = LocalFilmMaterials.current
    var official by rememberSaveable { mutableStateOf(true) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var choosingSecond by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialSecond) { choosingSecond = initialSecond }
    var favourites by rememberSaveable { mutableStateOf(false) }
    var favouriteClosing by rememberSaveable { mutableStateOf(false) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var detailSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    val detailOpen = detailKey != null
    var origin by rememberSaveable { mutableStateOf("") }
    var importMenu by remember { mutableStateOf(false) }
    var requestedSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedSecond by rememberSaveable { mutableStateOf(false) }
    var favouriteIndex by rememberSaveable { mutableIntStateOf(-1) }
    var favouriteRevealPlayed by rememberSaveable { mutableStateOf(false) }
    val galleryScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val pages = remember { mutableStateMapOf<String, List<LumixLabLut>>() }
    var localSubjects by remember { mutableStateOf<Map<String, Set<LutSubject>>>(emptyMap()) }
    var subjectRevision by remember { mutableIntStateOf(0) }
    val localCards = remember(state.luts) { state.luts.map { it.galleryCard() } }
    val rememberedDetail = remember(detailSnapshot) { detailSnapshot?.let { runCatching { restoreLutDetailSnapshot(it) }.getOrNull() } }
    val resolvedDetailCard = localCards.firstOrNull { it.key == detailKey } ?: rememberedDetail?.takeIf { "official:${it.code}" == detailKey }?.let { item ->
        MirrorLutCard(state.luts.firstOrNull { it.lumixLabSource?.code == item.code }, item)
    }
    var lastDetailCard by remember { mutableStateOf<MirrorLutCard?>(null) }
    val detailCard = resolvedDetailCard ?: lastDetailCard
    val duration = if (LocalMirrorReducedMotion.current) 0 else 420
    val columns = if (LocalConfiguration.current.screenWidthDp < 340 || LocalDensity.current.fontScale > 1.3f) 2 else 3
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.luts, subjectRevision) {
        try {
            localSubjects = workspace.processor.loadLutGallery(ProcessingCatalogue(state.luts, state.filmPresets, emptyList()))
                .entries.associate { it.id to it.subjects }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { workspace.notice(e.message ?: "LUT 分类读取失败") }
    }
    LaunchedEffect(browser.items, browser.query, browser.pageLoading) {
        if (!browser.pageLoading && browser.pageError == null && browser.query != null)
            pages[browser.query?.genreCode ?: "all"] = browser.items
    }
    LaunchedEffect(official, expanded) {
        if (official) {
            if (browser.genres.isEmpty() && !browser.genresLoading) workspace.officialLuts.loadGenres()
            val key = expanded ?: "all"
            workspace.selectOfficialLutCategory(key.takeUnless { it == "all" })
        }
    }
    LaunchedEffect(official, browser.genres) {
        if (official && browser.genres.isNotEmpty()) workspace.officialLuts.loadCategoryPreviews(workspace.processor.officialLutLanguage())
    }
    LaunchedEffect(requestedSelection, state.officialLutDownload, state.luts) {
        val code = requestedSelection ?: return@LaunchedEffect
        val downloaded = state.luts.firstOrNull { it.lumixLabSource?.code == code }
        if (downloaded != null && !state.busy) {
            requestedSelection = null; detailKey = null; workspace.officialLuts.closeDetail()
            onChoose(downloaded, requestedSecond)
        }
        else if (state.officialLutDownload?.code == code && state.officialLutDownload?.status in
            setOf(OfficialLutDownloadStatus.FAILED, OfficialLutDownloadStatus.CANCELLED)) requestedSelection = null
    }
    val categories = buildList {
        add(GalleryCategory("all", "全部", Color(0xFFF0CE6B)))
        if (official) browser.genres.forEachIndexed { i, genre -> add(GalleryCategory(genre.code, genre.title, categoryColour(i))) }
        else LutSubject.entries.forEachIndexed { i, subject -> add(GalleryCategory(subject.name, subject.label, categoryColour(i))) }
    }
    fun cardsFor(category: String): List<MirrorLutCard> = (if (official)
        (pages[category]?.takeIf { it.isNotEmpty() } ?: browser.categoryPreviews[category].orEmpty()).map { item ->
        MirrorLutCard(state.luts.firstOrNull { it.lumixLabSource?.code == item.code }, item)
    } else localCards.filter { category == "all" || localSubjects[it.local?.id].orEmpty().any { subject -> subject.name == category } })
        .filter { it.title.contains(query.trim(), true) || it.creator?.contains(query.trim(), true) == true }
    fun toggle(card: MirrorLutCard) {
        workspace.updatePersonal { current -> current.copy(
            favourites = if (card.key in current.favourites) current.favourites - card.key else current.favourites + card.key,
            savedLuts = card.official?.let { item -> (current.savedLuts + item).distinctBy { it.code } } ?: current.savedLuts) }
    }
    fun open(card: MirrorLutCard, location: String) {
        if (favouriteClosing) return
        lastDetailCard = card
        if (favourites) favouriteRevealPlayed = true
        detailKey = card.key; detailSnapshot = card.official?.detailSnapshot(); origin = location
    }
    LaunchedEffect(detailKey, state.recovering) {
        if (detailKey != null && !state.recovering && resolvedDetailCard == null) {
            detailKey = null; workspace.notice("这款本地 LUT 已不在目录中，请重新选择")
        }
        if (resolvedDetailCard != null) lastDetailCard = resolvedDetailCard
        if (detailKey != null) resolvedDetailCard?.official?.let { item ->
            // The official browser needs a language query before opening an offline remembered card.
            if (browser.query == null) workspace.refreshOfficialLuts()
            workspace.officialLuts.openDetail(item.code)
        }
    }
    fun closeDetail() { detailKey = null; requestedSelection = null; workspace.officialLuts.closeDetail() }
    BackHandler(detailOpen || favourites) { if (detailOpen) closeDetail() else favouriteClosing = true }
    LaunchedEffect(favouriteClosing) {
        if (favouriteClosing) {
            delay(if (duration == 0) 0 else 480)
            favourites = false; favouriteClosing = false
        }
    }
    LaunchedEffect(detailOpen, favourites) { onFullscreen(detailOpen || favourites) }
    DisposableEffect(Unit) { onDispose { onFullscreen(false) } }
    val storedOfficialCards = personal.savedLuts.map { item ->
        MirrorLutCard(state.luts.firstOrNull { it.lumixLabSource?.code == item.code }, item)
    }
    val favouriteCards = (storedOfficialCards + localCards).distinctBy { it.key }.filter { it.key in personal.favourites }
    SharedTransitionLayout {
        val shared = this
        AnimatedContent(targetState = when { detailOpen -> "detail"; favourites && !favouriteClosing -> "favourites"; else -> "gallery" },
            transitionSpec = {
                val circle = (initialState == "gallery" && targetState == "favourites") ||
                    (initialState == "favourites" && targetState == "gallery")
                if (circle) {
                    val layerOrder = if (targetState == "favourites") 1f else -1f
                    (fadeIn(tween(0)) togetherWith fadeOut(tween(0, delayMillis = if (duration == 0) 0 else 520)))
                        .apply { targetContentZIndex = layerOrder }
                } else fadeIn(tween(duration)) togetherWith fadeOut(tween(if (duration == 0) 0 else 280))
            }, label = "lut-morph") { page ->
            val visibility = this
            when (page) {
                "detail" -> detailCard?.let { card ->
                    LutDetail(card, origin, state, workspace, browser, personal, choosingSecond, shared, visibility,
                        onBack = ::closeDetail, onFavourite = { toggle(card) }, onSlot = { choosingSecond = it },
                        onSubjectsEdited = { subjectRevision++ }, onSelect = {
                            val asset = card.local ?: state.luts.firstOrNull { it.lumixLabSource?.code == card.official?.code }
                            if (asset != null) { closeDetail(); onChoose(asset, choosingSecond) }
                            else card.official?.let { item ->
                                if (workspace.downloadOfficialLut(item.code)) {
                                    requestedSecond = choosingSecond; requestedSelection = item.code
                                }
                            }
                        })
                }
                "favourites" -> FavouriteFan(favouriteCards, state, workspace, personal, shared, visibility,
                    initialPage = favouriteIndex, onPage = { favouriteIndex = it },
                    reveal = !favouriteRevealPlayed, onReveal = { favouriteRevealPlayed = true },
                    closing = favouriteClosing || !favourites, onClose = { favouriteClosing = true },
                    onOpen = ::open, onFavourite = ::toggle)
                else -> Box(Modifier.fillMaxSize()) {
                    val scroll = galleryScroll
                    var searchVisible by remember { mutableStateOf(true) }
                    LaunchedEffect(scroll.isScrollInProgress) {
                        if (scroll.isScrollInProgress) searchVisible = false else { delay(700); searchVisible = true }
                    }
                    Column(Modifier.fillMaxSize()) {
                    PageHeading("COLOUR / COLLECT", "LUT", onImportPhoto)
                    LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(bottom = 250.dp)) {
                        item {
                            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MirrorChoice(!official, { official = false; expanded = null }, label = { Text("我的 LUT") })
                                MirrorChoice(official, { official = true; expanded = null }, label = { Text("Lumix Lab") })
                            }
                            SlotStrip(state, choosingSecond) { choosingSecond = it }
                        }
                        val daily = (if (official) cardsFor("all") else localCards).let { cards ->
                            if (cards.isEmpty()) null else cards[Math.floorMod(java.time.LocalDate.now().toEpochDay(), cards.size.toLong()).toInt()]
                        }
                        if (daily != null && expanded == null) item {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                Text("今日推荐", fontFamily = MirrorFonts.ChineseEditorial, fontSize = 26.sp)
                                Polaroid(daily, "daily:${daily.key}", state, workspace, personal, shared, visibility,
                                    Modifier.fillMaxWidth().padding(top = 12.dp), large = false, recommendation = true,
                                    onClick = { open(daily, "daily:${daily.key}") }, onFavourite = { toggle(daily) })
                            }
                        }
                        if (official && (browser.genresError != null || browser.pageError != null)) item {
                            Column(Modifier.padding(20.dp)) {
                                Text(browser.genresError ?: browser.pageError.orEmpty(), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { workspace.officialLuts.loadGenres(); workspace.refreshOfficialLuts(expanded?.takeUnless { it == "all" }) }) {
                                    Text("重试目录")
                                }
                            }
                        }
                        if (official && browser.pageLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
                        categories.forEach { category ->
                            val focus = expanded == category.key
                            val showCards = expanded == null || focus
                            val cards = cardsFor(category.key)
                            item(key = "heading:${category.key}") {
                            Column(Modifier.fillMaxWidth().animateContentSize(tween(duration, easing = MirrorEase))
                                .background(category.colour.copy(alpha = if (focus) .92f else .82f))) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
                                    expanded = if (focus) null else category.key
                                }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(category.title, Modifier.weight(1f), fontFamily = MirrorFonts.ChineseEditorial,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 26.sp, color = Color(0xFF202321))
                                    Text(if (focus) "▴" else "▾", fontSize = 21.sp, color = Color(0xFF202321))
                                }
                                if (focus && cards.isEmpty()) Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                        Text(if (official) browser.categoryPreviewErrors[category.key]?.let { "例图暂不可用 · 点击分类重试" }
                                            ?: "正在读取这一类的创作者作品" else "这一类还没有 LUT。可在 LUT 介绍页为它设置题材分类。", color = tones.secondaryInk,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (showCards) {
                                val rows = cards.take(if (focus || query.isNotBlank()) cards.size else columns).chunked(columns)
                                items(rows, key = { row -> "grid:${category.key}:${row.first().key}" }) { row ->
                                        Row(Modifier.fillMaxWidth().animateItem(fadeInSpec = tween(duration), placementSpec = tween(duration, easing = MirrorEase))
                                            .background(category.colour.copy(alpha = if (focus) .92f else .82f))
                                            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            row.forEach { card ->
                                                val location = "${category.key}:${card.key}"
                                                key(card.key) {
                                                Polaroid(card, location, state, workspace, personal, shared, visibility, Modifier.weight(1f),
                                                    onClick = { open(card, location) }, onFavourite = { toggle(card) })
                                                }
                                            }
                                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                if (official && focus && browser.query?.genreCode == category.key.takeUnless { it == "all" } && browser.nextOffset != null)
                                    item(key = "more:${category.key}") {
                                        TextButton(modifier = Modifier.fillMaxWidth().background(category.colour), enabled = !browser.pageLoading,
                                            onClick = workspace.officialLuts::loadMore) { Text("加载更多作品") }
                                    }
                            }
                        }
                    }
                    }
                    AnimatedVisibility(searchVisible || query.isNotBlank(), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 184.dp),
                        enter = fadeIn(), exit = fadeOut()) {
                        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索名称与创作者") }, singleLine = true,
                            shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = tones.paperHighlight,
                                unfocusedContainerColor = tones.paperHighlight))
                    }
                    RoundAction("查看收藏", DotGlyph.STAR, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 136.dp),
                        onClick = { favouriteRevealPlayed = false; favouriteClosing = false; favourites = true })
                    RoundAction("导入或下载 LUT", DotGlyph.PLUS, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 136.dp),
                        onClick = { importMenu = true })
                }
            }
        }
    }
    if (importMenu) AlertDialog(onDismissRequest = { importMenu = false }, title = { Text("添加 LUT") }, text = {
        Column {
            TextButton(onClick = { importMenu = false; onImportLut() }) { Text("导入本地 .cube") }
            TextButton(onClick = { importMenu = false; official = true; expanded = null }) { Text("从 Lumix Lab 浏览与下载") }
        }
    }, confirmButton = { TextButton(onClick = { importMenu = false }) { Text("返回") } })
}

private fun categoryColour(index: Int) = listOf(Color(0xFFA4BAB7), Color(0xFFD3A4B8), Color(0xFFB1B7D5),
    Color(0xFFB8C49E), Color(0xFFD9B282), Color(0xFF9AAABD), Color(0xFFC1A9B7))[index % 7]

@Composable
private fun SlotStrip(state: WorkspaceState, second: Boolean, onSlot: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (slot in listOf(false, true)) {
            val id = if (slot) state.lutRecipe.second.id else state.lutRecipe.first.id
            MirrorChoice(second == slot, { onSlot(slot) }, enabled = !state.busy, modifier = Modifier.weight(1f), label = {
                Text("LUT ${if (slot) 2 else 1} · ${state.luts.firstOrNull { it.id == id }?.name ?: "选择"}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            })
        }
    }
}

@Composable
private fun Polaroid(card: MirrorLutCard, origin: String, state: WorkspaceState, workspace: WorkspaceViewModel,
    personal: MirrorPersonalState, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
    modifier: Modifier, large: Boolean = false, recommendation: Boolean = false, onClick: () -> Unit, onFavourite: () -> Unit,
    onColours: (LutExampleColours) -> Unit = {}) {
    val reportColours by rememberUpdatedState(onColours)
    val previewKey = remember(card.key, card.official?.thumbnail, card.local, if (card.official == null) state.current?.id else null, large) {
        listOf(card.key, card.official?.thumbnail?.path, if (card.official == null) card.local?.id else null,
            if (card.official == null) state.current?.id else null, large)
    }
    val cached = workspace.displayPreviews.peek(previewKey)
    var bitmap by remember(previewKey) { mutableStateOf(cached?.image) }
    var colours by remember(previewKey) { mutableStateOf(cached?.colours) }
    var error by remember(previewKey) { mutableStateOf<String?>(null) }
    var retry by rememberSaveable(card.key) { mutableIntStateOf(0) }
    var loading by remember(previewKey) { mutableStateOf(false) }
    LaunchedEffect(previewKey, retry) {
        loading = bitmap == null; error = null
        try {
            if (card.official != null) {
                val example = workspace.displayPreviews.load(previewKey) {
                    workspace.processor.officialLutCatalogue.example(card.official, if (large) 1280 else 320)
                        .let { MirrorPreview(it.image, it.colours) }
                }
                bitmap = example.image; colours = example.colours
                example.colours?.let(reportColours)
            } else if (card.local != null) {
                state.current?.let { source ->
                    val preview = workspace.displayPreviews.load(previewKey) {
                        workspace.processor.previewLutCard(source, card.local, if (large) 512 else 192)
                            .let { MirrorPreview(it.image, it.colours) }
                    }
                    bitmap = preview.image; colours = preview.colours
                    preview.colours?.let(reportColours)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "预览暂不可用" }
        catch (_: OutOfMemoryError) { error = "预览内存不足" }
        finally { loading = false }
    }
    val swatch = colours?.swatches?.firstOrNull()
    val background = swatch?.let { Color(it.argb) } ?: LocalFilmMaterials.current.paperHighlight
    val ink = swatch?.let { Color(it.titleTextArgb).copy(alpha = 1f) } ?: LocalFilmMaterials.current.ink
    val motionDuration = if (LocalMirrorReducedMotion.current) 0 else 420
    val morph = with(shared) { Modifier.sharedBounds(rememberSharedContentState(origin), visibility,
        boundsTransform = { _, _ -> tween(motionDuration, easing = MirrorEase) }) }
    val photoContent: @Composable (Modifier) -> Unit = { photoModifier ->
        Box(photoModifier.background(Color.Black.copy(.08f)), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), card.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("预览不可用", color = ink, fontSize = 11.sp)
                TextButton(onClick = { retry++ }) { Text("重试", color = ink) }
            } else if (bitmap == null) {
                if (loading && (card.official != null || state.current != null)) CircularProgressIndicator(Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
                else Text("导入照片\n查看效果", Modifier.padding(8.dp), color = ink, fontSize = 11.sp)
            }
        }
    }
    val favouriteContent: @Composable () -> Unit = {
        FavouriteAction(card.key in personal.favourites, card.title, ink, onFavourite, compact = !large && !recommendation)
    }
    if (recommendation) {
        Row(modifier.then(morph).clip(RoundedCornerShape(18.dp)).background(background)
            .clickable(role = Role.Button, onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            photoContent(Modifier.size(86.dp))
            Column(Modifier.weight(1f)) {
                Text(card.title, fontFamily = MirrorFonts.Handwriting, fontSize = 28.sp, color = ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(card.creator ?: "发现新的色彩", fontSize = 11.sp, color = ink)
            }
            favouriteContent()
        }
        return
    }
    Column(modifier.then(morph).clip(RoundedCornerShape(5.dp)).background(background)
        .clickable(role = Role.Button, onClick = onClick).padding(if (large) 12.dp else 5.dp)) {
        photoContent(Modifier.fillMaxWidth().aspectRatio(1f))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(card.title, Modifier.weight(1f).padding(start = 4.dp), fontFamily = MirrorFonts.Handwriting,
                fontSize = if (large) 28.sp else 14.sp, lineHeight = if (large) 32.sp else 17.sp,
                maxLines = if (large) 1 else 2, overflow = TextOverflow.Ellipsis, color = ink)
            favouriteContent()
        }
        if (large) Text(if (card.official != null) "创作者例图" else "当前照片 · 单 LUT 预览", color = ink,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FavouriteAction(selected: Boolean, title: String, ink: Color, onClick: () -> Unit, compact: Boolean = false) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    val colour by animateColorAsState(if (selected) Color(0xFFF5CC70) else ink.copy(.07f), tween(180), label = "favourite-colour")
    Box(modifier = Modifier.size(48.dp).clickable(role = Role.Button, onClick = {
        onClick(); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch { scale.snapTo(.82f); scale.animateTo(1.12f, spring(dampingRatio = .48f)); scale.animateTo(1f, tween(160)) }
    }).semantics {
            contentDescription = if (selected) "已收藏 $title，点击取消" else "收藏 $title"
            this.selected = selected
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(if (compact) 30.dp else 42.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .background(colour, CircleShape), contentAlignment = Alignment.Center) {
            DotIcon(DotGlyph.STAR, Modifier.size(if (compact) { if (selected) 19.dp else 17.dp } else { if (selected) 24.dp else 21.dp }),
                if (selected) Color(0xFF392C13) else ink.copy(.8f), filled = selected)
        }
    }
}

@Composable
private fun LutDetail(card: MirrorLutCard, origin: String, state: WorkspaceState, workspace: WorkspaceViewModel,
    browser: LumixLabBrowserState, personal: MirrorPersonalState, second: Boolean, shared: SharedTransitionScope,
    visibility: AnimatedVisibilityScope, onBack: () -> Unit, onFavourite: () -> Unit, onSlot: (Boolean) -> Unit,
    onSubjectsEdited: () -> Unit, onSelect: () -> Unit) {
    val tones = LocalFilmMaterials.current
    var avatar by remember(card.key) { mutableStateOf<Bitmap?>(null) }
    var subjects by remember(card.key) { mutableStateOf<Set<LutSubject>>(emptySet()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(card.key) {
        card.official?.creator?.let { creator ->
            try { avatar = workspace.processor.officialLutCatalogue.creatorAvatar(creator) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Display the creator's initial when no avatar can be loaded. */ }
        }
        card.local?.let { asset ->
            try { subjects = workspace.processor.loadLutGallery(
                ProcessingCatalogue(listOf(asset), emptyList(), emptyList())).entries.firstOrNull()?.subjects.orEmpty() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { workspace.notice(e.message ?: "分类读取失败") }
        }
    }
    Column(Modifier.fillMaxSize().background(tones.background)) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { RoundAction("返回 LUT 列表", DotGlyph.LEFT, onClick = onBack) }
            item { Polaroid(card, origin, state, workspace, personal, shared, visibility, Modifier.fillMaxWidth(), true,
                onClick = {}, onFavourite = onFavourite) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(tones.paperHighlight), contentAlignment = Alignment.Center) {
                        avatar?.let { Image(it.asImageBitmap(), card.creator, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            ?: Text(card.creator?.take(1) ?: "L", fontFamily = MirrorFonts.Editorial, fontSize = 24.sp)
                    }
                    Column {
                        Text(card.creator ?: if (card.local?.builtin == true) "内置 LUT" else "本地导入")
                        Text(if (card.official != null) "Lumix Lab 创作者" else "${card.local?.photoStyle ?: "JPEG"} 输入",
                            color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                val info = browser.detail?.takeIf { it.lut.code == card.official?.code }
                Text(info?.overview ?: card.local?.lumixLabSource?.overview ?: if (card.official != null)
                    if (browser.detailLoading) "正在加载创作者介绍…" else browser.detailError ?: "创作者暂未提供介绍。"
                    else "此 LUT 使用导入照片显示效果，可独立调整强度，并与第二款 LUT 叠加。")
                (info?.photoStyle ?: card.local?.photoStyle)?.let { Text("建议输入照片风格：$it", color = tones.secondaryInk) }
                if (card.official != null && browser.detailCode == card.official.code && browser.detailError != null)
                    TextButton(onClick = { workspace.officialLuts.openDetail(card.official.code) }) { Text("重试加载介绍") }
            }
            if (card.local != null) item {
                Text("题材分类", style = MaterialTheme.typography.titleSmall)
                LutSubject.entries.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { subject -> MirrorChoice(subject in subjects, onClick = {
                        val next = if (subject in subjects) subjects - subject else subjects + subject
                        scope.launch {
                            try { workspace.processor.setLutSubjects(card.local, next); subjects = next; onSubjectsEdited() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { workspace.notice(e.message ?: "分类未保存") }
                        }
                    }, label = { Text(subject.label) }) }
                } }
            }
            item { SlotStrip(state, second, onSlot) }
            state.officialLutDownload?.takeIf { it.code == card.official?.code }?.let { download -> item {
                Text(when (download.status) { OfficialLutDownloadStatus.RUNNING -> "正在下载与导入…"
                    OfficialLutDownloadStatus.SAVED -> "已保存到我的 LUT"
                    OfficialLutDownloadStatus.CANCELLED -> "下载已取消"
                    OfficialLutDownloadStatus.FAILED -> download.error ?: "下载失败，可重试" })
                if (download.status == OfficialLutDownloadStatus.RUNNING) TextButton(onClick = workspace::cancelOfficialLutDownload) { Text("取消下载") }
            } }
        }
        MirrorButton(enabled = !state.busy && state.officialLutDownload?.status != OfficialLutDownloadStatus.RUNNING,
            onClick = onSelect, modifier = Modifier.fillMaxWidth().padding(20.dp).height(56.dp), shape = RoundedCornerShape(28.dp)) {
            Text(if (card.local == null) "下载并选择该 LUT →" else "选择该 LUT · 槽 ${if (second) 2 else 1} →")
        }
    }
}

@Composable
private fun FavouriteFan(cards: List<MirrorLutCard>, state: WorkspaceState, workspace: WorkspaceViewModel,
    personal: MirrorPersonalState, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
    initialPage: Int, onPage: (Int) -> Unit,
    reveal: Boolean, onReveal: () -> Unit,
    closing: Boolean,
    onClose: () -> Unit, onOpen: (MirrorLutCard, String) -> Unit, onFavourite: (MirrorLutCard) -> Unit) {
    val tones = LocalFilmMaterials.current
    val colourHints = remember { mutableStateMapOf<String, LutExampleColours>() }
    var entered by remember { mutableStateOf(!reveal) }
    LaunchedEffect(Unit) { entered = true; onReveal() }
    val progress = animateFloatAsState(if (entered && !closing) 1f else 0f,
        tween(if (LocalMirrorReducedMotion.current) 0 else 480, easing = MirrorEase), label = "favourites-circle")
    val originPadding = with(LocalDensity.current) { 46.dp.toPx() }
    val originBottom = with(LocalDensity.current) { 162.dp.toPx() }
    val dark = isSystemInDarkTheme()
    val palette = if (dark) listOf(Color(0xFF162E4A),Color(0xFF14334A),Color(0xFF4D8395))
        else listOf(Color(0xFFE8E7DE),Color(0xFFCBD9D8),Color(0xFFAFC8D2))
    Box(Modifier.fillMaxSize().graphicsLayer {
        val p = progress.value
        clip = true
        shape = GenericShape { size, _ ->
            val radius = hypot(size.width, size.height) * p
            val cx = originPadding; val cy = size.height - originBottom
            addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
        }
    }.background(Brush.verticalGradient(palette))) {
        LiquidMaterial(Modifier.fillMaxSize(), palette, moving = false)
        Column(Modifier.fillMaxSize()) {
            PageHeading("YOUR COLOUR COLLECTION", "收藏")
            if (cards.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                DotIcon(DotGlyph.STAR, Modifier.size(52.dp), tones.ink)
                Text("留下喜欢的色彩", Modifier.padding(top = 24.dp), fontFamily = MirrorFonts.ChineseEditorial, fontSize = 26.sp)
                Text("点击拍立得上的星星，收藏会出现在这里。", Modifier.padding(top = 12.dp), color = tones.secondaryInk)
            }
            else {
                val pager = rememberPagerState(initialPage = if (initialPage < 0) minOf(2, cards.size / 2)
                    else initialPage.coerceIn(0, cards.lastIndex), pageCount = { cards.size })
                LaunchedEffect(cards.map { it.key }) {
                    if (pager.currentPage > cards.lastIndex) pager.scrollToPage(cards.lastIndex)
                }
                val haptics = LocalHapticFeedback.current
                var last by remember { mutableIntStateOf(pager.currentPage) }
                LaunchedEffect(pager.currentPage) {
                    if (last != pager.currentPage) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    last = pager.currentPage
                    onPage(pager.currentPage)
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val cardWidth = (maxWidth * .38f).coerceIn(138.dp, 190.dp)
                HorizontalPager(pager, Modifier.fillMaxWidth().height(cardWidth + 144.dp).align(Alignment.Center),
                    pageSize = PageSize.Fixed(cardWidth), contentPadding = PaddingValues(horizontal = (maxWidth - cardWidth) / 2),
                    pageSpacing = -cardWidth * .47f, beyondViewportPageCount = 2, key = { cards[it].key }) { index ->
                    val card = cards[index]
                    val origin = "favourite:${card.key}"
                    Polaroid(card, origin, state, workspace, personal, shared, visibility,
                        Modifier.fillMaxWidth().zIndex(4f - kotlin.math.abs(pager.currentPage - index).toFloat())
                            .graphicsLayer {
                                val distance = (pager.currentPage - index) + pager.currentPageOffsetFraction
                                rotationZ = -distance.coerceIn(-2f, 2f) * 10f
                                rotationY = distance.coerceIn(-2f, 2f) * 9f
                                translationY = kotlin.math.abs(distance).coerceAtMost(3f) * 36.dp.toPx()
                                scaleX = 1f - kotlin.math.abs(distance).coerceIn(0f, 2f) * .07f; scaleY = scaleX
                                cameraDistance = 12f * density
                            }.shadow(12.dp,RoundedCornerShape(5.dp)),
                        onClick = { if (!closing) onOpen(card, origin) }, onFavourite = { if (!closing) onFavourite(card) },
                        onColours = { colourHints[card.key] = it })
                }
                }
                val selected = cards.getOrNull(pager.settledPage)
                Text(selected?.title.orEmpty(), Modifier.align(Alignment.CenterHorizontally), fontFamily = MirrorFonts.Handwriting,
                    fontSize = 32.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FavouriteColourLens(colourHints[selected?.key], palette, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
                    selected?.title.orEmpty()) { if (!closing && selected != null) onOpen(selected, "favourite:${selected.key}") }
                Text("${pager.currentPage + 1} / ${cards.size} · 左右滑动，点按查看", Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                    color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(136.dp))
        }
        RoundAction("退出收藏", DotGlyph.CLOSE, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 136.dp), onClick = onClose)
    }
}

@Composable
private fun FavouriteColourLens(colours: LutExampleColours?, fallback: List<Color>, modifier: Modifier,
    title: String, onClick: () -> Unit) {
    val rawHints = colours?.swatches?.take(3)?.map { Color(it.argb) }?.takeIf { it.isNotEmpty() } ?: fallback
    val hints = if (rawHints.size == 1) listOf(rawHints.single(), rawHints.single()) else rawHints
    Box(modifier.size(96.dp).clip(CircleShape).background(Brush.linearGradient(hints))
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "查看 $title 的介绍" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(.48f),Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width*.26f,size.height*.20f),radius=size.width*.8f))
            drawCircle(Brush.radialGradient(listOf(Color.Transparent,Color.Black.copy(.18f)),radius=size.width*.72f))
        }
        DotIcon(DotGlyph.RIGHT, Modifier.size(26.dp), Color.White)
    }
}
