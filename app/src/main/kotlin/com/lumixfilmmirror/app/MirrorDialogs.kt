package com.lumixfilmmirror.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts
import kotlinx.coroutines.delay

/** Platform window/IME handling with the project's own paper, typography and motion. */
@Composable
internal fun MirrorDialog(title: String, onDismiss: () -> Unit,
    titleFont: FontFamily = MirrorFonts.ChineseEditorial,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val tones = LocalFilmMaterials.current
    val reduced = LocalMirrorReducedMotion.current
    var visible by remember { mutableStateOf(false) }
    var closing by rememberSaveable { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(closing) {
        if (closing) { visible = false; delay(if (reduced) 0 else 180); currentDismiss() }
    }
    val close = { closing = true }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp),
            contentAlignment = Alignment.Center) {
            val availableHeight = maxHeight
            AnimatedVisibility(visible, enter = fadeIn(tween(if (reduced) 0 else 220)) +
                scaleIn(tween(if (reduced) 0 else 300, easing = MirrorEase), initialScale = .94f),
                exit = fadeOut(tween(if (reduced) 0 else 180)) + scaleOut(tween(if (reduced) 0 else 180), targetScale = .97f)) {
                val shape = RoundedCornerShape(28.dp)
                Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = availableHeight)
                    .clip(shape).background(Brush.verticalGradient(listOf(tones.paperHighlight, tones.paper)))
                    .border(1.dp, tones.rim.copy(.65f), shape).verticalScroll(rememberScrollState()).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title, Modifier.weight(1f), fontFamily = titleFont, fontSize = 26.sp, lineHeight = 34.sp, color = tones.ink)
                        RoundAction("关闭$title", DotGlyph.CLOSE, Modifier.size(48.dp), onClick = close)
                    }
                    CompositionLocalProvider(LocalContentColor provides tones.ink) { content(close) }
                }
            }
        }
    }
}

@Composable
internal fun MirrorTextField(value: String, onValueChange: (String) -> Unit, hint: String,
    modifier: Modifier = Modifier, singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = Int.MAX_VALUE,
    enabled: Boolean = true, glass: Boolean = false, keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default) {
    val tones = LocalFilmMaterials.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(18.dp)
    BasicTextField(value, onValueChange, modifier.fillMaxWidth().heightIn(min = 52.dp)
        .clip(shape).background(tones.paperHighlight.copy(if (glass) .4f else 1f)).border(if (focused) 1.5.dp else 1.dp,
            if (focused) tones.amber else tones.rim.copy(.65f), shape).padding(horizontal = 16.dp, vertical = 14.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = tones.ink),
        cursorBrush = SolidColor(tones.amber), interactionSource = interactions,
        singleLine = singleLine, minLines = minLines, maxLines = maxLines, enabled = enabled,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = tones.secondaryInk); inner() } })
}

@Composable
internal fun MirrorTextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tones = LocalFilmMaterials.current
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = tones.amber.copy(if (enabled) 1f else .45f))
    }
}

@Composable
internal fun MirrorToggle(value: Boolean, label: String, enabled: Boolean = true, onValueChange: (Boolean) -> Unit) {
    val tones = LocalFilmMaterials.current
    val thumb by androidx.compose.animation.core.animateDpAsState(if (value) 28.dp else 4.dp,
        tween(if (LocalMirrorReducedMotion.current) 0 else 180), label = "toggle-thumb")
    Box(Modifier.size(64.dp, 48.dp).toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = onValueChange)
        .then(Modifier.semantics { contentDescription = label }), contentAlignment = Alignment.Center) {
        Box(Modifier.size(60.dp, 36.dp).clip(RoundedCornerShape(18.dp)).background(if (value) tones.ink else tones.rim)) {
            Box(Modifier.offset(x = thumb, y = 4.dp).size(28.dp).clip(RoundedCornerShape(14.dp))
                .background(if (value) tones.paperHighlight else tones.paper))
        }
    }
}
