package com.lumixfilmmirror.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lumixfilmmirror.design.MirrorFonts
import com.lumixfilmmirror.media.frame.FrameCameraNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Display metadata only: never invent a camera from a filename, remembered device or film preset. */
internal fun developmentCameraModel(exif: String?, connected: String?): String? {
    fun clean(value: String?): String? = value?.trim()?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotBlank() && it.uppercase(java.util.Locale.ROOT) !in
            setOf("UNKNOWN", "UNKNOWN MODEL", "N/A", "PANASONIC", "PANASONIC CORPORATION", "LUMIX", "未知") }
        ?.let { FrameCameraNames.format(it).replace(Regex("^Lumix\\s+", RegexOption.IGNORE_CASE), "") }
        ?.take(48)
    return clean(exif) ?: clean(connected)
}

/** The photo determines the gate. Only the material between the sprite's corners can extend. */
private data class DevelopmentGate(val width: Float, val height: Float,
    val photoWidth: Float, val photoHeight: Float, val scale: Float) {
    val left get() = 328f * scale
    val top get() = 273f * scale
}

private fun developmentGate(availableWidth: Float, availableHeight: Float, ratio: Float): DevelopmentGate {
    // Pixel measurements of the original 1536 x 1024 sprite's transparent opening.
    // Keep a useful physical rail width; reduce the rails for very small or extreme windows.
    var scale = minOf(44f / 328f, availableWidth / 1536f, availableHeight / 1024f)
    fun photoWidth() = minOf(availableWidth - 654f * scale, (availableHeight - 503f * scale) * ratio)
    val initialWidth = photoWidth()
    scale = minOf(scale, initialWidth / 160f, initialWidth / ratio / 160f)
    val width = photoWidth()
    val height = width / ratio
    return DevelopmentGate(width + 654f * scale, height + 503f * scale, width, height, scale)
}

/** Fictional variable film gate; the image and all four material corners keep their own proportions. */
@Composable
internal fun DevelopmentCamera(state: WorkspaceState, workspace: WorkspaceViewModel, filter: ColorFilter,
    progress: Float, onReady: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val camera by workspace.cameraState.collectAsState()
    val model = developmentCameraModel(state.current?.metadata?.camera, camera.device?.model?.takeIf { camera.connected })
    val body by produceState<Bitmap?>(null, context) {
        var owned: Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                context.assets.open("development/lumix-analogue-back-v1.png").use {
                    owned = BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })
                }
            }
            value = owned
            awaitCancellation()
        } finally { owned?.recycle() }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val source = state.current ?: return@BoxWithConstraints
        if (maxWidth <= 0.dp || maxHeight <= 0.dp) return@BoxWithConstraints
        val ratio = source.size.width.toFloat() / source.size.height
        val gate = developmentGate(maxWidth.value, maxHeight.value, ratio)
        Box(Modifier.width(gate.width.dp).height(gate.height.dp).graphicsLayer {
            translationY = (1f - progress) * 5.dp.toPx()
        }) {
            Box(Modifier.offset(gate.left.dp, gate.top.dp).width(gate.photoWidth.dp).height(gate.photoHeight.dp)
                .clip(RoundedCornerShape((12f * gate.scale).dp)).background(Color.Black)) {
                EditedPreview(state, workspace, colourFilter = filter, onReady = onReady,
                    compactHeight = gate.photoHeight.dp, matte = Color.Black)
            }
            body?.let { bitmap -> Canvas(Modifier.fillMaxSize()) {
                val unit = gate.scale.dp.toPx()
                // Slice through the transparent opening. Corners are scaled uniformly;
                // top/bottom rails extend horizontally and side rails extend vertically.
                val sx = intArrayOf(0, 400, 1136, 1536)
                val sy = intArrayOf(0, 340, 720, 1024)
                val dx = intArrayOf(0, (400 * unit).roundToInt(), (size.width - 400 * unit).roundToInt(), size.width.roundToInt())
                val dy = intArrayOf(0, (340 * unit).roundToInt(), (size.height - 304 * unit).roundToInt(), size.height.roundToInt())
                val image = bitmap.asImageBitmap()
                for (row in 0..2) for (column in 0..2) {
                    val w = dx[column + 1] - dx[column]
                    val h = dy[row + 1] - dy[row]
                    if (w > 0 && h > 0) drawImage(image,
                        srcOffset = IntOffset(sx[column], sy[row]),
                        srcSize = IntSize(sx[column + 1] - sx[column], sy[row + 1] - sy[row]),
                        dstOffset = IntOffset(dx[column], dy[row]), dstSize = IntSize(w, h))
                }
            } }
            Text("LUMIX", Modifier.offset(gate.left.dp, (136f * gate.scale).dp), color = Color(0xFFE5E1D8),
                fontFamily = MirrorFonts.Body, fontWeight = FontWeight.Bold,
                fontSize = (90f * gate.scale).sp, lineHeight = (96f * gate.scale).sp, letterSpacing = 1.sp)
            model?.let { Text(it, Modifier.offset(gate.left.dp, (gate.height - 170f * gate.scale).dp)
                .width(gate.photoWidth.dp), color = Color(0xFFCCC4B7), fontFamily = MirrorFonts.Body,
                fontSize = (74f * gate.scale).sp, lineHeight = (90f * gate.scale).sp,
                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
