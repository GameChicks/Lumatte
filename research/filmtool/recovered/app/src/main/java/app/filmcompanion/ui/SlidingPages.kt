package app.filmcompanion.ui



import androidx.compose.animation.*

import androidx.compose.animation.core.CubicBezierEasing

import androidx.compose.animation.core.tween

import androidx.compose.runtime.Composable

import androidx.compose.ui.Modifier



/** Slide the content while navigation and camera preview retain their own lifecycle. */

@Composable

internal fun <T> SlidingPages(

    target: T, order: (T) -> Int, modifier: Modifier = Modifier,

    contentKey: (T) -> Any = { it as Any }, content: @Composable (T) -> Unit

) {

    val reduced = LocalCompanionSettings.current.reducedMotion

    AnimatedContent(target, modifier, contentKey = contentKey, transitionSpec = {

        if (reduced) fadeIn(tween(100)) togetherWith fadeOut(tween(80))

        else {

            val direction = if (order(targetState) > order(initialState)) 1 else -1

            val easing = CubicBezierEasing(.22f, .8f, .25f, 1f)

            (slideInHorizontally(tween(380, easing = easing)) { (it * .32f * direction).toInt() } +

                fadeIn(tween(260, easing = easing)) + scaleIn(tween(380, easing = easing), initialScale = .985f)) togetherWith

                (slideOutHorizontally(tween(380, easing = easing)) { (-it * .16f * direction).toInt() } +

                    fadeOut(tween(180)) + scaleOut(tween(380, easing = easing), targetScale = .985f))

        }.using(SizeTransform(clip = true))

    }, label = "sliding page") { content(it) }

}
