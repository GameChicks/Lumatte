package com.lumixfilmmirror.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.lumixfilmmirror.model.JpegOutput
import com.lumixfilmmirror.model.PixelSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

sealed interface ExportDestination {
    data object Gallery : ExportDestination
    data class Folder(val treeUri: Uri) : ExportDestination
}

/** Shared by local import and future camera/engine output. Default dimensions are never capped. */
class JpegOutputWriter(context: Context) {
    private val context = context.applicationContext
    private val cache = JpegExportCache(this.context)

    suspend fun inspectCache(): JpegExportCacheSnapshot = cache.inspect()
    suspend fun cleanCache(): JpegExportCleanup = cache.clean()

    suspend fun write(source: SourceImage, settings: JpegOutput, destination: ExportDestination,
        onPublished: suspend (Uri) -> Unit = {}): Uri = withContext(Dispatchers.IO) {
        JpegExportAccess.mutex.withLock {
            val size = settings.contentSize(source.size)
            val temp = cache.newTemporaryFile()
            try {
                val pixels = decode(source, size)
                try {
                    temp.outputStream().buffered().use { output ->
                        check(pixels.compress(Bitmap.CompressFormat.JPEG, settings.compressionQuality, output)) { "无法写入 JPG" }
                    }
                } finally { pixels.recycle() }
                coroutineContext.ensureActive()
                writeExif(source, temp, size)
                FileOutputStream(temp, true).use { it.fd.sync() }
                coroutineContext.ensureActive()
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                val name = "LFM-$stamp-${UUID.randomUUID()}.jpg"
                when (destination) {
                    ExportDestination.Gallery -> publishGallery(temp, name, onPublished)
                    is ExportDestination.Folder -> publishFolder(temp, name, destination.treeUri, onPublished)
                }
            } finally { temp.delete() }
        }
    }

    fun preview(source: SourceImage, longEdge: Int = 1280): Bitmap {
        val factor = minOf(1.0, longEdge.toDouble() / maxOf(source.size.width, source.size.height))
        return decode(source, PixelSize(maxOf(1, (source.size.width * factor).toInt()),
            maxOf(1, (source.size.height * factor).toInt())))
    }

    private fun decode(source: SourceImage, size: PixelSize): Bitmap {
        val ratio = minOf(source.size.width.toFloat() / size.width, source.size.height.toFloat() / size.height)
        var sample = 1
        while (sample * 2 <= ratio) sample *= 2
        val input = BitmapFactory.decodeFile(source.jpeg.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }) ?: error("无法解码内嵌 JPEG")
        try {
            val output = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                val width = size.width.toFloat(); val height = size.height.toFloat()
                val destination = when (source.orientation) {
                    2 -> floatArrayOf(width, 0f, 0f, 0f, width, height)
                    3 -> floatArrayOf(width, height, 0f, height, width, 0f)
                    4 -> floatArrayOf(0f, height, width, height, 0f, 0f)
                    5 -> floatArrayOf(0f, 0f, 0f, height, width, 0f)
                    6 -> floatArrayOf(width, 0f, width, height, 0f, 0f)
                    7 -> floatArrayOf(width, height, width, 0f, 0f, height)
                    8 -> floatArrayOf(0f, height, 0f, 0f, width, height)
                    else -> floatArrayOf(0f, 0f, width, 0f, 0f, height)
                }
                val transform = Matrix().apply { setPolyToPoly(
                    floatArrayOf(0f, 0f, input.width.toFloat(), 0f, 0f, input.height.toFloat()), 0,
                    destination, 0, 3,
                ) }
                Canvas(output).drawBitmap(input, transform, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                return output
            } catch (error: Throwable) { output.recycle(); throw error }
        } finally { input.recycle() }
    }

    private fun writeExif(source: SourceImage, file: File, size: PixelSize) {
        val input = ExifInterface(source.jpeg)
        val output = ExifInterface(file)
        val tags = listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH)
        tags.forEach { tag -> input.getAttribute(tag)?.let { output.setAttribute(tag, it) } }
        output.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        output.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
        output.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
        output.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
        output.setAttribute(ExifInterface.TAG_SOFTWARE, "Lumix Film Mirror")
        output.saveAttributes()
    }

    private suspend fun copy(file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(64 * 1024)
                var copied = 0L
                val expected = file.length()
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(bytes)
                    if (count < 0) break
                    output.write(bytes, 0, count)
                    copied += count
                }
                check(copied == expected && expected > 0) { "JPG 副本未完整写入" }
                output.flush()
            }
        } ?: error("无法打开导出目标")
    }

    private suspend fun copyGallery(file: File, uri: Uri) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rwt") ?: error("无法打开相册 JPG")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                val expected = file.length()
                var copied = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                }
                check(copied == expected && expected > 0) { "相册 JPG 尚未完整" }
                output.fd.sync()
            }
        }
        val stored = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        check(stored == file.length()) { "相册 JPG 尺寸不一致" }
    }

    private suspend fun publishGallery(file: File, name: String, onPublished: suspend (Uri) -> Unit): Uri {
        val resolver = context.contentResolver
        val details = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, cache.galleryPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(cache.collection, details) ?: error("无法创建相册文件")
        try {
            copyGallery(file, uri)
            coroutineContext.ensureActive()
            // Publication and the caller's success record survive cancellation at the IO return boundary.
            withContext(NonCancellable) {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) {
                    "无法完成相册 JPG 发布"
                }
                onPublished(uri)
            }
            return uri
        } catch (error: Throwable) {
            runCatching { cache.discardPending(uri, name) }.exceptionOrNull()?.let { error.addSuppressed(it) }
            throw error
        }
    }

    private suspend fun publishFolder(file: File, name: String, tree: Uri, onPublished: suspend (Uri) -> Unit): Uri {
        val folder = DocumentFile.fromTreeUri(context, tree) ?: error("导出文件夹不可用")
        val target = folder.createFile("image/jpeg", name) ?: error("无法创建导出文件")
        var committed = false
        try {
            copy(file, target.uri)
            coroutineContext.ensureActive()
            withContext(NonCancellable) { committed = true; onPublished(target.uri) }
            return target.uri
        } catch (error: Throwable) {
            if (!committed) runCatching { check(target.delete()) { "无法移除未完成的文件夹导出" } }
                .exceptionOrNull()?.let { error.addSuppressed(it) }
            throw error
        }
    }
}
