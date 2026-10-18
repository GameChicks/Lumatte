@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.lumixfilmmirror.app

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import com.lumixfilmmirror.design.*
import com.lumixfilmmirror.media.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.roundToInt

/** A separate, zoomable contact sheet. No horizontal/vertical world limits and no tile borders.
 * Colour ranking comes from real loaded example palettes, never from LUT names. */
@Composable
internal fun MirrorColorExplorer(cards:List<MirrorLutCard>,state:WorkspaceState,workspace:WorkspaceViewModel,
    personal:MirrorPersonalState,shared:SharedTransitionScope,visibility:AnimatedVisibilityScope,
    onBack:()->Unit,onOpen:(MirrorLutCard,String)->Unit,onFavourite:(MirrorLutCard)->Unit) {
    val tones=LocalFilmMaterials.current
    val dark=LocalFilmDark.current
    val density=LocalDensity.current
    val reduced=LocalMirrorReducedMotion.current
    var panX by rememberSaveable { mutableFloatStateOf(-158f) }
    var panY by rememberSaveable { mutableFloatStateOf(280f) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var paletteOpen by rememberSaveable { mutableStateOf(false) }
    var hue by rememberSaveable { mutableStateOf<Float?>(null) }
    var saturation by rememberSaveable { mutableFloatStateOf(.44f) }
    var mono by rememberSaveable { mutableStateOf(false) }
    var online by rememberSaveable { mutableStateOf(true) }
    var nameQuery by rememberSaveable { mutableStateOf("") }
    var onlineRevision by remember { mutableIntStateOf(0) }
    var remoteCards by remember { mutableStateOf<List<MirrorLutCard>>(emptyList()) }
    var onlineLoading by remember { mutableStateOf(false) }
    var indexComplete by remember { mutableStateOf(false) }
    var onlineProblem by remember { mutableStateOf<String?>(null) }
    val palettes=remember(state.current?.id) { mutableStateMapOf<String,LutExampleColours>() }
    val allCards=remember(cards,remoteCards,online) {
        (if(online)remoteCards+cards.map { if(it.official!=null)it.copy(photoPreview=false) else it } else cards.filter { it.local!=null })
            .distinctBy { it.key }
    }
    LaunchedEffect(online,paletteOpen,onlineRevision,state.current?.id) {
        if(!online)return@LaunchedEffect
        onlineLoading=true;onlineProblem=null
        try {
            val index=workspace.processor.officialLutExplorer.index(workspace.processor.officialLutLanguage()) { snapshot ->
                remoteCards=snapshot.items.map { item -> MirrorLutCard(state.luts.firstOrNull { it.lumixLabSource?.code==item.code },item) }
                indexComplete=snapshot.complete
                if(paletteOpen) {
                    snapshot.items.filter { "official:${it.code}" !in palettes && it.thumbnail!=null }.chunked(2).forEach { pair ->
                        val analysed=coroutineScope { pair.map { item -> async {
                            try { item to workspace.processor.officialLutExplorer.colours(item) }
                            catch(error:CancellationException) { throw error }
                            catch(_:Exception) { item to null }
                        } }.awaitAll() }
                        analysed.forEach { (item,colours) ->
                            if(colours!=null)palettes["official:${item.code}"]=colours
                            else onlineProblem="部分例图未能分析，当前颜色结果不包含这些款式。"
                        }
                    }
                }
            }
            indexComplete=index.complete
            index.problem?.let { onlineProblem=it }
        } catch(error:CancellationException) { throw error }
        catch(_:Exception) { onlineProblem="Lab 暂时无法读取，可重试；已显示的卡片仍可浏览。" }
        finally { onlineLoading=false }
    }
    var scanning by remember { mutableStateOf(false) }
    var scanProblem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(paletteOpen,state.current?.id,online,cards.map { it.local?.id }) {
        val source=state.current
        if(!paletteOpen || source==null)return@LaunchedEffect
        scanning=true;scanProblem=null
        try {
            val assets=cards.filter { !online || it.official==null }.mapNotNull { it.local }.distinctBy { it.id }
            val snapshot=workspace.processor.lutGalleryColours(source,ProcessingCatalogue(assets,emptyList(),emptyList()),longEdge=128) { entry ->
                cards.firstOrNull { it.local?.id==entry.lutId }?.let { palettes[it.key]=entry.colours }
            }
            if(snapshot.problems.isNotEmpty())scanProblem="部分预览暂不可用，可继续浏览已读取的色彩。"
        } catch(error:CancellationException) { throw error }
        catch(_:Exception) { scanProblem="色彩读取暂不可用，可继续浏览卡片。" }
        catch(_:OutOfMemoryError) { scanProblem="色彩读取内存不足，可收起色盘后重试。" }
        finally {scanning=false}
    }
    fun distance(card:MirrorLutCard):Float {
        val palette=palettes[card.key] ?: return 720f
        if(mono)return if(palette.mode==LutExampleColourMode.NEAR_MONOCHROME)0f else 360f
        val selected=hue ?: return 0f
        return palette.swatches.minOfOrNull { sample ->
            val hsv=FloatArray(3); android.graphics.Color.colorToHSV(sample.argb,hsv)
            val delta=abs(hsv[0]-selected)
            minOf(delta,360f-delta)+abs(hsv[1]-saturation)*65f+abs(hsv[2]-atlasBrightness(saturation))*35f
        } ?: 720f
    }
    val colourSnapshot=palettes.toMap()
    val ranked=remember(allCards,nameQuery,hue,saturation,mono,colourSnapshot) {
        val matching=allCards.filter { nameQuery.isBlank() || it.title.contains(nameQuery.trim(),true) || it.creator?.contains(nameQuery.trim(),true)==true }
        if(hue!=null || mono)matching.sortedWith(compareBy<MirrorLutCard>{distance(it)}.thenBy{it.key}) else matching
    }
    val sceneInk=if(dark)Color(0xFFEDEFE0) else Color(0xFF293347)
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))
        .background(if(dark)Color(0xFF10131B) else Color(0xFFF2EDE1)).pointerInput(density.density) {
            detectTransformGestures(panZoomLock=true) { centroid,pan,scale,_ ->
                val next=(zoom*scale).coerceIn(.55f,2.4f)
                val ratio=next/zoom
                val anchor=centroid/density.density
                panX=anchor.x-(anchor.x-panX)*ratio+pan.x/density.density
                panY=anchor.y-(anchor.y-panY)*ratio+pan.y/density.density
                zoom=next
            }
        }) {
        val viewWidth=maxWidth.value; val viewHeight=maxHeight.value
        val paletteHeight=maxHeight*.62f
        fun changeZoom(next:Float) {
            val fitted=next.coerceIn(.55f,2.4f)
            val ratio=fitted/zoom
            panX=viewWidth/2-(viewWidth/2-panX)*ratio
            panY=viewHeight/2-(viewHeight/2-panY)*ratio
            zoom=fitted
        }
        fun centerCards() { panX=-158f*zoom;panY=280f }
        Canvas(Modifier.fillMaxSize()) {
            val step=20.dp.toPx()*zoom
            val sx=((panX*density.density)%step+step)%step
            val sy=((panY*density.density)%step+step)%step
            var y=sy
            while(y<size.height) { var x=sx
                while(x<size.width) { drawCircle(sceneInk.copy(.19f),.65.dp.toPx()*zoom,Offset(x,y)); x+=step }
                y+=step
            }
        }
        ranked.forEachIndexed { index,card ->
            val x=panX+((index%5)*178f+(if(index%2==0)0f else 10f))*zoom
            val y=panY+((index/5)*229f+(index%3)*22f)*zoom
            if(x> -180*zoom && x<viewWidth+30*zoom && y> -260*zoom && y<viewHeight+30*zoom)key(card.key) {
                Polaroid(card,"explore:${card.key}",state,workspace,personal,shared,visibility,
                    Modifier.offset { IntOffset(with(density){x.dp.roundToPx()},with(density){y.dp.roundToPx()}) }.width(152.dp)
                        .graphicsLayer { scaleX=zoom;scaleY=zoom;transformOrigin=TransformOrigin(0f,0f);rotationZ=if(index%2==0)-2f else 2f },
                    onClick={onOpen(card,"explore:${card.key}")},onFavourite={onFavourite(card)},
                    onColours={palettes[card.key]=it})
            }
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Brush.verticalGradient(listOf(
            if(dark)Color(0xFF10131B) else Color(0xFFF2EDE1),Color.Transparent))).padding(20.dp)) {
            Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                RoundAction("返回 LUT 列表",DotGlyph.LEFT,onClick=onBack)
                Column(Modifier.weight(1f)) {
                    MirrorLayeredTitle("Color","Atlas",sceneInk,if(dark)Color(0xFFD4C38A) else Color(0xFF906A35),compact=true)
                    Text("拖动画布 · 双指缩放 · 点按查看",fontSize=11.sp,color=sceneInk.copy(.7f))
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MirrorChoice(online,{online=true},label={Text("整个 Lab")})
                MirrorChoice(!online,{online=false},label={Text("本地 LUT")})
                if(online && !onlineLoading)MirrorTextAction("重试取色",{onlineRevision++})
            }
            MirrorTextField(nameQuery,{nameQuery=it.take(120)},"搜索名称或作者")
            if(online)Text("Lab 已检索 ${remoteCards.size} 款 · 已取色 ${palettes.keys.count { it.startsWith("official:") }} 款"+
                if(onlineLoading)" · 正在继续" else if(indexComplete)" · 目录已读完" else " · 未覆盖全库",fontSize=10.sp,color=sceneInk.copy(.8f))
        }
        if(allCards.isEmpty() && !onlineLoading)Text(if(online)"Lab 暂无结果，可重试或切换本地 LUT。" else "这里还没有本地 LUT。",Modifier.align(Alignment.Center).padding(32.dp),color=sceneInk)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(!paletteOpen)Row(Modifier.align(Alignment.End).clip(RoundedCornerShape(24.dp)).background(tones.paper.copy(.94f)).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically) {
                MirrorTextAction("−",{changeZoom(zoom/1.2f)},Modifier.widthIn(min=48.dp).semantics { contentDescription="缩小画布" },enabled=zoom>.55f)
                MirrorTextAction("${(zoom*100).roundToInt()}%",{changeZoom(1f)},Modifier.semantics { contentDescription="恢复画布原始比例" })
                MirrorTextAction("+",{changeZoom(zoom*1.2f)},Modifier.widthIn(min=48.dp).semantics { contentDescription="放大画布" },enabled=zoom<2.4f)
            }
            AnimatedVisibility(paletteOpen,
                enter=expandVertically(tween(if(reduced)0 else 320,easing=MirrorEase),expandFrom=Alignment.Bottom)+fadeIn(tween(if(reduced)0 else 220)),
                exit=shrinkVertically(tween(if(reduced)0 else 280,easing=MirrorEase),shrinkTowards=Alignment.Bottom)+fadeOut(tween(if(reduced)0 else 180))) {
            Column(Modifier.fillMaxWidth().heightIn(max=paletteHeight).clip(RoundedCornerShape(24.dp)).background(tones.paper.copy(.97f))
                .verticalScroll(rememberScrollState())
                .border(1.dp,tones.rim.copy(.4f),RoundedCornerShape(24.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    MirrorChoice(!mono,{mono=false},label={Text("彩色")})
                    MirrorChoice(mono,{mono=true;centerCards()},label={Text("黑白")})
                    Spacer(Modifier.weight(1f)); MirrorTextAction("清除筛选",{hue=null;mono=false;centerCards()})
                }
                if(!mono)AtlasColorPad(hue ?: 38f,saturation) { h,s -> hue=h;saturation=s;centerCards() }
                LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    listOf(0f,38f,64f,124f,177f,232f,286f).forEach { swatchHue -> item {
                        Box(Modifier.size(48.dp).padding(5.dp).clip(CircleShape).background(Color.hsv(swatchHue,.65f,.92f))
                            .clickable { hue=swatchHue;mono=false;centerCards() }
                            .semantics { contentDescription="色相 ${swatchHue.toInt()} 度" })
                    } }
                }
                Text(if(online)"全 Lab 逐页查找，按创作者小例图取色；不下载 LUT 文件，不代表实际试套效果。" else "依据当前照片预览的颜色排列，不代表 LUT 的固定适用范围。",fontSize=10.sp,color=tones.secondaryInk)
                if(scanning || onlineLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
                scanProblem?.let { Text(it,fontSize=10.sp,color=tones.secondaryInk) }
                if(online)onlineProblem?.let { Text(it,fontSize=10.sp,color=tones.secondaryInk) }
                if(palettes.isEmpty())Text("拖动浏览时会读取可见卡片的颜色。",fontSize=10.sp,color=tones.secondaryInk)
            }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(tones.paper.copy(.94f)).padding(horizontal=14.dp,vertical=8.dp),
                horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                MirrorTextAction(if(paletteOpen)"收起色盘 ↓" else "打开色盘 ↑",{paletteOpen=!paletteOpen})
                Text(if(mono)"黑白" else if(hue==null)"所有色彩" else "按预览色彩排列",Modifier.weight(1f).padding(horizontal=4.dp),
                    maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=11.sp,color=tones.secondaryInk)
                MirrorTextAction("回到中心",{centerCards()})
            }
        }
    }
}

private fun atlasBrightness(saturation:Float)=.96f-.66f*((saturation-.20f)/.55f).coerceIn(0f,1f)

@Composable
private fun AtlasColorPad(hue:Float,saturation:Float,onColor:(Float,Float)->Unit) {
    val current by rememberUpdatedState(onColor)
    val reduced=LocalMirrorReducedMotion.current
    var pressed by remember { mutableStateOf(false) }
    val focus by animateFloatAsState(if(pressed)1f else 0f,tween(if(reduced)0 else 150),label="palette-touch")
    val selected=Color.hsv(hue,saturation,atlasBrightness(saturation))
    val glow by animateColorAsState(selected,tween(if(reduced)0 else 100),label="palette-color")
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Canvas(Modifier.fillMaxWidth().height(156.dp).clip(RoundedCornerShape(24.dp)).pointerInput(Unit) {
        val inset=18.dp.toPx()
        fun pick(p:Offset) {
            val x=((p.x-inset)/(size.width-2*inset).coerceAtLeast(1f)).coerceIn(0f,1f)
            val y=((p.y-inset)/(size.height-2*inset).coerceAtLeast(1f)).coerceIn(0f,1f)
            current(x*359.99f,.20f+.55f*y)
        }
        awaitEachGesture {
            val down=awaitFirstDown();down.consume();pressed=true;pick(down.position)
            try {
                do {
                    val change=awaitPointerEvent().changes.firstOrNull { it.id==down.id } ?: break
                    if(change.pressed)pick(change.position)
                    change.consume()
                } while(change.pressed)
            } finally { pressed=false }
        }
    }.semantics {
        contentDescription="色彩选择板，横向调整色相，纵向调整浓淡与明暗"
        stateDescription="色相 ${hue.roundToInt()} 度，浓淡 ${((saturation-.20f)/.55f*100).roundToInt()}%"
        customActions=listOf(
            CustomAccessibilityAction("下一个色相") { current((hue+15f)%360f,saturation);true },
            CustomAccessibilityAction("上一个色相") { current((hue+345f)%360f,saturation);true },
            CustomAccessibilityAction("加深颜色") { current(hue,(saturation+.05f).coerceAtMost(.75f));true },
            CustomAccessibilityAction("减淡颜色") { current(hue,(saturation-.05f).coerceAtLeast(.20f));true })
    }) {
        // Soft continuous color field, with the same color mapping as the touch point.
        val inset=18.dp.toPx()
        drawRect(Brush.horizontalGradient((0..12).map { Color.hsv((it*30f).coerceAtMost(359.99f),.75f,.96f) },startX=inset,endX=size.width-inset))
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(.73f),Color.Transparent),startY=inset,endY=size.height-inset))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(.68f)),startY=inset,endY=size.height-inset))
        val center=Offset(inset+hue/360f*(size.width-2*inset),inset+((saturation-.20f)/.55f).coerceIn(0f,1f)*(size.height-2*inset))
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(.18f),Color.Transparent,Color.Black.copy(.14f))))
        drawCircle(Brush.radialGradient(listOf(glow.copy(.40f+focus*.15f),Color.Transparent),center,90.dp.toPx()),90.dp.toPx(),center)
        val step=14.dp.toPx()
        var y=inset
        while(y<=size.height-inset) {
            var x=inset
            while(x<=size.width-inset) {
                val point=Offset(x,y)
                val near=(1f-(point-center).getDistance()/65.dp.toPx()).coerceIn(0f,1f)
                drawCircle(Color.White.copy(.18f+near*(.50f+focus*.22f)),(.75f+near*.65f).dp.toPx(),point)
                x+=step
            }
            y+=step
        }
        drawCircle(Color.White.copy(.08f+focus*.10f),(17f+focus*5).dp.toPx(),center)
        drawCircle(Color.Black.copy(.15f),8.dp.toPx(),center+Offset(0f,1.dp.toPx()))
        drawCircle(Color.White,(6.5f+focus*1.5f).dp.toPx(),center)
    }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(selected))
        Text(if(pressed)"正在寻找相近色彩…" else "横向选色 · 纵向调整浓淡",fontSize=11.sp,color=LocalFilmMaterials.current.secondaryInk)
    }
    }
}

@Composable
internal fun MirrorLutRecommendations(state:WorkspaceState,workspace:WorkspaceViewModel,personal:MirrorPersonalState,
    shared:SharedTransitionScope,visibility:AnimatedVisibilityScope,onBack:()->Unit,onImport:()->Unit,
    onOpen:(MirrorLutCard,String)->Unit,onFavourite:(MirrorLutCard)->Unit,onChoose:(LutAsset)->Unit) {
    val tones=LocalFilmMaterials.current
    val dark=LocalFilmDark.current
    var includeUnknown by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var style by rememberSaveable { mutableStateOf("all") }
    LaunchedEffect(style) { if(style=="MONOCHROME")style="all" }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<LutRecommendationResult?>(null) }
    var discoverOnline by rememberSaveable { mutableStateOf(true) }
    var official by remember { mutableStateOf<OfficialLutSuggestions?>(null) }
    var discovering by remember { mutableStateOf(false) }
    var onlineProblem by remember { mutableStateOf<String?>(null) }
    var previousOnlineRetry by remember { mutableIntStateOf(0) }
    val photo=state.current
    LaunchedEffect(photo?.id,state.luts.mapNotNull { it.lumixLabSource?.code },style,includeUnknown,discoverOnline,retry) {
        official=null;onlineProblem=null
        if(photo==null || !discoverOnline) { discovering=false;return@LaunchedEffect }
        discovering=true
        try {
            val styles=if(style=="all" || style=="MONOCHROME")LutRecommendationStyle.entries.filter { it!=LutRecommendationStyle.MONOCHROME }.toSet() else setOf(LutRecommendationStyle.valueOf(style))
            val refresh=retry!=previousOnlineRetry
            previousOnlineRetry=retry
            official=workspace.processor.discoverOfficialLuts(photo,state.luts,styles,refresh=refresh,allowUnknownInput=includeUnknown)
        } catch(error:CancellationException) { throw error }
        catch(_:Exception) { onlineProblem="官方试套暂不可用，仍可使用本地试套推荐。" }
        finally { discovering=false }
    }
    LaunchedEffect(photo?.id,state.luts,style,includeUnknown,retry) {
        result=null;problem=null
        if(photo==null)return@LaunchedEffect
        loading=true
        try {
            delay(200)
            val styles=if(style=="all" || style=="MONOCHROME")LutRecommendationStyle.entries.filter { it!=LutRecommendationStyle.MONOCHROME }.toSet() else setOf(LutRecommendationStyle.valueOf(style))
            result=workspace.processor.recommendLuts(photo,state.luts.take(256),
                LutRecommendationOptions(styles=styles,perStyle=5,allowUnknownInput=includeUnknown))
        } catch(error:CancellationException) { throw error }
        catch(error:Exception) { problem=error.message ?: "推荐暂不可用" }
        catch(_:OutOfMemoryError) { problem="照片小图分析内存不足，请稍后重试" }
        finally { loading=false }
    }
    val recommendations=remember(result,state.luts) {
        val byStyle=result?.recommendations.orEmpty().groupBy { it.style }
        (0 until 5).flatMap { index -> LutRecommendationStyle.entries.mapNotNull { byStyle[it]?.getOrNull(index) } }
            .distinctBy { info -> state.luts.firstOrNull { it.id==info.id }?.lumixLabSource?.code ?: info.id }.take(5)
    }
    val choices=remember(recommendations,official,state.luts) {
        val local=recommendations.mapNotNull { info -> state.luts.firstOrNull { it.id==info.id }?.let {
            Triple(MirrorLutCard(local=it,photoPreview=true),info.reasons,info.warnings)
        } }
        val remote=official?.suggestions.orEmpty().map { Triple(MirrorLutCard(official=it.lut,photoPreview=true),it.reasons,it.warnings) }
        (0 until maxOf(local.size,remote.size)).flatMap { index -> listOfNotNull(local.getOrNull(index),remote.getOrNull(index)) }
            .distinctBy { it.first.key }.take(5)
    }
    val cards=choices.map { it.first }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCard=cards.firstOrNull { it.key==selectedKey } ?: cards.getOrNull(selected.coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().padding(top=12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    RoundAction("返回 LUT 列表",DotGlyph.LEFT,onClick=onBack)
                    Column(Modifier.weight(1f)) {
                        MirrorLayeredTitle("Color","Suggestions",tones.ink,if(dark)Color(0xFFBDD5E7) else Color(0xFF405E73),compact=true)
                        Text("智能推荐 · 用你的照片试套",fontSize=11.sp,color=tones.secondaryInk)
                    }
                    MirrorTextAction(if(showFilters) "收起" else "筛选", { showFilters=!showFilters })
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom=20.dp)) {
                if(photo==null)EmptyImport("从这张照片出发。","导入照片，查看本地与官方 LUT 在同一张照片上的效果。",onImport)
                else {
                    Column(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(photo.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=11.sp,color=tones.secondaryInk)
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            listOf("all" to "彩色推荐", "NATURAL" to "自然", "WARM" to "暖调", "COOL" to "冷调").forEach { option -> item {
                                MirrorChoice(style==option.first,{style=option.first;selected=0;selectedKey=null},label={Text(option.second,fontSize=11.sp)})
                            } }
                        }
                        AnimatedVisibility(showFilters) { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("在线试用官方 LUT",Modifier.weight(1f),fontSize=11.sp)
                                MirrorToggle(discoverOnline,"在线试用官方 LUT") {discoverOnline=it;selected=0;selectedKey=null}
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("包含未标注输入的 LUT",Modifier.weight(1f),fontSize=11.sp)
                                MirrorToggle(includeUnknown,"包含未标注输入的 LUT") {includeUnknown=it;selected=0;selectedKey=null}
                            }
                            Text("在线试用会临时获取少量 LUT；选用后才加入本地库。",fontSize=10.sp,color=tones.secondaryInk)
                        } }
                        if(discovering)Text("正在获取官方候选，用你的照片试套…",fontSize=11.sp,color=tones.secondaryInk)
                        (official?.problems.orEmpty()+listOfNotNull(onlineProblem)).distinct().filter { it!=result?.inputProblem }.forEach {
                            Text(it,fontSize=11.sp,color=tones.secondaryInk)
                        }
                    }
                    if(loading)Column(Modifier.fillMaxWidth().height(250.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp));Text("正在用照片小图试套…",Modifier.padding(top=16.dp),fontSize=12.sp)
                    }
                    else if(cards.isNotEmpty()) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val width=(maxWidth*.68f).coerceIn(180.dp,312.dp)
                            val pager=rememberPagerState(initialPage=selected.coerceIn(0,cards.lastIndex),pageCount={cards.size})
                            LaunchedEffect(cards.map { it.key }) {
                                val target=cards.indexOfFirst { it.key==selectedKey }.takeIf { it>=0 }
                                    ?: selected.coerceIn(0,cards.lastIndex)
                                if(pager.currentPage!=target)pager.scrollToPage(target)
                            }
                            LaunchedEffect(pager.settledPage) {
                                selected=pager.settledPage;selectedKey=cards.getOrNull(selected)?.key
                            }
                            HorizontalPager(pager,Modifier.fillMaxWidth().height(width+116.dp),pageSize=PageSize.Fixed(width),
                                contentPadding=PaddingValues(horizontal=(maxWidth-width)/2),pageSpacing=-width*.14f,beyondViewportPageCount=1,
                                key={cards.getOrNull(it)?.key ?: "recommendation-removed:$it"}) { index ->
                                val card=cards.getOrNull(index) ?: return@HorizontalPager
                                val d=(pager.currentPage-index)+pager.currentPageOffsetFraction
                                Polaroid(card,"recommendation:${card.key}",state,workspace,personal,shared,visibility,
                                    Modifier.fillMaxWidth().zIndex(3-abs(d)).graphicsLayer {
                                        rotationZ=-d.coerceIn(-2f,2f)*10f; rotationY=-d.coerceIn(-2f,2f)*9f
                                        translationY=d*d*8.dp.toPx(); cameraDistance=12*density
                                    }.shadow(12.dp,RoundedCornerShape(5.dp)),collection=true,
                                    onClick={onOpen(card,"recommendation:${card.key}")},onFavourite={onFavourite(card)})
                            }
                        }
                        val activeIndex=cards.indexOfFirst { it.key==selectedCard?.key }.coerceAtLeast(0)
                        val info=choices.getOrNull(activeIndex)
                        Column(Modifier.padding(horizontal=26.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                            Text("${activeIndex+1} / ${cards.size} · 左右滑动，点按查看",fontSize=11.sp,color=tones.secondaryInk)
                            info?.let {
                                Text(it.second.joinToString(" · "),fontSize=13.sp)
                                if(it.third.isNotEmpty())Text(it.third.joinToString("\n"),fontSize=11.sp,color=tones.secondaryInk)
                            }
                        }
                    } else Column(Modifier.fillMaxWidth().padding(28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(problem ?: result?.inputProblem ?: if(discovering) "本地暂无合适候选，正在试套官方 LUT…" else "暂无符合当前条件的候选。可调整色调或输入选项，也可到官方目录浏览。",fontSize=14.sp)
                        if(result?.inputProblem==null)MirrorTextAction("重新推荐",{retry++})
                        else MirrorTextAction("换一张彩色照片 →",onImport,enabled=!state.busy)
                    }
                    Column(Modifier.padding(horizontal=24.dp,vertical=16.dp)) {
                        Text("卡片均为你的照片在本机试套的效果，照片不会上传。",fontSize=10.sp,color=tones.secondaryInk)
                        AnimatedVisibility(showFilters) { official?.let {
                            Text("官方目录候选 ${it.catalogueCandidates} 款，经例图初筛后最多试套 5 款；不是全库效果评选。",fontSize=10.sp,color=tones.secondaryInk)
                        } }
                        if(state.luts.size>256)Text("本次分析前 256 款本地 LUT。",fontSize=10.sp,color=tones.secondaryInk)
                        MirrorTextAction("换一张照片 →",onImport,enabled=!state.busy)
                        if(state.photos.size>1)PhotoStrip(state,workspace)
                    }
                }
                }
            }
        }
        MirrorButton(modifier=Modifier.fillMaxWidth().padding(20.dp).heightIn(min=56.dp),
            enabled=selectedCard!=null && !state.busy && !loading,
            onClick={selectedCard?.let { card ->
                card.local?.let(onChoose) ?: onOpen(card,"recommendation:${card.key}")
            }}) { Text(if(selectedCard?.local!=null) "使用这款 LUT →" else "查看并下载这款 LUT →") }
    }
}
