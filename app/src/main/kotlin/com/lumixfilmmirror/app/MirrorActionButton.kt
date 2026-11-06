package com.lumixfilmmirror.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.design.LocalFilmMaterials

/** Shared secondary action: the same minimum touch size, radius, palette and disabled state. */
@Composable
internal fun MirrorActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val tones = LocalFilmMaterials.current
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, tones.rim.copy(alpha = .5f)),
        colors = ButtonDefaults.textButtonColors(containerColor = tones.paperHighlight, contentColor = tones.ink,
            disabledContainerColor = tones.paperHighlight.copy(alpha = .5f), disabledContentColor = tones.secondaryInk.copy(alpha = .5f)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), content = content)
}
