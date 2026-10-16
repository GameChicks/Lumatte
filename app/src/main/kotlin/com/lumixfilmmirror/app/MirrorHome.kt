package com.lumixfilmmirror.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts
import com.lumixfilmmirror.storage.TransferTask
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.unit.Constraints

@Composable
internal fun MirrorHome(state: WorkspaceState, camera: CameraWorkspaceState, personal: MirrorPersonalState,
    transfers: List<TransferTask>, workspace: WorkspaceViewModel, onCamera: (String?) -> Unit, onAlbum: () -> Unit, onSettings: () -> Unit) {
    val tones = LocalFilmMaterials.current
    var noteEditing by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    val homePixels = rememberGraphicsLayer()
    val homeOrigin = remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalMirrorDotArt provides true) {
    BoxWithConstraints(Modifier.fillMaxSize().background(tones.background)) {
    val displayScale = LocalDensity.current.fontScale
    val viewportWidth = maxWidth
    val cardHeight = ((maxWidth - 40.dp) * .654f + 120.dp * (displayScale - 1f).coerceAtLeast(0f)).coerceIn(218.dp, 340.dp)
    Column(Modifier.fillMaxSize().onGloballyPositioned { homeOrigin.value = it.positionInRoot() }
        .drawWithContent { homePixels.record { this@drawWithContent.drawContent() }; drawLayer(homePixels) }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { MirrorLayeredTitle("Pine","frame",tones.ink,tones.amber,compact=true) }
                Spacer(Modifier.size(52.dp))
            }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 170.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            BoxWithConstraints(Modifier.fillMaxWidth().height(cardHeight + viewportWidth * .40f)) {
                WelcomePerspective(Modifier.fillMaxWidth().height(maxWidth * .53f).align(Alignment.TopCenter))
                CameraHero(camera, personal, workspace, onCamera, Modifier.align(Alignment.BottomCenter), cardHeight)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionAtmosphere("蓝牙\n连接", "连接你的相机", listOf(Color(0xFFFFCF78), Color(0xFFED653B), Color(0xFF428FA6)),
                    Modifier.weight(1f), cardHeight, DotGlyph.BLUETOOTH, "BLUETOOTH\nCONNECT", { onCamera(null) })
                ActionAtmosphere("进入\n相册", "导入图片", listOf(Color(0xFF285D34), Color(0xFF89BB64), Color(0xFFF0DF93)),
                    Modifier.weight(1f), cardHeight, DotGlyph.ALBUM, "IMPORT\nPHOTOS", onAlbum)
            }
        }
        item { UsageCard(state, transfers, cardHeight) }
        item {
            Atmosphere(Modifier.fillMaxWidth().height(cardHeight).clickable(role = Role.Button) {
                note = personal.journal; noteEditing = true
            }, listOf(Color(0xFFAEA891), Color(0xFF777967), Color(0xFF414D43))) {
                Text("“", Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = if (personal.journal.isBlank()) 20.dp else 54.dp),
                    fontFamily = MirrorFonts.Dots, fontSize = 60.sp, color = Color.White.copy(.30f))
                Text("”", Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp),
                    fontFamily = MirrorFonts.Dots, fontSize = 60.sp, color = Color.White.copy(.30f))
                if (personal.journal.isBlank()) Text("创作记录", Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    fontFamily = MirrorFonts.Dots, fontSize = 38.sp, lineHeight = 46.sp, color = Color.White)
                else Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("创作记录", fontFamily = MirrorFonts.Dots, fontSize = 32.sp, lineHeight = 40.sp, color = Color.White)
                    Spacer(Modifier.height(20.dp))
                    Text(personal.journal, Modifier.weight(1f).padding(horizontal = 12.dp),
                        maxLines = 4, lineHeight = 24.sp, overflow = TextOverflow.Ellipsis, color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
    }
    RoundAction("设置", DotGlyph.SETTINGS, Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 14.dp)
        .clip(CircleShape).frostedBackdrop(homePixels, !LocalMirrorReducedMotion.current, homeOrigin),
        glass = true, onClick = onSettings)
    }
    }
    if (noteEditing) MirrorDialog("创作记录", onDismiss = { noteEditing = false }, titleFont = MirrorFonts.Dots) { close ->
        Text("写下光线、地点与故事，文字仅保存在本机。", color = tones.secondaryInk)
        MirrorTextField(note, { note = it.take(4000) }, "此刻想记下什么？", minLines = 5, maxLines = 7)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MirrorTextAction("取消", close, Modifier.weight(1f))
            MirrorButton(onClick = { workspace.updatePersonal { it.copy(journal = note) }; close() }, Modifier.weight(1f)) { Text("保存") }
        }
    }
}

@Composable
private fun CameraHero(camera: CameraWorkspaceState, personal: MirrorPersonalState,
    workspace: WorkspaceViewModel, onConnect: (String?) -> Unit, modifier: Modifier, cardHeight: Dp) {
    val scope = rememberCoroutineScope()
    val reduced = LocalMirrorReducedMotion.current
    val count = maxOf(1, personal.cameras.size)
    val pager = rememberPagerState(pageCount = { count })
    var mode by rememberSaveable { mutableStateOf("normal") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(count) { if (pager.currentPage >= count) pager.scrollToPage(count - 1) }
    LaunchedEffect(camera.device?.udn, camera.connected, personal.cameras) {
        if (camera.connected) personal.cameras.indexOfFirst { it.id == camera.device?.rememberedIdentity() }
            .takeIf { it >= 0 }?.let { if (reduced) pager.scrollToPage(it) else pager.animateScrollToPage(it) }
    }
    val currentId = personal.cameras.getOrNull(pager.currentPage)?.id
    val gesture = Modifier.pointerInput(mode, currentId) {
        // Claim vertical movement only; horizontal pager and parent list keep their own gestures.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var vertical = false
            var total = 0f
            val change = awaitTouchSlopOrCancellation(down.id) { pointer, over ->
                if (abs(over.y) > abs(over.x) * 1.25f) {
                    vertical = true; total = over.y; pointer.consume()
                }
            }
            if (change != null && vertical) {
                val released = drag(change.id) { pointer -> total += pointer.position.y - pointer.previousPosition.y; pointer.consume() }
                if (released && abs(total) > 64.dp.toPx()) {
                    mode = if (mode != "normal") "normal" else if (total > 0) "add" else "delete"
                }
            }
        }
    }
    val colours = when {
        mode == "delete" -> listOf(Color(0xFFCEADA6), Color(0xFF9C6670), Color(0xFF4E394C))
        mode == "add" || personal.cameras.isEmpty() -> listOf(Color(0xFFDDD5BC), Color(0xFF779895), Color(0xFF345A65))
        else -> listOf(Color(0xFF82BEE5), Color(0xFFADD6E8), Color(0xFF124863))
    }
    Atmosphere(modifier.fillMaxWidth().height(cardHeight).then(gesture)
        .clickable(enabled = mode == "add" || personal.cameras.isEmpty(), role = Role.Button, onClick = { onConnect(null) }), colours,
        flowing = !reduced && (mode == "add" || personal.cameras.isEmpty())) {
        AnimatedContent(targetState = mode, label = "camera-card-state", transitionSpec = {
            fadeIn(tween(if (reduced) 0 else 220)) togetherWith fadeOut(tween(if (reduced) 0 else 160))
        }) { target ->
            if (target == "add" || personal.cameras.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    HomeDisplayText("新增你的\n松下相机", Modifier.fillMaxWidth().weight(1f), 72f, centered = true)
                    TextButton(enabled = !camera.busy, onClick = { onConnect(null) }) { Text("点按开始连接 →", color = Color.White.copy(.75f)) }
                    if (personal.cameras.isNotEmpty()) TextButton(onClick = { mode = "normal" }) { Text("返回已有相机", color = Color.White) }
                }
            } else if (target == "delete") {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("删除本机", fontFamily = MirrorFonts.Dots, fontSize = 30.sp, color = Color.White)
                    Text(personal.cameras.getOrNull(pager.currentPage)?.model.orEmpty(), color = Color.White, fontSize = 24.sp)
                    Text("移除本机记录，已传入的照片会保留。", color = Color.White)
                    Row {
                        TextButton(enabled = !camera.busy && currentId != null, onClick = { deleting = currentId }) { Text("移除记录", color = Color.White) }
                        TextButton(onClick = { mode = "normal" }) { Text("返回", color = Color.White) }
                    }
                }
            } else {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
                    val saved = personal.cameras.getOrNull(index)
                    val connected = camera.connected && saved?.id == camera.device?.rememberedIdentity()
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("你的", color = Color.White, fontSize = 21.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                        Text("松下相机", color = Color.White.copy(.6f), fontSize = 21.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        HomeDisplayText("Lumix", Modifier.fillMaxWidth().height(30.dp), 30f)
                        HomeDisplayText(saved?.model?.removePrefix("DC-")?.removePrefix("DMC-") ?: "—",
                            Modifier.fillMaxWidth().height(70.dp), 76f)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White.copy(.08f))
                                .border(.7.dp, Color.White.copy(.26f), RoundedCornerShape(22.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Canvas(Modifier.size(6.dp)) { drawCircle(if (connected) Color(0xFF5BEB9B) else Color(0xFFD9CBA4)) }
                                Text(if (connected) "已连接" else "未连接", color = Color.White, fontSize = 11.sp, lineHeight = 14.sp)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(enabled = !camera.busy, onClick = { onConnect(saved?.id) }) {
                                Text(if (connected) "浏览相册 →" else "连接 →", color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            }
        }
        if (personal.cameras.size > 1 && mode == "normal") Row(Modifier.align(Alignment.TopEnd).padding(14.dp)) {
            IconButton(onClick = { scope.launch { val page = (pager.currentPage - 1 + count) % count
                if (reduced) pager.scrollToPage(page) else pager.animateScrollToPage(page) } },
                modifier = Modifier.semantics { contentDescription = "上一台相机" }) {
                DotIcon(DotGlyph.LEFT, tint = Color.White)
            }
            IconButton(onClick = { scope.launch { val page = (pager.currentPage + 1) % count
                if (reduced) pager.scrollToPage(page) else pager.animateScrollToPage(page) } },
                modifier = Modifier.semantics { contentDescription = "下一台相机" }) {
                DotIcon(DotGlyph.RIGHT, tint = Color.White)
            }
        }
        if (mode == "normal" && personal.cameras.isNotEmpty()) TextButton(onClick = { mode = "add" },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = if (count > 1) 54.dp else 12.dp)) {
            Text("＋ 新增", color = Color.White, fontSize = 12.sp)
        }
    }
    deleting?.let { id -> MirrorDialog("删除本机记录？", onDismiss = { deleting = null }) { close ->
        Text("移除相机卡片和本机名称记录，保留照片、传输记录和创作记录。")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MirrorTextAction("取消", close, Modifier.weight(1f))
            MirrorButton(onClick = {
                if (camera.device?.rememberedIdentity() == id) workspace.disconnectCamera()
                workspace.forgetCamera(id); mode = "normal"; close()
            }, Modifier.weight(1f), enabled = !camera.busy, destructive = true) { Text("删除") }
        }
    } }
}

@Composable
private fun WelcomePerspective(modifier: Modifier) {
    val context = LocalContext.current
    val dark = com.lumixfilmmirror.design.LocalFilmDark.current
    val font = remember { ResourcesCompat.getFont(context, com.lumixfilmmirror.design.R.font.nhl_minnesota) }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Canvas(modifier.semantics { contentDescription = "Welcome" }) {
        paint.typeface = font; paint.textSize = 100f
        paint.textSize *= size.width / paint.measureText("WELCOME")
        val fm = paint.fontMetrics
        val naturalHeight = fm.bottom - fm.top
        val matrix = android.graphics.Matrix()
        matrix.setPolyToPoly(floatArrayOf(0f, 0f, size.width, 0f, size.width, naturalHeight, 0f, naturalHeight), 0,
            floatArrayOf(0f, 0f, size.width, size.height * .23f, size.width, size.height, 0f, size.height), 0, 4)
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, naturalHeight,
            if (dark) intArrayOf(0xFFF6F3E9.toInt(), 0xFFE8EAE6.toInt(), 0xFF659EC0.toInt())
            else intArrayOf(0xFF222A2E.toInt(), 0xFF294459.toInt(), 0xFF5D8FA8.toInt()), floatArrayOf(0f, .62f, 1f), android.graphics.Shader.TileMode.CLAMP)
        drawContext.canvas.nativeCanvas.apply { save(); concat(matrix); drawText("WELCOME", 0f, -fm.top, paint); restore() }
    }
}

@Composable
internal fun Atmosphere(modifier: Modifier, colours: List<Color>, flowing: Boolean = false,
    content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Box(modifier.shadow(16.dp, shape, ambientColor = colours.first().copy(.15f), spotColor = colours.first().copy(.24f))
        .clip(shape).background(Brush.verticalGradient(colours)).border(1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(.55f), Color.White.copy(.06f), Color.White.copy(.22f))), shape)) {
        if (flowing) LiquidMaterial(Modifier.fillMaxSize(), colours, moving = true)
        HomeMaterialLighting(Modifier.fillMaxSize(), colours)
        content()
    }
}

@Composable
private fun ActionAtmosphere(title: String, subtitle: String, colours: List<Color>, modifier: Modifier, height: Dp,
    glyph: DotGlyph, footnote: String, onClick: () -> Unit) {
    Atmosphere(modifier.height(height).clickable(role = Role.Button, onClick = onClick), colours) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(.09f))
                    .border(.8.dp, Color.White.copy(.28f), CircleShape), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalMirrorDotArt provides false) { DotIcon(glyph, Modifier.size(22.dp), Color.White) }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(30.dp).border(.8.dp, Color.White.copy(.3f), CircleShape),contentAlignment = Alignment.Center) {
                    DotIcon(DotGlyph.RIGHT, Modifier.size(16.dp), Color.White)
                }
            }
            HomeDisplayText(title, Modifier.fillMaxWidth().weight(1f), 46f)
            Text(subtitle, color = Color.White.copy(.76f), fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
            Text(footnote, color = Color.White.copy(.58f), fontSize = 9.sp, lineHeight = 11.sp)
        }
    }
}

/** Fit display artwork to the space, leaving captions and touch targets at their readable size. */
@Composable
private fun HomeDisplayText(text: String, modifier: Modifier, preferredSize: Float, centered: Boolean = false) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = with(density) { maxWidth.roundToPx() }
        val height = with(density) { maxHeight.roundToPx() }
        val lines = text.count { it == '\n' } + 1
        val size = remember(text, width, height, density.fontScale, preferredSize) {
            var candidate = preferredSize
            while (candidate > 14f) {
                val result = measurer.measure(AnnotatedString(text),
                    style = TextStyle(fontFamily = MirrorFonts.Dots, fontSize = candidate.sp, lineHeight = (candidate * 1.15f).sp),
                    softWrap = true, maxLines = lines, constraints = Constraints(maxWidth = width))
                if (!result.hasVisualOverflow && result.size.height <= height) break
                candidate -= 1f
            }
            candidate
        }
        Text(text, modifier = Modifier.align(if (centered) Alignment.Center else Alignment.TopStart),
            style = TextStyle(fontFamily = MirrorFonts.Dots, fontSize = size.sp, lineHeight = (size * 1.15f).sp),
            color = Color.White, softWrap = true, maxLines = lines,
            textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
            overflow = if (lines > 1) TextOverflow.Clip else TextOverflow.Ellipsis)
    }
}

@Composable
private fun UsageCard(state: WorkspaceState, transfers: List<TransferTask>, height: Dp) {
    val month = YearMonth.now()
    val captures = remember(state.captureDays) {
        state.captureDays.mapNotNull { (day, count) -> runCatching { LocalDate.parse(day) to count }.getOrNull() }.toMap()
    }
    val imported = remember(transfers, month) { transfers.count { task ->
        task.state in setOf("IMPORTED", "SAVED") && task.completedAt?.let {
            YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) == month
        } == true
    } }
    val total = captures.filterKeys { YearMonth.from(it) == month }.values.sum()
    Atmosphere(Modifier.fillMaxWidth().height(height), listOf(Color(0xFF7F9BB9), Color(0xFF536E94), Color(0xFF293B5A))) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("相机用量", Modifier.weight(1f), fontFamily = MirrorFonts.Dots, fontSize = 27.sp, color = Color.White)
                Text("${month.year} / ${month.monthValue.toString().padStart(2, '0')}", color = Color.White.copy(.8f), fontSize = 13.sp)
            }
            Canvas(Modifier.fillMaxWidth().weight(1f).semantics {
                contentDescription = "${month.monthValue} 月拍摄 $total 张，传入 $imported 项"
            }) {
                val first = month.atDay(1)
                val leading = first.dayOfWeek.value - 1
                val columns = 7; val rows = 6
                val cellWidth = size.width / columns
                val cellHeight = size.height / rows
                val radius = minOf(cellWidth, cellHeight) * .15f
                repeat(month.lengthOfMonth()) { index ->
                    val slot = leading + index
                    val count = captures[first.plusDays(index.toLong())] ?: 0
                    val color = when {
                        count == 0 -> Color.White.copy(.16f)
                        count < 5 -> Color(0xFF94BBD5)
                        count < 20 -> Color(0xFFC8E8EE)
                        else -> Color(0xFFFFD293)
                    }
                    drawCircle(color, radius.coerceAtMost(4.dp.toPx()),
                        Offset((slot % columns + .5f)*cellWidth, (slot / columns + .5f)*cellHeight))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("本月拍摄 $total 张", fontSize = 14.sp, color = Color.White)
                Text("传入 $imported 项", fontSize = 14.sp, color = Color.White.copy(.75f))
            }
        }
    }
}
