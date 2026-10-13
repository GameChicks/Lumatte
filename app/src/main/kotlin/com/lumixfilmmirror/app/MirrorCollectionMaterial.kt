package com.lumixfilmmirror.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Stationary spatial light, independent of the home page's moving colour field. */
@Composable
internal fun CollectionBackdrop(modifier: Modifier, dark: Boolean) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val colours = if (dark) arrayOf(
            0f to Color(0xFF091C3C), .38f to Color(0xFF0B2C50),
            .65f to Color(0xFF174B75), 1f to Color(0xFF96C5DF))
        else arrayOf(0f to Color(0xFFE2EDF5), .42f to Color(0xFFCEDFEE),
            .7f to Color(0xFFB4CFE2), 1f to Color(0xFFDCEAF6))
        drawRect(Brush.verticalGradient(colorStops = colours))
        drawRect(Brush.radialGradient(listOf(Color(0xFF008EAA).copy(if (dark) .25f else .08f), Color.Transparent),
            center = Offset(w * 1.10f, h * .10f), radius = w * 1.15f))
        drawRect(Brush.radialGradient(listOf(Color(0xFF70C2D2).copy(if (dark) .42f else .18f), Color.Transparent),
            center = Offset(w * .08f, h * .95f), radius = w * 1.02f))
        drawRect(Brush.radialGradient(listOf(Color(0xFFCED1FB).copy(if (dark) .37f else .24f), Color.Transparent),
            center = Offset(w * .98f, h * 1.07f), radius = w * .88f))
    }
}

@Composable
internal fun FavouriteColourLens(modifier: Modifier, diameter: Dp,
    title: String, enabled: Boolean, onClick: () -> Unit) {
    Box(modifier.size(diameter).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "查看 $title 的介绍" }) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val centre = Offset(w / 2, w / 2)
            val radius = w * .43f
            drawCircle(Brush.linearGradient(listOf(Color(0xFF8FD6E5), Color(0xFFAAA9EF), Color(0xFF4265F3)),
                start = Offset(w * .18f, w * .13f), end = Offset(w * .63f, w * .88f)), radius)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF76E2D6).copy(.83f), Color.Transparent),
                center = Offset(w * .20f, w * .28f), radius = w * .61f), radius, centre)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFF0AEF4).copy(.90f), Color.Transparent),
                center = Offset(w * .70f, w * .30f), radius = w * .63f), radius, centre)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF3554EE).copy(.8f), Color.Transparent),
                center = Offset(w * .44f, w * .89f), radius = w * .56f), radius, centre)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(.28f), Color.Transparent),
                center = Offset(w * .29f, w * .22f), radius = w * .48f), radius, centre)
            drawArc(Color.White.copy(.92f), -90f, 82f, false,
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = Size(w - 4.dp.toPx(), w - 4.dp.toPx()),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            val highlight = Offset(w * .72f, w * .47f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(.50f), Color.Transparent),
                center = highlight, radius = 7.dp.toPx()), 7.dp.toPx(), highlight)
            drawCircle(Color.White.copy(.96f), 2.5.dp.toPx(), highlight)
        }
    }
}
