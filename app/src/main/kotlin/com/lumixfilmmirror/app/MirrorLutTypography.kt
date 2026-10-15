package com.lumixfilmmirror.app

import com.lumixfilmmirror.design.MirrorFonts

/** Switch the whole mixed name to the provided Chinese handwriting only when Han characters occur. */
internal fun lutNameFont(name:String) = if(name.codePoints().anyMatch {
    Character.UnicodeScript.of(it)==Character.UnicodeScript.HAN
}) MirrorFonts.ChineseHandwriting else MirrorFonts.Handwriting
