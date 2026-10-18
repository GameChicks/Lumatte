@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.lumixfilmmirror.app

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lumixfilmmirror.design.LocalFilmDark
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts
import com.lumixfilmmirror.media.*
import com.lumixfilmmirror.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal fun FilmCategory.subtitle() = when (this) {
    FilmCategory.NEGATIVE -> "Color Negative"
    FilmCategory.CINEMA -> "Motion Picture"
    FilmCategory.REVERSAL -> "Reversal Film"
    FilmCategory.MONOCHROME -> "Black & White"
}

@Composable
internal fun FilmCategories(state: WorkspaceState, workspace:WorkspaceViewModel, positions: Map<String, Int>, onImport: () -> Unit, shared: SharedTransitionScope,
    visibility: AnimatedVisibilityScope, arrivalAngle: Float, departureAngle: Float,
    linkedCategory: String?, onCategory: (FilmCategory) -> Unit) {
    val reduced = LocalMirrorReducedMotion.current
    val tones = LocalFilmMaterials.current
    BoxWithConstraints(Modifier.fillMaxSize().background(tones.background).statusBarsPadding()) {
        val cardHeight = ((maxHeight-230.dp)/4).coerceIn(160.dp,186.dp)
        val fontScale = LocalDensity.current.fontScale
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp, vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { MirrorLayeredTitle("Film","Stories",tones.ink,tones.amber,compact=true) }
                RoundAction("导入照片",DotGlyph.PLUS,enabled=!state.busy,onClick=onImport)
            }
            if(state.photos.isNotEmpty())Column(Modifier.padding(horizontal=20.dp)) { PhotoManagementActions(state,workspace) }
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=118.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                FilmCategory.entries.forEachIndexed { index, category -> item(key=category.name) {
                    val films=state.filmPresets.filter { it.category==category }
                    val film=films.getOrNull(positions[category.name] ?: defaultFilmIndex(films,category)) ?: films.firstOrNull()
                    BoxWithConstraints(Modifier.fillMaxWidth().height(cardHeight + 64.dp*(fontScale-1f).coerceAtLeast(0f))
                        .clip(RoundedCornerShape(23.dp)).clickable(role=Role.Button) { onCategory(category) }) {
                        FilmAtmosphere(category,Modifier.fillMaxSize(),card=true)
                        val cardInk=when(category) {
                            FilmCategory.NEGATIVE -> Color(0xFF432809)
                            FilmCategory.CINEMA -> Color(0xFFF8F3DF)
                            FilmCategory.REVERSAL -> Color(0xFFF1F1D3)
                            FilmCategory.MONOCHROME -> Color(0xFF2D343A)
                        }
                        Column(Modifier.fillMaxHeight().fillMaxWidth(.66f).padding(start=22.dp,top=16.dp,bottom=17.dp)) {
                            Text("${index+1}".padStart(2,'0')+" / "+category.subtitle().uppercase(),fontSize=8.sp,
                                color=cardInk.copy(.72f),letterSpacing=.5.sp)
                            val morph=with(shared) { Modifier.sharedBounds(rememberSharedContentState("category-${category.name}"),visibility,
                                boundsTransform={ _,_->tween(if(reduced)0 else 420,easing=MirrorEase) }) }
                            Spacer(Modifier.height(6.dp))
                            FilmWordmark(category.editorialTitle(),morph.fillMaxWidth().weight(1f),size=72f,poster=true,ink=cardInk)
                            Spacer(Modifier.height(8.dp))
                            Text(category.label,fontFamily=MirrorFonts.Body,fontWeight=FontWeight.Medium,fontSize=12.sp,lineHeight=16.sp,color=cardInk)
                        }
                        film?.let {
                            val linked=category.name==linkedCategory
                            val roll=filmObjectRotation(visibility,10f,if(linked)arrivalAngle else 10f,if(linked)departureAngle else 10f)
                            val morph=with(shared) { Modifier.sharedElement(rememberSharedContentState("film-${it.id}"),visibility,
                                boundsTransform={ _,_->tween(if(reduced)0 else 420,easing=MirrorEase) }) }
                            FilmCartridge(it,Modifier.align(Alignment.CenterEnd).offset(x=8.dp,y=9.dp)
                                .width(maxWidth*.39f).height(cardHeight*1.02f).then(morph),roll=roll.value,yaw=-6f,grounded=true)
                        }
                        FilmSmallAction("浏览${category.label}",DotGlyph.RIGHT,
                            Modifier.align(Alignment.BottomEnd).padding(end=13.dp,bottom=12.dp),light=true) { onCategory(category) }
                    }
                } }
            }
        }
    }
}

@Composable
internal fun FilmCarousel(films: List<FilmPreset>, category: FilmCategory, initial: Int, armed: Boolean,
    reduced: Boolean, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
    arrivalAngle: Float, departureAngle: Float, onBack: () -> Unit,
    onPage: (Int) -> Unit, onUserScroll: () -> Unit, onDetail: (FilmPreset) -> Unit, onCategory: (FilmCategory) -> Unit) {
    val tones=LocalFilmMaterials.current
    val dark=LocalFilmDark.current
    val count=films.size
    val pager=rememberPagerState(initialPage=if(count==0)0 else count*100+initial.coerceIn(0,count-1),pageCount={count*201})
    val feedback=rememberMirrorTick()
    val automatic=LocalMirrorAutomaticFilmDetails.current
    val scope=rememberCoroutineScope()
    val roll=filmObjectRotation(visibility,-74f,arrivalAngle,departureAngle)
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(if(reduced)0 else 650); entered=true }
    LaunchedEffect(pager.interactionSource) {
        pager.interactionSource.interactions.collect { if(it is DragInteraction.Start)onUserScroll() }
    }
    LaunchedEffect(pager.settledPage) { if(count>0)onPage(pager.settledPage%count) }
    var previous by remember { mutableIntStateOf(pager.currentPage) }
    LaunchedEffect(pager.currentPage) {
        if(previous!=pager.currentPage)feedback()
        previous=pager.currentPage
    }
    LaunchedEffect(pager.settledPage,pager.isScrollInProgress,armed,entered,count,automatic,reduced) {
        if(entered && armed && automatic && !reduced && count>0 && !pager.isScrollInProgress) {
            delay(450)
            if(!pager.isScrollInProgress && abs(pager.currentPageOffsetFraction)<.01f)onDetail(films[pager.settledPage%count])
        }
    }
    val density=LocalDensity.current
    val blur=remember(density.density,reduced) { List(7) { if(it==0 || reduced)null else BlurEffect(it*density.density*.65f,it*density.density*.65f,TileMode.Clamp) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        FilmAtmosphere(category,Modifier.fillMaxSize())
        val top=WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom=0.dp
        val viewHeight=(maxHeight-top-bottom).coerceAtLeast(220.dp)
        val step=(viewHeight*.23f).coerceIn(134.dp,180.dp)
        if(count>0) VerticalPager(pager,Modifier.fillMaxSize().padding(top=top,bottom=bottom),
            pageSize=PageSize.Fixed(step),contentPadding=PaddingValues(vertical=((viewHeight-step)/2).coerceAtLeast(0.dp)),
            beyondViewportPageCount=1) { index ->
            val film=films[index%count]
            val distance=(pager.currentPage-index)+pager.currentPageOffsetFraction
            val center=(1f-abs(distance).coerceIn(0f,1f))
            Row(Modifier.fillMaxSize().zIndex(center),verticalAlignment=Alignment.CenterVertically) {
                Canvas(Modifier.padding(start=22.dp).width(9.dp).fillMaxHeight()) {
                    val x=size.width/2
                    drawLine(tones.ink.copy(.18f),Offset(x,0f),Offset(x,size.height),1.dp.toPx())
                    if(center>.9f)drawRoundRect(Color(0xFFF2C34C),Offset(x-2.dp.toPx(),size.height/2-12.dp.toPx()),Size(4.dp.toPx(),24.dp.toPx()),
                        androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                    else drawCircle(tones.ink.copy(.32f),2.dp.toPx(),Offset(x,size.height/2))
                }
                Spacer(Modifier.weight(.28f))
                val morph=if(index==pager.currentPage)with(shared) { Modifier.sharedElement(rememberSharedContentState("film-${film.id}"),visibility,
                    boundsTransform={ _,_->tween(if(reduced)0 else 420,easing=MirrorEase) }) } else Modifier
                FilmCartridge(film,Modifier.weight(.72f).requiredHeight(step*1.43f).then(morph)
                    .graphicsLayer {
                        scaleX=.8f+center*.2f; scaleY=scaleX
                        translationX=abs(distance).coerceIn(0f,2f)*11.dp.toPx()
                        alpha=.32f+center*.68f
                        renderEffect=blur[if(abs(distance)>.55f)(abs(distance)*2f).toInt().coerceIn(1,6) else 0]
                    }.clickable(role=Role.Button) {
                        if(index==pager.currentPage)onDetail(film) else scope.launch {
                            onUserScroll()
                            if(reduced) { pager.scrollToPage(index); onPage(index%count); onDetail(film) }
                            else pager.animateScrollToPage(index)
                        }
                    },roll=roll.value+distance.coerceIn(-2f,2f)*3f,yaw=-7f+distance.coerceIn(-1f,1f)*6f,
                    pitch=4f,illumination=.60f+center*.4f)
            }
        } else Text("此分类暂无胶卷",Modifier.align(Alignment.Center))
        Canvas(Modifier.fillMaxSize()) {
            val edge=if(dark)Color(0xFF14161D) else Color(0xFFEAD8B8)
            drawRect(Brush.verticalGradient(listOf(edge,Color.Transparent),startY=0f,endY=64.dp.toPx()))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent,edge),startY=size.height-130.dp.toPx(),endY=size.height))
        }
        FilmSmallAction("返回胶卷分类",DotGlyph.LEFT,Modifier.align(Alignment.TopStart).padding(start=18.dp,top=top+10.dp),onClick=onBack)
        Column(Modifier.align(Alignment.BottomStart).padding(start=22.dp,bottom=142.dp,end=22.dp).fillMaxWidth(.58f)) {
            val morph=with(shared) { Modifier.sharedBounds(rememberSharedContentState("category-${category.name}"),visibility,
                boundsTransform={ _,_->tween(if(reduced)0 else 420,easing=MirrorEase) }) }
            Box(morph) { MirrorLayeredTitle(category.editorialTitle(),when(category) {
                FilmCategory.NEGATIVE -> "Color"; FilmCategory.CINEMA -> "Motion"
                FilmCategory.REVERSAL -> "Reversal"; FilmCategory.MONOCHROME -> "Silver"
            },tones.ink,tones.ink.copy(.78f)) }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start=22.dp,end=22.dp,bottom=16.dp),
            verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Text(if(reduced || !automatic)"上下滑动选卷 · 点按查看介绍" else "上下滑动选卷\n停留片刻，展开这一卷",fontSize=11.sp,
                lineHeight=16.sp,color=tones.secondaryInk)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                FilmCategory.entries.forEach { entry -> item(key=entry.name) {
                    MirrorChoice(category==entry,{if(category!=entry)onCategory(entry)},label={Text(entry.label,fontSize=10.sp)})
                } }
            }
        }
    }
}

@Composable
internal fun FilmDetail(film: FilmPreset,state: WorkspaceState,workspace: WorkspaceViewModel,onImport: () -> Unit,
    shared: SharedTransitionScope,visibility: AnimatedVisibilityScope,arrivalAngle: Float,departureAngle: Float,
    onBack: () -> Unit,onChoose: () -> Unit) {
    val info=FilmEditorialLibrary.forFilm(film)
    val name=film.editorialName()
    val tones=LocalFilmMaterials.current
    val dark=LocalFilmDark.current
    val reduced=LocalMirrorReducedMotion.current
    val rotation=filmObjectRotation(visibility,0f,arrivalAngle,departureAngle)
    val context=LocalContext.current
    var fullStory by rememberSaveable(film.id) { mutableStateOf(false) }
    val statusBarTop=WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(if(dark)Color(0xFF0B100D) else Color(0xFFF3EDE0))) {
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=14.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item(key="hero") {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val heroWidth=maxWidth
                    val heroHeight=(heroWidth*.92f).coerceIn(270.dp,380.dp)+statusBarTop
                    Box(Modifier.fillMaxWidth().height(heroHeight)) {
                        FilmAtmosphere(film.category,Modifier.fillMaxSize(),accent=info.accent,hero=true)
                        FilmSmallAction("返回选卷",DotGlyph.LEFT,Modifier.align(Alignment.TopStart).padding(start=18.dp,top=statusBarTop+10.dp),light=true,onClick=onBack)
                        val morph=with(shared) { Modifier.sharedElement(rememberSharedContentState("film-${film.id}"),visibility,
                            boundsTransform={ _,_->tween(if(reduced)0 else 420,easing=MirrorEase) }) }
                        FilmCartridge(film,Modifier.align(Alignment.BottomCenter).width(heroWidth*.56f).height(heroHeight-44.dp).then(morph),
                            roll=rotation.value,yaw=-4f,pitch=1f,grounded=true)
                    }
                }
            }
            item(key="title") {
                Column(Modifier.padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    FilmWordmark(listOfNotNull(name.title,name.speed).joinToString(" "),Modifier.fillMaxWidth(),size=62f,poster=true)
                    Text(film.category.subtitle(),fontFamily=MirrorFonts.FilmDisplay,fontStyle=FontStyle.Italic,fontSize=20.sp,color=tones.amber)
                    name.variant?.let { Text(it,fontSize=12.sp,color=tones.secondaryInk) }
                }
            }
            item(key="technical") {
                val speed=name.speed?.takeWhile { it.isDigit() }
                val cells=listOf("格式" to if(film.id.startsWith("lfm_"))"自有模拟" else "135 / 35mm",
                    "名义感光度" to (speed?.let { "ISO $it" } ?: "不设 ISO"),"色彩基准" to info.balance,"原片工艺" to info.process)
                Row(Modifier.fillMaxWidth().padding(horizontal=22.dp)) {
                    cells.forEachIndexed { index,cell ->
                        if(index>0)Box(Modifier.width(1.dp).height(34.dp).background(tones.rim.copy(.55f)))
                        Column(Modifier.weight(1f).padding(horizontal=6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(cell.first,fontSize=9.sp,color=tones.secondaryInk)
                            Text(cell.second,fontFamily=MirrorFonts.Body,fontWeight=FontWeight.Medium,fontSize=12.sp,
                                lineHeight=16.sp,maxLines=2)
                        }
                    }
                }
            }
            item(key="story") {
                Column(Modifier.padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        info.tags.forEach { tag -> Text(tag,Modifier.clip(RoundedCornerShape(18.dp))
                            .background(info.accent.copy(if(dark).19f else .18f)).padding(horizontal=13.dp,vertical=7.dp),
                            fontSize=11.sp,color=tones.ink) }
                    }
                    Text(info.tagline,fontFamily=MirrorFonts.Body,fontWeight=FontWeight.Medium,fontSize=18.sp,lineHeight=27.sp)
                    Text(info.story,fontSize=14.sp,lineHeight=23.sp,color=tones.ink.copy(.85f),maxLines=if(fullStory)Int.MAX_VALUE else 4,
                        overflow=TextOverflow.Ellipsis)
                    MirrorTextAction(if(fullStory)"收起介绍 ↑" else "读完整介绍 ↓",{fullStory=!fullStory})
                    if(info.note.isNotBlank())Text(info.note,fontSize=11.sp,lineHeight=17.sp,color=tones.secondaryInk)
                }
            }
            item(key="traits") {
                Row(Modifier.fillMaxWidth().padding(horizontal=22.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    listOf(Triple(DotGlyph.LUT,"色彩倾向",info.colour),Triple(DotGlyph.FILM,"颗粒表现",info.grain),
                        Triple(DotGlyph.FRAME,"反差与层次",info.contrast)).forEach { (icon,label,value) ->
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            DotIcon(icon,tint=if(dark)Color(0xFFE1BF72) else Color(0xFF80673B))
                            Text(label,fontSize=10.sp,color=tones.secondaryInk)
                            Text(value,fontSize=12.sp,fontWeight=FontWeight.Medium)
                        }
                    }
                }
            }
            item(key="examples") { FilmDetailExamples(film,state,workspace,onChoose) }
            item(key="source") {
                Column(Modifier.padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("适合尝试 · ${info.use}",fontSize=12.sp,color=tones.secondaryInk)
                    info.source?.let { source -> MirrorTextAction("胶卷资料与来源 ↗",{
                        try { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(source))) }
                        catch (_:Exception) { workspace.notice("没有可用浏览器，请稍后查看") }
                    }) }
                }
            }
        }
        Row(Modifier.padding(horizontal=20.dp,vertical=12.dp).fillMaxWidth().heightIn(min=56.dp)
            .clip(RoundedCornerShape(30.dp)).background(Color(0xFF191C26)).clickable(enabled=!state.busy,role=Role.Button,onClick=onChoose)
            .padding(horizontal=22.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("选择此卷",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.Medium,color=Color(0xFFF3C653))
            DotIcon(DotGlyph.RIGHT,tint=Color(0xFFF3C653))
        }
    }
}

@Composable
private fun FilmDetailExamples(film: FilmPreset,state: WorkspaceState,workspace: WorkspaceViewModel,onChoose: () -> Unit) {
    val tones=LocalFilmMaterials.current
    Column(Modifier.fillMaxWidth().padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("样片预览",Modifier.weight(1f),fontFamily=MirrorFonts.ChineseEditorial,fontSize=17.sp)
            Text(if(state.photos.isEmpty())"用你的照片" else "你的照片 · 默认配方",fontSize=10.sp,color=tones.secondaryInk)
        }
        if(state.photos.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(12.dp)).background(tones.ink.copy(.035f))
                .border(1.dp,tones.rim.copy(.5f),RoundedCornerShape(12.dp)).clickable(enabled=!state.busy,onClick=onChoose),contentAlignment=Alignment.Center) {
                Text("导入照片，查看这一卷的实际模拟 →",fontSize=12.sp,color=tones.secondaryInk)
            }
        } else Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            state.photos.take(3).forEach { source ->
                val key=listOf("film-detail",source.id,film.id)
                var image by remember(key) { mutableStateOf(workspace.displayPreviews.peek(key)?.image) }
                var problem by remember(key) { mutableStateOf(false) }
                LaunchedEffect(key) {
                    try {
                        image=workspace.displayPreviews.load(key,keepLoading=false) { MirrorPreview(workspace.processor.preview(source,
                            PhotoRecipe.Film(FilmParameters(presetId=film.id),source.id.value.hashCode().toLong()),
                            ProcessingCatalogue(state.luts,state.filmPresets,emptyList()),longEdge=320)) }.image
                    } catch(error:CancellationException) { throw error }
                    catch(_:Exception) { problem=true }
                    catch(_:OutOfMemoryError) { problem=true }
                }
                Box(Modifier.weight(1f).aspectRatio(1.12f).clip(RoundedCornerShape(10.dp)).background(tones.paperHighlight),contentAlignment=Alignment.Center) {
                    image?.let { Image(it.asImageBitmap(),"${film.name} · ${source.name}",Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
                        ?: Text(if(problem)"暂不可用" else "正在预览",fontSize=10.sp,color=tones.secondaryInk)
                }
            }
            repeat(3-minOf(3,state.photos.size)) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
internal fun FilmWordmark(word: String,modifier: Modifier=Modifier,size: Float=78f,italic: Boolean=false,
    poster: Boolean=false,ink: Color=LocalFilmMaterials.current.ink) {
    val density=LocalDensity.current
    val measurer=rememberTextMeasurer()
    BoxWithConstraints(modifier,contentAlignment=Alignment.CenterStart) {
        val style=TextStyle(fontFamily=if(poster)MirrorFonts.FilmPoster else MirrorFonts.FilmDisplay,
            fontStyle=if(italic)FontStyle.Italic else FontStyle.Normal,fontWeight=if(poster)FontWeight.Bold else FontWeight.Normal,fontSize=size.sp)
        val width=with(density) { maxWidth.toPx() }
        val natural=measurer.measure(word,style,softWrap=false).size
        val heightRatio=if(constraints.hasBoundedHeight)constraints.maxHeight.toFloat()/natural.height.coerceAtLeast(1) else 1f
        val fitted=size*minOf(1f,width/natural.width.coerceAtLeast(1),heightRatio)
        Text(word,style=style.copy(fontSize=fitted.sp),color=ink,
            maxLines=1,softWrap=false,overflow=TextOverflow.Clip)
    }
}

@Composable
private fun FilmSmallAction(label: String,glyph: DotGlyph,modifier: Modifier=Modifier,light: Boolean=false,onClick: () -> Unit) {
    val ink=if(light)Color(0xFF1F241B) else LocalFilmMaterials.current.ink
    Box(modifier.size(48.dp).clickable(role=Role.Button,onClick=onClick).semantics { contentDescription=label },contentAlignment=Alignment.Center) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(if(light)Color.White.copy(.45f) else ink.copy(.07f)),contentAlignment=Alignment.Center) {
            DotIcon(glyph,Modifier.size(19.dp),tint=ink)
        }
    }
}

@Composable
private fun FilmAtmosphere(category: FilmCategory,modifier: Modifier,card: Boolean=false,hero: Boolean=false,
    accent: Color=category.colour()) {
    val dark=LocalFilmDark.current
    if(hero) {
        MirrorColorField(modifier,if(category==FilmCategory.MONOCHROME)listOf(Color(0xFFCED1C5),Color(0xFF66786E),Color(0xFFEAE4D3))
            else listOf(accent,Color(0xFFFFD36A),Color(0xFF407FA0),Color(0xFFD65D3E)))
        return
    }
    Canvas(modifier) {
        val w=size.width; val h=size.height
        val base=if(card)category.colour() else if(dark)Color(0xFF14161D) else Color(0xFFEAD8B8)
        drawRect(base)
        drawRect(Brush.radialGradient(listOf(accent.copy(if(card).14f else if(dark).36f else .55f),Color.Transparent),
            center=Offset(w*.88f,h*.44f),radius=w*.57f))
        drawRect(Brush.radialGradient(listOf(if(card)Color.White.copy(.34f) else if(dark)Color(0xFF9BAED0).copy(.18f) else Color(0xFFF7C590).copy(.45f),Color.Transparent),
            center=Offset(w*.22f,h*.18f),radius=w*.72f))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(if(card).12f else if(dark).24f else .04f))))
        if(hero) {
            val shelf=h*.88f
            drawRect(Brush.verticalGradient(listOf(Color.Transparent,if(dark)Color(0xFF29261B).copy(.36f) else Color(0xFFC6B28B).copy(.20f),Color.Transparent),
                startY=shelf-12.dp.toPx(),endY=h),topLeft=Offset(0f,shelf-12.dp.toPx()),size=Size(w,h-shelf+12.dp.toPx()))
        }
        if(!card && !hero) {
            drawRect(Brush.verticalGradient(listOf(base.copy(.18f),Color.Transparent,base.copy(.85f)),startY=0f,endY=h))
        }
    }
}
