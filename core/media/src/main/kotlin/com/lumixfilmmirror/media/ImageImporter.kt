package com.lumixfilmmirror.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.lumixfilmmirror.media.frame.FrameCameraNames
import com.lumixfilmmirror.model.InputOrigin
import com.lumixfilmmirror.model.PhotoId
import com.lumixfilmmirror.model.PixelSize
import com.lumixfilmmirror.model.FrameOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class PhotoMetadata(
    val camera: String = "", val lens: String = "", val capturedAt: String = "",
    val iso: String = "", val aperture: String = "", val shutter: String = "", val focalLength: String = "",
) {
    val frameCameraName: String get() = FrameCameraNames.format(camera)
    val detail: String get() = listOf(lens, focalLength, aperture, shutter, iso).filter { it.isNotBlank() }.joinToString("  ·  ")
    fun frameDetail(options: FrameOptions): String = listOf(
        lens.takeIf { options.showLens }, focalLength.takeIf { options.showFocalLength },
        aperture.takeIf { options.showAperture }, shutter.takeIf { options.showShutter },
        iso.takeIf { options.showIso }, capturedAt.takeIf { options.showCapturedAt }
    ).filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  ")
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
        rawEmbeddedPreview: Boolean = false, photoId: PhotoId? = null): SourceImage = withContext(Dispatchers.IO) {
        val id = photoId ?: PhotoId(UUID.randomUUID().toString())
        require(UUID.fromString(id.value).toString() == id.value)
        val directory = File(root, id.value)
        // Stable IDs are reserved by transfer tasks; never delete a finished import on retry.
        SourceManifest.read(directory, id)?.let { return@withContext it }
        if (directory.exists()) check(directory.deleteRecursively()) { "无法整理未完成的导入副本" }
        check(directory.mkdirs())
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
                val bestPixels = extractRawPreview(staged, directory, jpeg)
                check(bestPixels > 0) { "未找到可用的内嵌 JPEG，请导入相机 JPEG 成片" }
            }
            val encoded = jpegSize(jpeg) ?: error("JPEG 数据无法读取")
            val embeddedExif = runCatching { ExifInterface(jpeg) }.getOrNull()
            val originalExif = if (isJpeg) embeddedExif else runCatching { ExifInterface(staged) }.getOrNull()
            val orientation = embeddedExif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)?.takeIf { it in 1..8 }
                ?: originalExif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)?.takeIf { it in 1..8 } ?: 1
            val rotated = orientation in setOf(5, 6, 7, 8)
            val size = if (rotated) PixelSize(encoded.height, encoded.width) else encoded
            val metadata = metadata(originalExif ?: embeddedExif)
            if (staged.exists()) check(staged.delete())
            val photo = SourceImage(id, name, jpeg, size, orientation,
                if (isJpeg && !rawEmbeddedPreview) InputOrigin.Jpeg else InputOrigin.RawEmbeddedJpeg(size), metadata)
            SourceManifest.write(photo)
            photo
        } catch (error: Throwable) {
            // Only this import's private copy is removed. The source provider and original stay read-only.
            directory.deleteRecursively()
            throw error
        }
    }

    private suspend fun extractRawPreview(staged: File, directory: File, jpeg: File): Long {
        val operationContext = coroutineContext
        // A truncated/unknown TIFF layout must not prevent the independent native preview path.
        val ranges = try { RawJpegExtractor.candidates(staged) } catch (error: IOException) { emptyList() }
        var bestPixels = 0L
        for ((index, range) in ranges.withIndex()) {
            operationContext.ensureActive()
            val candidate = File(directory, "preview-$index.jpg")
            try { RawJpegExtractor.extract(staged, range, candidate) { operationContext.ensureActive() } }
            catch (error: IOException) {
                if (candidate.exists()) check(candidate.delete()) { "无法清理损坏的预览副本" }
                continue
            }
            bestPixels = selectPreview(candidate, jpeg, bestPixels)
        }
        if (bestPixels > 0) return bestPixels
        for (candidate in LibRawPreview.extract(staged, directory)) {
            operationContext.ensureActive()
            bestPixels = selectPreview(candidate, jpeg, bestPixels)
        }
        return bestPixels
    }

    private fun selectPreview(candidate: File, jpeg: File, bestPixels: Long): Long {
        val size = jpegSize(candidate)
        val pixels = size?.let { it.width.toLong() * it.height } ?: 0L
        if (pixels > bestPixels) {
            if (jpeg.exists()) check(jpeg.delete()) { "无法替换预览副本" }
            check(candidate.renameTo(jpeg)) { "无法保存内嵌 JPEG" }
            return pixels
        }
        check(candidate.delete()) { "无法清理多余预览副本" }
        return bestPixels
    }

    suspend fun recoverImport(id: PhotoId): SourceImage? = withContext(Dispatchers.IO) {
        require(UUID.fromString(id.value).toString() == id.value)
        SourceManifest.read(File(root, id.value), id)
    }

    suspend fun recoverImports(): ImportRecovery = withContext(Dispatchers.IO) {
        val photos = mutableListOf<SourceImage>()
        val problems = mutableListOf<String>()
        root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { File(it, "source.json").lastModified() }.forEach { directory ->
            coroutineContext.ensureActive()
            if (runCatching { UUID.fromString(directory.name).toString() == directory.name }.getOrDefault(false)) {
                try { SourceManifest.read(directory, PhotoId(directory.name))?.let(photos::add) }
                catch (error: Exception) { problems += "${directory.name}：${error.message ?: "导入记录无法恢复"}" }
            }
        }
        ImportRecovery(photos, problems)
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
