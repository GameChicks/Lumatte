package com.lumixfilmmirror.media

import android.content.Context
import android.net.Uri
import com.lumixfilmmirror.model.JpegOutput
import com.lumixfilmmirror.model.PhotoRecipe
import com.lumixfilmmirror.model.PhotoProcessingStage
import com.lumixfilmmirror.model.BatchExportOptions
import com.lumixfilmmirror.model.FrameLayouts
import com.lumixfilmmirror.model.FrameOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ProcessingCatalogue(val luts: List<LutAsset>, val films: List<FilmPreset>, val problems: List<String>)

/** Processing entry point for a future frontend. No Compose, screen or animation dependency. */
class PhotoProcessor(context: Context) {
    private val luts = LutLibrary(context)
    private val films = FilmCatalogue(context)
    private val previewGate = Semaphore(1)
    val writer = JpegOutputWriter(context)

    suspend fun loadCatalogue(): ProcessingCatalogue {
        val library = luts.load()
        return ProcessingCatalogue(library.assets, films.load(), library.problems)
    }

    suspend fun importLut(uri: Uri): LutAsset = luts.import(uri)
    suspend fun importFrameImage(uri: Uri): String = writer.frameAssets.importImage(uri)
    suspend fun importFrameFont(uri: Uri): String = writer.frameAssets.importFont(uri)
    fun exportLayout(source: SourceImage, options: BatchExportOptions) =
        FrameLayouts.plan(options.jpeg.contentSize(source.size), options.frame)

    fun lutPlan(recipe: com.lumixfilmmirror.model.LutRecipe, assets: List<LutAsset>) = luts.plan(recipe, assets)
    fun filmPlan(recipe: PhotoRecipe.Film, presets: List<FilmPreset>) = films.plan(recipe.parameters, presets, recipe.grainSeed)

    suspend fun preview(source: SourceImage, recipe: PhotoRecipe, catalogue: ProcessingCatalogue,
        longEdge: Int = 960) = withContext(Dispatchers.Default) {
        require(longEdge in 64..2048) { "预览长边需在 64～2048 像素之间" }
        previewGate.withPermit {
            val active = coroutineContext
            writer.preview(source, longEdge,
                lut = (recipe as? PhotoRecipe.Lut)?.let { luts.plan(it.parameters, catalogue.luts) },
                film = (recipe as? PhotoRecipe.Film)?.let { films.plan(it.parameters, catalogue.films, it.grainSeed) }) {
                active.ensureActive()
            }
        }
    }

    suspend fun export(source: SourceImage, recipe: PhotoRecipe, catalogue: ProcessingCatalogue,
        output: JpegOutput, destination: ExportDestination,
        frame: FrameOptions = FrameOptions(), frameNumber: Int = 1,
        onStage: suspend (PhotoProcessingStage) -> Unit = {}, onPublished: suspend (Uri) -> Unit = {}): Uri =
        writer.write(source, output, destination,
            lut = (recipe as? PhotoRecipe.Lut)?.let { luts.plan(it.parameters, catalogue.luts) },
            film = (recipe as? PhotoRecipe.Film)?.let { films.plan(it.parameters, catalogue.films, it.grainSeed) },
            frame = frame, frameNumber = frameNumber,
            onStage = onStage, onPublished = onPublished)

    /** Complete composition preview. longEdge bounds the final canvas, including frame and text. */
    suspend fun previewExport(source: SourceImage, recipe: PhotoRecipe, catalogue: ProcessingCatalogue,
        options: BatchExportOptions, longEdge: Int = 960, frameNumber: Int = 1) = withContext(Dispatchers.Default) {
        require(longEdge in 64..2048) { "预览长边需在 64～2048 像素之间" }
        previewGate.withPermit {
            val active = coroutineContext
            writer.preview(source, longEdge,
                lut = (recipe as? PhotoRecipe.Lut)?.let { luts.plan(it.parameters, catalogue.luts) },
                film = (recipe as? PhotoRecipe.Film)?.let { films.plan(it.parameters, catalogue.films, it.grainSeed) },
                frame = options.frame, output = options.jpeg, frameNumber = frameNumber) { active.ensureActive() }
        }
    }

    suspend fun export(source: SourceImage, recipe: PhotoRecipe, catalogue: ProcessingCatalogue,
        options: BatchExportOptions, destination: ExportDestination, frameNumber: Int = 1,
        onStage: suspend (PhotoProcessingStage) -> Unit = {}, onPublished: suspend (Uri) -> Unit = {}): Uri =
        export(source, recipe, catalogue, options.jpeg, destination, options.frame, frameNumber, onStage, onPublished)
}
