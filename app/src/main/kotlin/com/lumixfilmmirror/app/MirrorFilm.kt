@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.lumixfilmmirror.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts
import com.lumixfilmmirror.media.FilmCategory
import com.lumixfilmmirror.media.FilmPreset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal fun FilmCategory.editorialTitle() = when (this) {
    FilmCategory.NEGATIVE -> "Negative"
    FilmCategory.CINEMA -> "Cine"
    FilmCategory.REVERSAL -> "Slide"
    FilmCategory.MONOCHROME -> "B&W"
}
internal fun FilmCategory.colour() = when (this) {
    FilmCategory.NEGATIVE -> Color(0xFFFFCC56)
    FilmCategory.CINEMA -> Color(0xFF4E8CCC)
    FilmCategory.REVERSAL -> Color(0xFF6DA275)
    FilmCategory.MONOCHROME -> Color(0xFFC4C3B9)
}

private fun defaultFilmIndex(films: List<FilmPreset>, category: FilmCategory): Int {
    val preferred = when(category) { FilmCategory.NEGATIVE -> "kodak_gold_200"; FilmCategory.CINEMA -> "kodak_vision3_500t"
        FilmCategory.REVERSAL -> "fujifilm_velvia_100"; FilmCategory.MONOCHROME -> null }
    return films.indexOfFirst { it.id == preferred }.coerceAtLeast(0)
}

private fun filmSceneAngle(route: String) = when {
    route == "home" -> 12f
    FilmCategory.entries.any { it.name == route } -> -66f
    else -> 0f
}

@Composable
private fun filmObjectRotation(visibility: AnimatedVisibilityScope, visibleAngle: Float,
    arrivalAngle: Float, departureAngle: Float): State<Float> {
    val duration = if (LocalMirrorReducedMotion.current) 0 else 420
    return visibility.transition.animateFloat(transitionSpec = { tween(duration, easing = MirrorEase) }, label = "cartridge-pose") { phase ->
        when (phase) {
            EnterExitState.PreEnter -> arrivalAngle
            EnterExitState.Visible -> visibleAngle
            EnterExitState.PostExit -> departureAngle
        }
    }
}

@Composable
internal fun MirrorFilm(state: WorkspaceState, onImport: () -> Unit, onChoose: (FilmPreset) -> Unit,
    onFullscreen: (Boolean) -> Unit) {
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var positions by rememberSaveable { mutableStateOf(hashMapOf<String, Int>()) }
    var armed by rememberSaveable { mutableStateOf(true) }
    val category = categoryName?.let { FilmCategory.valueOf(it) }
    val detail = state.filmPresets.firstOrNull { it.id == detailId }
    val reduced = LocalMirrorReducedMotion.current
    val back: () -> Unit = {
        if (detailId != null) { detailId = null; armed = false }
        else { categoryName = null; armed = true }
    }
    BackHandler(category != null, onBack = back)
    LaunchedEffect(category != null) { onFullscreen(category != null) }
    DisposableEffect(Unit) { onDispose { onFullscreen(false) } }
    SharedTransitionLayout {
        val shared = this
        val scene = updateTransition(detailId ?: categoryName ?: "home", label = "film-scene")
        val arrivalAngle = filmSceneAngle(scene.currentState)
        val departureAngle = filmSceneAngle(scene.targetState)
        scene.AnimatedContent(transitionSpec = {
            fadeIn(tween(if (reduced) 0 else 420)) togetherWith fadeOut(tween(if (reduced) 0 else 420))
        }) { route ->
            val visibility = this
            when {
                route == "home" -> FilmCategories(state, positions, onImport, shared, visibility, arrivalAngle, departureAngle,
                    categoryName ?: scene.currentState.takeIf { key -> FilmCategory.entries.any { it.name == key } }) { selected ->
                    categoryName = selected.name; armed = true
                }
                FilmCategory.entries.any { it.name == route } -> {
                    val shownCategory = FilmCategory.valueOf(route)
                    val films = state.filmPresets.filter { it.category == shownCategory }
                    FilmCarousel(films, shownCategory, positions[route] ?: defaultFilmIndex(films,shownCategory), armed, reduced, shared, visibility,
                        arrivalAngle, departureAngle,
                        onBack = back, onPage = { page -> positions = HashMap(positions).apply { put(route, page) } },
                        onUserScroll = { if (categoryName == route && detailId == null) armed = true },
                        onDetail = { if (categoryName == route && detailId == null) { armed = false; detailId = it.id } },
                        onCategory = { selected -> categoryName = selected.name; armed = true })
                }
                else -> state.filmPresets.firstOrNull { it.id == route }?.let { film ->
                    FilmDetail(film, shared, visibility, arrivalAngle, departureAngle, onBack = back, onChoose = {
                        detailId = null; armed = false; onChoose(film)
                    })
                }
            }
        }
    }
}

@Composable
private fun FilmCategories(state: WorkspaceState, positions: Map<String, Int>, onImport: () -> Unit, shared: SharedTransitionScope,
    visibility: AnimatedVisibilityScope, arrivalAngle: Float, departureAngle: Float,
    linkedCategory: String?, onCategory: (FilmCategory) -> Unit) {
    val reduced = LocalMirrorReducedMotion.current
    Column(Modifier.fillMaxSize()) {
    PageHeading("LUMIX / 胶片模拟", "Film", onImport)
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 172.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FilmCategory.entries.forEachIndexed { index, category -> item(key = category.name) {
            val films = state.filmPresets.filter { it.category == category }
            val representative = films.getOrNull(positions[category.name] ?: defaultFilmIndex(films,category)) ?: films.firstOrNull()
            Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(190.dp).clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(category.colour(), category.colour().copy(.65f))))
                .clickable { onCategory(category) }) {
                Column(Modifier.padding(20.dp)) {
                    val headingMorph = with(shared) { Modifier.sharedBounds(rememberSharedContentState("category-${category.name}"), visibility,
                        boundsTransform = { _, _ -> tween(if (reduced) 0 else 420, easing = MirrorEase) }) }
                    Text(category.editorialTitle(), headingMorph, fontFamily = MirrorFonts.Editorial, fontSize = 76.sp, lineHeight = 78.sp,
                        color = Color(0xFF171916), maxLines = 1, overflow = TextOverflow.Clip)
                    Text(when(category) { FilmCategory.NEGATIVE -> "Color Negative"; FilmCategory.CINEMA -> "Motion Picture";
                        FilmCategory.REVERSAL -> "Reversal Film"; FilmCategory.MONOCHROME -> "Black & White" },
                        fontFamily=MirrorFonts.Editorial, fontSize=13.sp, lineHeight=16.sp, color=Color(0xFF24241E))
                    Spacer(Modifier.height(8.dp))
                    Text(category.label, fontSize=14.sp, lineHeight=18.sp, fontWeight=FontWeight.Bold, color=Color(0xFF22241F))
                }
                representative?.let { film ->
                    val linked = category.name == linkedCategory
                    val rotation = filmObjectRotation(visibility, 12f, if (linked) arrivalAngle else 12f, if (linked) departureAngle else 12f)
                    val morph = with(shared) { Modifier.sharedElement(rememberSharedContentState("film-${film.id}"), visibility,
                        boundsTransform = { _, _ -> tween(if (reduced) 0 else 420, easing = MirrorEase) }) }
                    FilmCartridge(film, Modifier.align(Alignment.CenterEnd).width(150.dp).height(195.dp).then(morph)
                        .graphicsLayer { rotationZ = rotation.value })
                }
                RoundAction("浏览${category.label}", DotGlyph.RIGHT, Modifier.align(Alignment.BottomEnd).padding(14.dp), onClick={onCategory(category)})
            }
        } }
    }
    }
}

@Composable
private fun FilmCarousel(films: List<FilmPreset>, category: FilmCategory, initial: Int, armed: Boolean,
    reduced: Boolean, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
    arrivalAngle: Float, departureAngle: Float, onBack: () -> Unit,
    onPage: (Int) -> Unit, onUserScroll: () -> Unit, onDetail: (FilmPreset) -> Unit, onCategory: (FilmCategory) -> Unit) {
    val tones = LocalFilmMaterials.current
    val pager = rememberPagerState(initialPage = if (films.isEmpty()) 0 else films.size * 100 + initial.coerceIn(0, films.lastIndex),
        pageCount = { films.size * 201 })
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sceneRotation = filmObjectRotation(visibility, -66f, arrivalAngle, departureAngle)
    val displayDensity = LocalDensity.current.density
    val neighbourBlurs = remember(displayDensity, reduced) {
        List(8) { level -> if (level == 0 || reduced) null else
            BlurEffect(level * .6f * displayDensity, level * .6f * displayDensity, TileMode.Clamp) }
    }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(if (reduced) 0 else 1000); entered = true }
    LaunchedEffect(pager.interactionSource) {
        pager.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) onUserScroll()
        }
    }
    LaunchedEffect(pager.settledPage) { if (films.isNotEmpty()) onPage(pager.settledPage % films.size) }
    var previousPage by remember { mutableIntStateOf(pager.currentPage) }
    LaunchedEffect(pager.currentPage) {
        if (previousPage != pager.currentPage) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); previousPage = pager.currentPage
        }
    }
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress, armed, entered, films.size) {
        if (entered && armed && !pager.isScrollInProgress && !reduced && films.isNotEmpty()) {
            delay(500)
            if (!pager.isScrollInProgress && abs(pager.currentPageOffsetFraction) < .01f)
                onDetail(films[pager.settledPage % films.size])
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        FilmStage(category)
        val pageHeight = (maxHeight * .23f).coerceIn(165.dp, 220.dp)
        val viewportHeight = (maxHeight - 252.dp).coerceAtLeast(pageHeight)
        if (films.isNotEmpty()) VerticalPager(pager, modifier = Modifier.fillMaxSize().padding(top = 148.dp, bottom = 104.dp),
            pageSize = PageSize.Fixed(pageHeight), contentPadding = PaddingValues(vertical = (viewportHeight - pageHeight) / 2),
            beyondViewportPageCount = 1) { index ->
            val filmIndex = index % films.size
            val film = films[filmIndex]
            val isCentre by remember(pager, index) { derivedStateOf {
                index == pager.currentPage && abs(pager.currentPageOffsetFraction) < .1f
            } }
            Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(8.dp).fillMaxHeight()) {
                    val centre = 1f - abs((pager.currentPage - index) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    drawLine(tones.secondaryInk.copy(.20f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
                    drawCircle(if (centre > .9f) tones.amber else tones.secondaryInk.copy(.3f),
                        if (centre > .9f) 3.dp.toPx() else 2.dp.toPx(), Offset(size.width / 2, size.height / 2))
                }
                Column(Modifier.weight(.30f).padding(start = 10.dp).graphicsLayer {
                    val centre = 1f - abs((pager.currentPage - index) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    alpha = .22f + centre * .78f
                }) {
                    Text("${filmIndex + 1}".padStart(2, '0'), color = tones.amber, fontSize=11.sp)
                    Text(film.name, fontFamily = MirrorFonts.Editorial, fontSize = 14.sp, lineHeight = 19.sp, maxLines=3)
                    if (isCentre) MirrorTextAction("介绍 →", { onDetail(film) })
                }
                val morph = if (index == pager.currentPage) with(shared) { Modifier.sharedElement(rememberSharedContentState("film-${film.id}"), visibility,
                    boundsTransform = { _, _ -> tween(if (reduced) 0 else 420, easing = MirrorEase) }) } else Modifier
                FilmCartridge(film, Modifier.weight(.70f).requiredHeight(pageHeight * 1.25f).then(morph)
                    .graphicsLayer {
                        val distance = (pager.currentPage - index) + pager.currentPageOffsetFraction
                        val centre = 1f - abs(distance).coerceIn(0f, 1f)
                        scaleX = .72f + centre * .28f; scaleY = scaleX
                        rotationZ = sceneRotation.value + distance.coerceIn(-2f,2f) * 4f
                        rotationY = distance.coerceIn(-1f, 1f) * -18f
                        alpha = .2f + centre * .8f
                        cameraDistance = 12 * density
                        renderEffect = neighbourBlurs[if (abs(distance) > .5f) (abs(distance) * 3f).toInt().coerceIn(1, 7) else 0]
                    }.clickable {
                        if (index == pager.currentPage) onDetail(film)
                        else scope.launch {
                            // A tapped neighbour first becomes the centre object, preserving its
                            // shared bounds and the position to return to after reading the detail.
                            onUserScroll()
                            if (reduced) {
                                pager.scrollToPage(index); onPage(filmIndex); onDetail(film)
                            } else pager.animateScrollToPage(index)
                        }
                    })
            }
        } else Text("此分类暂时没有胶卷模型。", Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.TopStart).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction("返回胶卷分类", DotGlyph.LEFT, onClick = onBack)
            Text("${category.label} / FILM", Modifier.padding(start = 14.dp), color = tones.secondaryInk,
                style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 78.dp)) {
            val headingMorph = with(shared) { Modifier.sharedBounds(rememberSharedContentState("category-${category.name}"), visibility,
                boundsTransform = { _, _ -> tween(if (reduced) 0 else 420, easing = MirrorEase) }) }
            Text(category.editorialTitle(), headingMorph, fontFamily = MirrorFonts.Editorial,
                fontStyle=FontStyle.Italic, fontSize = 44.sp, lineHeight = 48.sp)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilmCategory.entries.forEach { entry -> item(key = entry.name) {
                    MirrorChoice(category == entry, { if (category != entry) onCategory(entry) }, label = { Text(entry.label) })
                } }
            }
            Text(if (reduced) "上下滑动选卷 · 点击查看介绍" else "上下滑动选卷 · 停留片刻查看介绍",
                color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FilmDetail(film: FilmPreset, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
    arrivalAngle: Float, departureAngle: Float,
    onBack: () -> Unit, onChoose: () -> Unit) {
    val tones = LocalFilmMaterials.current
    val reduced = LocalMirrorReducedMotion.current
    val rotation = filmObjectRotation(visibility, 0f, arrivalAngle, departureAngle)
    val shortName = film.shortTitle()
    val number = Regex("(?:^| )([0-9]{2,4}[A-Za-z]*)$").find(shortName)?.groupValues?.get(1)
    val title = if (number == null) shortName else shortName.removeSuffix(number).trim()
    val story = when {
        film.id.contains("gold") -> "温暖的金色、柔和的层次与日常的光。把街道、旅行和身边的人，留在一卷熟悉的色彩里。"
        film.id.contains("velvia") -> "饱满的色彩与鲜明的反差。适合用山林、天空与季节的变化，探索反转片的表现。"
        film.category == FilmCategory.CINEMA -> "把电影胶卷的色彩语言带入静态照片。预览中调整曝光、颗粒与 Halation，找到属于这张照片的氛围。"
        film.category == FilmCategory.MONOCHROME -> "让光影和形状成为主角。用明暗层次、颗粒和黑柔，重新观看一张照片。"
        else -> "选择一卷，观察光线如何改变色彩。进入预览后，独立调整曝光、颗粒与黑柔，为照片找到合适的呈现。"
    }
    Column(Modifier.fillMaxSize().background(tones.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction("返回选卷", DotGlyph.LEFT, onClick = onBack)
            Text("${film.category.label} / Film", Modifier.padding(start = 16.dp), color = tones.secondaryInk,
                fontFamily = MirrorFonts.ChineseEditorial, fontSize = 16.sp)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().height(300.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(film.name.substringBefore(' '), style = MaterialTheme.typography.labelSmall, color = tones.secondaryInk)
                        Text(title, fontFamily = MirrorFonts.Editorial, fontSize = 60.sp, lineHeight = 62.sp)
                        number?.let { Text(it, fontFamily = MirrorFonts.Editorial, fontSize = 74.sp, lineHeight = 76.sp, color = tones.amber) }
                    }
                    val morph = with(shared) { Modifier.sharedElement(rememberSharedContentState("film-${film.id}"), visibility,
                        boundsTransform = { _, _ -> tween(if (reduced) 0 else 420, easing = MirrorEase) }) }
                    FilmCartridge(film, Modifier.width(158.dp).height(280.dp).then(morph).graphicsLayer { rotationZ = rotation.value })
                }
            }
            item { Text("这一卷的故事", fontFamily = MirrorFonts.ChineseEditorial, fontSize = 24.sp)
                Text(film.description ?: story, Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyLarge) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(DotGlyph.LUT to "色彩", DotGlyph.FILM to "颗粒", DotGlyph.FRAME to "层次").forEach { (icon, label) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(tones.paperHighlight).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DotIcon(icon, tint = tones.amber)
                            Text(label, fontFamily = MirrorFonts.ChineseEditorial, fontSize = 15.sp)
                        }
                    }
                }
            }
            item { Text("${if (film.positive) "直接呈现正像" else "显影后渐进反转"} / 可调整曝光、黑柔与 Halation",
                color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall) }
        }
        MirrorButton(onClick = onChoose, modifier = Modifier.fillMaxWidth().padding(20.dp).height(56.dp),
            shape = RoundedCornerShape(28.dp)) { Text("选择此卷 →") }
    }
}

@Composable
internal fun FilmCartridge(film: FilmPreset, modifier: Modifier = Modifier) {
    val artwork = when (film.id) {
        "kodak_gold_200" -> R.drawable.cartridge_gold_200_v2
        "fujifilm_c200" -> R.drawable.cartridge_c200_v1
        "fujifilm_velvia_100" -> R.drawable.cartridge_velvia_100_v2
        "kodak_vision3_500t" -> R.drawable.cartridge_vision3_500t_v2
        else -> null
    }
    if (artwork != null) {
        Image(painterResource(artwork), film.name, modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        return
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val height = minOf(maxHeight, maxWidth / .72f)
        val width = height * .72f
        Box(Modifier.width(width).height(height)) {
            Image(painterResource(R.drawable.cartridge_blank_v1), film.name, Modifier.fillMaxSize())
            StockLabel(film, Modifier.offset(x = width * .185f, y = height * .25f).width(width * .64f).height(height * .59f))
        }
    }
}

@Composable
private fun FilmStage(category: FilmCategory) {
    val dark = isSystemInDarkTheme()
    Canvas(Modifier.fillMaxSize()) {
        drawRect(if(dark) Color(0xFF10120F) else Color(0xFFF0EADD))
        drawRect(Brush.radialGradient(listOf(category.colour().copy(if(dark) .28f else .25f),Color.Transparent),
            center=Offset(size.width*.88f,size.height*.42f),radius=size.width*.95f))
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(if(dark) .44f else .04f),Color.Transparent,
            Color.Black.copy(if(dark) .58f else .08f))))
    }
}
