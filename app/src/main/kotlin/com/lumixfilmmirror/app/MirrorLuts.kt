@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.lumixfilmmirror.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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

internal data class MirrorLutCard(val local: LutAsset? = null, val official: LumixLabLut? = null, val photoPreview:Boolean=false) {
    val key: String get() = official?.let { "official:${it.code}" }
        ?: local?.lumixLabSource?.let { "official:${it.code}" } ?: requireNotNull(local).id
    val title: String get() = official?.title ?: requireNotNull(local).name
    val creator: String? get() = official?.creator?.name ?: local?.lumixLabSource?.creatorName
}
private data class GalleryCategory(val key: String, val title: String, val colour: Color)

internal fun LutAsset.galleryCard(): MirrorLutCard {
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
    onImmersive: (Boolean) -> Unit = {},
    onFullscreen: (Boolean) -> Unit) {
    val browser by workspace.officialLuts.state.collectAsState()
    val tones = LocalFilmMaterials.current
    var official by rememberSaveable { mutableStateOf(true) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchedOfficial by remember { mutableStateOf<List<LumixLabLut>>(emptyList()) }
    var globalSearching by remember { mutableStateOf(false) }
    var globalSearchProblem by remember { mutableStateOf<String?>(null) }
    var globalSearchComplete by remember { mutableStateOf(false) }
    var searchEditing by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    var choosingSecond by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialSecond) { choosingSecond = initialSecond }
    var favourites by rememberSaveable { mutableStateOf(false) }
    var discovery by rememberSaveable { mutableStateOf<String?>(null) }
    val discoveryState=rememberSaveableStateHolder()
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
    val detailCard = (resolvedDetailCard ?: lastDetailCard)?.let {
        if(origin.startsWith("recommendation:") || (origin.startsWith("explore:") && it.local!=null && state.current!=null))it.copy(photoPreview=true) else it
    }
    val duration = if (LocalMirrorReducedMotion.current) 0 else 420
    val columns = if (LocalConfiguration.current.screenWidthDp < 340 || LocalDensity.current.fontScale > 1.3f) 2 else 3
    val scope = rememberCoroutineScope()
    LaunchedEffect(official,query.trim()) {
        if(!official || query.isBlank()) { globalSearching=false;return@LaunchedEffect }
        delay(350);globalSearching=true;globalSearchProblem=null
        try {
            val index=workspace.processor.officialLutExplorer.index(workspace.processor.officialLutLanguage()) {
                searchedOfficial=it.items;globalSearchComplete=it.complete
            }
            globalSearchComplete=index.complete;globalSearchProblem=index.problem
        } catch(error:CancellationException) { throw error }
        catch(_:Exception) { globalSearchProblem="Lab 全库检索暂不可用，当前结果可能不完整。" }
        finally { globalSearching=false }
    }
    LaunchedEffect(discovery) { if(discovery!=null) { searchEditing=false;focusManager.clearFocus() } }
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
        (if(query.isNotBlank()) { if(category=="all")searchedOfficial else emptyList() }
            else pages[category]?.takeIf { it.isNotEmpty() } ?: browser.categoryPreviews[category].orEmpty()).map { item ->
        MirrorLutCard(state.luts.firstOrNull { it.lumixLabSource?.code == item.code }, item)
    } else localCards.filter { category == "all" || localSubjects[it.local?.id].orEmpty().any { subject -> subject.name == category } })
        .filter { it.title.contains(query.trim(), true) || it.creator?.contains(query.trim(), true) == true }
    fun toggle(card: MirrorLutCard) {
        workspace.updatePersonal { current -> current.copy(
            favourites = if (card.key in current.favourites) current.favourites - card.key else current.favourites + card.key,
            savedLuts = card.official?.let { item -> (current.savedLuts + item).distinctBy { it.code } } ?: current.savedLuts) }
    }
    fun open(card: MirrorLutCard, location: String) {
        searchEditing = false; focusManager.clearFocus()
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
    BackHandler(detailOpen || favourites || discovery!=null) { if (detailOpen) closeDetail() else if(discovery!=null)discovery=null else favourites = false }
    BackHandler(searchEditing && !detailOpen && !favourites) { searchEditing = false; focusManager.clearFocus() }
    LaunchedEffect(detailOpen, favourites, searchEditing,discovery) { onFullscreen(detailOpen || favourites || searchEditing || discovery!=null) }
    LaunchedEffect(detailOpen,discovery,favourites) { onImmersive((favourites || discovery=="recommendations") && !detailOpen) }
    DisposableEffect(Unit) { onDispose { onFullscreen(false); onImmersive(false) } }
    val storedOfficialCards = personal.savedLuts.map { item ->
        MirrorLutCard(state.luts.firstOrNull { it.lumixLabSource?.code == item.code }, item)
    }
    val favouriteCards = (storedOfficialCards + localCards).distinctBy { it.key }.filter { it.key in personal.favourites }
    SharedTransitionLayout {
        val shared = this
        AnimatedContent(targetState = when { detailOpen -> "detail"; discovery!=null -> discovery!!; favourites -> "favourites"; else -> "gallery" },
            transitionSpec = {
                val circle = (initialState == "gallery" && targetState == "favourites") ||
                    (initialState == "favourites" && targetState == "gallery")
                if (circle) {
                    // Keep the collection above the gallery in both directions.
                    // Its visibility transition owns the circular clip and its lifetime.
                    (fadeIn(tween(0)) togetherWith fadeOut(tween(0, delayMillis = if (duration == 0) 0 else 480)))
                        .apply { targetContentZIndex = if (targetState == "favourites") 1f else 0f }
                } else fadeIn(tween(duration)) togetherWith fadeOut(tween(if (duration == 0) 0 else 280))
            }, label = "lut-morph") { page ->
            val visibility = this
            when (page) {
                "explore" -> discoveryState.SaveableStateProvider("explore") {
                    val knownOfficial=(pages.values.flatten()+browser.categoryPreviews.values.flatten()+personal.savedLuts)
                        .distinctBy { it.code }.map { item -> MirrorLutCard(state.luts.firstOrNull { it.lumixLabSource?.code==item.code },item) }
                    MirrorColorExplorer((localCards+knownOfficial).distinctBy { it.key }.take(256)
                        .map { if(state.current!=null && it.local!=null)it.copy(photoPreview=true) else it },state,workspace,personal,
                        shared,visibility,onBack={discovery=null},onOpen=::open,onFavourite=::toggle)
                }
                "recommendations" -> discoveryState.SaveableStateProvider("recommendations") {
                    MirrorLutRecommendations(state,workspace,personal,shared,visibility,
                        onBack={discovery=null},onImport=onImportPhoto,onOpen=::open,onFavourite=::toggle,
                        onChoose={asset -> discovery=null; onChoose(asset,choosingSecond)})
                }
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
                    closing = !favourites, onClose = { favourites = false },
                    onOpen = ::open, onFavourite = ::toggle)
                else -> Box(Modifier.fillMaxSize()) {
                    val galleryPixels = rememberGraphicsLayer()
                    val galleryOrigin = remember { mutableStateOf(Offset.Zero) }
                    val scroll = galleryScroll
                    var searchVisible by remember { mutableStateOf(true) }
                    LaunchedEffect(scroll.isScrollInProgress) {
                        if (scroll.isScrollInProgress) searchVisible = false else { delay(700); searchVisible = true }
                    }
                    Column(Modifier.fillMaxSize().statusBarsPadding().onGloballyPositioned { galleryOrigin.value = it.positionInRoot() }
                        .drawWithContent { galleryPixels.record { this@drawWithContent.drawContent() }; drawLayer(galleryPixels) }) {
                    PageHeading("COLOR / COLLECT", "LUT") { searchEditing = false; focusManager.clearFocus(); onImportPhoto() }
                    val bandColours = categories.associate { category ->
                        category.key to key(category.key) { animateColorAsState(category.colour.copy(alpha = 1f),
                            tween(duration, easing = MirrorEase), label = "category-paper:${category.key}") }
                    }
                    LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(bottom = 250.dp)) {
                        item {
                            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MirrorChoice(!official, { official = false; expanded = null }, label = { Text("我的 LUT") })
                                MirrorChoice(official, { official = true; expanded = null }, label = { Text("Lumix Lab") })
                            }
                            SlotStrip(state, choosingSecond) { choosingSecond = it }
                            if(state.photos.isNotEmpty())Column(Modifier.padding(horizontal=20.dp)) { PhotoManagementActions(state,workspace) }
                            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                MirrorChoice(false,{discovery="explore"},Modifier.weight(1f),label={Text("色彩探索 ↗")})
                                MirrorChoice(false,{discovery="recommendations"},Modifier.weight(1f),label={Text("智能推荐 ↗")})
                            }
                        }
                        val daily = (if (official) cardsFor("all") else localCards).let { cards ->
                            if (cards.isEmpty()) null else cards[Math.floorMod(java.time.LocalDate.now().toEpochDay(), cards.size.toLong()).toInt()]
                        }
                        if (daily != null && expanded == null && query.isBlank()) item {
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
                        if(official && query.isNotBlank())item {
                            Column(Modifier.padding(horizontal=20.dp,vertical=10.dp)) {
                                Text("全 Lab 检索 · 已读 ${searchedOfficial.size} 款"+if(globalSearching)" · 正在继续" else if(globalSearchComplete)" · 目录已读完" else " · 未覆盖全库",style=MaterialTheme.typography.bodySmall)
                                if(globalSearching)LinearProgressIndicator(Modifier.fillMaxWidth())
                                globalSearchProblem?.let { Text(it,color=tones.secondaryInk) }
                            }
                        }
                        categories.filter { !official || query.isBlank() || it.key=="all" }.forEach { category ->
                            val focus = expanded == category.key
                            val showCards = query.isNotBlank() || expanded == null || focus
                            val cards = cardsFor(category.key)
                            val bandColour = bandColours.getValue(category.key)
                            item(key = "heading:${category.key}") {
                            val arrowRotation = animateFloatAsState(if (focus) 180f else 0f,
                                tween(duration, easing = MirrorEase), label = "category-arrow:${category.key}")
                            Column(Modifier.fillMaxWidth().animateItem(fadeInSpec = tween(duration),
                                placementSpec = tween(duration, easing = MirrorEase))
                                .animateContentSize(tween(duration, easing = MirrorEase)).drawBehind { drawRect(bandColour.value) }) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
                                    expanded = if (focus) null else category.key
                                }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(category.title, Modifier.weight(1f), fontFamily = MirrorFonts.ChineseEditorial,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 26.sp, color = Color(0xFF202321))
                                    Text("▾", Modifier.graphicsLayer { rotationZ = arrowRotation.value }, fontSize = 21.sp, color = Color(0xFF202321))
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
                                            .drawBehind { drawRect(bandColour.value) }
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
                                if (official && query.isBlank() && focus && browser.query?.genreCode == category.key.takeUnless { it == "all" } && browser.nextOffset != null)
                                    item(key = "more:${category.key}") {
                                        TextButton(modifier = Modifier.fillMaxWidth().background(category.colour), enabled = !browser.pageLoading,
                                            onClick = workspace.officialLuts::loadMore) { Text("加载更多作品") }
                                    }
                            }
                        }
                    }
                    }
                    AnimatedVisibility(searchVisible || query.isNotBlank() || searchEditing,
                        modifier = Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = if (searchEditing) 20.dp else 136.dp),
                        enter = fadeIn(), exit = fadeOut()) {
                        if (searchEditing) {
                            val searchFocus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { searchFocus.requestFocus() }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MirrorTextField(query, { query = it }, "名称或创作者", Modifier.weight(1f).focusRequester(searchFocus)
                                    .clip(RoundedCornerShape(18.dp)).frostedBackdrop(galleryPixels, !LocalMirrorReducedMotion.current, galleryOrigin),
                                    singleLine = true, glass = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { searchEditing = false; focusManager.clearFocus() }))
                                RoundAction("收起搜索", DotGlyph.CLOSE, onClick = { searchEditing = false; focusManager.clearFocus() })
                            }
                        } else Row(Modifier.fillMaxWidth().padding(horizontal = 84.dp).heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(26.dp)).frostedBackdrop(galleryPixels, !LocalMirrorReducedMotion.current, galleryOrigin)
                            .background(tones.paperHighlight.copy(.40f))
                            .border(1.dp, tones.rim, RoundedCornerShape(26.dp))
                            .clickable(role = Role.Button) { searchEditing = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            DotIcon(DotGlyph.SEARCH, Modifier.size(19.dp))
                            Text(query.ifBlank { "搜索" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (!searchEditing) {
                    RoundAction("查看收藏", DotGlyph.STAR, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 136.dp)
                        .clip(CircleShape).frostedBackdrop(galleryPixels, !LocalMirrorReducedMotion.current, galleryOrigin), glass = true,
                        onClick = { favouriteRevealPlayed = false; favourites = true })
                    RoundAction("导入或下载 LUT", DotGlyph.PLUS, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 136.dp)
                        .clip(CircleShape).frostedBackdrop(galleryPixels, !LocalMirrorReducedMotion.current, galleryOrigin), glass = true,
                        onClick = { importMenu = true })
                    }
                }
            }
        }
    }
    if (importMenu) MirrorDialog("添加 LUT", onDismiss = { importMenu = false }) { close ->
        Text("选择本地配方，或发现创作者分享的色彩。", color = tones.secondaryInk)
        MirrorButton(onClick = { close(); onImportLut() }, Modifier.fillMaxWidth()) { Text("导入本地 .cube / .vlt") }
        MirrorChoice(false, { official = true; expanded = null; close() }, Modifier.fillMaxWidth(),
            label = { Text("从 Lumix Lab 浏览与下载") })
    }
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
internal fun Polaroid(card: MirrorLutCard, origin: String, state: WorkspaceState, workspace: WorkspaceViewModel,
    personal: MirrorPersonalState, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
    modifier: Modifier, large: Boolean = false, recommendation: Boolean = false, onClick: () -> Unit, onFavourite: () -> Unit,
    onColours: (LutExampleColours) -> Unit = {}, collection: Boolean = false) {
    val reportColours by rememberUpdatedState(onColours)
    val actualPhoto=card.photoPreview || card.official==null
    val previewKey = remember(card.key, card.official?.thumbnail, card.local, if (actualPhoto) state.current?.id else null, large,actualPhoto) {
        listOf(card.key, card.official?.thumbnail?.path, if (card.official == null) card.local?.id else null,
            if (actualPhoto) state.current?.id else null, large,actualPhoto)
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
            if (card.official != null && !actualPhoto) {
                val example = workspace.displayPreviews.load(previewKey) {
                    workspace.processor.officialLutCatalogue.example(card.official, if (large) 1280 else 320)
                        .let { MirrorPreview(it.image, it.colours) }
                }
                bitmap = example.image; colours = example.colours
                example.colours?.let(reportColours)
            } else if (actualPhoto && card.official != null && card.local == null) {
                state.current?.let { source ->
                    val preview = workspace.displayPreviews.load(previewKey, keepLoading = false) {
                        workspace.processor.previewOfficialLutCard(source, card.official, if (large) 512 else 320)
                            .let { MirrorPreview(it.image, it.colours) }
                    }
                    bitmap = preview.image; colours = preview.colours
                    preview.colours?.let(reportColours)
                }
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
    val swatch = if (collection) colours?.swatches?.maxByOrNull { sample ->
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(sample.argb, hsv); hsv[1] * (.3f + hsv[2])
    } else colours?.swatches?.firstOrNull()
    val sampledPaper = swatch?.let { Color(it.argb) } ?: LocalFilmMaterials.current.paperHighlight
    val background = if (collection && swatch != null) lerp(sampledPaper, Color(0xFFF4F1E7), .24f) else sampledPaper
    val ink = if (collection && swatch != null) { if (background.luminance() > .28f) Color(0xFF17252C) else Color(0xFFF8F7F2) }
        else swatch?.let { Color(it.titleTextArgb).copy(alpha = 1f) } ?: LocalFilmMaterials.current.ink
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
    val favouriteContent: @Composable RowScope.() -> Unit = {
        FavouriteAction(card.key in personal.favourites, card.title, ink, onFavourite,
            modifier = Modifier.alignBy { it.measuredHeight / 2 }, compact = !large && !recommendation)
    }
    val opticalOffset = with(LocalDensity.current) { ((if (large || recommendation) 28.sp else 14.sp) *
        if(lutNameFont(card.title)==MirrorFonts.ChineseHandwriting).08f else .30f).roundToPx() }
    if (recommendation) {
        Row(modifier.then(morph).clip(RoundedCornerShape(18.dp)).background(background)
            .clickable(role = Role.Button, onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            photoContent(Modifier.size(86.dp))
            Column(Modifier.weight(1f).alignBy { val baseline = it[FirstBaseline]
                if (baseline == AlignmentLine.Unspecified) it.measuredHeight / 2 else baseline - opticalOffset }) {
                Text(card.title, fontFamily = lutNameFont(card.title), fontSize = 28.sp, color = ink,
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
            Text(card.title, Modifier.weight(1f).alignBy { val baseline = it[FirstBaseline]
                if (baseline == AlignmentLine.Unspecified) it.measuredHeight / 2 else baseline - opticalOffset }.padding(start = 4.dp), fontFamily = lutNameFont(card.title),
                fontSize = if (large) 28.sp else 14.sp, lineHeight = if (large) 32.sp else 17.sp,
                maxLines = if (large) 1 else 2, overflow = TextOverflow.Ellipsis, color = ink)
            favouriteContent()
        }
        if (large) Text(if (!actualPhoto) "创作者例图" else "当前照片 · 单 LUT 预览", color = ink,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FavouriteAction(selected: Boolean, title: String, ink: Color, onClick: () -> Unit,
    modifier: Modifier = Modifier, compact: Boolean = false) {
    val scope = rememberCoroutineScope()
    val feedback = rememberMirrorTick()
    val reduced = LocalMirrorReducedMotion.current
    val scale = remember { Animatable(1f) }
    val colour by animateColorAsState(if (selected) Color(0xFFF5CC70) else ink.copy(.07f), tween(if (reduced) 0 else 180), label = "favourite-colour")
    Box(modifier = modifier.size(48.dp).clickable(role = Role.Button, onClick = {
        onClick(); feedback()
        if (!reduced) scope.launch { scale.snapTo(.82f); scale.animateTo(1.12f, spring(dampingRatio = .48f)); scale.animateTo(1f, tween(160)) }
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
    Column(Modifier.fillMaxSize().background(tones.background).statusBarsPadding()) {
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
    val revealOnEntry = remember { reveal }
    LaunchedEffect(Unit) { onReveal() }
    // Register with AnimatedContent, so an outgoing collection stays composed until
    // its clip has closed. Returning from card details does not replay the reveal.
    val reducedMotion = LocalMirrorReducedMotion.current
    val progress = visibility.transition.animateFloat(
        transitionSpec = { tween(if (reducedMotion) 0 else 480, easing = MirrorEase) },
        label = "favourites-circle"
    ) { phase ->
        when (phase) {
            EnterExitState.PreEnter -> if (revealOnEntry) 0f else 1f
            EnterExitState.Visible -> 1f
            EnterExitState.PostExit -> if (closing) 0f else 1f
        }
    }
    val originPadding = with(LocalDensity.current) { 46.dp.toPx() }
    val originBottom = with(LocalDensity.current) { 162.dp.toPx() }
    val dark = com.lumixfilmmirror.design.LocalFilmDark.current
    val scenePixels = rememberGraphicsLayer()
    val sceneOrigin = remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val p = progress.value
        clip = true
        shape = GenericShape { size, _ ->
            val radius = hypot(size.width, size.height) * p
            val cx = originPadding; val cy = size.height - originBottom
            addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
        }
    }) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { sceneOrigin.value = it.positionInRoot() }
            .drawWithContent { scenePixels.record { this@drawWithContent.drawContent() }; drawLayer(scenePixels) }) {
        CollectionBackdrop(Modifier.fillMaxSize(), dark)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Column(Modifier.fillMaxWidth().semantics { heading() }) {
                    MirrorLayeredTitle("Color", "Collection", tones.ink,
                        if (dark) Color(0xFFB7C9E0) else Color(0xFF56758D))
                    Text("收藏", Modifier.padding(top = 10.dp), fontFamily = MirrorFonts.ChineseEditorial,
                        fontSize = 15.sp, color = if (dark) Color(0xFFB7C9E0) else Color(0xFF56758D))
                }
            }
            if (cards.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                DotIcon(DotGlyph.STAR, Modifier.size(52.dp), tones.ink)
                Text("留下喜欢的色彩", Modifier.padding(top = 24.dp), fontFamily = MirrorFonts.ChineseEditorial, fontSize = 26.sp)
                Text("点击拍立得上的星星，收藏会出现在这里。", Modifier.padding(top = 12.dp), color = tones.secondaryInk)
            }
            else {
                val compact = LocalConfiguration.current.screenHeightDp < 620 || LocalDensity.current.fontScale > 1.3f
                val collectionScroll = rememberScrollState()
                Column(Modifier.weight(1f).fillMaxWidth().then(
                    if (compact) Modifier.verticalScroll(collectionScroll) else Modifier)) {
                val pager = rememberPagerState(initialPage = if (initialPage < 0) minOf(2, cards.size / 2)
                    else initialPage.coerceIn(0, cards.lastIndex), pageCount = { cards.size })
                LaunchedEffect(cards.map { it.key }) {
                    if (pager.currentPage > cards.lastIndex) pager.scrollToPage(cards.lastIndex)
                }
                val feedback = rememberMirrorTick()
                var last by remember { mutableIntStateOf(pager.currentPage) }
                LaunchedEffect(pager.currentPage) {
                    if (last != pager.currentPage) feedback()
                    last = pager.currentPage
                    onPage(pager.currentPage)
                }
                BoxWithConstraints((if (compact) Modifier.height(460.dp) else Modifier.weight(1f)).fillMaxWidth()) {
                val cardWidth = minOf((maxWidth * .66f).coerceIn(210.dp, 330.dp), (maxHeight - 92.dp).coerceAtLeast(150.dp))
                HorizontalPager(pager, Modifier.fillMaxWidth().height(cardWidth + 128.dp).align(Alignment.Center),
                    pageSize = PageSize.Fixed(cardWidth), contentPadding = PaddingValues(horizontal = (maxWidth - cardWidth) / 2),
                    pageSpacing = -cardWidth * .16f, beyondViewportPageCount = 2,
                    // A removal can reach the key map before the old page-count snapshot
                    // is invalidated. Keep real keys stable and tolerate that one-frame index.
                    key = { index -> cards.getOrNull(index)?.key ?: "removed-favourite:$index" }) { index ->
                    val card = cards.getOrNull(index) ?: return@HorizontalPager
                    val origin = "favourite:${card.key}"
                    Polaroid(card, origin, state, workspace, personal, shared, visibility,
                        Modifier.fillMaxWidth().zIndex(4f - kotlin.math.abs(pager.currentPage - index).toFloat())
                            .graphicsLayer {
                                val distance = (pager.currentPage - index) + pager.currentPageOffsetFraction
                                rotationZ = -distance.coerceIn(-2f, 2f) * 11f
                                rotationY = distance.coerceIn(-2f, 2f) * -10f
                                translationY = distance.coerceIn(-2.5f, 2.5f).let { it * it } * 10.dp.toPx()
                                scaleX = 1f; scaleY = 1f
                                cameraDistance = 12f * density
                            }.shadow(12.dp,RoundedCornerShape(5.dp)),
                        onClick = { if (!closing) onOpen(card, origin) }, onFavourite = { if (!closing) onFavourite(card) },
                        collection = true)
                }
                }
                Text("${pager.currentPage + 1} / ${cards.size} · 左右滑动，点按查看",
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
                        .background(if (dark) Color(0xFF102C46).copy(.76f) else Color.White.copy(.38f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = if (dark) Color(0xFFE5F1F9) else Color(0xFF314B5D), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(136.dp))
                }
            }
            if (cards.isEmpty()) Spacer(Modifier.height(136.dp))
        }
        }
        RoundAction("退出收藏", DotGlyph.CLOSE, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 136.dp)
            .clip(CircleShape).frostedBackdrop(scenePixels, !LocalMirrorReducedMotion.current, sceneOrigin), glass = true, onClick = onClose)
    }
}
