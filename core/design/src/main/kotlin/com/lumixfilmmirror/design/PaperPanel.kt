package com.lumixfilmmirror.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/** Material belongs around the photograph; the image pixels keep their original contrast. */
@Composable
fun PaperPanel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val tones = LocalFilmMaterials.current
    val shape = RoundedCornerShape(FilmSpacing.cardRadius)
    Box(
        modifier.shadow(5.dp, shape, ambientColor = tones.shadow, spotColor = tones.shadow)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(tones.paperHighlight, tones.paper)))
            .border(1.dp, tones.rim, shape),
        content = content,
    )
}
