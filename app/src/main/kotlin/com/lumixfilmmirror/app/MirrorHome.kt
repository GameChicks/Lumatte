package com.lumixfilmmirror.app

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts
import com.lumixfilmmirror.storage.TransferTask
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun MirrorHome(state: WorkspaceState, camera: CameraWorkspaceState, personal: MirrorPersonalState,
    transfers: List<TransferTask>, workspace: WorkspaceViewModel, onCamera: () -> Unit, onAlbum: () -> Unit, onTools: () -> Unit) {
    val tones = LocalFilmMaterials.current
    var noteEditing by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    var settings by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 170.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("LUMIX / FILM MIRROR", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = tones.secondaryInk)
                TextButton(onClick = { settings = true }) { Text("偏好") }
            }
            Box(Modifier.fillMaxWidth().height(124.dp)) {
                Text("WELCOME", Modifier.fillMaxWidth().align(Alignment.BottomStart).graphicsLayer { translationY = 24.dp.toPx() }, fontFamily = MirrorFonts.Welcome,
                    fontSize = 91.sp, lineHeight = 100.sp, maxLines = 1)
            }
        }
        item { CameraHero(camera, personal, workspace, onCamera) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionAtmosphere("连接\n相机", "蓝牙与 Wi-Fi", listOf(Color(0xFFECC080), Color(0xFFCD7055), Color(0xFF316B8F)),
                    Modifier.weight(1f), onCamera)
                ActionAtmosphere("进入\n相册", "选择与传入照片", listOf(Color(0xFF325E4D), Color(0xFF87AE69), Color(0xFFEAD68B)),
                    Modifier.weight(1f), onAlbum)
            }
        }
        item { UsageCard(state, transfers) }
        item {
            Atmosphere(Modifier.fillMaxWidth().height(248.dp).clickable(role = Role.Button) {
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
    workspace: WorkspaceViewModel, onConnect: () -> Unit) {
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
        else -> listOf(Color(0xFF98C9DB), Color(0xFF507E9E), Color(0xFF193B58))
    }
    Atmosphere(Modifier.fillMaxWidth().height(248.dp).then(gesture), colours,
        flowing = !reduced && (mode == "add" || personal.cameras.isEmpty())) {
        AnimatedContent(targetState = mode, label = "camera-card-state") { target ->
            if (target == "add" || personal.cameras.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("新增你的\n松下相机", fontFamily = MirrorFonts.Dots, fontSize = 31.sp, color = Color.White)
                    TextButton(enabled = !camera.busy, onClick = onConnect) { Text("开始连接 →", color = Color.White) }
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
                        Spacer(Modifier.height(12.dp))
                        Text("Lumix", fontFamily = MirrorFonts.Dots, fontSize = 26.sp, color = Color.White)
                        Text(saved?.model?.removePrefix("DC-") ?: "—", fontFamily = MirrorFonts.Dots, fontSize = 46.sp,
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
internal fun Atmosphere(modifier: Modifier, colours: List<Color>, flowing: Boolean = false,
    content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    val phase = if (flowing) rememberInfiniteTransition(label = "camera-ripple").animateFloat(0f, 1f,
        infiniteRepeatable(tween(4800, easing = LinearEasing)), label = "ripple-phase").value else 0f
    Box(modifier.clip(shape).background(Brush.verticalGradient(colours)).border(1.dp, Color.White.copy(.25f), shape)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(Color.White.copy(.22f), Color.Transparent),
                center = Offset(size.width * (.2f + phase * .45f), size.height * .2f), radius = size.width * .85f))
            for (x in 0 until (size.width / 13.dp.toPx()).toInt()) for (y in 0 until (size.height / 13.dp.toPx()).toInt())
                drawCircle(Color.White.copy(.075f), .65.dp.toPx(), Offset(x * 13.dp.toPx(), y * 13.dp.toPx()))
            if (flowing) repeat(3) { n ->
                val fraction = (phase + n / 3f) % 1f
                drawCircle(Color.White.copy((1f - fraction) * .14f), fraction * size.width,
                    center, style = Stroke(1.4.dp.toPx()))
            }
        }
        content()
    }
}

@Composable
private fun ActionAtmosphere(title: String, subtitle: String, colours: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    Atmosphere(modifier.height(190.dp).clickable(role = Role.Button, onClick = onClick), colours) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontFamily = MirrorFonts.Dots, fontSize = 34.sp, lineHeight = 40.sp, color = Color.White)
            Text(subtitle, color = Color.White.copy(.85f), fontSize = 12.sp)
        }
        DotIcon(DotGlyph.RIGHT, Modifier.align(Alignment.TopEnd).padding(14.dp), Color.White)
    }
}

@Composable
private fun UsageCard(state: WorkspaceState, transfers: List<TransferTask>) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var metric by rememberSaveable { mutableStateOf("transfer") }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var monthDetail by remember { mutableStateOf(false) }
    val month = YearMonth.now().plusMonths(offset.toLong())
    val transferDates = remember(transfers) { transfers.filter { it.state in setOf("IMPORTED", "SAVED") && it.completedAt != null }
        .groupingBy { Instant.ofEpochMilli(requireNotNull(it.completedAt)).atZone(ZoneId.systemDefault()).toLocalDate() }.eachCount() }
    val captureDates = remember(state.processingBatches) {
        state.processingBatches.flatMap { it.photos }.distinctBy { it.id }.mapNotNull { photo ->
            val value = photo.metadata.capturedAt.take(10).replace(':', '-')
            runCatching { LocalDate.parse(value) }.getOrNull()
        }.groupingBy { it }.eachCount()
    }
    val dates = if (metric == "transfer") transferDates else captureDates
    val total = dates.filterKeys { YearMonth.from(it) == month }.values.sum()
    val pager = rememberPagerState(initialPage = 1, pageCount = { 3 })
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != 1 && !pager.isScrollInProgress) {
            offset += pager.settledPage - 1; pager.scrollToPage(1)
        }
    }
    Atmosphere(Modifier.fillMaxWidth().height(248.dp), listOf(Color(0xFF839FAF), Color(0xFF526E85), Color(0xFF2A4258))) {
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
