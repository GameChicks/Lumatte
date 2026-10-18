package com.lumixfilmmirror.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.lumixfilmmirror.model.JpegOutput
import com.lumixfilmmirror.model.PhotoRecipe
import com.lumixfilmmirror.model.PhotoProcessingStage
import com.lumixfilmmirror.model.BatchExportOptions
import com.lumixfilmmirror.model.FrameLayouts
import com.lumixfilmmirror.model.FrameOptions
import com.lumixfilmmirror.model.PixelSize
import com.lumixfilmmirror.model.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ProcessingCatalogue(val luts: List<LutAsset>, val films: List<FilmPreset>, val problems: List<String>)
enum class OfficialLutDownloadStage { READING_DETAIL, DOWNLOADING, IMPORTING }

/** Processing entry point for a future frontend. No Compose, screen or animation dependency. */
class PhotoProcessor(context: Context) {
    private val applicationContext = context.applicationContext
    private val luts = LutLibrary(context)
    private val films = FilmCatalogue(context)
    private val lutGallery = LutGalleryLibrary(context)
    private val lutRecommender = LutRecommender(context)
    /** Metadata/curves for offline modelling; drafts are not silently added to the render catalogue. */
    val filmProfileDrafts = FilmProfileDraftLibrary(context)
    /** Explicit Internet requests only. Local catalogue/preview/export do not contact Lumix Lab. */
    val officialLutCatalogue by lazy { LumixLabCatalogueClient() }
    val officialLutExplorer by lazy { OfficialLutExplorer(officialLutCatalogue) }
    private val officialLutRecommender by lazy { OfficialLutRecommender(officialLutCatalogue) }
    private val mobileFilmModels = MobileFilmModelLibrary(context)
    private val previewGate = Semaphore(1)
    private val previewCache = EffectPreviewCache()
    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var trimJob: Job? = null
    val writer = JpegOutputWriter(context, mobileFilmModels)

    suspend fun loadCatalogue(): ProcessingCatalogue {
        val library = luts.load()
        return ProcessingCatalogue(library.assets, films.load(), library.problems)
    }

    suspend fun importLut(uri: Uri): LutAsset = luts.import(uri)
    /** Frontend-independent suggestions. Uses at most a 256 px JPEG and bounded colour samples. */
    suspend fun recommendLuts(source: SourceImage, assets: List<LutAsset>,
        options: LutRecommendationOptions = LutRecommendationOptions()): LutRecommendationResult {
        require(assets.size <= 256) { "请分组推荐，每次最多 256 个 LUT" }
        val snapshot = assets.toList()
        val settings = options.copy(styles = options.styles.toSet(), inputOverrides = options.inputOverrides.toMap())
        return withContext(Dispatchers.Default) { previewGate.withPermit {
            val scale = minOf(1.0, 256.0 / maxOf(source.size.width, source.size.height))
            val size = PixelSize(maxOf(1, (source.size.width * scale).toInt()), maxOf(1, (source.size.height * scale).toInt()))
            val current = coroutineContext
            val pixels = writer.previewPixels(source, size, null, null) { current.ensureActive() }
            try { lutRecommender.recommend(pixels, snapshot, settings) { current.ensureActive() } }
            finally { pixels.recycle() }
        } }
    }
    /** Explicit download action; returns a normal offline asset usable in either LUT slot. */
    fun officialLutLanguage(): String = LumixLabLanguages.forContext(applicationContext)

    /** Internet discovery uses creator thumbnails only; the user's photo stays on this device. */
    suspend fun discoverOfficialLuts(source: SourceImage, assets: List<LutAsset>,
        styles: Set<LutRecommendationStyle> = LutRecommendationStyle.entries.toSet(),
        refresh: Boolean = false): OfficialLutSuggestions {
        val excluded = assets.mapNotNull { it.lumixLabSource?.code }.toSet()
        val selectedStyles = styles.toSet()
        val photo = withContext(Dispatchers.Default) { previewGate.withPermit {
            val scale = minOf(1.0, 128.0 / maxOf(source.size.width, source.size.height))
            val size = PixelSize(maxOf(1, (source.size.width * scale).toInt()), maxOf(1, (source.size.height * scale).toInt()))
            val current = coroutineContext
            val pixels = writer.previewPixels(source, size, null, null) { current.ensureActive() }
            try { LutGalleryColours.analyse(pixels, LutColourOrigin.PHOTO_PREVIEW, source.id.value) }
            finally { pixels.recycle() }
        } }
        return officialLutRecommender.recommend(photo, officialLutLanguage(), selectedStyles, excluded, refresh)
    }

    suspend fun downloadOfficialLut(code: String, language: String = officialLutLanguage(),
        onStage: suspend (OfficialLutDownloadStage) -> Unit = {}): LutAsset {
        onStage(OfficialLutDownloadStage.READING_DETAIL)
        val detail = officialLutCatalogue.detail(code, language)
        onStage(OfficialLutDownloadStage.DOWNLOADING)
        val cube = officialLutCatalogue.downloadCube(detail)
        onStage(OfficialLutDownloadStage.IMPORTING)
        return luts.importOfficial(detail, cube)
    }
    /** Display metadata only; the caller keeps the existing processing catalogue for rendering. */
    suspend fun loadLutGallery(catalogue: ProcessingCatalogue): LutGallerySnapshot = lutGallery.load(catalogue.luts)
    suspend fun setLutSubjects(asset: LutAsset, subjects: Set<LutSubject>): LutGalleryEntry = lutGallery.setSubjects(asset, subjects)
    suspend fun importFrameImage(uri: Uri): String = writer.frameAssets.importImage(uri)
    suspend fun importFrameFont(uri: Uri): String = writer.frameAssets.importFont(uri)
    fun exportLayout(source: SourceImage, options: BatchExportOptions) =
        FrameLayouts.plan(options.jpeg.contentSize(source.size), options.frame)
    fun lutBackend(): LutBackend = NativeLutRenderer.backend()
    fun filmBackend(): FilmBackend = FilmBackend.MOBILE_GLES3_WITH_CPU_FALLBACK
    fun filmSpatialEffectsInfo(): FilmSpatialEffectsInfo = FilmSpatialEffectsInfo()
    /** Packaged model data and its cache only; this does not switch or initialize the film backend. */
    suspend fun mobileFilmModelsInfo(): MobileFilmModelsInfo = mobileFilmModels.info()
    suspend fun previewCacheInfo(): PreviewCacheInfo = previewGate.withPermit { previewCache.info() }
    suspend fun clearPreviewCache() = previewGate.withPermit { previewCache.clear() }
    /** Releases discardable preview/GPU caches after current work, leaving recipes and output intact. */
    suspend fun clearProcessingCaches() = previewGate.withPermit {
        previewCache.clear()
        writer.releaseIdleFilmEngine()
        mobileFilmModels.clear()
    }
    /** Coalesced, nonblocking application memory callback. Never clears a borrowed/in-flight image. */
    @Synchronized fun requestMemoryTrim() {
        if (trimJob?.isActive == true) return
        trimJob = trimScope.launch { clearProcessingCaches() }
    }

    fun lutPlan(recipe: com.lumixfilmmirror.model.LutRecipe, assets: List<LutAsset>) = luts.plan(recipe, assets)
    fun filmPlan(recipe: PhotoRecipe.Film, presets: List<FilmPreset>, spatialOptions: FilmSpatialOptions = FilmSpatialOptions()) =
        films.plan(recipe.parameters, presets, recipe.grainSeed, spatialOptions)

    /** Current-photo card, full single-LUT strength and no grain/frame. Never a creator's example. */
    suspend fun previewLutCard(source: SourceImage, asset: LutAsset, longEdge: Int = 320): LutCardPreview {
        require(longEdge in 64..512) { "LUT 卡片长边需在 64～512 像素之间" }
        var produced: Bitmap? = null
        try {
            return withContext(Dispatchers.Default) {
                previewGate.withPermit {
                    val recipe = PhotoRecipe.Lut(com.lumixfilmmirror.model.LutRecipe(
                        first = com.lumixfilmmirror.model.LutSlot(asset.id, 1f)))
                    val catalogue = ProcessingCatalogue(listOf(asset), emptyList(), emptyList())
                    val layout = FrameLayouts.preview(exportLayout(source, BatchExportOptions()), longEdge)
                    val pixels = effectPreview(source, recipe, catalogue, photoSize(layout)).also { produced = it }
                    val colours = LutGalleryColours.analyse(pixels, LutColourOrigin.PHOTO_PREVIEW, source.id.value)
                    coroutineContext.ensureActive()
                    LutCardPreview(asset.id, pixels, colours)
                }
            }
        } catch (error: Throwable) { produced?.recycle(); throw error }
    }

    /** Whole local-catalogue colour metadata, one small bitmap at a time. No image is returned/retained. */
    suspend fun lutGalleryColours(source: SourceImage, catalogue: ProcessingCatalogue, longEdge: Int = 192,
        onEntry: suspend (LutGalleryColourEntry) -> Unit = {}): LutGalleryColourSnapshot {
        require(longEdge in 64..512)
        val entries = mutableListOf<LutGalleryColourEntry>()
        val problems = mutableListOf<String>()
        for (asset in catalogue.luts) {
            coroutineContext.ensureActive()
            val entry = try {
                val preview = previewLutCard(source, asset, longEdge)
                try { LutGalleryColourEntry(preview.lutId, preview.colours) }
                finally { preview.image.recycle() }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { problems += "${asset.name}：例图取色失败"; null }
            if (entry != null) {
                entries += entry
                onEntry(entry) // Consumer failure propagates; it is not a failed LUT calculation.
            }
        }
        coroutineContext.ensureActive()
        return LutGalleryColourSnapshot(entries, problems)
    }

    suspend fun preview(source: SourceImage, recipe: PhotoRecipe, catalogue: ProcessingCatalogue,
        longEdge: Int = 960, filmOutputSize: PixelSize? = null) = ownedPreview {
        require(longEdge in 64..2048) { "预览长边需在 64～2048 像素之间" }
        previewGate.withPermit {
            val layout = FrameLayouts.preview(exportLayout(source, BatchExportOptions()), longEdge)
            effectPreview(source, recipe, catalogue, photoSize(layout), filmOutputSize)
        }
    }

    suspend fun export(source: SourceImage, recipe: PhotoRecipe, catalogue: ProcessingCatalogue,
        output: JpegOutput, destination: ExportDestination,
        frame: FrameOptions = FrameOptions(), frameNumber: Int = 1,
        onStage: suspend (PhotoProcessingStage) -> Unit = {}, onTargetCreated: suspend (Uri) -> Unit = {},
        onPublished: suspend (Uri) -> Unit = {}): Uri =
        writer.write(source, output, destination,
            lut = (recipe as? PhotoRecipe.Lut)?.let { luts.plan(it.parameters, catalogue.luts) },
            film = (recipe as? PhotoRecipe.Film)?.let { films.plan(it.parameters, catalogue.films, it.grainSeed) },
            frame = frame, frameNumber = frameNumber,
            onStage = onStage, onTargetCreated = onTargetCreated, onPublished = onPublished,
            preserveEmbeddedJpeg = recipe == PhotoRecipe.Original && output.dimensionPercent == 100 &&
                output.compressionQuality == 100 && frame.preset == com.lumixfilmmirror.model.FramePreset.NONE)

    /** Complete composition preview. longEdge bounds the final canvas, including frame and text. */
    suspend fun previewExport(source: SourceImage, recipe: PhotoRecipe, catalogue: ProcessingCatalogue,
        options: BatchExportOptions, longEdge: Int = 960, frameNumber: Int = 1,
        matchFilmOutput: Boolean = false) = ownedPreview {
        require(longEdge in 64..2048) { "预览长边需在 64～2048 像素之间" }
        previewGate.withPermit {
            val active = coroutineContext
            require(frameNumber > 0)
            val layout = FrameLayouts.preview(exportLayout(source, options), longEdge)
            val pixels = effectPreview(source, recipe, catalogue, photoSize(layout),
                if(matchFilmOutput)options.jpeg.contentSize(source.size) else null)
            try {
                val stock = (recipe as? PhotoRecipe.Film)?.let { films.plan(it.parameters, catalogue.films, it.grainSeed).preset }
                val result = writer.framePreview(pixels, source, options.frame, layout, stock, frameNumber) { active.ensureActive() }
                if (result !== pixels) pixels.recycle()
                result
            } catch (error: Throwable) { pixels.recycle(); throw error }
        }
    }

    private fun photoSize(layout: FrameLayout) = PixelSize(maxOf(1, kotlin.math.round(layout.photo.width).toInt()),
        maxOf(1, kotlin.math.round(layout.photo.height).toInt()))

    private suspend fun effectPreview(source: SourceImage, recipe: PhotoRecipe, catalogue: ProcessingCatalogue, size: PixelSize,
        filmOutputSize: PixelSize? = null): Bitmap {
        val lut = (recipe as? PhotoRecipe.Lut)?.parameters
        fun table(id: String?) = id?.let { value ->
            requireNotNull(catalogue.luts.firstOrNull { it.id == value }) { "所选 LUT 不可用，请重新选择" }.table
        }
        val film = (recipe as? PhotoRecipe.Film)?.let { films.plan(it.parameters, catalogue.films, it.grainSeed) }
        val renderSize = filmOutputSize?.takeIf { film != null }
        val key = EffectPreviewKey(source.id.value, source.jpeg.absolutePath, source.jpeg.length(), source.jpeg.lastModified(),
            source.size, source.orientation, size, recipe, table(lut?.first?.id), table(lut?.second?.id), film?.preset, renderSize)
        coroutineContext.ensureActive()
        previewCache.copy(key)?.let { return it }
        val active = coroutineContext
        val pixels = writer.previewPixels(source, size, lut?.let { luts.plan(it, catalogue.luts) }, film,
            filmOutputSize = renderSize) { active.ensureActive() }
        try {
            active.ensureActive()
            previewCache.remember(key, pixels)
            return pixels
        } catch (error: Throwable) { pixels.recycle(); throw error }
    }

    /** A cancelled dispatcher hand-off must also release a completed result the caller never received. */
    private suspend fun ownedPreview(block: suspend () -> Bitmap): Bitmap {
        var produced: Bitmap? = null
        try {
            return withContext(Dispatchers.Default) { block().also { produced = it } }
        } catch (error: Throwable) { produced?.recycle(); throw error }
    }

    suspend fun export(source: SourceImage, recipe: PhotoRecipe, catalogue: ProcessingCatalogue,
        options: BatchExportOptions, destination: ExportDestination, frameNumber: Int = 1,
        onStage: suspend (PhotoProcessingStage) -> Unit = {}, onTargetCreated: suspend (Uri) -> Unit = {},
        onPublished: suspend (Uri) -> Unit = {}): Uri =
        export(source, recipe, catalogue, options.jpeg, destination, options.frame, frameNumber,
            onStage = onStage, onTargetCreated = onTargetCreated, onPublished = onPublished)
}
