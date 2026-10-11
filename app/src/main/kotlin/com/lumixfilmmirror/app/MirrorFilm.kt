@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.lumixfilmmirror.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlin.math.abs

internal fun FilmCategory.editorialTitle() = when (this) {
    FilmCategory.NEGATIVE -> "Negative"
    FilmCategory.CINEMA -> "Cinema"
    FilmCategory.REVERSAL -> "Slide"
    FilmCategory.MONOCHROME -> "B&W"
}
internal fun FilmCategory.colour() = when (this) {
    FilmCategory.NEGATIVE -> Color(0xFFE9BC57)
    FilmCategory.CINEMA -> Color(0xFFBE7654)
    FilmCategory.REVERSAL -> Color(0xFF7BACC0)
    FilmCategory.MONOCHROME -> Color(0xFFB5B4AB)
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
        AnimatedContent(targetState = detailId ?: categoryName ?: "home", label = "film-scene",
            transitionSpec = { fadeIn(tween(if (reduced) 0 else 420)) togetherWith fadeOut(tween(if (reduced) 0 else 280)) }) { route ->
            val visibility = this
            when {
                route == "home" -> FilmCategories(state, positions, onImport, shared, visibility) { selected ->
                    categoryName = selected.name; armed = true
                }
                FilmCategory.entries.any { it.name == route } -> {
                    val shownCategory = FilmCategory.valueOf(route)
                    val films = state.filmPresets.filter { it.category == shownCategory }
                    FilmCarousel(films, shownCategory, positions[route] ?: 0, armed, reduced, shared, visibility,
                        onBack = back, onPage = { page -> positions = HashMap(positions).apply { put(route, page) } },
                        onUserScroll = { armed = true }, onDetail = { detailId = it.id })
                }
                else -> state.filmPresets.firstOrNull { it.id == route }?.let { film ->
                    FilmDetail(film, shared, visibility, onBack = back, onChoose = {
                        detailId = null; armed = false; onChoose(film)
                    })
                }
            }
        }
    }
}

@Composable
private fun FilmCategories(state: WorkspaceState, positions: Map<String, Int>, onImport: () -> Unit, shared: SharedTransitionScope,
    visibility: AnimatedVisibilityScope, onCategory: (FilmCategory) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 172.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeading("ANALOG / DIGITAL", "Film", onImport) }
        if (state.photos.isEmpty()) item {
            Text("先浏览胶卷，或导入照片开始创作。", Modifier.padding(horizontal = 20.dp),
                color = LocalFilmMaterials.current.secondaryInk, style = MaterialTheme.typography.bodySmall)
        }
        FilmCategory.entries.forEachIndexed { index, category -> item(key = category.name) {
            val films = state.filmPresets.filter { it.category == category }
            val representative = films.getOrNull(positions[category.name] ?: 0) ?: films.firstOrNull()
            Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(190.dp).clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(category.colour(), category.colour().copy(.65f))))
                .clickable { onCategory(category) }) {
                Column(Modifier.padding(20.dp)) {
                    Text("0${index + 1} / ${category.label}", fontSize = 11.sp, color = Color(0xFF24241E))
                    val headingMorph = with(shared) { Modifier.sharedBounds(rememberSharedContentState("category-${category.name}"), visibility,
                        boundsTransform = { _, _ -> tween(420, easing = MirrorEase) }) }
                    Text(category.editorialTitle(), headingMorph, fontFamily = MirrorFonts.Editorial, fontSize = 52.sp,
                        color = Color(0xFF171916), maxLines = 1)
                    Text("${state.filmPresets.count { it.category == category }} 款胶卷", fontSize = 12.sp, color = Color(0xFF31342B))
                }
                representative?.let { film ->
                    val morph = with(shared) { Modifier.sharedBounds(rememberSharedContentState("film-${film.id}"), visibility) }
                    FilmCartridge(film, Modifier.align(Alignment.CenterEnd).width(126.dp).height(176.dp).then(morph)
                        .graphicsLayer { rotationZ = 12f })
                }
                DotIcon(DotGlyph.RIGHT, Modifier.align(Alignment.BottomStart).padding(20.dp), Color(0xFF24241E))
            }
        } }
    }
}

@Composable
private fun FilmCarousel(films: List<FilmPreset>, category: FilmCategory, initial: Int, armed: Boolean,
    reduced: Boolean, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope, onBack: () -> Unit,
    onPage: (Int) -> Unit, onUserScroll: () -> Unit, onDetail: (FilmPreset) -> Unit) {
    val tones = LocalFilmMaterials.current
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, maxOf(0, films.lastIndex)), pageCount = { films.size })
    val haptics = LocalHapticFeedback.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(if (reduced) 0 else 450); entered = true }
    LaunchedEffect(pager.isScrollInProgress) { if (pager.isScrollInProgress) onUserScroll() }
    LaunchedEffect(pager.settledPage) { onPage(pager.settledPage) }
    var previousPage by remember { mutableIntStateOf(pager.currentPage) }
    LaunchedEffect(pager.currentPage) {
        if (previousPage != pager.currentPage) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); previousPage = pager.currentPage
        }
    }
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress, armed, entered, films.size) {
        if (entered && armed && !pager.isScrollInProgress && !reduced && films.isNotEmpty()) {
            delay(400); onDetail(films[pager.settledPage])
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(tones.background)) {
        val pageHeight = (maxHeight / 3).coerceIn(165.dp, 250.dp)
        if (films.isNotEmpty()) VerticalPager(pager, modifier = Modifier.fillMaxSize(), pageSize = PageSize.Fixed(pageHeight),
            contentPadding = PaddingValues(vertical = (maxHeight - pageHeight) / 2), beyondViewportPageCount = 1) { index ->
            val distance = (pager.currentPage - index) + pager.currentPageOffsetFraction
            val centre = 1f - abs(distance).coerceIn(0f, 1f)
            Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(.44f).graphicsLayer { alpha = .25f + centre * .75f }) {
                    Text("${index + 1}".padStart(2, '0'), fontFamily = MirrorFonts.Dots, color = tones.amber)
                    Text(films[index].name, style = MaterialTheme.typography.titleSmall)
                    if (centre > .9f) TextButton(onClick = { onDetail(films[index]) }) { Text("查看介绍 →") }
                }
                val morph = with(shared) { Modifier.sharedBounds(rememberSharedContentState("film-${films[index].id}"), visibility) }
                FilmCartridge(films[index], Modifier.weight(.56f).height(pageHeight - 6.dp).then(morph)
                    .graphicsLayer {
                        scaleX = .72f + centre * .28f; scaleY = scaleX
                        rotationZ = 18f - centre * 10f
                        rotationY = distance.coerceIn(-1f, 1f) * -18f
                        alpha = .2f + centre * .8f
                        cameraDistance = 12 * density
                    }.clickable { onDetail(films[index]) })
            }
        } else Text("此分类暂时没有胶卷模型。", Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.TopStart).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction("返回胶卷分类", DotGlyph.LEFT, onClick = onBack)
            val headingMorph = with(shared) { Modifier.sharedBounds(rememberSharedContentState("category-${category.name}"), visibility,
                boundsTransform = { _, _ -> tween(if (reduced) 0 else 420, easing = MirrorEase) }) }
            Text(category.editorialTitle(), Modifier.padding(start = 16.dp).then(headingMorph), fontFamily = MirrorFonts.Editorial, fontSize = 30.sp)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Text(category.editorialTitle(), fontFamily = MirrorFonts.Editorial, fontSize = 38.sp)
            Text(if (reduced) "上下滑动选卷 · 点击查看介绍" else "上下滑动选卷 · 停留片刻查看介绍",
                color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FilmDetail(film: FilmPreset, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
    onBack: () -> Unit, onChoose: () -> Unit) {
    val tones = LocalFilmMaterials.current
    Column(Modifier.fillMaxSize().background(tones.background)) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { RoundAction("返回选卷", DotGlyph.LEFT, onClick = onBack) }
            item {
                Row(Modifier.fillMaxWidth().height(265.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(film.category.label, style = MaterialTheme.typography.labelSmall, color = tones.secondaryInk)
                        Text(film.name, fontFamily = MirrorFonts.Editorial, fontSize = 42.sp, lineHeight = 44.sp)
                    }
                    val morph = with(shared) { Modifier.sharedBounds(rememberSharedContentState("film-${film.id}"), visibility) }
                    FilmCartridge(film, Modifier.width(158.dp).height(255.dp).then(morph))
                }
            }
            item { HorizontalDivider(); Text(film.description ?: "${film.category.label} · ${film.name}", Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.bodyLarge) }
            item { Text("${if (film.positive) "直接呈现正像" else "显影后渐进反转"} / 可调整曝光、黑柔与 Halation",
                color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall) }
        }
        Button(onClick = onChoose, modifier = Modifier.fillMaxWidth().padding(20.dp).height(56.dp),
            shape = RoundedCornerShape(28.dp)) { Text("选择此卷 →") }
    }
}

@Composable
internal fun FilmCartridge(film: FilmPreset, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val height = minOf(maxHeight, maxWidth / .72f)
        val width = height * .72f
        Box(Modifier.width(width).height(height)) {
            Image(painterResource(R.drawable.cartridge_blank_v1), film.name, Modifier.fillMaxSize())
            Column(Modifier.offset(x = width * .185f, y = height * .26f).width(width * .62f).height(height * .58f)
                .background(Brush.horizontalGradient(listOf(film.category.colour().copy(.75f), film.category.colour(), film.category.colour().copy(.7f))))
                .padding(horizontal = 5.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("LUMIX", fontSize = 8.sp, color = Color(0xFF252620), fontWeight = FontWeight.Bold)
                Text(film.name.removePrefix("Kodak ").removePrefix("Fujifilm "), fontFamily = MirrorFonts.Body,
                    fontWeight = FontWeight.Bold, fontSize = if (width > 150.dp) 22.sp else 15.sp, lineHeight = 19.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color(0xFF161A18))
                Text("FILM MIRROR", fontSize = 7.sp, color = Color(0xFF252620))
            }
        }
    }
}
