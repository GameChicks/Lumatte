package app.filmcompanion.ui



import android.widget.Toast

import android.net.Uri

import android.app.Activity

import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.Image

import androidx.compose.foundation.background

import androidx.compose.foundation.clickable

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.statusBarsPadding

import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.lazy.grid.GridCells

import androidx.compose.foundation.lazy.grid.LazyVerticalGrid

import androidx.compose.foundation.lazy.grid.items

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.outlined.Add

import androidx.compose.material.icons.outlined.CameraAlt

import androidx.compose.material.icons.outlined.Close

import androidx.compose.material.icons.outlined.FolderOpen

import androidx.compose.material.icons.outlined.Settings

import androidx.compose.material.icons.outlined.Tune

import androidx.compose.material3.Button

import androidx.compose.material3.ButtonDefaults

import androidx.compose.material3.AlertDialog

import androidx.compose.material3.TextButton

import androidx.compose.material3.Icon

import androidx.compose.material3.IconButton

import androidx.compose.material3.OutlinedTextField

import androidx.compose.material3.Text

import androidx.compose.runtime.Composable

import androidx.compose.runtime.collectAsState

import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.remember

import androidx.compose.runtime.SideEffect

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.res.painterResource

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp

import androidx.core.view.WindowCompat

import androidx.compose.ui.window.Dialog

import app.filmcompanion.R

import app.filmcompanion.FilmCompanionApplication

import app.filmcompanion.data.CameraEntity

import app.filmcompanion.data.FilmRepository

import app.filmcompanion.data.FilmStockEntity

import app.filmcompanion.data.LensEntity

import app.filmcompanion.data.RollEntity

import app.filmcompanion.data.MeterCalibrationEntity

import app.filmcompanion.domain.EiEngine

import app.filmcompanion.media.BackupService

import java.time.LocalDate

import kotlinx.coroutines.launch



private enum class Page { LIBRARY, METER, RECORD, ROLLS, SETTINGS }

private enum class LibraryTab { CAMERAS, FILMS }



@Composable

fun FilmCompanionApp(repository: FilmRepository) {

    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    val cameras by repository.cameras.collectAsState(initial = emptyList())

    val lenses by repository.lenses.collectAsState(initial = emptyList())

    val stocks by repository.filmStocks.collectAsState(initial = emptyList())

    val rolls by repository.rolls.collectAsState(initial = emptyList())

    var pageName by rememberSaveable { mutableStateOf(Page.LIBRARY.name) }

    var libraryTabName by rememberSaveable { mutableStateOf(LibraryTab.CAMERAS.name) }

    var dialog by remember { mutableStateOf<String?>(null) }

    var detailCamera by remember { mutableStateOf<CameraEntity?>(null) }

    var detailLens by remember { mutableStateOf<LensEntity?>(null) }

    var selectedRollId by rememberSaveable { mutableStateOf<String?>(null) }

    val page = Page.valueOf(pageName)

    val libraryTab = LibraryTab.valueOf(libraryTabName)

    val activeRoll = rolls.firstOrNull { it.id == selectedRollId && it.status == "SHOOTING" }

        ?: rolls.firstOrNull { it.status == "SHOOTING" }

    val selectedRoll = rolls.firstOrNull { it.id == selectedRollId } ?: activeRoll ?: rolls.firstOrNull()



    val dark = page == Page.METER || page == Page.RECORD

    SideEffect {

        (context as? Activity)?.window?.let { window ->

            WindowCompat.getInsetsController(window, window.decorView).apply {

                isAppearanceLightStatusBars = !dark

                isAppearanceLightNavigationBars = !dark

            }

        }

    }

    Box(Modifier.fillMaxSize().background(if (dark) FilmColors.Instrument else FilmColors.Library)) {

        when (page) {

            Page.LIBRARY -> LibraryScreen(

                cameras, lenses, stocks, rolls, libraryTab,

                onTab = { libraryTabName = it.name },

                onAdd = { dialog = if (libraryTab == LibraryTab.CAMERAS) "add_camera" else "add_film" },

                onAddLens = { dialog = "add_lens" },

                onSettings = { pageName = Page.SETTINGS.name },

                onCamera = { detailCamera = it },

                onLens = { detailLens = it },

                onRoll = { selectedRollId = it.id; pageName = Page.ROLLS.name },

                onNewRoll = { dialog = "new_roll" }

            )

            Page.METER, Page.RECORD -> InstrumentScreen(

                roll = activeRoll,

                film = stocks.firstOrNull { it.id == activeRoll?.filmStockId },

                lens = lenses.firstOrNull { it.id == activeRoll?.defaultLensId },

                isRecord = page == Page.RECORD,

                onNewRoll = { dialog = "new_roll" },

                onRecord = { aperture, shutter, ev100, mode, focal, evComp, actualAperture, actualShutter, confirmed ->

                    val roll = activeRoll

                    if (roll != null) scope.launch {

                        val saved = repository.recordFrame(

                            roll, roll.defaultLensId, focal, aperture, shutter,

                            actualAperture, actualShutter,

                            confirmed, ev100, mode, evComp, ""

                        )

                        Toast.makeText(context, if (saved == null) "本卷已满或已结束" else "已记录第 ${saved.frameNumber} 张", Toast.LENGTH_SHORT).show()

                    }

                }

            )

            Page.ROLLS -> RollScreen(

                rolls, stocks, cameras, selectedRoll,

                onSelect = { selectedRollId = it.id },

                onNewRoll = { dialog = "new_roll" },

                onFinish = { roll -> scope.launch { repository.setRollStatus(roll, "FINISHED") } },

                onOpen = { dialog = "roll_detail" }

            )

            Page.SETTINGS -> SettingsScreen(repository, onBack = { pageName = Page.LIBRARY.name })

        }



        if (page != Page.SETTINGS) {

            BottomNavigation(

                selected = page,

                onClick = { pageName = it.name },

                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)

            )

        }

    }



    detailCamera?.let { camera ->

        GearDetailDialog(camera, onClose = { detailCamera = null })

    }

    detailLens?.let { lens -> LensDetailDialog(lens, onClose = { detailLens = null }) }

    when (dialog) {

        "add_camera" -> AddCameraDialog(

            onDismiss = { dialog = null },

            onSubmit = { brand, model, mount -> scope.launch { repository.addCamera(brand, model, mount) }; dialog = null }

        )

        "add_lens" -> AddLensDialog(

            onDismiss = { dialog = null },

            onSubmit = { brand, model, mount, min, max, aperture -> scope.launch { repository.addLens(brand, model, mount, min, max, aperture) }; dialog = null }

        )

        "add_film" -> AddFilmDialog(

            onDismiss = { dialog = null },

            onSubmit = { brand, model, iso, type -> scope.launch { repository.addFilmStock(brand, model, iso, type) }; dialog = null }

        )

        "new_roll" -> NewRollDialog(cameras, lenses, stocks,

            onDismiss = { dialog = null },

            onCreate = { name, camera, lens, film, ei, count, expiration, storage ->

                scope.launch {

                    val roll = repository.startRoll(name, camera, lens, film, ei, count, expiration, storage)

                    selectedRollId = roll.id

                    pageName = Page.METER.name

                }

                dialog = null

            }

        )

        "roll_detail" -> selectedRoll?.let { roll ->

            RollDetailDialog(roll, repository, onClose = { dialog = null })

        }

    }

}



@Composable

private fun LibraryScreen(

    cameras: List<CameraEntity>, lenses: List<LensEntity>, stocks: List<FilmStockEntity>, rolls: List<RollEntity>,

    tab: LibraryTab, onTab: (LibraryTab) -> Unit, onAdd: () -> Unit, onAddLens: () -> Unit, onSettings: () -> Unit,

    onCamera: (CameraEntity) -> Unit, onLens: (LensEntity) -> Unit, onRoll: (RollEntity) -> Unit, onNewRoll: () -> Unit

) {

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 24.dp)) {

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {

            Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFFD6D7D3)).padding(4.dp)) {

                TabPill("▤", tab == LibraryTab.FILMS) { onTab(LibraryTab.FILMS) }

                TabPill("▣", tab == LibraryTab.CAMERAS) { onTab(LibraryTab.CAMERAS) }

            }

            Spacer(Modifier.weight(1f))

            RoundIcon(Icons.Outlined.Settings, "设置", onSettings)

            Spacer(Modifier.size(8.dp))

            RoundIcon(Icons.Outlined.CameraAlt, "添加镜头", onAddLens)

            Spacer(Modifier.size(8.dp))

            RoundIcon(Icons.Outlined.Add, "新增", onAdd)

        }

        Spacer(Modifier.height(32.dp))

        if (tab == LibraryTab.CAMERAS) {

            Text("持有 ${cameras.size} 台", modifier = Modifier.padding(horizontal = 20.dp), color = FilmColors.Muted, fontSize = 15.sp)

            Spacer(Modifier.height(18.dp))

            LazyVerticalGrid(

                columns = GridCells.Fixed(2),

                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 116.dp),

                horizontalArrangement = Arrangement.spacedBy(12.dp),

                verticalArrangement = Arrangement.spacedBy(12.dp)

            ) {

                items(cameras, key = { it.id }) { camera ->

                    CameraCard(camera) { onCamera(camera) }

                }

                item { AddCard("添加机身", onAdd) }

                item { AddCard("添加镜头", onAddLens) }

                items(lenses, key = { it.id }) { lens -> LensCard(lens) { onLens(lens) } }

            }

        } else {

            val shooting = rolls.filter { it.status == "SHOOTING" }

            Text("正在拍摄 ${shooting.size} 卷", modifier = Modifier.padding(horizontal = 20.dp), color = FilmColors.Muted, fontSize = 15.sp)

            Spacer(Modifier.height(18.dp))

            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 116.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                items(shooting.size) { index -> RollCard(shooting[index], stocks.firstOrNull { it.id == shooting[index].filmStockId }) { onRoll(shooting[index]) } }

                item { AddCard("新建一卷", onNewRoll) }

                item { Text("胶卷目录", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp)) }

                items(stocks.size) { index -> FilmStockRow(stocks[index]) }

            }

        }

private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {

    Box(Modifier.size(width = 52.dp, height = 38.dp).clip(RoundedCornerShape(21.dp)).background(if (selected) FilmColors.LibraryRaised else Color.Transparent).clickable(onClick = onClick), contentAlignment = Alignment.Center) {

        Text(label, color = if (selected) FilmColors.Ink else FilmColors.Muted, fontSize = 22.sp)

    }

}



@Composable

private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {

    Box(Modifier.size(46.dp).clip(CircleShape).background(FilmColors.LibraryRaised).clickable(onClick = onClick), contentAlignment = Alignment.Center) {

        Icon(icon, contentDescription = description, tint = FilmColors.Ink, modifier = Modifier.size(24.dp))

    }

}



@Composable

private fun CameraCard(camera: CameraEntity, onClick: () -> Unit) {

    Column(Modifier.fillMaxWidth().height(216.dp).clip(RoundedCornerShape(28.dp)).background(FilmColors.LibraryCard).clickable(onClick = onClick).padding(16.dp)) {

        GearImage(camera.imageKey, Modifier.fillMaxWidth().height(139.dp))

        Spacer(Modifier.height(8.dp))

        Text("${camera.brand} ${camera.model}", color = FilmColors.Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

        Text("${camera.format}  ·  单反", color = FilmColors.Muted, fontSize = 11.sp)

    }

}



@Composable

private fun LensCard(lens: LensEntity, onClick: () -> Unit) {

    Column(Modifier.fillMaxWidth().height(216.dp).clip(RoundedCornerShape(28.dp)).background(FilmColors.LibraryCard).clickable(onClick = onClick).padding(16.dp)) {

        GearImage(lens.imageKey, Modifier.fillMaxWidth().height(139.dp))

        Spacer(Modifier.height(8.dp))

        Text(lens.model, color = FilmColors.Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

        Text("${lens.focalMinMm}${if (lens.focalMaxMm != lens.focalMinMm) "–${lens.focalMaxMm}" else ""}mm  ·  f/${lens.maximumAperture}", color = FilmColors.Muted, fontSize = 11.sp)

    }

}



@Composable

private fun GearImage(key: String?, modifier: Modifier = Modifier) {

    val res = when (key) {

        "canon_ftbn" -> R.drawable.canon_ftbn

        "nfd_50_14" -> R.drawable.nfd_50_14

        "nfd_75_200" -> R.drawable.nfd_75_200

        "fd_135_25" -> R.drawable.fd_135_25

        else -> null

    }

    Box(modifier, contentAlignment = Alignment.Center) {

        if (res != null) Image(painterResource(res), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)

        else Icon(Icons.Outlined.CameraAlt, null, modifier = Modifier.size(66.dp), tint = FilmColors.Muted)

    }

}



@Composable

private fun AddCard(title: String, onClick: () -> Unit) {

    Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(28.dp)).background(FilmColors.LibraryRaised).clickable(onClick = onClick), contentAlignment = Alignment.Center) {

        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            Icon(Icons.Outlined.Add, null, tint = FilmColors.Muted)

            Text(title, color = FilmColors.Muted, fontSize = 13.sp)

        }

    }

}



@Composable

private fun RollCard(roll: RollEntity, stock: FilmStockEntity?, onClick: () -> Unit) {

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(FilmColors.LibraryCard).clickable(onClick = onClick).padding(20.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {

            FilmPackage(stock)

            Spacer(Modifier.size(16.dp))

            Column {

                Text(roll.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = FilmColors.Ink)

                Text("${stock?.model ?: "胶卷"} · EI ${roll.chosenEi}", fontSize = 13.sp, color = FilmColors.Muted)

            }

        }

        Spacer(Modifier.height(18.dp))

        Text("${roll.nextFrameNumber - 1} / ${roll.frameCapacity}", fontSize = 28.sp, fontWeight = FontWeight.Medium, color = FilmColors.Ink)

        Text("Box ISO ${roll.boxIso}  ·  本卷 EI ${roll.chosenEi}", fontSize = 12.sp, color = FilmColors.Muted)

    }

}



@Composable

fun FilmPackage(stock: FilmStockEntity?, modifier: Modifier = Modifier) {

    val color = when {

        stock?.model?.contains("UltraMax") == true -> Color(0xFFE5AF16)

        stock?.model?.contains("Gold") == true -> Color(0xFFE2B82C)

        stock?.model?.contains("Velvia") == true -> Color(0xFF3F856A)

        stock?.model?.contains("Ektar") == true -> Color(0xFFC9442C)

        stock?.model?.contains("ColorPlus") == true -> Color(0xFFE59A16)

        stock?.brand == "CineStill" -> Color(0xFF5C5C78)

        else -> Color(0xFFD1A55E)

    }

    Box(modifier.size(46.dp, 54.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF20201F))) {

        Box(Modifier.fillMaxWidth(0.76f).fillMaxHeight().background(color).align(Alignment.CenterEnd))

        Text("36", Modifier.align(Alignment.TopStart).padding(2.dp), color = Color.White, fontSize = 6.sp, fontWeight = FontWeight.Bold)

        Column(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.73f).padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {

            Text(stock?.brand ?: "FILM", color = Color.Black, fontSize = 6.sp, fontWeight = FontWeight.Black, maxLines = 1)

            Text(stock?.model?.replace("UltraMax", "ULTRAMAX") ?: "135", color = Color.Black, fontSize = 7.sp, fontWeight = FontWeight.Black, maxLines = 2, lineHeight = 7.sp)

            Text(stock?.boxIso?.toString() ?: "", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black)

        }

    }

}



@Composable

private fun FilmStockRow(stock: FilmStockEntity) {

    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(FilmColors.LibraryCard).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {

        FilmPackage(stock)

        Spacer(Modifier.size(14.dp))

        Column {

            Text("${stock.brand} ${stock.model}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = FilmColors.Ink)

            Text("Box ISO ${stock.boxIso} · ${stock.process}", fontSize = 12.sp, color = FilmColors.Muted)

        }

    }

}



@Composable

private fun BottomNavigation(selected: Page, onClick: (Page) -> Unit, modifier: Modifier = Modifier) {

    Row(modifier.clip(RoundedCornerShape(38.dp)).background(if (selected == Page.METER || selected == Page.RECORD) FilmColors.Panel else FilmColors.LibraryRaised).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {

        listOf(Triple(Page.METER, "◠", "测光"), Triple(Page.RECORD, "∞", "记录"), Triple(Page.LIBRARY, "▣", "器材"), Triple(Page.ROLLS, "▥", "胶卷")).forEach { (page, symbol, label) ->

            val active = selected == page

            Column(Modifier.size(width = 76.dp, height = 58.dp).clip(RoundedCornerShape(30.dp)).background(if (active) { if (selected == Page.METER || selected == Page.RECORD) Color.Black else Color(0xFFD4D5D2) } else Color.Transparent).clickable { onClick(page) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {

                Text(symbol, fontSize = 24.sp, color = if (selected == Page.METER || selected == Page.RECORD) FilmColors.Ivory else FilmColors.Ink, lineHeight = 25.sp)

                Text(label, fontSize = 11.sp, color = if (active) { if (selected == Page.METER || selected == Page.RECORD) FilmColors.Ivory else FilmColors.Ink } else FilmColors.Muted)

            }

        }

    }                Spacer(Modifier.weight(1f))



            }

            Spacer(Modifier.height(16.dp))

            GearImage(camera.imageKey, Modifier.fillMaxWidth().height(270.dp))

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                SpecTile("画幅", camera.format, Modifier.weight(1f))

                SpecTile("类型", "单反", Modifier.weight(1f))

                SpecTile("卡口", camera.mount, Modifier.weight(1f))

            }

        }

    }

}



@Composable

private fun LensDetailDialog(lens: LensEntity, onClose: () -> Unit) {

    Dialog(onDismissRequest = onClose) {

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(34.dp)).background(FilmColors.LibraryRaised).padding(24.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {

                Text(lens.brand, fontSize = 24.sp, fontWeight = FontWeight.Black, color = FilmColors.Ink)

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭") }

            }

            Text(lens.model, fontSize = 18.sp, color = FilmColors.Ink)

            Spacer(Modifier.height(16.dp))

            GearImage(lens.imageKey, Modifier.fillMaxWidth().height(260.dp))

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                SpecTile("焦距", "${lens.focalMinMm}${if (lens.focalMaxMm != lens.focalMinMm) "–${lens.focalMaxMm}" else ""}mm", Modifier.weight(1f))

                SpecTile("最大光圈", "f/${lens.maximumAperture}", Modifier.weight(1f))

                SpecTile("卡口", lens.mount, Modifier.weight(1f))

            }

        }

    }

}



@Composable

private fun SpecTile(label: String, value: String, modifier: Modifier) {

    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFFE6E7E3)).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {

        Text(label, fontSize = 10.sp, color = FilmColors.Muted)

        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FilmColors.Ink, maxLines = 1)

    }

}



@Composable

private fun FormDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {

    Dialog(onDismissRequest = onDismiss) {

        Column(Modifier.fillMaxWidth().heightIn(max = 630.dp).clip(RoundedCornerShape(30.dp)).background(FilmColors.LibraryRaised).verticalScroll(rememberScrollState()).padding(22.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {

                Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = FilmColors.Ink)

                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") }

            }

            Spacer(Modifier.height(10.dp))

            content()

        }

    }

}



@Composable

private fun AddCameraDialog(onDismiss: () -> Unit, onSubmit: (String, String, String) -> Unit) {

    var brand by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }; var mount by remember { mutableStateOf("Canon FD") }

    FormDialog("添加机身", onDismiss) {

        OutlinedTextField(brand, { brand = it }, label = { Text("品牌") }, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(model, { model = it }, label = { Text("型号") }, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(mount, { mount = it }, label = { Text("卡口") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(14.dp))

        Button(onClick = { onSubmit(brand, model, mount) }, enabled = brand.isNotBlank() && model.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("保存机身") }

    }

}



@Composable

private fun AddLensDialog(onDismiss: () -> Unit, onSubmit: (String, String, String, Int, Int, Double) -> Unit) {

    var brand by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }; var mount by remember { mutableStateOf("Canon FD") }

    var min by remember { mutableStateOf("") }; var max by remember { mutableStateOf("") }; var aperture by remember { mutableStateOf("") }

    FormDialog("添加镜头", onDismiss) {

        OutlinedTextField(brand, { brand = it }, label = { Text("品牌") }, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(model, { model = it }, label = { Text("型号") }, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(mount, { mount = it }, label = { Text("卡口") }, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(min, { min = it }, label = { Text("起始焦距 mm") }, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(max, { max = it }, label = { Text("最长焦距 mm，定焦可留空") }, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(aperture, { aperture = it }, label = { Text("最大光圈，例如 1.4") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(14.dp))

        Button(onClick = { onSubmit(brand, model, mount, min.toInt(), max.toIntOrNull() ?: min.toInt(), aperture.toDouble()) }, enabled = brand.isNotBlank() && model.isNotBlank() && min.toIntOrNull() != null && aperture.toDoubleOrNull() != null, modifier = Modifier.fillMaxWidth()) { Text("保存镜头") }

    }

}



@Composable

private fun AddFilmDialog(onDismiss: () -> Unit, onSubmit: (String, String, Int, String) -> Unit) {

    var brand by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }; var iso by remember { mutableStateOf("") }; var type by remember { mutableStateOf("COLOR_NEGATIVE") }

    FormDialog("自定义胶卷", onDismiss) {

        OutlinedTextField(brand, { brand = it }, label = { Text("品牌") }, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(model, { model = it }, label = { Text("型号") }, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(iso, { iso = it }, label = { Text("Box ISO") }, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(type, { type = it }, label = { Text("类型：COLOR_NEGATIVE / BW / COLOR_REVERSAL") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(14.dp))

        Button(onClick = { onSubmit(brand, model, iso.toInt(), type) }, enabled = brand.isNotBlank() && model.isNotBlank() && (iso.toIntOrNull() ?: 0) > 0, modifier = Modifier.fillMaxWidth()) { Text("保存胶卷") }

    }

}



@Composable

private fun NewRollDialog(cameras: List<CameraEntity>, lenses: List<LensEntity>, stocks: List<FilmStockEntity>, onDismiss: () -> Unit, onCreate: (String, CameraEntity, LensEntity?, FilmStockEntity, Int, Int, String?, String) -> Unit) {

    var name by remember { mutableStateOf("") }

    var cameraId by remember { mutableStateOf(cameras.firstOrNull()?.id) }

    var lensId by remember { mutableStateOf(lenses.firstOrNull()?.id) }

    var stockId by remember { mutableStateOf(stocks.firstOrNull()?.id) }

    var capacity by remember { mutableStateOf(36) }

    var expiration by remember { mutableStateOf("") }

    var storage by remember { mutableStateOf("UNKNOWN") }

    val camera = cameras.firstOrNull { it.id == cameraId }

    val compatibleLenses = lenses.filter { it.mount.equals(camera?.mount, ignoreCase = true) }

    val stock = stocks.firstOrNull { it.id == stockId }

    val date = runCatching { LocalDate.parse(expiration) }.getOrNull()

    val validDate = expiration.isBlank() || date != null

    val recommendation = stock?.let { EiEngine.recommend(it, date, storage) }

    var eiText by remember(stockId, expiration, storage) { mutableStateOf(recommendation?.center?.toString() ?: "") }

    FormDialog("新建一卷", onDismiss) {

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

            OutlinedTextField(name, { name = it }, label = { Text("卷名，例如 上海 26-09") }, modifier = Modifier.fillMaxWidth())

            Text("机身", fontSize = 12.sp, color = FilmColors.Muted)

            ChoiceRow(cameras.map { it.id to "${it.brand} ${it.model}" }, cameraId) {

                cameraId = it

                val mount = cameras.firstOrNull { camera -> camera.id == it }?.mount

                lensId = lenses.firstOrNull { lens -> lens.mount.equals(mount, ignoreCase = true) }?.id

            }

            Text("镜头", fontSize = 12.sp, color = FilmColors.Muted)

            ChoiceRow(compatibleLenses.map { it.id to it.model }, lensId) { lensId = it }

            Text("胶卷", fontSize = 12.sp, color = FilmColors.Muted)

            ChoiceRow(stocks.map { it.id to it.model }, stockId) { stockId = it }

            OutlinedTextField(expiration, { expiration = it }, label = { Text("到期日 YYYY-MM-DD，可留空") }, modifier = Modifier.fillMaxWidth())

            if (!validDate) Text("请按 YYYY-MM-DD 输入日期", color = Color(0xFFB44431), fontSize = 11.sp)

            Text("保存条件", fontSize = 12.sp, color = FilmColors.Muted)

            ChoiceRow(listOf("UNKNOWN" to "未知", "FROZEN" to "冷冻", "REFRIGERATED" to "冷藏", "CONTROLLED_ROOM" to "常温", "HOT_HUMID" to "高温潮湿"), storage) { storage = it }

            recommendation?.let {

                Text("Box ISO ${stock?.boxIso}  ·  建议 EI ${it.lower}–${it.upper}  ·  置信度${it.confidence}", fontSize = 12.sp, color = FilmColors.Muted)

                Text(it.explanation, fontSize = 11.sp, color = FilmColors.Muted, maxLines = 2)

            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {

                OutlinedTextField(eiText, { eiText = it }, label = { Text("本卷 EI") }, modifier = Modifier.weight(1f))

                Text("$capacity 张", Modifier.clip(RoundedCornerShape(12.dp)).background(FilmColors.LibraryCard).clickable { capacity = if (capacity == 36) 24 else 36 }.padding(14.dp), fontSize = 13.sp)

            }

            Button(onClick = { if (camera != null && stock != null) onCreate(name, camera, compatibleLenses.firstOrNull { it.id == lensId }, stock, eiText.toInt(), capacity, expiration.ifBlank { null }, storage) }, enabled = name.isNotBlank() && camera != null && stock != null && validDate && (eiText.toIntOrNull() ?: 0) > 0, modifier = Modifier.fillMaxWidth()) { Text("装卷并开始") }

        }

    }

}



@Composable

private fun ChoiceRow(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {

    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {

        items(options.size) { index ->

            val option = options[index]

            Text(option.second, Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected == option.first) FilmColors.Ink else FilmColors.LibraryCard).clickable { onSelect(option.first) }.padding(horizontal = 13.dp, vertical = 9.dp), color = if (selected == option.first) Color.White else FilmColors.Ink, fontSize = 12.sp, maxLines = 1)

        }

    }

}



@Composable

private fun RollScreen(rolls: List<RollEntity>, stocks: List<FilmStockEntity>, cameras: List<CameraEntity>, selected: RollEntity?, onSelect: (RollEntity) -> Unit, onNewRoll: () -> Unit, onFinish: (RollEntity) -> Unit, onOpen: () -> Unit) {

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp).padding(top = 24.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {

            Text("胶卷", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FilmColors.Ink, modifier = Modifier.weight(1f))

            RoundIcon(Icons.Outlined.Add, "新建一卷", onNewRoll)

        }

        Spacer(Modifier.height(16.dp))

        Text("按卷归档", color = FilmColors.Muted, fontSize = 13.sp)

        Spacer(Modifier.height(18.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 120.dp)) {

            items(rolls.size) { index ->

                val roll = rolls[index]

                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp)).background(FilmColors.LibraryCard).clickable { onSelect(roll); onOpen() }.padding(18.dp)) {

                    Row(verticalAlignment = Alignment.CenterVertically) {

                        FilmPackage(stocks.firstOrNull { it.id == roll.filmStockId })

                        Spacer(Modifier.size(12.dp))

                        Column(Modifier.weight(1f)) {

                            Text(roll.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = FilmColors.Ink)

                            Text("${cameras.firstOrNull { it.id == roll.cameraId }?.model ?: "机身"} · ${stocks.firstOrNull { it.id == roll.filmStockId }?.model ?: "胶卷"}", fontSize = 12.sp, color = FilmColors.Muted)

                        }

                        Text("${roll.nextFrameNumber - 1}/${roll.frameCapacity}", color = FilmColors.Ink, fontSize = 13.sp)

                    }

                    Spacer(Modifier.height(14.dp))

                    Text(when (roll.status) { "SHOOTING" -> "拍摄中"; "FINISHED" -> "待导入扫描"; "ARCHIVED" -> "已归档"; else -> roll.status }, color = FilmColors.Muted, fontSize = 12.sp)

                    if (roll.status == "SHOOTING") {

                        Text("结束本卷", Modifier.align(Alignment.End).clickable { onFinish(roll) }.padding(8.dp), color = FilmColors.Ink, fontSize = 12.sp)

                    }

                }

            }

        }

    }

}



@Composable

private fun SettingsScreen(repository: FilmRepository, onBack: () -> Unit) {

    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    val backup = remember { BackupService(context, (context.applicationContext as FilmCompanionApplication).database) }

    var message by remember { mutableStateOf("") }

    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    var calibration by remember { mutableStateOf("0.0") }

    androidx.compose.runtime.LaunchedEffect(Unit) {

        calibration = repository.dao().getCalibration("default")?.evOffset?.toString() ?: "0.0"

    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->

        if (uri != null) scope.launch { message = runCatching { "已备份 ${backup.write(uri)} 卷；扫描原件未复制" }.getOrElse { "备份失败：${it.message}" } }

    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->

        restoreUri = uri

    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(22.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {

            IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, "返回") }

            Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        }

        Spacer(Modifier.height(24.dp))

        Text("测光校准", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

        Text("按已知测光表对照，输入 EV 偏移。建议在不同照明下校验。", fontSize = 12.sp, color = FilmColors.Muted)

        OutlinedTextField(calibration, { calibration = it }, label = { Text("EV 偏移，例如 +0.3") }, modifier = Modifier.fillMaxWidth())

        Button(onClick = {

            val value = calibration.toDoubleOrNull()

            if (value != null && value in -3.0..3.0) scope.launch {

                repository.dao().putCalibration(MeterCalibrationEntity("default", value, 1))

                message = "测光校准已保存"

            } else message = "请输入 -3 至 +3 的 EV 偏移"

        }) { Text("保存校准") }

        Spacer(Modifier.height(28.dp))

        Text("本地备份与恢复", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

        Text("备份器材、胶卷、逐帧记录和扫描文件引用，不复制扫描原件。恢复前请保留原备份和原件。", fontSize = 12.sp, color = FilmColors.Muted)

        Spacer(Modifier.height(12.dp))

        Button(onClick = { backupLauncher.launch("film-companion-${LocalDate.now()}.zip") }, modifier = Modifier.fillMaxWidth()) { Text("导出本地备份") }

        Button(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("从备份恢复") }

        if (message.isNotEmpty()) Text(message, Modifier.padding(top = 12.dp), fontSize = 12.sp, color = FilmColors.Ink)

        Spacer(Modifier.height(20.dp))

        Text("Film Companion · P0 · 全部数据保存在本机", fontSize = 12.sp, color = FilmColors.Muted)

    }

    restoreUri?.let { uri -> AlertDialog(

        onDismissRequest = { restoreUri = null },

        title = { Text("恢复本地备份？") },

        text = { Text("这会替换当前器材、胶卷、逐帧记录和扫描索引。请确认已保存当前数据的备份。扫描原件仍在原位置。") },

        confirmButton = { TextButton(onClick = { restoreUri = null; scope.launch { message = runCatching { "已恢复 ${backup.restore(uri)} 卷；若原件不可访问，请在卷内重新关联" }.getOrElse { "恢复失败：${it.message}" } } }) { Text("确认恢复") } },

        dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("取消") } }

    ) }

}
