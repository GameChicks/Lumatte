package com.lumixfilmmirror.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.lumixfilmmirror.model.JpegOutput
import com.lumixfilmmirror.model.PixelSize
import com.lumixfilmmirror.model.PhotoProcessingStage
import com.lumixfilmmirror.model.FrameOptions
import com.lumixfilmmirror.model.FramePreset
import com.lumixfilmmirror.model.FrameLayouts
import com.lumixfilmmirror.media.frame.FrameRenderer
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
class JpegOutputWriter internal constructor(context: Context, models: MobileFilmModelLibrary) {
    constructor(context: Context) : this(context, MobileFilmModelLibrary(context))
    private val context = context.applicationContext
    private val cache = JpegExportCache(this.context)
    private val folderJournal = SafExportJournal(this.context)
    private val filmEngine = FilmEngine(models)
    private val frames = FrameRenderer(this.context)
    val frameAssets get() = frames.assets
    internal suspend fun releaseIdleFilmEngine() = filmEngine.releaseIdleEngine()

    suspend fun inspectCache(): JpegExportCacheSnapshot = cache.inspect()
    suspend fun cleanCache(): JpegExportCleanup = cache.clean()
    suspend fun inspectFolderExports(): FolderExportSnapshot = withContext(Dispatchers.IO) {
        JpegExportAccess.mutex.withLock { folderJournal.inspect() }
    }
    suspend fun forgetFolderExport(id: String): FolderExportSnapshot = withContext(Dispatchers.IO) {
        JpegExportAccess.mutex.withLock { folderJournal.forget(id); folderJournal.inspect() }
    }

    suspend fun write(source: SourceImage, settings: JpegOutput, destination: ExportDestination,
        lut: LutRenderPlan? = null,
        film: FilmRenderPlan? = null,
        frame: FrameOptions = FrameOptions(),
        frameNumber: Int = 1,
        onStage: suspend (PhotoProcessingStage) -> Unit = {},
        onTargetCreated: suspend (Uri) -> Unit = {},
        onPublished: suspend (Uri) -> Unit = {}): Uri = withContext(Dispatchers.IO) {
        JpegExportAccess.mutex.withLock {
            require(lut == null || film == null) { "请分别选择胶片模拟或 LUT 处理" }
            require(frameNumber > 0)
            onStage(PhotoProcessingStage.PREPARING)
            val size = settings.contentSize(source.size)
            val layout = FrameLayouts.plan(size, frame)
            val temp = cache.newTemporaryFile()
            try {
                var pixels = decode(source, size)
                try {
                    onStage(PhotoProcessingStage.RENDERING)
                    film?.let { renderFilm(source, size, it, pixels) { fresh -> pixels = fresh } }
                    val renderContext = coroutineContext
                    lut?.render(pixels, source) { renderContext.ensureActive() }
                    if (frame.preset != FramePreset.NONE) {
                        onStage(PhotoProcessingStage.FRAMING)
                        val framed = frames.render(pixels, source, frame, layout, film?.preset, frameNumber) { renderContext.ensureActive() }
                        if (framed !== pixels) { pixels.recycle(); pixels = framed }
                    }
                    renderContext.ensureActive()
                    onStage(PhotoProcessingStage.ENCODING)
                    temp.outputStream().buffered().use { output ->
                        check(pixels.compress(Bitmap.CompressFormat.JPEG, settings.compressionQuality, output)) { "无法写入 JPG" }
                    }
                } finally { pixels.recycle() }
                coroutineContext.ensureActive()
                writeExif(source, temp, layout.canvas)
                FileOutputStream(temp, true).use { it.fd.sync() }
                coroutineContext.ensureActive()
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                val name = "LFM-$stamp-${UUID.randomUUID()}.jpg"
                onStage(PhotoProcessingStage.PUBLISHING)
                when (destination) {
                    ExportDestination.Gallery -> publishGallery(temp, name, onTargetCreated, onPublished)
                    is ExportDestination.Folder -> publishFolder(source, temp, name, destination.treeUri, onTargetCreated, onPublished)
                }
            } finally { temp.delete() }
        }
    }

    suspend fun preview(source: SourceImage, longEdge: Int = 1280, lut: LutRenderPlan? = null,
        film: FilmRenderPlan? = null, frame: FrameOptions = FrameOptions(), output: JpegOutput = JpegOutput(),
        frameNumber: Int = 1, checkActive: () -> Unit = {}): Bitmap {
        require(longEdge > 0)
        require(lut == null || film == null) { "请分别选择胶片模拟或 LUT 处理" }
        require(frameNumber > 0)
        val layout = FrameLayouts.preview(FrameLayouts.plan(output.contentSize(source.size), frame), longEdge)
        val pixels = previewPixels(source, PixelSize(maxOf(1, kotlin.math.round(layout.photo.width).toInt()),
            maxOf(1, kotlin.math.round(layout.photo.height).toInt())), lut, film, checkActive)
        try {
            checkActive()
            val result = frames.render(pixels, source, frame, layout, film?.preset, frameNumber, checkActive)
            if (result !== pixels) pixels.recycle()
            return result
        }
        catch (error: Throwable) { pixels.recycle(); throw error }
    }

    /** Shared treatment-only stage, allowing frame previews to reuse an unchanged effect image. */
    internal suspend fun previewPixels(source: SourceImage, size: PixelSize, lut: LutRenderPlan?,
        film: FilmRenderPlan?, checkActive: () -> Unit): Bitmap {
        require(lut == null || film == null) { "请分别选择胶片模拟或 LUT 处理" }
        checkActive()
        var pixels = decode(source, size)
        try {
            film?.let { renderFilm(source, size, it, pixels) { fresh -> pixels = fresh } }
            lut?.render(pixels, source, checkActive)
            checkActive()
            return pixels
        } catch (error: Throwable) { pixels.recycle(); throw error }
    }

    internal fun framePreview(pixels: Bitmap, source: SourceImage, options: FrameOptions,
        layout: com.lumixfilmmirror.model.FrameLayout, stock: FilmPreset?, frameNumber: Int, checkActive: () -> Unit) =
        frames.render(pixels, source, options, layout, stock, frameNumber, checkActive)

    /** GPU may have committed earlier tiles. CPU must start from the private immutable JPEG. */
    private suspend fun renderFilm(source: SourceImage, size: PixelSize, plan: FilmRenderPlan,
        initial: Bitmap, onReset: (Bitmap) -> Unit) {
        try { filmEngine.render(initial, plan, source.size) }
        catch (error: MobileFilmGpuFailure) {
            coroutineContext.ensureActive()
            filmEngine.disableGpu()
            initial.recycle()
            val fresh = decode(source, size)
            onReset(fresh) // Transfer cleanup ownership before the next cancellable operation.
            filmEngine.renderCpu(fresh, plan, source.size)
        }
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
            return BitmapOrientation.render(input, size, source.orientation)
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

    private suspend fun publishGallery(file: File, name: String, onTargetCreated: suspend (Uri) -> Unit,
        onPublished: suspend (Uri) -> Unit): Uri {
        val resolver = context.contentResolver
        val details = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, cache.galleryPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(cache.collection, details) ?: error("无法创建相册文件")
        try {
            withContext(NonCancellable) { onTargetCreated(uri) }
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

    private suspend fun publishFolder(source: SourceImage, file: File, name: String, tree: Uri,
        onTargetCreated: suspend (Uri) -> Unit,
        onPublished: suspend (Uri) -> Unit): Uri {
        val folder = DocumentFile.fromTreeUri(context, tree) ?: error("导出文件夹不可用")
        val prepared = folderJournal.prepare(source, name, tree, file.length())
        var target: DocumentFile? = null
        var targetRecorded = false
        var committed = false
        try {
            val created = folder.createFile("image/jpeg", name) ?: error("提供者未返回导出文件，请核对文件夹")
            target = created
            val record = folderJournal.created(prepared, created.uri)
            targetRecorded = true
            withContext(NonCancellable) { onTargetCreated(created.uri) }
            copy(file, created.uri)
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                // Preserve the closed, fully copied file even if recording the decision fails.
                committed = true
                try { folderJournal.committed(record) }
                catch (error: Exception) { throw java.io.IOException("JPG 已写入，但完成记录未保存，请在文件夹中核对", error) }
                onPublished(created.uri)
            }
            return created.uri
        } catch (error: Throwable) {
            val failedTarget = target
            if (!committed && targetRecorded && failedTarget != null) runCatching {
                check(failedTarget.delete()) { "无法移除未完成的文件夹导出，请核对文件夹记录" }
                folderJournal.forget(prepared.id)
            }
                .exceptionOrNull()?.let { error.addSuppressed(it) }
            throw error
        }
    }
}
