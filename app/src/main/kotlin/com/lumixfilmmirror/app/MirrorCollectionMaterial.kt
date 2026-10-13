package com.lumixfilmmirror.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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
