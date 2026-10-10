package app.filmcompanion.ui



import androidx.compose.animation.core.Animatable

import androidx.compose.animation.core.CubicBezierEasing

import androidx.compose.animation.core.tween

import androidx.compose.ui.draw.drawBehind

import androidx.compose.foundation.gestures.awaitEachGesture

import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.gestures.calculateZoom

import androidx.compose.foundation.layout.*

import androidx.compose.runtime.*

import androidx.compose.ui.Modifier

import androidx.compose.ui.composed

import androidx.compose.ui.geometry.Rect

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.TransformOrigin

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.layout.boundsInWindow

import androidx.compose.ui.layout.onGloballyPositioned

import androidx.compose.ui.platform.LocalView

import androidx.compose.ui.platform.testTag

import androidx.compose.ui.unit.dp

import androidx.compose.ui.window.Dialog

import androidx.compose.ui.window.DialogProperties

import androidx.compose.ui.window.DialogWindowProvider

import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.first

import androidx.core.view.WindowCompat

import androidx.compose.ui.geometry.lerp



internal class CardOrigins {

    val bounds = mutableMapOf<String, Rect>()

    var selectedKey: String? = null

    var activeKey by mutableStateOf<String?>(null)

}

internal val LocalCardOrigins = staticCompositionLocalOf { CardOrigins() }



internal fun Modifier.cardOrigin(key: String): Modifier = composed {

    val registry = LocalCardOrigins.current

    var last by remember { mutableStateOf<Rect?>(null) }

    DisposableEffect(key, registry) { onDispose { if (registry.bounds[key] == last) registry.bounds.remove(key) } }

    onGloballyPositioned { coordinates ->

        val rect = coordinates.boundsInWindow()

        if (rect.width > 0 && rect.height > 0) { last = rect; registry.bounds[key] = rect }

    }.graphicsLayer { alpha = if (registry.activeKey == key) 0f else 1f }

}



/** Ignore one-finger scrolling/tapping. Only claim an outward, two-finger gesture. */

internal fun Modifier.openOnPinch(onOpen: () -> Unit): Modifier = composed {

    val latest by rememberUpdatedState(onOpen)

    pointerInput(Unit) {

        awaitEachGesture {

            awaitFirstDown(requireUnconsumed = false)

            var zoom = 1f

            var opened = false

            do {

                val event = awaitPointerEvent()

                if (event.changes.count { it.pressed } >= 2 && !event.changes.any { it.isConsumed }) {

                    zoom *= event.calculateZoom()

                    if (zoom > 1.12f && !opened) {

                        opened = true

                        event.changes.forEach { it.consume() }

                        latest()

                    }

                }

            } while (event.changes.any { it.pressed })

        }

    }

}



internal class ExpansionSlot {

    var target by mutableStateOf<Rect?>(null)

}

internal class ExpansionScope(val progress: () -> Float, private val origins: Map<String, Rect>) {

    val slots = mutableStateMapOf<String, ExpansionSlot>()

    var ready by mutableStateOf(false)

    /** Secondary content waits until the card has room, then leaves before it shrinks. */

    @Composable fun Details(modifier: Modifier = Modifier, content: @Composable () -> Unit) {

        Box(modifier.graphicsLayer {

            alpha = if (ready) ((progress() - .4f) / .6f).coerceIn(0f, 1f) else 0f

        }.testTag("card-secondary")) { content() }

    }

    @Composable fun Element(key: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {

        val slot = remember(key) { ExpansionSlot() }

        DisposableEffect(key) { slots[key] = slot; onDispose { slots.remove(key) } }

        // Measure outside the moving layer. The same child remains mounted throughout

        // opening, scrolling and closing; settling never swaps in a second copy.

        Box(modifier) {

            Box(Modifier.onGloballyPositioned { coordinates ->

                val target = coordinates.boundsInWindow(clipBounds = false)

                if (target != slot.target) slot.target = target

            }) {

                Box(Modifier.graphicsLayer {

                    val target = slot.target

                    if (!ready || target == null || target.width <= 0f || target.height <= 0f) {

                        alpha = 0f

                    } else {

                        val value = progress()

                        val bounds = lerp(origins[key] ?: target, target, value)

                        val scale = minOf(bounds.width / target.width, bounds.height / target.height)

                        transformOrigin = TransformOrigin.Center

                        scaleX = scale; scaleY = scale

                        translationX = bounds.center.x - target.center.x

                        translationY = bounds.center.y - target.center.y

                        alpha = if (origins.containsKey(key)) 1f else value

                    }

                }.testTag("shared:$key")) { content() }

            }

        }

    }

}



/** One clock drives the card and every shared element; closing traverses the same geometry. */

@Composable

internal fun CardExpansionDialog(

    originKey: String,

    onClose: () -> Unit,

    onClosing: () -> Unit = {},

    tone: LibraryMaterialTone = LibraryMaterialTone.NEUTRAL,

    content: @Composable ExpansionScope.(close: () -> Unit) -> Unit

) {

    val registry = LocalCardOrigins.current

    val origins = remember(originKey) { registry.bounds.toMap() }

    val reduced = LocalCompanionSettings.current.reducedMotion

    val progress = remember(originKey) { Animatable(if (reduced) 1f else 0f) }

    val expansion = remember(originKey) { ExpansionScope({ progress.value }, origins) }

    val scope = rememberCoroutineScope()

    var closing by remember { mutableStateOf(false) }

    val latestClose by rememberUpdatedState(onClose)

    DisposableEffect(originKey, registry) {

        onDispose { if (registry.activeKey == originKey) registry.activeKey = null }

    }

    val close: () -> Unit = {

        if (!closing) { closing = true; onClosing(); scope.launch {

            if (expansion.ready) progress.animateTo(0f, tween(if (reduced) 0 else 280, easing = CubicBezierEasing(.32f, .0f, .25f, 1f)))

            latestClose()

        } }

    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {

        val view = LocalView.current

        val dark = LocalFilmDark.current

        SideEffect { (view.parent as? DialogWindowProvider)?.window?.apply {

            setWindowAnimations(0)

            setDimAmount(0f)

            WindowCompat.getInsetsController(this, decorView).apply {

                isAppearanceLightStatusBars = !dark

                isAppearanceLightNavigationBars = !dark

            }

        } }

        BoxWithConstraints(Modifier.fillMaxSize().testTag("card-expansion")) {

            val target = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())

            val origin = origins[originKey] ?: target

            Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = .30f * progress.value)) })

            Box(Modifier.fillMaxSize().graphicsLayer {

                val value = progress.value

                val mask = lerp(origin, target, value)

                val radius = 24.dp.toPx() * (1f - value)

                shape = object : androidx.compose.ui.graphics.Shape {

                    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection,

                        density: androidx.compose.ui.unit.Density) = androidx.compose.ui.graphics.Outline.Rounded(

                        androidx.compose.ui.geometry.RoundRect(mask, androidx.compose.ui.geometry.CornerRadius(radius)))

                }

                clip = true

                alpha = if (expansion.ready) 1f else 0f

            }.libraryMaterial(0.dp, tone)) { expansion.content(close) }

            LaunchedEffect(originKey) {

                // Slot registration and native Dialog layout are asynchronous. Wait for a stable

                // destination; changing slot bounds must not cancel the single expansion clock.

                withFrameNanos { }; withFrameNanos { }

                snapshotFlow { expansion.slots.values.all { it.target != null } }.first { it }

                expansion.ready = true

                registry.activeKey = originKey.takeIf { origins.containsKey(it) }

                if (!closing && !reduced) progress.animateTo(1f,

                    tween(420, easing = CubicBezierEasing(.22f, .80f, .25f, 1f)))

            }

        }

    }

}
