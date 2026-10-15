@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.lumixfilmmirror.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import com.lumixfilmmirror.media.FilmCategory
import com.lumixfilmmirror.media.FilmPreset

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

internal fun defaultFilmIndex(films: List<FilmPreset>, category: FilmCategory): Int {
    val preferred = when(category) { FilmCategory.NEGATIVE -> "kodak_gold_200"; FilmCategory.CINEMA -> "kodak_vision3_500t"
        FilmCategory.REVERSAL -> "fujifilm_velvia_100"; FilmCategory.MONOCHROME -> null }
    return films.indexOfFirst { it.id == preferred }.coerceAtLeast(0)
}

private fun filmSceneAngle(route: String) = when {
    route == "home" -> 12f
    FilmCategory.entries.any { it.name == route } -> -74f
    else -> 0f
}

@Composable
internal fun filmObjectRotation(visibility: AnimatedVisibilityScope, visibleAngle: Float,
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
internal fun MirrorFilm(state: WorkspaceState, workspace: WorkspaceViewModel, onImport: () -> Unit, onChoose: (FilmPreset) -> Unit,
    onFullscreen: (Boolean) -> Unit) {
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var positions by rememberSaveable { mutableStateOf(hashMapOf<String, Int>()) }
    var armed by rememberSaveable { mutableStateOf(true) }
    val category = categoryName?.let { FilmCategory.valueOf(it) }
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
                route == "home" -> FilmCategories(state, workspace, positions, onImport, shared, visibility, arrivalAngle, departureAngle,
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
                    FilmDetail(film, state, workspace, onImport, shared, visibility, arrivalAngle, departureAngle, onBack = back, onChoose = {
                        detailId = null; armed = false; onChoose(film)
                    })
                }
            }
        }
    }
}
