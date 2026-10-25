package com.lumixfilmmirror.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
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

/** Fictional analogue film gate, with the real processed photo behind the physical opening. */
@Composable
internal fun DevelopmentCamera(state: WorkspaceState, workspace: WorkspaceViewModel, filter: ColorFilter,
    progress: Float, onReady: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val camera by workspace.cameraState.collectAsState()
    val model = developmentCameraModel(state.current?.metadata?.camera, camera.device?.model?.takeIf { camera.connected })
    val body by produceState<Bitmap?>(null, context) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("development/lumix-analogue-back-v1.png").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })
            }
        }
    }
    DisposableEffect(body) { val owned = body; onDispose { owned?.recycle() } }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A close view of the film gate keeps the photo readable on a portrait phone.
        // Fit both bounds rather than letting a wide/short window push controls off screen.
        val width = minOf(maxWidth, maxHeight * 1.087f)
        val height = width / 1.087f
        val bodyWidth = width * 1.38f
        val bodyLeft = (width - bodyWidth) / 2
        Box(Modifier.width(width).height(height).clip(RoundedCornerShape(18.dp)).graphicsLayer {
            translationY = (1f - progress) * 5.dp.toPx()
        }) {
        // Coordinates come from this sprite's actual transparent film gate, not its outer bounds.
        Box(Modifier.offset(bodyLeft + bodyWidth * .214f, height * .265f).width(bodyWidth * .574f).height(height * .512f)
            .clip(RoundedCornerShape(3.dp)).background(Color.Black)) {
            EditedPreview(state, workspace, colourFilter = filter, onReady = onReady,
                compactHeight = height * .512f, matte = Color.Black)
        }
        body?.let { bitmap -> Canvas(Modifier.fillMaxSize()) {
            drawImage(bitmap.asImageBitmap(), dstOffset = IntOffset(bodyLeft.toPx().roundToInt(), 0),
                dstSize = IntSize(bodyWidth.toPx().roundToInt(), size.height.roundToInt()))
        } }
        Text("LUMIX", Modifier.offset(width * .085f, height * .142f), color = Color(0xFFE5E1D8),
            fontFamily = MirrorFonts.Body, fontWeight = FontWeight.Bold,
            fontSize = (width.value * .045f).coerceIn(12f, 25f).sp, letterSpacing = 1.3.sp)
        model?.let { Text(it, Modifier.offset(width * .50f, height * .843f).width(width * .38f),
            color = Color(0xFFCCC4B7), fontFamily = MirrorFonts.Body, fontSize = 10.sp,
            textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
