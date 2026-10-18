package com.lumixfilmmirror.media.frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.lumixfilmmirror.media.BitmapOrientation
import com.lumixfilmmirror.media.FilmCartridgeArtwork
import com.lumixfilmmirror.model.PixelSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** App-owned immutable copies. Keys, never provider URIs or absolute paths, enter frame recipes. */
class FrameAssets(context: Context) {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, "frame-assets")

    private fun file(key: String, extension: String): File {
        require(key.matches(Regex("[a-f0-9]{64}\\.$extension")))
        return File(directory, key).also { require(it.isFile) { "画框素材副本不可用，请重新导入" } }
    }

    internal fun bitmap(key: String): Bitmap = requireNotNull(BitmapFactory.decodeFile(file(key, "png").path)) {
        "无法读取画框装饰图"
    }
    internal fun font(key: String): Typeface = Typeface.createFromFile(file(key, "font"))
    internal fun bundled(name: String): Bitmap {
        require(name.matches(Regex("[a-z0-9_]+\\.png")))
        return context.assets.open("frames/art/$name").use { requireNotNull(BitmapFactory.decodeStream(it)) }
    }
    internal fun bundledFont(name: String): Typeface = Typeface.createFromAsset(context.assets, "frames/fonts/$name")
    internal fun cover(stockId: String?): Bitmap? {
        return cartridge(stockId, 256)
    }
    internal fun cartridge(stockId: String?, longEdge: Int = 2048): Bitmap? {
        if (stockId == null) return null
        val resource = FilmCartridgeArtwork.resource(stockId) ?: return null
        require(longEdge in 64..4096)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(context.resources, resource, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2L) >= longEdge) sample *= 2
        return BitmapFactory.decodeResource(context.resources, resource, BitmapFactory.Options().apply {
            inScaled = false; inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    suspend fun importFont(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = read(uri, 32 * 1024 * 1024)
        val sfnt = bytes.take(4).map { it.toInt() and 255 }
        require(sfnt == listOf(0, 1, 0, 0) || sfnt == listOf(79, 84, 84, 79) || sfnt == listOf(116, 116, 99, 102)) {
            "请选择有效的 TTF、OTF 或 TTC 字体"
        }
        save(bytes, "font") { Typeface.createFromFile(it) }
    }

    /** Transparent PNG logos remain transparent; only the private copy is normalized and reduced. */
    suspend fun importImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = read(uri, 32 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取画框装饰图" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2L) >= 640) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
        try {
            val orientation = ByteArrayInputStream(bytes).use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            }.takeIf { it in 1..8 } ?: 1
            val rotated = orientation in 5..8
            val width = if (rotated) decoded.height else decoded.width
            val height = if (rotated) decoded.width else decoded.height
            val factor = minOf(1f, 640f / maxOf(width, height))
            val pixels = BitmapOrientation.render(decoded, PixelSize(maxOf(1, (width * factor).roundToInt()),
                maxOf(1, (height * factor).roundToInt())), orientation)
            try {
                coroutineContext.ensureActive()
                val png = ByteArrayOutputStream().use { output ->
                    check(pixels.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
                }
                save(png, "png") { requireNotNull(BitmapFactory.decodeFile(it.path)).recycle() }
            } finally { pixels.recycle() }
        } finally { decoded.recycle() }
    }

    private suspend fun read(uri: Uri, limit: Int): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= limit) { "画框素材超过 32 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取所选画框素材")
    }

    private fun save(bytes: ByteArray, extension: String, inspect: (File) -> Unit): String {
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + ".$extension"
        check(directory.isDirectory || directory.mkdirs())
        val temp = File.createTempFile("import-", ".part", directory)
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            inspect(temp)
            val target = File(directory, key)
            synchronized(FrameAssets::class.java) {
                if (!target.exists()) check(temp.renameTo(target)) { "无法保存画框素材副本" }
            }
        } finally { temp.delete() }
        return key
    }
}
