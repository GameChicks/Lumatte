package com.lumixfilmmirror.media

import com.lumixfilmmirror.model.*

data class FrameCaptionField(val key: String, val label: String, val text: String, val font: FrameFont, val fontKey: String?)

internal fun captionTypeSize(field:FrameCaptionField,fields:List<FrameCaptionField>,frame:FrameOptions):Float = when(field.key) {
    "title"->if(frame.preset==FramePreset.TICKET)35f else 24f
    "subtitle"->if(fields.none { it.key=="title" })24f else 18f
    "footer"->14f
    else->20f
}

/** Tight vertical proof bounds from the same caption geometry used by the export renderer. */
internal fun captionProofBounds(source:SourceImage,frame:FrameOptions,stock:FilmPreset?,layout:FrameLayout):FloatBox {
    val unit=minOf(layout.canvas.width,layout.canvas.height)/1000f
    val textUnit=if(frame.preset==FramePreset.FILM)layout.canvas.width/1250f else unit
    val fields=frameCaptionFields(source,frame,stock)
    val visible=fields.filter {it.text.isNotBlank() && (frame.showPhotoMetadata || it.key=="signature")}
    val badge=frame.preset==FramePreset.PALETTE && (frame.imageKey!=null || stock!=null)
    val natural=(if(badge)52*unit else 0f)+(if(badge && visible.isNotEmpty())12*unit else 0f)+
        visible.sumOf { (textUnit*captionTypeSize(it,fields,frame)*1.5f*it.text.take(240).split('\n').take(3).size).toDouble() }.toFloat()+
        textUnit*8*maxOf(0,visible.size-1)
    val box=layout.caption
    val height=minOf(box.height,natural)+minOf(box.height,textUnit*12)
    val center=(box.top+box.bottom)/2
    return FloatBox(box.left,maxOf(box.top,center-height/2),box.right,minOf(box.bottom,center+height/2))
}

/** The editor exposes the same nonempty automatic lines that the renderer actually prints. */
fun frameCaptionFields(source: SourceImage, frame: FrameOptions, stock: FilmPreset?): List<FrameCaptionField> {
    val ticket=frame.preset==FramePreset.TICKET
    val camera=source.metadata.frameCameraName.takeIf { frame.showCamera }.orEmpty()
    val brand=frame.filmBrand.ifBlank { stock?.name?.substringBefore(' ') ?: "LUMIX" }
    val film=frame.filmName.ifBlank { stock?.name?.substringAfter(' ') ?: "FILM MIRROR" }
    val base=frame.titleFont ?: when(frame.preset) {
        FramePreset.FILM,FramePreset.SLIDE->FrameFont.TYPEWRITER
        FramePreset.TICKET->FrameFont.GREAT_VIBES
        FramePreset.MUSEUM->FrameFont.SERIF
        else->FrameFont.SPACED
    }
    val title=frame.title ?: if(ticket)"Where the light lingers" else camera
    val subtitle=frame.subtitle ?: if(ticket)listOf(camera,"$brand $film").filter { it.isNotBlank() }.joinToString("  /  ") else "$brand $film"
    val footer=frame.footer ?: if(ticket)"A moment, kept in light." else source.metadata.frameDetail(frame)
    return buildList {
        if(title.isNotBlank())add(FrameCaptionField("title","主标题",title,base,frame.titleFontKey))
        if(subtitle.isNotBlank())add(FrameCaptionField("subtitle",if(isEmpty())"文字"else"副标题",subtitle,
            frame.subtitleFont ?: if(ticket)FrameFont.SPACED else base,frame.subtitleFontKey))
        if(footer.isNotBlank())add(FrameCaptionField("footer","拍摄注记",footer,
            frame.footerFont ?: if(ticket)FrameFont.TYPEWRITER else FrameFont.SANS,frame.footerFontKey))
        add(FrameCaptionField("signature","签名",frame.signature,frame.signatureFont,frame.signatureFontKey))
    }
}
