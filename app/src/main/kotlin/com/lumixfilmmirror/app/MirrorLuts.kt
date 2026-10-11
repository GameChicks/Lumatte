@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.lumixfilmmirror.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
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
    var official by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var choosingSecond by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialSecond) { choosingSecond = initialSecond }
    var favourites by rememberSaveable { mutableStateOf(false) }
    var detailOpen by remember { mutableStateOf(false) }
    var detailCard by remember { mutableStateOf<MirrorLutCard?>(null) }
    var origin by remember { mutableStateOf("") }
    var importMenu by remember { mutableStateOf(false) }
    var requestedSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var favouriteIndex by rememberSaveable { mutableIntStateOf(0) }
    val galleryScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val pages = remember { mutableStateMapOf<String, List<LumixLabLut>>() }
    var localSubjects by remember { mutableStateOf<Map<String, Set<LutSubject>>>(emptyMap()) }
    val localCards = state.luts.map { it.galleryCard() }
    val duration = if (LocalMirrorReducedMotion.current) 0 else 420
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.luts, detailOpen) {
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
            if (key !in pages) workspace.refreshOfficialLuts(genreCode = key.takeUnless { it == "all" })
        }
    }
    LaunchedEffect(requestedSelection, state.officialLutDownload, state.luts) {
        val code = requestedSelection ?: return@LaunchedEffect
        val downloaded = state.luts.firstOrNull { it.lumixLabSource?.code == code }
        if (downloaded != null && !state.busy) { requestedSelection = null; onChoose(downloaded, choosingSecond) }
        else if (state.officialLutDownload?.code == code && state.officialLutDownload?.status in
            setOf(OfficialLutDownloadStatus.FAILED, OfficialLutDownloadStatus.CANCELLED)) requestedSelection = null
    }
    val categories = buildList {
        add(GalleryCategory("all", "全部", Color(0xFFF0CE6B)))
        if (official) browser.genres.forEachIndexed { i, genre -> add(GalleryCategory(genre.code, genre.title, categoryColour(i))) }
        else LutSubject.entries.forEachIndexed { i, subject -> add(GalleryCategory(subject.name, subject.label, categoryColour(i))) }
    }
    fun cardsFor(category: String): List<MirrorLutCard> = (if (official) pages[category].orEmpty().map { item ->
        MirrorLutCard(state.luts.firstOrNull { it.lumixLabSource?.code == item.code }, item)
    } else localCards.filter { category == "all" || localSubjects[it.local?.id].orEmpty().any { subject -> subject.name == category } })
        .filter { it.title.contains(query.trim(), true) || it.creator?.contains(query.trim(), true) == true }
    fun toggle(card: MirrorLutCard) {
        workspace.updatePersonal { current -> current.copy(
            favourites = if (card.key in current.favourites) current.favourites - card.key else current.favourites + card.key,
            savedLuts = card.official?.let { item -> (current.savedLuts + item).distinctBy { it.code } } ?: current.savedLuts) }
    }
    fun open(card: MirrorLutCard, location: String) {
        detailCard = card; origin = location; detailOpen = true
        card.official?.let { item ->
            // The official browser needs a language query before opening an offline remembered card.
            if (browser.query == null) workspace.refreshOfficialLuts()
            workspace.officialLuts.openDetail(item.code)
        }
    }
    fun closeDetail() { detailOpen = false; requestedSelection = null; workspace.officialLuts.closeDetail() }
    BackHandler(detailOpen || favourites) { if (detailOpen) closeDetail() else favourites = false }
    LaunchedEffect(detailOpen, favourites) { onFullscreen(detailOpen || favourites) }
    DisposableEffect(Unit) { onDispose { onFullscreen(false) } }
    val storedOfficialCards = personal.savedLuts.map { item ->
        MirrorLutCard(state.luts.firstOrNull { it.lumixLabSource?.code == item.code }, item)
    }
    val favouriteCards = (storedOfficialCards + localCards).distinctBy { it.key }.filter { it.key in personal.favourites }
    SharedTransitionLayout {
        val shared = this
        AnimatedContent(targetState = when { detailOpen -> "detail"; favourites -> "favourites"; else -> "gallery" },
            transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(if (duration == 0) 0 else 280)) }, label = "lut-morph") { page ->
            val visibility = this
            when (page) {
                "detail" -> detailCard?.let { card ->
                    LutDetail(card, origin, state, workspace, browser, personal, choosingSecond, shared, visibility,
                        onBack = ::closeDetail, onFavourite = { toggle(card) }, onSlot = { choosingSecond = it }, onSelect = {
                            val asset = card.local ?: state.luts.firstOrNull { it.lumixLabSource?.code == card.official?.code }
                            if (asset != null) onChoose(asset, choosingSecond)
                            else card.official?.let { item ->
                                if (workspace.downloadOfficialLut(item.code)) requestedSelection = item.code
                            }
                        })
                }
                "favourites" -> FavouriteFan(favouriteCards, state, workspace, personal, shared, visibility,
                    initialPage = favouriteIndex, onPage = { favouriteIndex = it },
                    onClose = { favourites = false }, onOpen = ::open, onFavourite = ::toggle)
                else -> Box(Modifier.fillMaxSize()) {
                    val scroll = galleryScroll
                    var searchVisible by remember { mutableStateOf(true) }
                    LaunchedEffect(scroll.isScrollInProgress) {
                        if (scroll.isScrollInProgress) searchVisible = false else { delay(700); searchVisible = true }
                    }
                    LazyColumn(state = scroll, contentPadding = PaddingValues(bottom = 250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { PageHeading("COLOUR / COLLECT", "LUT", onImportPhoto) }
                        item {
                            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!official, { official = false; expanded = null }, label = { Text("我的 LUT") })
                                FilterChip(official, { official = true; expanded = null }, label = { Text("Lumix Lab") })
                            }
                            SlotStrip(state, choosingSecond) { choosingSecond = it }
                        }
                        val daily = (if (official) cardsFor("all") else localCards).let { cards ->
                            if (cards.isEmpty()) null else cards[Math.floorMod(java.time.LocalDate.now().toEpochDay(), cards.size.toLong()).toInt()]
                        }
                        if (daily != null) item {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                Text("今日推荐", fontFamily = MirrorFonts.Dots, fontSize = 24.sp)
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
                        categories.forEach { category -> item(key = category.key) {
                            val focus = expanded == category.key
                            val showCards = expanded == null || focus
                            val cards = cardsFor(category.key)
                            Column(Modifier.fillMaxWidth().animateContentSize(tween(duration, easing = MirrorEase))
                                .background(category.colour.copy(alpha = if (focus) .32f else .16f))) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
                                    expanded = if (focus) null else category.key
                                }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(category.title, Modifier.weight(1f), fontFamily = MirrorFonts.Body, fontSize = 23.sp)
                                    Text(if (focus) "−" else "+", fontFamily = MirrorFonts.Dots, fontSize = 24.sp)
                                }
                                if (showCards) {
                                    if (cards.isEmpty()) Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                        Text(if (official) "展开分类加载创作者作品" else "此分类还没有 LUT", color = tones.secondaryInk,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    cards.take(if (focus || query.isNotBlank()) 6 else 3).chunked(3).forEach { row ->
                                        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            row.forEach { card ->
                                                val location = "${category.key}:${card.key}"
                                                Polaroid(card, location, state, workspace, personal, shared, visibility, Modifier.weight(1f),
                                                    onClick = { open(card, location) }, onFavourite = { toggle(card) })
                                            }
                                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                    if (focus && cards.size > 6) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(cards.drop(6), key = { it.key }) { card ->
                                            val location = "more:${category.key}:${card.key}"
                                            Polaroid(card, location, state, workspace, personal, shared, visibility, Modifier.width(112.dp),
                                                onClick = { open(card, location) }, onFavourite = { toggle(card) })
                                        }
                                    }
                                    if (official && focus && browser.query?.genreCode == category.key.takeUnless { it == "all" } && browser.nextOffset != null)
                                        TextButton(enabled = !browser.pageLoading, onClick = workspace.officialLuts::loadMore) { Text("加载更多作品") }
                                }
                            }
                        } }
                    }
                    AnimatedVisibility(searchVisible || query.isNotBlank(), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 184.dp),
                        enter = fadeIn(), exit = fadeOut()) {
                        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索名称与创作者") }, singleLine = true,
                            shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = tones.paperHighlight,
                                unfocusedContainerColor = tones.paperHighlight))
                    }
                    RoundAction("查看收藏", DotGlyph.STAR, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 116.dp),
                        onClick = { favourites = true })
                    RoundAction("导入或下载 LUT", DotGlyph.PLUS, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 116.dp),
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (slot in listOf(false, true)) {
            val id = if (slot) state.lutRecipe.second.id else state.lutRecipe.first.id
            FilterChip(second == slot, { onSlot(slot) }, modifier = Modifier.weight(1f), label = {
                Text("LUT ${if (slot) 2 else 1} · ${state.luts.firstOrNull { it.id == id }?.name ?: "选择"}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            })
        }
    }
}

@Composable
private fun Polaroid(card: MirrorLutCard, origin: String, state: WorkspaceState, workspace: WorkspaceViewModel,
    personal: MirrorPersonalState, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
    modifier: Modifier, large: Boolean = false, recommendation: Boolean = false, onClick: () -> Unit, onFavourite: () -> Unit) {
    var bitmap by remember(card.key, state.current?.id, large) { mutableStateOf<Bitmap?>(null) }
    var colours by remember(card.key, state.current?.id) { mutableStateOf<LutExampleColours?>(null) }
    var error by remember(card.key, state.current?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(card.key, state.current?.id, large) {
        try {
            if (card.official != null) {
                val example = workspace.processor.officialLutCatalogue.example(card.official, if (large) 1280 else 320)
                bitmap = example.image; colours = example.colours
            } else if (card.local != null) {
                state.current?.let { source ->
                    val preview = workspace.processor.previewLutCard(source, card.local, if (large) 512 else 192)
                    bitmap = preview.image; colours = preview.colours
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "预览暂不可用" }
        catch (_: OutOfMemoryError) { error = "预览内存不足" }
    }
    val swatch = colours?.swatches?.firstOrNull()
    val background = swatch?.let { Color(it.argb) } ?: LocalFilmMaterials.current.paperHighlight
    val ink = swatch?.let { Color(it.titleTextArgb) } ?: LocalFilmMaterials.current.ink
    val motionDuration = if (LocalMirrorReducedMotion.current) 0 else 420
    val morph = with(shared) { Modifier.sharedBounds(rememberSharedContentState(origin), visibility,
        boundsTransform = { _, _ -> tween(motionDuration, easing = MirrorEase) }) }
    val photoContent: @Composable (Modifier) -> Unit = { photoModifier ->
        Box(photoModifier.background(Color.Black.copy(.08f)), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), card.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Text(if (error != null) "预览不可用" else if (card.official != null) "加载例图…" else "导入照片\n查看效果",
                    Modifier.padding(8.dp), color = ink, fontSize = 11.sp)
        }
    }
    val favouriteContent: @Composable () -> Unit = {
        IconButton(onClick = onFavourite, modifier = Modifier.size(48.dp).semantics {
            contentDescription = if (card.key in personal.favourites) "取消收藏 ${card.title}" else "收藏 ${card.title}"
            selected = card.key in personal.favourites
        }) { DotIcon(DotGlyph.STAR, Modifier.size(if (large) 24.dp else 17.dp),
            if (card.key in personal.favourites) ink else ink.copy(.45f)) }
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
                fontSize = if (large) 28.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink)
            favouriteContent()
        }
        if (large) Text(if (card.official != null) "创作者例图" else "当前照片 · 单 LUT 预览", color = ink,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LutDetail(card: MirrorLutCard, origin: String, state: WorkspaceState, workspace: WorkspaceViewModel,
    browser: LumixLabBrowserState, personal: MirrorPersonalState, second: Boolean, shared: SharedTransitionScope,
    visibility: AnimatedVisibilityScope, onBack: () -> Unit, onFavourite: () -> Unit, onSlot: (Boolean) -> Unit, onSelect: () -> Unit) {
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
            }
            if (card.local != null) item {
                Text("题材分类", style = MaterialTheme.typography.titleSmall)
                LutSubject.entries.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { subject -> FilterChip(subject in subjects, onClick = {
                        val next = if (subject in subjects) subjects - subject else subjects + subject
                        scope.launch {
                            try { workspace.processor.setLutSubjects(card.local, next); subjects = next }
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
        Button(enabled = !state.busy && state.officialLutDownload?.status != OfficialLutDownloadStatus.RUNNING,
            onClick = onSelect, modifier = Modifier.fillMaxWidth().padding(20.dp).height(56.dp), shape = RoundedCornerShape(28.dp)) {
            Text(if (card.local == null) "下载并选择该 LUT →" else "选择该 LUT · 槽 ${if (second) 2 else 1} →")
        }
    }
}

@Composable
private fun FavouriteFan(cards: List<MirrorLutCard>, state: WorkspaceState, workspace: WorkspaceViewModel,
    personal: MirrorPersonalState, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
    initialPage: Int, onPage: (Int) -> Unit,
    onClose: () -> Unit, onOpen: (MirrorLutCard, String) -> Unit, onFavourite: (MirrorLutCard) -> Unit) {
    val tones = LocalFilmMaterials.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(if (entered) 1f else 0f,
        tween(if (LocalMirrorReducedMotion.current) 0 else 480, easing = MirrorEase), label = "favourites-circle")
    val originPadding = with(LocalDensity.current) { 46.dp.toPx() }
    val shape = GenericShape { size, _ ->
        val radius = hypot(size.width, size.height) * progress
        val cx = originPadding; val cy = size.height - originPadding
        addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
    }
    Box(Modifier.fillMaxSize().clip(shape).background(Brush.verticalGradient(listOf(tones.paperHighlight, tones.background)))) {
        Column(Modifier.fillMaxSize()) {
            PageHeading("YOUR COLOUR COLLECTION", "收藏")
            if (cards.isEmpty()) Text("还没有收藏。点击任意拍立得上的星星，留下喜欢的色彩。", Modifier.padding(24.dp))
            else {
                val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, cards.lastIndex), pageCount = { cards.size })
                val haptics = LocalHapticFeedback.current
                var last by remember { mutableIntStateOf(pager.currentPage) }
                LaunchedEffect(pager.currentPage) {
                    if (last != pager.currentPage) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    last = pager.currentPage
                    onPage(pager.currentPage)
                }
                HorizontalPager(pager, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 48.dp),
                    pageSpacing = (-12).dp, beyondViewportPageCount = 1) { index ->
                    val distance = (pager.currentPage - index) + pager.currentPageOffsetFraction
                    val card = cards[index]
                    val origin = "favourite:${card.key}"
                    Polaroid(card, origin, state, workspace, personal, shared, visibility,
                        Modifier.fillMaxWidth().graphicsLayer {
                            rotationZ = distance.coerceIn(-1f, 1f) * 12f
                            translationY = kotlin.math.abs(distance) * 28.dp.toPx()
                            scaleX = 1f - kotlin.math.abs(distance).coerceIn(0f, 1f) * .15f; scaleY = scaleX
                        }, onClick = { onOpen(card, origin) }, onFavourite = { onFavourite(card) })
                }
                Text("${pager.currentPage + 1} / ${cards.size} · 左右滑动选择", Modifier.align(Alignment.CenterHorizontally), color = tones.secondaryInk)
            }
            Spacer(Modifier.height(128.dp))
        }
        RoundAction("退出收藏", DotGlyph.CLOSE, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 116.dp), onClick = onClose)
    }
}
