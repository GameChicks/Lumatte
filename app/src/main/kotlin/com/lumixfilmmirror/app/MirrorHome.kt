package com.lumixfilmmirror.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
internal fun MirrorHome(state: WorkspaceState, camera: CameraWorkspaceState, personal: MirrorPersonalState,
    transfers: List<TransferTask>, workspace: WorkspaceViewModel, onCamera: () -> Unit, onAlbum: () -> Unit, onTools: () -> Unit) {
    val tones = LocalFilmMaterials.current
    var noteEditing by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    var settings by rememberSaveable { mutableStateOf(false) }
    CompositionLocalProvider(LocalMirrorDotArt provides true) {
    BoxWithConstraints(Modifier.fillMaxSize().background(if (isSystemInDarkTheme()) Color(0xFF070A0B) else Color(0xFFF1F0EB))) {
    val cardHeight = ((maxWidth - 40.dp) * .654f).coerceIn(218.dp, 280.dp)
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 170.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("LUMIX / FILM MIRROR", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = tones.secondaryInk)
                TextButton(onClick = { settings = true }) { Text("偏好") }
            }
        }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth().height(cardHeight + maxWidth * .40f)) {
                WelcomePerspective(Modifier.fillMaxWidth().height(maxWidth * .53f).align(Alignment.TopCenter))
                CameraHero(camera, personal, workspace, onCamera, Modifier.align(Alignment.BottomCenter), cardHeight)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionAtmosphere("蓝牙\n连接", "连接你的相机", listOf(Color(0xFFFFCF78), Color(0xFFED653B), Color(0xFF428FA6)),
                    Modifier.weight(1f), cardHeight, onCamera)
                ActionAtmosphere("进入\n相册", "导入图片", listOf(Color(0xFF285D34), Color(0xFF89BB64), Color(0xFFF0DF93)),
                    Modifier.weight(1f), cardHeight, onAlbum)
            }
        }
        item { UsageCard(state, transfers, cardHeight) }
        item {
            Atmosphere(Modifier.fillMaxWidth().height(cardHeight).clickable(role = Role.Button) {
                note = personal.journal; noteEditing = true
            }, listOf(Color(0xFFAEA891), Color(0xFF777967), Color(0xFF414D43))) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("创作记录", fontFamily = MirrorFonts.Dots, fontSize = 30.sp, color = Color.White)
                    Text("“", fontFamily = MirrorFonts.Dots, fontSize = 36.sp, color = Color.White.copy(.6f))
                    Text(personal.journal.ifBlank { "写下今天的光线、地点与故事。" }, Modifier.weight(1f).padding(start = 12.dp),
                        maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color.White, fontSize = 16.sp)
                    Text("”", Modifier.align(Alignment.End), fontFamily = MirrorFonts.Dots, fontSize = 36.sp,
                        color = Color.White.copy(.6f))
                }
            }
        }
    }
    }
    }
    if (noteEditing) AlertDialog(onDismissRequest = { noteEditing = false }, title = {
        Text("创作记录", fontFamily = MirrorFonts.Dots)
    }, text = { OutlinedTextField(value = note, onValueChange = { note = it.take(4000) }, minLines = 5,
        maxLines = 10, label = { Text("文字仅保存在本机") }) }, confirmButton = {
        TextButton(onClick = { workspace.updatePersonal { it.copy(journal = note) }; noteEditing = false }) { Text("保存") }
    }, dismissButton = { TextButton(onClick = { noteEditing = false }) { Text("取消") } })
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("显示与动态效果") }, text = {
        Column {
            Text("外观跟随系统浅色 / 深色模式。")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("减少动态效果", Modifier.weight(1f))
                Switch(personal.reducedMotion, { value -> workspace.updatePersonal { it.copy(reducedMotion = value) } })
            }
            Text("关闭自动展开、持续涟漪和模糊，保留点击操作。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { settings = false; onTools() }) { Text("批次、任务与缓存管理") }
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("完成") } })
}

@Composable
private fun CameraHero(camera: CameraWorkspaceState, personal: MirrorPersonalState,
    workspace: WorkspaceViewModel, onConnect: () -> Unit, modifier: Modifier, cardHeight: Dp) {
    val scope = rememberCoroutineScope()
    val count = maxOf(1, personal.cameras.size)
    val pager = rememberPagerState(pageCount = { count })
    var mode by rememberSaveable { mutableStateOf("normal") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(count) { if (pager.currentPage >= count) pager.scrollToPage(count - 1) }
    LaunchedEffect(camera.device?.udn, camera.connected, personal.cameras) {
        if (camera.connected) personal.cameras.indexOfFirst { it.id == camera.device?.rememberedIdentity() }
            .takeIf { it >= 0 }?.let { pager.animateScrollToPage(it) }
    }
    val reduced = LocalMirrorReducedMotion.current
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
        .clickable(enabled = mode == "add" || personal.cameras.isEmpty(), role = Role.Button, onClick = onConnect), colours,
        flowing = !reduced && (mode == "add" || personal.cameras.isEmpty())) {
        AnimatedContent(targetState = mode, label = "camera-card-state") { target ->
            if (target == "add" || personal.cameras.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val titleSize = (maxWidth.value / 4.6f).coerceIn(40f, 65f)
                        Text("新增你的\n松下相机", Modifier.fillMaxWidth(), fontFamily = MirrorFonts.Dots,
                            fontSize = titleSize.sp, lineHeight = (titleSize * 1.22f).sp, color = Color.White,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    TextButton(enabled = !camera.busy, onClick = onConnect) { Text("点按开始连接 →", color = Color.White.copy(.75f)) }
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
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Text("你的", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("松下相机", color = Color.White.copy(.6f), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("Lumix", fontFamily = MirrorFonts.Dots, fontSize = 27.sp, lineHeight = 33.sp, color = Color.White)
                        Text(saved?.model?.removePrefix("DC-") ?: "—", fontFamily = MirrorFonts.Dots, fontSize = 61.sp, lineHeight = 72.sp,
                            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (connected) "● 已连接" else "○ 未连接", Modifier.weight(1f), color = Color.White, fontSize = 12.sp)
                            TextButton(enabled = !camera.busy, onClick = onConnect) {
                                Text(if (connected) "浏览相册 →" else "连接 →", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
        if (personal.cameras.size > 1 && mode == "normal") Row(Modifier.align(Alignment.TopEnd).padding(14.dp)) {
            IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1 + count) % count) } },
                modifier = Modifier.semantics { contentDescription = "上一台相机" }) {
                DotIcon(DotGlyph.LEFT, tint = Color.White)
            }
            IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % count) } },
                modifier = Modifier.semantics { contentDescription = "下一台相机" }) {
                DotIcon(DotGlyph.RIGHT, tint = Color.White)
            }
        }
        if (mode == "normal" && personal.cameras.isNotEmpty()) TextButton(onClick = { mode = "add" },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = if (count > 1) 54.dp else 12.dp)) {
            Text("＋ 新增", color = Color.White, fontSize = 12.sp)
        }
    }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除本机记录？") },
        text = { Text("移除相机卡片和本机名称记录，保留照片、传输记录和创作记录。") }, confirmButton = {
            TextButton(onClick = {
                if (camera.device?.rememberedIdentity() == id) workspace.disconnectCamera()
                workspace.forgetCamera(id); deleting = null; mode = "normal"
            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun WelcomePerspective(modifier: Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
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
        content()
    }
}

@Composable
private fun ActionAtmosphere(title: String, subtitle: String, colours: List<Color>, modifier: Modifier, height: Dp, onClick: () -> Unit) {
    Atmosphere(modifier.height(height).clickable(role = Role.Button, onClick = onClick), colours) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontFamily = MirrorFonts.Dots, fontSize = 49.sp, lineHeight = 64.sp, color = Color.White)
            Text(subtitle, color = Color.White.copy(.68f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
        DotIcon(DotGlyph.RIGHT, Modifier.align(Alignment.TopEnd).padding(14.dp), Color.White)
    }
}

@Composable
private fun UsageCard(state: WorkspaceState, transfers: List<TransferTask>, height: Dp) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var metric by rememberSaveable { mutableStateOf("transfer") }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var monthDetail by remember { mutableStateOf(false) }
    val month = YearMonth.now().plusMonths(offset.toLong())
    val transferDates = remember(transfers) { transfers.filter { it.state in setOf("IMPORTED", "SAVED") && it.completedAt != null }
        .groupingBy { Instant.ofEpochMilli(requireNotNull(it.completedAt)).atZone(ZoneId.systemDefault()).toLocalDate() }.eachCount() }
    val captureDates = remember(state.captureDays) {
        state.captureDays.mapKeys { LocalDate.parse(it.key) }
    }
    val dates = if (metric == "transfer") transferDates else captureDates
    val total = dates.filterKeys { YearMonth.from(it) == month }.values.sum()
    val pager = rememberPagerState(initialPage = 1, pageCount = { 3 })
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != 1 && !pager.isScrollInProgress) {
            offset += pager.settledPage - 1; pager.scrollToPage(1)
        }
    }
    Atmosphere(Modifier.fillMaxWidth().height(height), listOf(Color(0xFF839FAF), Color(0xFF526E85), Color(0xFF2A4258))) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("相机用量", Modifier.weight(1f), fontFamily = MirrorFonts.Dots, fontSize = 27.sp, color = Color.White)
                Text("$total ${if (metric == "capture") "张" else "项"}", color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { offset-- }, modifier = Modifier.size(48.dp).semantics { contentDescription = "上一个月" }) { DotIcon(DotGlyph.LEFT, tint = Color.White) }
                TextButton(onClick = { monthDetail = true }, modifier = Modifier.weight(1f)) {
                    Text("${month.year} / ${month.monthValue.toString().padStart(2, '0')} · 每日", color = Color.White)
                }
                IconButton(onClick = { offset++ }, modifier = Modifier.size(48.dp).semantics { contentDescription = "下一个月" }) { DotIcon(DotGlyph.RIGHT, tint = Color.White) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                val shown = month.plusMonths((page - 1).toLong())
                val first = shown.atDay(1)
                val leading = first.dayOfWeek.value - 1
                val weeks = (leading + shown.lengthOfMonth() + 6) / 7
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    repeat(weeks) { week -> Column(verticalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxHeight()) {
                        repeat(7) { day ->
                            val date = first.plusDays((week * 7 + day - leading).toLong())
                            val inside = YearMonth.from(date) == shown
                            val count = if (inside) dates[date] ?: 0 else 0
                            val colour = when { !inside -> Color.Transparent; count == 0 -> Color.White.copy(.16f)
                                count < 5 -> Color(0xFF70AABF); count < 20 -> Color(0xFF8CCCE0); else -> Color(0xFFF3D49B) }
                            Canvas(Modifier.size(10.dp).semantics { contentDescription = if (inside) "$date，$count 项" else "月外日期" }) {
                                drawCircle(colour, 3.dp.toPx())
                            }
                        }
                    } }
                }
            }
            Row {
                TextButton(onClick = { metric = "transfer" }) { Text("传入", color = if (metric == "transfer") Color.White else Color.White.copy(.5f)) }
                TextButton(onClick = { metric = "capture" }) { Text("拍摄 · 已导入", color = if (metric == "capture") Color.White else Color.White.copy(.5f)) }
            }
        }
    }
    if (monthDetail) AlertDialog(onDismissRequest = { monthDetail = false }, title = { Text("$month · 每日用量") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { Text("拍摄量为已导入照片的 EXIF 日期；传入量为实际完成日。", style = MaterialTheme.typography.bodySmall) }
            for (day in 1..month.lengthOfMonth()) item {
                val date = month.atDay(day)
                TextButton(onClick = { monthDetail = false; selectedDay = date }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("$day 日 · 拍摄 ${captureDates[date] ?: 0} 张 · 传入 ${transferDates[date] ?: 0} 项")
                }
            }
            item { Text("旧记录中 ${transfers.count { it.state in setOf("IMPORTED", "SAVED") && it.completedAt == null }} 项缺少传入完成日期，不计入月份。",
                style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = { monthDetail = false }) { Text("完成") } })
    selectedDay?.let { date -> AlertDialog(onDismissRequest = { selectedDay = null }, title = { Text(date.toString()) },
        text = { Text("已导入照片中拍摄于此日：${captureDates[date] ?: 0} 张\n此日完成传入：${transferDates[date] ?: 0} 项\n\n拍摄日期来自照片 EXIF；缺失日期不推算。传入按实际完成日归组，含照片与视频。旧记录缺少完成日期时不推算。") },
        confirmButton = { TextButton(onClick = { selectedDay = null }) { Text("完成") } }) }
}
