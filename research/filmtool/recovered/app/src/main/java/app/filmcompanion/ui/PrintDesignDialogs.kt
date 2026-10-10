package app.filmcompanion.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.filmcompanion.media.*
import kotlinx.coroutines.launch

@Composable
private fun DesignPanel(title: String,onClose: ()->Unit,onDone: ()->Unit,preview: (@Composable ()->Unit)?=null,content: @Composable ColumnScope.()->Unit) {
    MotionDialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxSize().background(FilmColors.Library).systemBarsPadding().padding(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(title,color=FilmColors.Ink,fontSize=25.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                SoftAction("取消",onClick=onClose)
            }
            preview?.invoke()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
            SoftAction("完成",Modifier.fillMaxWidth().height(54.dp),selected=true,radius=27.dp,onClick=onDone)
        }
    }
}

@Composable
fun PaletteControls(colors: Map<PaletteMood,Int>,mood: PaletteMood,manual: Int?,onChange: (PaletteMood,Int?)->Unit) {
    val rgb=manual ?: colors[mood] ?: android.graphics.Color.GRAY
    var hex by remember(rgb) {mutableStateOf("%06X".format(rgb and 0xFFFFFF))}
    Text("从照片取色",color=FilmColors.Ink,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        PaletteMood.entries.forEach {choice ->
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(28.dp).background(Color(colors[choice] ?: rgb),androidx.compose.foundation.shape.CircleShape))
                SoftAction(choice.title,selected=mood==choice && manual==null,onClick={onChange(choice,null)})
            }
        }
    }
    Text(mood.english+if(manual==null) " · 自动" else " · 自定义",color=FilmColors.Muted,fontSize=11.sp)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(42.dp).background(Color(rgb),androidx.compose.foundation.shape.RoundedCornerShape(12.dp)))
        OutlinedTextField(hex,{value -> hex=value.filter {it.isDigit() || it.lowercaseChar() in 'a'..'f'}.take(6)
            if(hex.length==6)onChange(mood,0xFF000000.toInt() or hex.toInt(16))},
            label={Text("色号 HEX")},singleLine=true,modifier=Modifier.weight(1f))
    }
    val hsv=remember(rgb) {FloatArray(3).also {android.graphics.Color.colorToHSV(rgb,it)}}
    listOf("色调" to (0f..360f),"浓淡" to (0f..1f),"明暗" to (0f..1f)).forEachIndexed {index,(label,range) ->
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(label,color=FilmColors.Muted,fontSize=12.sp,modifier=Modifier.width(40.dp))
            Slider(hsv[index],{value -> val next=hsv.copyOf();next[index]=value;onChange(mood,android.graphics.Color.HSVToColor(next))},
                valueRange=range,modifier=Modifier.weight(1f),colors=SliderDefaults.colors(thumbColor=FilmColors.Ink,activeTrackColor=FilmColors.Ink,inactiveTrackColor=FilmColors.Muted.copy(alpha=.2f)))
        }
    }
}

@Composable
fun DecorationImageControls(imageKey: String?,enabled: Boolean=true,onImporting: (Boolean)->Unit={},onChange: (String?)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var importing by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf("")}
    val preview=remember(imageKey) {PrintAssets(context).bitmap(imageKey)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) {
            importing=true;onImporting(true)
            scope.launch {
                try {
                    val name=context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else null} ?: "decoration.png"
                    onChange(PrintAssets(context).importImage(uri,name));status="装饰图片已载入"
                } catch(error: kotlinx.coroutines.CancellationException) {throw error}
                catch(error: Exception) {status="图片读取失败："+(error.message ?: "请选择 PNG 或 JPG")}
                finally {importing=false;onImporting(false)}
            }
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            preview?.let {Image(it.asImageBitmap(),"已上传的装饰图片",Modifier.size(42.dp))}
            Column {
                Text("装饰图片",color=FilmColors.Ink,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                Text("支持 PNG / JPG；未上传时使用胶卷图。",color=FilmColors.Muted,fontSize=11.sp)
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            SoftAction(if(importing)"正在载入…" else if(imageKey==null)"上传装饰图片" else "更换装饰图片",Modifier.weight(1f),
                enabled=enabled && !importing,onClick={picker.launch(arrayOf("image/png","image/jpeg"))})
            SoftAction("恢复胶卷图",Modifier.weight(1f),enabled=enabled && !importing && imageKey!=null,onClick={onChange(null);status=""})
        }
        if(status.isNotBlank())Text(status,color=FilmColors.Muted,fontSize=11.sp)
    }
}

@Composable
fun CartridgeDesignDialog(initial: CartridgeSettings,identity: PrintIdentity,onDone: (CartridgeSettings)->Unit,onClose: ()->Unit) {
    val context=LocalContext.current
    var draft by remember {mutableStateOf(initial)}
    val resolved=identity.cartridge(draft)
    val preview=remember(resolved) {
        Bitmap.createBitmap(300,440,Bitmap.Config.ARGB_8888).also {b ->
            val painter=FilmStripPainter(context)
            try {painter.cartridge(Canvas(b),RectF(22f,5f,278f,435f),resolved)} finally {painter.close()}
        }
    }
    // Compose can retain a previously drawn bitmap for one frame; avoid recycling during recomposition.
    DesignPanel("暗盒设置",onClose,{onDone(draft)}) {
        Image(preview.asImageBitmap(),"暗盒例图",Modifier.fillMaxWidth().height(190.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            SoftAction("135 暗盒",Modifier.weight(1f),selected=resolved.roll120!=true,onClick={draft=draft.copy(roll120=false)})
            SoftAction("120 卷轴",Modifier.weight(1f),selected=resolved.roll120==true,onClick={draft=draft.copy(roll120=true)})
        }
        OutlinedTextField(draft.brand.ifBlank {identity.brand},{draft=draft.copy(brand=it.take(60))},label={Text("品牌 / Logo 文字")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(draft.film.ifBlank {identity.film},{draft=draft.copy(film=it.take(80))},label={Text("胶卷名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(draft.notes,{draft=draft.copy(notes=it.take(60))},label={Text("侧边小字")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(resolved.blackWhite==true,{draft=draft.copy(blackWhite=it)},modifier=Modifier.semantics {contentDescription="黑白卷标识"})
            Column {Text("黑白卷",color=FilmColors.Ink);Text(if(resolved.blackWhite==true) "Black & White Film · 黑白片边" else "Color Negative Film · 橙色片边",color=FilmColors.Muted,fontSize=11.sp)}
        }
        SoftAction("恢复胶卷资料与自动取色",onClick={draft=CartridgeSettings()})
        PaletteControls(identity.colors,draft.mood,draft.color) {m,c->draft=draft.copy(mood=m,color=c)}
    }
}

private enum class TextPart(val title: String) {TITLE("主标题"),SUBTITLE("副标题"),FOOTER("底部小字"),SIGNATURE("作者签名")}

@Composable
private fun FontSampleGrid(text: String,selected: PrintFont,customSelected: Boolean=false,onChoose: (PrintFont)->Unit) {
    PrintFont.entries.chunked(2).forEach {row ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            row.forEach {font ->
                Column(Modifier.weight(1f).libraryMaterial(16.dp).clickable {onChoose(font)}.padding(12.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(PrintFonts.displayText(text.ifBlank {"Your words"},font),fontFamily=FontFamily(Font(font.resource)),
                        fontSize=28.sp,lineHeight=36.sp,color=FilmColors.Ink,modifier=Modifier.heightIn(min=68.dp),maxLines=3,
                        overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    SoftAction(font.title,Modifier.fillMaxWidth(),selected=selected==font && !customSelected,onClick={onChoose(font)})
                }
            }
        }
    }
}

@Composable
fun PhotoTextDesignDialog(initial: PhotoRecipe,identity: PrintIdentity,source: Bitmap,startWithSignature: Boolean=false,onDone: (PhotoRecipe)->Unit,onClose: ()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var draft by remember {mutableStateOf(initial)}
    var part by remember {mutableStateOf(if(startWithSignature)TextPart.SIGNATURE else TextPart.TITLE)}
    var status by remember {mutableStateOf("")}
    var importing by remember {mutableStateOf(false)}
    var zoom by remember {mutableStateOf(1f)}
    var captionPreview by remember {mutableStateOf<Bitmap?>(null)}
    var previewFailure by remember {mutableStateOf<String?>(null)}
    val oldPreviews=remember {mutableListOf<Bitmap>()}
    DisposableEffect(Unit) {onDispose {captionPreview?.recycle();oldPreviews.forEach {it.recycle()}}}
    val ticket=draft.border==PhotoBorder.TICKET
    val title=if(ticket) "Where the light lingers" else identity.camera
    val subtitle=if(ticket)identity.subtitle else identity.brand+" "+identity.film
    val titleFont=draft.font ?: when(draft.border) {
        PhotoBorder.FILM -> PrintFont.TYPEWRITER
        PhotoBorder.TICKET -> PrintFont.GREAT_VIBES
        PhotoBorder.MUSEUM -> PrintFont.SERIF
        PhotoBorder.SLIDE -> PrintFont.TYPEWRITER
        else -> PrintFont.SPACED
    }
    val selected=when(part) {
        TextPart.TITLE->titleFont
        TextPart.SUBTITLE->draft.subtitleFont ?: if(ticket)PrintFont.SPACED else titleFont
        TextPart.FOOTER->draft.footerFont ?: if(ticket)PrintFont.TYPEWRITER else PrintFont.SANS
        TextPart.SIGNATURE->draft.signatureFont
    }
    val value=when(part) {
        TextPart.TITLE->draft.titleText ?: title
        TextPart.SUBTITLE->draft.subtitleText ?: subtitle
        TextPart.FOOTER->draft.footerText ?: if(ticket)"A moment, kept in light." else ""
        TextPart.SIGNATURE->draft.signature
    }
    val customSelected=draft.customFont!=null && (part==TextPart.TITLE || part==TextPart.SUBTITLE && !ticket && draft.subtitleFont==null)
    LaunchedEffect(draft,source,identity) {
        var rendered: Bitmap?=null
        try {
            kotlinx.coroutines.delay(90)
            val crop=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val full=PhotoPrintRenderer(context).render(source,draft,identity.subtitle,"",identity,null)
                try {
                    val layout=PrintLayouts.plan(source.width,source.height,draft,identity.cartridge(draft.cartridge).roll120==true)
                    val area=PrintLayouts.captionArea(layout,draft.border)
                    val left=area.left.toInt().coerceIn(0,full.width-1)
                    val right=area.right.toInt().coerceIn(left+1,full.width)
                    val top=area.top.toInt().coerceIn(0,full.height-1)
                    val bottom=area.bottom.toInt().coerceIn(top+1,full.height)
                    Bitmap.createBitmap(full,left,top,right-left,bottom-top).also {rendered=it}
                } finally {full.recycle()}
            }
            rendered=crop
            val old=captionPreview
            captionPreview=crop;rendered=null;previewFailure=null
            old?.let {oldPreviews+=it}
            // Let Compose drop the old image before releasing its pixels.
            kotlinx.coroutines.delay(150)
            oldPreviews.toList().forEach {it.recycle();oldPreviews.remove(it)}
        } catch(cancelled: kotlinx.coroutines.CancellationException) {throw cancelled}
        catch(error: Exception) {previewFailure=error.message ?: "预览生成失败"}
        finally {rendered?.recycle()}
    }
    fun changeFont(font: PrintFont) {
        draft=when(part) {
            TextPart.TITLE->draft.copy(font=font,customFont=null,customFontName=null)
            TextPart.SUBTITLE->draft.copy(subtitleFont=font)
            TextPart.FOOTER->draft.copy(footerFont=font)
            TextPart.SIGNATURE->draft.copy(signatureFont=font)
        }
    }
    fun changeText(text: String) {
        draft=when(part) {
            TextPart.TITLE->draft.copy(titleText=text.take(240))
            TextPart.SUBTITLE->draft.copy(subtitleText=text.take(240))
            TextPart.FOOTER->draft.copy(footerText=text.take(240))
            TextPart.SIGNATURE->draft.copy(signature=text.take(100))
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null) {
            importing=true
            scope.launch {
                try {
                    val name=context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else null} ?: "自选字体"
                    val key=PrintAssets(context).importFont(uri)
                    draft=draft.copy(customFont=key,customFontName=name);status="已载入 "+name
                } catch(error: kotlinx.coroutines.CancellationException) {throw error}
                catch(error: Exception) {status=error.message ?: "字体读取失败"} finally {importing=false}
            }
        }
    }
    DesignPanel("编辑文字与字体",onClose,{if(!importing)onDone(draft)},preview={
        Column(Modifier.padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                TextPart.entries.forEach {item ->SoftAction(item.title,Modifier.weight(1f),selected=part==item,enabled=!importing,onClick={part=item})}
            }
            OutlinedTextField(value,::changeText,label={Text(part.title)},modifier=Modifier.fillMaxWidth().testTag("type-text-input"),maxLines=3,enabled=!importing)
            BoxWithConstraints(Modifier.fillMaxWidth().height(172.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(FilmColors.Library).testTag("type-frame-preview")) {
                val image=captionPreview
                if(image!=null) {
                    val horizontal=rememberScrollState();val vertical=rememberScrollState()
                    // The crop already magnifies the frame's caption. Fit the whole group first,
                    // including tall film tails, then offer additional magnification and panning.
                    val aspect=image.width.toFloat()/image.height
                    val width=minOf(maxWidth,maxHeight*aspect)*zoom;val height=width/aspect
                    val density=androidx.compose.ui.platform.LocalDensity.current
                    LaunchedEffect(image,zoom,maxWidth) {
                        horizontal.scrollTo(with(density) {(width-maxWidth).toPx().toInt()/2})
                        vertical.scrollTo(with(density) {(height-maxHeight).toPx().toInt().coerceAtLeast(0)/2})
                    }
                    Box(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(vertical),contentAlignment=Alignment.Center) {
                        Image(image.asImageBitmap(),"当前${draft.border.title}的文字区域预览",Modifier.requiredSize(width,height),contentScale=androidx.compose.ui.layout.ContentScale.FillBounds)
                    }
                } else Text(previewFailure ?: "正在生成相框文字预览…",color=FilmColors.Muted,fontSize=12.sp,modifier=Modifier.align(Alignment.Center))
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("成片文字区 · 可拖动查看",color=FilmColors.Muted,fontSize=11.sp,modifier=Modifier.weight(1f))
                listOf(1f,2f,3f).forEach {scale ->SoftAction(if(scale==1f)"适合" else "${scale.toInt()}×",selected=zoom==scale,onClick={zoom=scale})}
            }
        }
    }) {
        Text("${part.title} · 10 款字体用当前文字展示。Julius 统一大写，原输入文字保留。",color=FilmColors.Muted,fontSize=12.sp)
        FontSampleGrid(value,selected,customSelected,::changeFont)
        if(part==TextPart.TITLE) {
            SoftAction(if(importing) "正在载入字体…" else draft.customFontName ?: "导入标题艺术字体",enabled=!importing,onClick={picker.launch(arrayOf("*/*"))})
            Text("主标题也可导入 TTF / OTF 字体。",color=FilmColors.Muted,fontSize=11.sp)
        }
        if(draft.border==PhotoBorder.PALETTE)DecorationImageControls(draft.imageKey,enabled=!importing,onImporting={importing=it}) {draft=draft.copy(imageKey=it)}
        SoftAction("恢复机型与胶卷默认文字",onClick={draft=draft.copy(titleText=null,subtitleText=null,footerText=null)})
        if(status.isNotBlank())Text(status,color=FilmColors.Muted,fontSize=11.sp)
    }
}
