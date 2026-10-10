package com.lumixfilmmirror.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.lumixfilmmirror.model.InputOrigin
import com.lumixfilmmirror.model.PhotoId
import com.lumixfilmmirror.model.PixelSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class PhotoMetadata(
    val camera: String = "", val lens: String = "", val capturedAt: String = "",
    val iso: String = "", val aperture: String = "", val shutter: String = "", val focalLength: String = "",
) {
    val detail: String get() = listOf(lens, focalLength, aperture, shutter, iso).filter { it.isNotBlank() }.joinToString("  ·  ")
}

data class SourceImage(
    val id: PhotoId, val name: String, val jpeg: File, val size: PixelSize,
    val orientation: Int, val origin: InputOrigin, val metadata: PhotoMetadata,
) {
    val rawNotice: String? get() = if (origin is InputOrigin.RawEmbeddedJpeg)
        "使用 RAW 内嵌 JPEG · ${size.width} × ${size.height} · 输出尺寸以预览图为准" else null
}

class ImageImporter(context: Context) {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "source-jpegs").apply { mkdirs() }

    suspend fun importImage(uri: Uri, displayName: String? = null,
        rawEmbeddedPreview: Boolean = false): SourceImage = withContext(Dispatchers.IO) {
        val id = PhotoId(UUID.randomUUID().toString())
        val directory = File(root, id.value).apply { check(mkdirs()) }
        val staged = File(directory, "input")
        try {
            val name = displayName ?: context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "照片"
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().buffered().use { output ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        output.write(bytes, 0, count)
                    }
                }
            } ?: error("无法读取所选文件")
            val isJpeg = staged.inputStream().use { it.read() == 0xff && it.read() == 0xd8 }
            val jpeg = File(directory, "source.jpg")
            if (isJpeg) {
                check(staged.renameTo(jpeg)) { "无法保存导入照片" }
            } else {
                val candidates = RawJpegExtractor.candidates(staged)
                var bestPixels = 0L
                for ((index, range) in candidates.withIndex()) {
                    coroutineContext.ensureActive()
                    val candidate = File(directory, "preview-$index.jpg")
                    RawJpegExtractor.extract(staged, range, candidate)
                    val size = jpegSize(candidate)
                    if (size != null && size.width.toLong() * size.height > bestPixels) {
                        if (jpeg.exists()) check(jpeg.delete())
                        check(candidate.renameTo(jpeg))
                        bestPixels = size.width.toLong() * size.height
                    } else check(candidate.delete())
                }
                check(bestPixels > 0) { "未找到可用的内嵌 JPEG，请导入相机 JPEG 成片" }
            }
            val encoded = jpegSize(jpeg) ?: error("JPEG 数据无法读取")
            val embeddedExif = runCatching { ExifInterface(jpeg) }.getOrNull()
            val originalExif = if (isJpeg) embeddedExif else runCatching { ExifInterface(staged) }.getOrNull()
            val orientation = embeddedExif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)?.takeIf { it in 1..8 }
                ?: originalExif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1
            val rotated = orientation in setOf(5, 6, 7, 8)
            val size = if (rotated) PixelSize(encoded.height, encoded.width) else encoded
            val metadata = metadata(originalExif ?: embeddedExif)
            if (staged.exists()) check(staged.delete())
            SourceImage(id, name, jpeg, size, orientation,
                if (isJpeg && !rawEmbeddedPreview) InputOrigin.Jpeg else InputOrigin.RawEmbeddedJpeg(size), metadata)
        } catch (error: Throwable) {
            // Only this import's private copy is removed. The source provider and original stay read-only.
            directory.deleteRecursively()
            throw error
        }
    }

    private fun jpegSize(file: File): PixelSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0 && options.outMimeType == "image/jpeg")
            PixelSize(options.outWidth, options.outHeight) else null
    }

    private fun metadata(exif: ExifInterface?): PhotoMetadata {
        fun get(tag: String) = exif?.getAttribute(tag).orEmpty()
        val exposure = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0) ?: 0.0
        val aperture = exif?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0) ?: 0.0
        val focal = exif?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0) ?: 0.0
        return PhotoMetadata(
            camera = listOf(get(ExifInterface.TAG_MAKE), get(ExifInterface.TAG_MODEL)).filter { it.isNotBlank() }.joinToString(" "),
            lens = get(ExifInterface.TAG_LENS_MODEL), capturedAt = get(ExifInterface.TAG_DATETIME_ORIGINAL),
            iso = get(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY).let { if (it.isBlank()) "" else "ISO $it" },
            aperture = if (aperture > 0) "f/%.1f".format(java.util.Locale.ROOT, aperture) else "",
            shutter = when { exposure <= 0 -> ""; exposure < 1 -> "1/${kotlin.math.round(1 / exposure).toInt()} s"; else -> "%.1f s".format(java.util.Locale.ROOT, exposure) },
            focalLength = if (focal > 0) "%.0f mm".format(java.util.Locale.ROOT, focal) else "",
        )
    }
}
