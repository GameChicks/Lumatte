package com.lumixfilmmirror.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.lumixfilmmirror.design.MirrorFonts
import com.lumixfilmmirror.media.FilmPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Reuses the recovered label originals, never the reference screen/mockup as a UI bitmap. */
private val labelAssets = mapOf(
    "kodak_gold_200" to "film_gold_200_image2.png", "kodak_ektar_100" to "film_ektar_100.png",
    "kodak_ultramax_400" to "film_ultramax_400.png", "fujifilm_c200" to "film_pkg_fuji_c200.png",
    "fujifilm_provia_100f" to "film_pkg_fuji_provia100f.png", "fujifilm_velvia_100" to "film_pkg_fuji_velvia100.png",
    "fujifilm_xtra_400" to "film_pkg_fuji_superia400.png", "kodak_ektachrome_100" to "film_pkg_kodak_e100.png",
    "kodak_vision3_50d" to "film_pkg_kodak_5203.png", "kodak_vision3_200t" to "film_pkg_kodak_5213.png",
    "kodak_vision3_500t" to "film_pkg_kodak_5219.png")
private val labels = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

internal fun FilmPreset.shortTitle() = name.removePrefix("Kodak ").removePrefix("Fujifilm ")

internal data class FilmEditorialName(val title: String, val speed: String?, val variant: String?)

/** Processing variants are not a different physical cartridge or an extra ISO number. */
internal fun FilmPreset.editorialName(): FilmEditorialName {
    val raw = shortTitle()
    if (id.startsWith("lfm_silver_")) return FilmEditorialName("Silver", null, raw.substringAfter(" · ", "").ifBlank { null })
    val push = Regex("\\s*\\(Push ([12])\\)$").find(raw)
    val stock = if (push == null) raw else raw.substring(0, push.range.first)
    val speed = Regex("(?:^| )([0-9]{2,4}[A-Za-z]*)$").find(stock)?.groupValues?.get(1)
    return FilmEditorialName(if (speed == null) stock else stock.removeSuffix(speed).trim(), speed,
        push?.groupValues?.get(1)?.let { "推片 +$it 档" })
}

@Composable
internal fun StockLabel(film: FilmPreset, modifier: Modifier) {
    val context = LocalContext.current
    val asset = labelAssets[film.id]
    var bitmap by remember(asset) { mutableStateOf<Bitmap?>(asset?.let { labels[it] }) }
    LaunchedEffect(asset) {
        try { if (asset != null && bitmap == null) bitmap = withContext(Dispatchers.IO) {
            labels[asset] ?: context.assets.open("frames/art/$asset").use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                    ?.also { labels.put(asset, it) }
            }
        } } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep the labelled cartridge if a recovered asset is unavailable. */ }
        catch (_: OutOfMemoryError) { /* Bounded fallback typography remains usable. */ }
    }
    Box(modifier.clip(RoundedCornerShape(2.dp))) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            ?: Column(Modifier.fillMaxSize().background(if (film.id.contains("portra")) Color(0xFFE4D2A4) else film.category.colour())
                .padding(horizontal = 7.dp, vertical = 9.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(film.name.substringBefore(' ').uppercase(), fontFamily = MirrorFonts.Body, fontWeight = FontWeight.Bold,
                    fontSize = 8.sp, color = Color(0xFF25251E))
                Text(film.shortTitle(), fontFamily = MirrorFonts.Body, fontWeight = FontWeight.Bold, fontSize = 19.sp,
                    lineHeight = 21.sp, color = Color(0xFF151716), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(12.dp).background(if (film.id.contains("portra")) Color(0xFF785477) else Color(0xFFB74935)))
            }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.horizontalGradient(colorStops = arrayOf(0f to Color.Black.copy(.30f), .14f to Color.White.copy(.12f),
                .40f to Color.Transparent, .72f to Color.Transparent, 1f to Color.Black.copy(.38f))))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(.09f), Color.Transparent, Color.Black.copy(.10f))))
        }
    }
}
