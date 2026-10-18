package com.lumixfilmmirror.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumixfilmmirror.media.ExportDestination
import com.lumixfilmmirror.media.ImageImporter
import com.lumixfilmmirror.media.JpegExportCacheSnapshot
import com.lumixfilmmirror.media.FolderExportSnapshot
import com.lumixfilmmirror.media.SourceImage
import com.lumixfilmmirror.media.LutAsset
import com.lumixfilmmirror.media.PhotoProcessor
import com.lumixfilmmirror.media.ProcessingCatalogue
import com.lumixfilmmirror.media.FilmPreset
import com.lumixfilmmirror.media.FilmRenderPlan
import com.lumixfilmmirror.media.LumixLabBrowser
import com.lumixfilmmirror.media.LumixLabLutQuery
import com.lumixfilmmirror.media.OfficialLutDownloadStage
import com.lumixfilmmirror.model.LutRecipe
import com.lumixfilmmirror.model.FilmBatch
import com.lumixfilmmirror.model.FilmBatchMode
import com.lumixfilmmirror.model.FilmParameters
import com.lumixfilmmirror.model.BatchPhoto
import com.lumixfilmmirror.model.PhotoTreatment
import com.lumixfilmmirror.model.PhotoRecipe
import com.lumixfilmmirror.model.PhotoProcessingStage
import com.lumixfilmmirror.model.JpegOutput
import com.lumixfilmmirror.model.FrameOptions
import com.lumixfilmmirror.model.BatchExportOptions
import com.lumixfilmmirror.model.PhotoId
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.camera.CameraWifiSecurity
import com.lumixfilmmirror.camera.BluetoothConnectionStep
import com.lumixfilmmirror.storage.WorkspaceStore
import com.lumixfilmmirror.storage.WorkspacePreferences
import com.lumixfilmmirror.storage.TransferTask
import com.lumixfilmmirror.storage.IncomingTransfer
import com.lumixfilmmirror.storage.ProcessingBatch
import com.lumixfilmmirror.storage.ExportRequestPhoto
import com.lumixfilmmirror.storage.ExportRun
import com.lumixfilmmirror.storage.ExportItemState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext

data class ExportedPhoto(val id: PhotoId, val name: String, val uri: Uri)
data class FailedPhoto(val id: PhotoId?, val name: String, val reason: String)
enum class OfficialLutDownloadStatus { RUNNING, SAVED, CANCELLED, FAILED }
data class OfficialLutDownloadState(val code: String, val status: OfficialLutDownloadStatus,
    val stage: OfficialLutDownloadStage?, val assetId: String? = null, val error: String? = null)

data class PhotoImportResult(val requestId: String, val batchId: String?, val imported: List<PhotoId>)

data class WorkspaceState(
    val photos: List<SourceImage> = emptyList(),
    val processingBatches: List<ProcessingBatch> = emptyList(),
    val captureDays: Map<String, Int> = emptyMap(),
    val processingBatchId: String? = null,
    val batchBusy: Boolean = false,
    val luts: List<LutAsset> = emptyList(),
    val lutRecipe: LutRecipe = LutRecipe(),
    val lutImporting: Boolean = false,
    val officialLutDownload: OfficialLutDownloadState? = null,
    val filmPresets: List<FilmPreset> = emptyList(),
    val treatment: PhotoTreatment = PhotoTreatment.LUT,
    val filmBatch: FilmBatch? = null,
    val selected: PhotoId? = null,
    val output: JpegOutput = JpegOutput(),
    val frame: FrameOptions = FrameOptions(),
    val destination: ExportDestination = ExportDestination.Gallery,
    val importing: Boolean = false,
    val photoImportResult: PhotoImportResult? = null,
    val exporting: Boolean = false,
    val processingStage: PhotoProcessingStage? = null,
    val processingPhotoId: PhotoId? = null,
    val exportCacheBusy: Boolean = false,
    val exportCache: JpegExportCacheSnapshot? = null,
    val folderExports: FolderExportSnapshot? = null,
    val cameraBusy: Boolean = false,
    val recovering: Boolean = true,
    val storageFailure: String? = null,
    val activeTransferBatch: String? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val exported: List<ExportedPhoto> = emptyList(),
    val exportRuns: List<ExportRun> = emptyList(),
    val exportRunId: String? = null,
    val failures: List<FailedPhoto> = emptyList(),
    val notice: String? = null,
) {
    val current: SourceImage? get() = photos.firstOrNull { it.id == selected }
    val processingBatch: ProcessingBatch? get() = processingBatches.firstOrNull { it.id == processingBatchId }
    val exportOptions: BatchExportOptions get() = BatchExportOptions(output, frame)
    val localBusy: Boolean get() = importing || lutImporting || exporting || exportCacheBusy || batchBusy || recovering || storageFailure != null
    val busy: Boolean get() = localBusy || cameraBusy
}

/** Answering the running scan belongs to that operation; other camera work remains exclusive. */
internal fun WorkspaceState.canSelectBluetoothCamera(camera: CameraWorkspaceState): Boolean =
    !localBusy && (!camera.busy || (camera.bluetoothStep == BluetoothConnectionStep.SCANNING && camera.activeTransferBatch == null))

private fun WorkspaceState.showBatch(batches: List<ProcessingBatch>, id: String?): WorkspaceState {
    val batch = batches.firstOrNull { it.id == id }
    val photos = batch?.photos.orEmpty()
    val next = copy(processingBatches = batches, processingBatchId = id, photos = photos,
        lutRecipe = batch?.lut ?: LutRecipe(),
        frame = batch?.frame ?: FrameOptions(),
        treatment = batch?.treatment ?: PhotoTreatment.LUT,
        filmBatch = batch?.let { syncFilmBatch(it.film, photos) },
        selected = selected?.takeIf { selected -> photos.any { it.id == selected } } ?: photos.firstOrNull()?.id)
    if (exporting || importing) return next
    val selectedRun = exportRuns.firstOrNull { it.id == exportRunId && it.batchId == id }
        ?: exportRuns.firstOrNull { it.batchId == id }
    if (selectedRun == null && exportRunId == null && id == processingBatchId) return next
    return next.showExportRun(selectedRun)
}

private fun WorkspaceState.showExportRun(run: ExportRun?): WorkspaceState {
    if (run == null) return copy(exportRunId = null, completed = 0, total = 0, exported = emptyList(),
        failures = emptyList(), processingStage = null, processingPhotoId = null)
    val active = run.items.firstOrNull { it.state == ExportItemState.RUNNING }
    return copy(exportRunId = run.id, completed = run.completed, total = run.items.size,
        processingStage = active?.stage, processingPhotoId = active?.photo?.id,
        exported = run.items.filter { it.state == ExportItemState.SAVED }.map { item ->
            ExportedPhoto(item.photo.id, item.photo.name, Uri.parse(requireNotNull(item.savedUri))) },
        failures = run.items.filter { it.state in setOf(ExportItemState.FAILED, ExportItemState.REVIEW) }.map { item ->
            FailedPhoto(item.photo.id, item.photo.name, if (item.state == ExportItemState.REVIEW)
                "保存结果需要核对 · ${item.error.orEmpty()}" else item.error ?: "导出失败") })
}

private fun syncFilmBatch(existing: FilmBatch?, photos: List<SourceImage>): FilmBatch? {
    if (photos.isEmpty()) return null
    val present = photos.map { it.id }.toSet()
    val entries = existing?.photos.orEmpty().filter { it.id in present }.toMutableList()
    val ids = entries.map { it.id }.toSet()
    photos.filter { it.id !in ids }.forEach { entries += BatchPhoto(it.id, FilmParameters(), it.id.value.hashCode().toLong()) }
    val reference=existing?.referenceId?.takeIf { it in present } ?: photos.first().id
    if(existing != null && existing.referenceId !in present && existing.mode==FilmBatchMode.FIRST_PHOTO) {
        val inherited=existing.effectiveParameters(existing.referenceId)
        val index=entries.indexOfFirst { it.id==reference }
        entries[index]=entries[index].copy(parameters=inherited)
    }
    return if (existing == null) FilmBatch(entries, reference) else existing.copy(photos = entries,referenceId=reference)
}

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    internal val displayPreviews = MirrorPreviewCache(viewModelScope)
    private val editingSessionBatches = (application as FilmMirrorApplication).editingSessionBatches
    val processor = (application as FilmMirrorApplication).processor
    val writer get() = processor.writer
    /** Frontend-facing browser. Construction has no network side effects. */
    val officialLuts = LumixLabBrowser(processor.officialLutCatalogue, viewModelScope)
    private var officialLutDownloadJob: Job? = null
    private val editDrafts = mutableMapOf<String, Pair<PhotoTreatment, FilmBatch?>>()
    private val editSaveJobs = mutableMapOf<String, Job>()
    private val lutDrafts = mutableMapOf<String, LutRecipe>()
    private val lutSaveJobs = mutableMapOf<String, Job>()
    private val frameDrafts = mutableMapOf<String, FrameOptions>()
    private val frameSaveJobs = mutableMapOf<String, Job>()
    private val importer = ImageImporter(application)
    private val store = WorkspaceStore(application)
    private val exports = (application as FilmMirrorApplication).exports
    val exportExecution = exports.state
    val exportQueue get() = exports.store
    private val camera = (application as FilmMirrorApplication).camera
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    private val tasksMutable = MutableStateFlow<List<TransferTask>>(emptyList())
    val transferTasks = tasksMutable.asStateFlow()
    private val incomingMutable = MutableStateFlow<List<IncomingTransfer>>(emptyList())
    val incomingTransfers = incomingMutable.asStateFlow()
    private var restoreJob: Job? = null
    private var observingStorage = false
    private val storageObservers = mutableListOf<Job>()
    private var exportJob: Job? = null
    private var exportCancelRequested = false
    private var creatingExport = false
    val cameraState = camera.cameraState

    private val personalStore = MirrorPreferences(application)
    private val personalMutable = MutableStateFlow(MirrorPersonalState())
    val personal = personalMutable.asStateFlow()
    private val personalSaveGate = kotlinx.coroutines.sync.Mutex()
    private var personalReady = false

    fun updatePersonal(change: (MirrorPersonalState) -> MirrorPersonalState) {
        if (!personalReady) return
        personalMutable.update(change)
        viewModelScope.launch {
            personalSaveGate.lock()
            try { personalStore.save(personalMutable.value) }
            catch (error: Exception) { notice(error.message ?: "个人设置未保存") }
            finally { personalSaveGate.unlock() }
        }
    }

    fun toggleFavourite(id: String) = updatePersonal { current ->
        current.copy(favourites = if (id in current.favourites) current.favourites - id else current.favourites + id)
    }

    fun forgetCamera(id: String) = updatePersonal { it.copy(cameras = it.cameras.filterNot { camera -> camera.id == id }) }

    init {
        viewModelScope.launch {
            try { personalMutable.value = personalStore.read(); personalReady = true }
            catch (error: Exception) { notice("个人设置读取失败，保留原记录：${error.message}"); return@launch }
            cameraState.collect { state ->
                state.device?.takeIf { state.connected }?.let { device ->
                    val remembered = RememberedCamera(device.rememberedIdentity(), device.model, device.friendlyName)
                    if (personalMutable.value.cameras.none { it.id == remembered.id })
                        updatePersonal { it.copy(cameras = it.cameras + remembered) }
                }
            }
        }
        viewModelScope.launch {
            exportExecution.collect { execution ->
                mutable.update { previous ->
                    val updated = if (execution.run != null &&
                        (execution.active || execution.run.batchId == previous.processingBatchId))
                        previous.showExportRun(execution.run) else previous
                    updated.copy(exporting = execution.active || creatingExport,
                        notice = execution.notice ?: previous.notice)
                }
            }
        }
        viewModelScope.launch {
            cameraState.collect { value ->
                val nextBatch = value.activeTransferBatch?.takeIf { it != mutable.value.activeTransferBatch }
                if (nextBatch != null) editingSessionBatches.add(nextBatch)
                mutable.update { previous ->
                    val updated = previous.copy(cameraBusy = value.busy, activeTransferBatch = value.activeTransferBatch)
                    if (nextBatch == null || previous.exporting || previous.importing || previous.lutImporting || previous.batchBusy) updated
                    else updated.showBatch(updated.processingBatches, nextBatch)
                }
                if (nextBatch != null && !mutable.value.recovering) persistPreferences()
            }
        }
        restoreWorkspace()
    }

    fun restoreWorkspace() {
        if (observingStorage || restoreJob?.isActive == true) return
        mutable.update { it.copy(recovering = true, storageFailure = null) }
        restoreJob = viewModelScope.launch {
            try {
                val problems = store.initialize(importer, recoverTransfers = !camera.isTransferring, recoverPhotos = false)
                val settings = store.readPreferences()
                exportQueue.initialize()
                val exportRuns = exportQueue.runs.first()
                val library = processor.loadCatalogue()
                val filmPresets = library.films
                val batches = availableBatches(store.processingBatches.first()).first
                val cleanup = writer.cleanCache()
                val folderExports = writer.inspectFolderExports()
                val cleanupProblems = (cleanup.problems + cleanup.remaining.problems).distinct()
                val cleanupNotice = if (cleanup.temporaryFilesRemoved + cleanup.pendingImagesRemoved > 0)
                    "已整理上次导出的 ${cleanup.temporaryFilesRemoved} 个中间文件、${cleanup.pendingImagesRemoved} 个未发布图片" else null
                mutable.update { previous ->
                    val batchId = exportExecution.value.run?.batchId?.takeIf { exportExecution.value.active }
                        ?: previous.activeTransferBatch
                        ?: settings.processingBatchId?.takeIf { id -> batches.any { it.id == id } }
                        ?: batches.firstOrNull { batch -> batch.photos.any { it.id.value == settings.selectedId } }?.id
                        ?: batches.firstOrNull { it.photos.isNotEmpty() }?.id ?: batches.firstOrNull()?.id
                    previous.copy(output = settings.output, selected = settings.selectedId?.let(::PhotoId), luts = library.luts, filmPresets = filmPresets,
                    exportRuns = exportRuns,
                    destination = settings.folderUri?.let { uri -> ExportDestination.Folder(Uri.parse(uri)) } ?: ExportDestination.Gallery,
                    exportCache = cleanup.remaining.copy(problems = cleanupProblems),
                    folderExports = folderExports,
                    notice = (problems + library.problems + listOfNotNull(cleanupNotice)).takeIf { it.isNotEmpty() }?.joinToString("\n"))
                        .showBatch(batches, batchId)
                }
                observingStorage = true
                observeStorage()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutable.update { it.copy(storageFailure = error.message ?: "工作台无法恢复，请重试") }
            } finally { mutable.update { it.copy(recovering = false) } }
        }
    }

    private fun observeStorage() {
        storageObservers.forEach { it.cancel() }
        storageObservers.clear()
        storageObservers += viewModelScope.launch {
            try { store.photos.collect { photos ->
                val days = photos.mapNotNull { photo ->
                    runCatching { java.time.LocalDate.parse(photo.metadata.capturedAt.take(10).replace(':', '-')).toString() }.getOrNull()
                }.groupingBy { it }.eachCount()
                mutable.update { if (it.captureDays == days) it else it.copy(captureDays = days) }
            } } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Editing remains available if historical usage cannot be read. */ }
        }
        storageObservers += viewModelScope.launch {
            try {
                store.processingBatches.collect { batches ->
                    val (stored, missing) = availableBatches(batches)
                    val available = stored.map {
                        val edit = editDrafts[it.id]
                        it.copy(lut = lutDrafts[it.id] ?: it.lut,
                            frame = frameDrafts[it.id] ?: it.frame,
                            treatment = edit?.first ?: it.treatment, film = edit?.second ?: it.film)
                    }
                    mutable.update { previous ->
                        val id = previous.processingBatchId ?: available.firstOrNull { it.photos.isNotEmpty() }?.id
                            ?: available.firstOrNull()?.id
                        previous.showBatch(available, id).copy(
                            notice = if (missing > 0) "$missing 张导入副本缺失，原有记录保留" else previous.notice)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { storageFailed(error.message ?: "无法读取照片记录") }
        }
        storageObservers += viewModelScope.launch {
            try {
                store.transfers.collect { tasks ->
                    tasksMutable.value = tasks
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { storageFailed(error.message ?: "无法读取传输记录") }
        }
        storageObservers += viewModelScope.launch {
            try { store.incomingTransfers.collect { incomingMutable.value = it } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { storageFailed(error.message ?: "无法读取接收确认记录") }
        }
        storageObservers += viewModelScope.launch {
            try { exportQueue.runs.collect { runs ->
                mutable.update { previous ->
                    val next = previous.copy(exportRuns = runs)
                    val run = runs.firstOrNull { it.id == previous.exportRunId && it.batchId == previous.processingBatchId }
                        ?: runs.firstOrNull { it.batchId == previous.processingBatchId }
                    if (previous.importing || (run == null && previous.exportRunId == null)) next
                    else next.showExportRun(run)
                }
            } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { storageFailed(error.message ?: "无法读取导出任务记录") }
        }
    }

    private fun storageFailed(message: String) {
        observingStorage = false
        mutable.update { it.copy(storageFailure = message) }
        storageObservers.forEach { it.cancel() }
        camera.stopTransfer(false, "记录存储中断，传输已暂停")
        exports.stop(reason = "记录存储中断，导出已暂停")
    }

    private suspend fun availableBatches(batches: List<ProcessingBatch>): Pair<List<ProcessingBatch>, Int> {
        val session = batches.filter { it.id in editingSessionBatches }
        val (available, missing) = store.available(session.flatMap { it.photos })
        val ids = available.map { it.id }.toSet()
        return session.map { it.copy(photos = it.photos.filter { photo -> photo.id in ids }) } to missing
    }

    private fun persistPreferences() {
        val snapshot = mutable.value
        viewModelScope.launch {
            try {
                store.savePreferences(WorkspacePreferences(snapshot.output,
                    (snapshot.destination as? ExportDestination.Folder)?.treeUri?.toString(), snapshot.selected?.value,
                    snapshot.processingBatchId))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(notice = error.message ?: "设置未能保存") } }
        }
    }

    fun notice(message: String) { mutable.update { it.copy(notice = message) } }
    fun clearNotice() { mutable.update { it.copy(notice = null) } }

    fun lutPlan(recipe: LutRecipe, assets: List<LutAsset>) = processor.lutPlan(recipe, assets)

    suspend fun previewPhoto(source: SourceImage, recipe: LutRecipe, assets: List<LutAsset>, longEdge: Int = 1280,
        film: FilmRenderPlan? = null) =
        processor.preview(source, film?.let { PhotoRecipe.Film(it.parameters, it.grainSeed) } ?: PhotoRecipe.Lut(recipe),
            ProcessingCatalogue(assets, listOfNotNull(film?.preset), emptyList()), longEdge)

    fun photoRecipe(snapshot: WorkspaceState, id: PhotoId, original: Boolean = false): PhotoRecipe {
        require(snapshot.photos.any { it.id == id }) { "照片不在当前批次中" }
        if (original || snapshot.treatment == PhotoTreatment.ORIGINAL) return PhotoRecipe.Original
        return if (snapshot.treatment == PhotoTreatment.LUT) PhotoRecipe.Lut(snapshot.lutRecipe)
        else {
            val batch = requireNotNull(snapshot.filmBatch) { "请先选择胶卷" }
            if (batch.mode == FilmBatchMode.FIRST_PHOTO) require(snapshot.photos.any { it.id == batch.referenceId }) {
                "原基准照片不可用，请明确指定新的基准照片"
            }
            PhotoRecipe.Film(batch.effectiveParameters(id), batch.photos.single { it.id == id }.grainSeed)
        }
    }

    suspend fun previewEditedPhoto(id: PhotoId, longEdge: Int = 960, original: Boolean = false): android.graphics.Bitmap {
        val snapshot = mutable.value
        val source = snapshot.photos.firstOrNull { it.id == id } ?: error("照片不在当前批次中")
        return processor.preview(source, photoRecipe(snapshot, id, original),
            ProcessingCatalogue(snapshot.luts, snapshot.filmPresets, emptyList()), longEdge)
    }

    suspend fun previewExportPhoto(id: PhotoId, longEdge: Int = 960): android.graphics.Bitmap {
        val snapshot = mutable.value
        val index = snapshot.photos.indexOfFirst { it.id == id }
        require(index >= 0) { "照片不在当前批次中" }
        return processor.previewExport(snapshot.photos[index], photoRecipe(snapshot, id),
            ProcessingCatalogue(snapshot.luts, snapshot.filmPresets, emptyList()),
            snapshot.exportOptions, longEdge, frameNumber = index + 1)
    }

    fun exportLayout(id: PhotoId): com.lumixfilmmirror.model.FrameLayout {
        val snapshot = mutable.value
        val source = snapshot.photos.firstOrNull { it.id == id } ?: error("照片不在当前批次中")
        return processor.exportLayout(source, snapshot.exportOptions, stockArtwork = snapshot.treatment == PhotoTreatment.FILM)
    }

    /** Export-stage settings apply to every photo in this batch, in either treatment mode. */
    fun updateFrame(frame: FrameOptions) {
        val snapshot = mutable.value
        val id = snapshot.processingBatchId ?: return
        if (snapshot.busy || snapshot.frame == frame) return
        if (frame.preset != snapshot.frame.preset && frame.preset in setOf(
                com.lumixfilmmirror.model.FramePreset.FILM, com.lumixfilmmirror.model.FramePreset.TICKET)) {
            output(snapshot.output.copy(format = com.lumixfilmmirror.model.ExportFormat.PNG))
        }
        frameDrafts[id] = frame
        mutable.update { it.copy(frame = frame, processingBatches = it.processingBatches.map { batch ->
            if (batch.id == id) batch.copy(frame = frame) else batch
        }) }
        frameSaveJobs.remove(id)?.cancel()
        frameSaveJobs[id] = viewModelScope.launch {
            try { delay(250); store.saveFrameRecipe(id, frame) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { notice(error.message ?: "画框设置未能保存，当前草稿仍保留") }
        }
    }

    fun filmPlan(snapshot: WorkspaceState, id: PhotoId): FilmRenderPlan {
        val batch = requireNotNull(snapshot.filmBatch) { "请先导入照片并选择胶卷" }
        if (batch.mode == FilmBatchMode.FIRST_PHOTO) require(snapshot.photos.any { it.id == batch.referenceId }) {
            "原基准照片不可用，请明确指定新的基准照片"
        }
        val entry = batch.photos.single { it.id == id }
        return processor.filmPlan(PhotoRecipe.Film(batch.effectiveParameters(id), entry.grainSeed), snapshot.filmPresets)
    }

    private fun saveEdit(treatment: PhotoTreatment, film: FilmBatch?) {
        val id = mutable.value.processingBatchId ?: return
        editDrafts[id] = treatment to film
        mutable.update { it.copy(treatment = treatment, filmBatch = film, processingBatches = it.processingBatches.map { batch ->
            if (batch.id == id) batch.copy(treatment = treatment, film = film) else batch
        }) }
        editSaveJobs.remove(id)?.cancel()
        editSaveJobs[id] = viewModelScope.launch {
            try { delay(250); store.saveEditRecipe(id, treatment, film) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { notice(error.message ?: "胶片配方未能保存，当前草稿仍保留") }
        }
    }

    fun treatment(value: PhotoTreatment) {
        val snapshot = mutable.value
        if (snapshot.busy || snapshot.photos.isEmpty() || snapshot.treatment == value) return
        saveEdit(value, syncFilmBatch(snapshot.filmBatch, snapshot.photos))
    }

    fun filmMode(mode: FilmBatchMode) {
        val snapshot = mutable.value
        val batch = snapshot.filmBatch ?: return
        if (!snapshot.busy) saveEdit(snapshot.treatment, batch.copy(mode = mode))
    }

    fun updateFilm(parameters: FilmParameters) {
        val snapshot = mutable.value
        val batch = snapshot.filmBatch ?: return
        if (snapshot.busy) return
        val id = if (batch.mode == FilmBatchMode.FIRST_PHOTO) batch.referenceId else snapshot.selected ?: return
        if (snapshot.photos.none { it.id == id }) { notice("原基准照片不可用，请明确指定新的基准照片"); return }
        saveEdit(snapshot.treatment, batch.updateParameters(id, parameters))
    }

    fun useCurrentFilmReference() {
        val snapshot = mutable.value
        val batch = snapshot.filmBatch ?: return
        val id = snapshot.selected ?: return
        if (!snapshot.busy && snapshot.photos.any { it.id == id }) saveEdit(snapshot.treatment, batch.copy(referenceId = id))
    }

    fun copyFilmReferenceToAll() {
        val snapshot = mutable.value
        val batch = snapshot.filmBatch ?: return
        if (snapshot.busy) return
        if (snapshot.photos.none { it.id == batch.referenceId }) {
            notice("原基准照片不可用，请明确指定新的基准照片"); return
        }
        saveEdit(snapshot.treatment, batch.copyReferenceToAll())
    }

    fun updateLut(recipe: LutRecipe) {
        val snapshot = mutable.value
        val id = snapshot.processingBatchId ?: return
        if (snapshot.busy || snapshot.lutRecipe == recipe) return
        lutDrafts[id] = recipe
        mutable.update { it.copy(lutRecipe = recipe, processingBatches = it.processingBatches.map { batch ->
            if (batch.id == id) batch.copy(lut = recipe) else batch
        }) }
        lutSaveJobs.remove(id)?.cancel()
        lutSaveJobs[id] = viewModelScope.launch {
            try {
                delay(250)
                store.saveLutRecipe(id, recipe)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { notice(error.message ?: "LUT 配方未能保存，当前调整仍保留") }
        }
    }

    fun importLuts(uris: List<Uri>) {
        if (mutable.value.busy || uris.isEmpty()) return
        mutable.update { it.copy(lutImporting = true) }
        viewModelScope.launch {
            var imported = 0
            val problems = mutableListOf<String>()
            try {
                for (uri in uris) {
                    coroutineContext.ensureActive()
                    try {
                        val asset = processor.importLut(uri)
                        mutable.update { it.copy(luts = it.luts.filterNot { existing -> existing.id == asset.id } + asset) }
                        imported++
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { problems += error.message ?: "LUT 导入失败" }
                }
            } finally {
                mutable.update { it.copy(lutImporting = false,
                    notice = (listOf("已导入 $imported 款 LUT") + problems).joinToString("\n")) }
            }
        }
    }

    fun refreshOfficialLuts(genreCode: String? = null, creatorCode: String? = null,
        language: String = processor.officialLutLanguage()) {
        officialLuts.refresh(LumixLabLutQuery(language, genreCode = genreCode, creatorCode = creatorCode))
    }

    fun selectOfficialLutCategory(genreCode: String?) {
        officialLuts.selectQuery(LumixLabLutQuery(processor.officialLutLanguage(), genreCode = genreCode))
    }

    /** Download never overwrites a slot or starts export. Selection remains an explicit batch edit. */
    fun downloadOfficialLut(code: String, language: String = processor.officialLutLanguage()): Boolean {
        if (mutable.value.busy || officialLutDownloadJob?.isActive == true) return false
        mutable.update { it.copy(lutImporting = true, officialLutDownload = OfficialLutDownloadState(code,
            OfficialLutDownloadStatus.RUNNING, OfficialLutDownloadStage.READING_DETAIL)) }
        officialLutDownloadJob = viewModelScope.launch {
            try {
                val asset = processor.downloadOfficialLut(code, language) { stage ->
                    mutable.update { it.copy(officialLutDownload = it.officialLutDownload?.copy(stage = stage)) }
                }
                mutable.update { it.copy(luts = it.luts.filterNot { existing -> existing.id == asset.id } + asset,
                    officialLutDownload = OfficialLutDownloadState(code, OfficialLutDownloadStatus.SAVED, null, asset.id)) }
            } catch (error: CancellationException) {
                mutable.update { it.copy(officialLutDownload = OfficialLutDownloadState(code, OfficialLutDownloadStatus.CANCELLED, null)) }
                throw error
            } catch (error: Exception) {
                mutable.update { it.copy(officialLutDownload = OfficialLutDownloadState(code, OfficialLutDownloadStatus.FAILED,
                    null, error = error.message ?: "官方 LUT 下载失败")) }
            } finally { mutable.update { it.copy(lutImporting = false) } }
        }
        return true
    }

    fun cancelOfficialLutDownload() { officialLutDownloadJob?.cancel() }

    /** Re-read durable assets after a late cancellation, external recovery or a failed UI delivery. */
    fun reloadLutLibrary(): Boolean {
        if (mutable.value.busy) return false
        mutable.update { it.copy(lutImporting = true) }
        viewModelScope.launch {
            try {
                val catalogue = processor.loadCatalogue()
                mutable.update { it.copy(luts = catalogue.luts, filmPresets = catalogue.films,
                    notice = catalogue.problems.takeIf { problems -> problems.isNotEmpty() }?.joinToString("\n") ?: it.notice) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { notice(error.message ?: "LUT 目录读取失败") }
            finally { mutable.update { it.copy(lutImporting = false) } }
        }
        return true
    }

    fun selectLutSlot(id: String?, second: Boolean = false): Boolean {
        val current = mutable.value
        if (current.busy || current.processingBatchId == null || (id != null && current.luts.none { it.id == id })) return false
        val recipe = if (second) current.lutRecipe.copy(second = current.lutRecipe.second.copy(id = id))
            else current.lutRecipe.copy(first = current.lutRecipe.first.copy(id = id))
        updateLut(recipe)
        return true
    }

    private fun withCamera(action: () -> Unit) { if (!mutable.value.busy) action() }
    fun connectCamera() = withCamera(camera::connectCamera)
    fun connectCameraWifi(ssid: String, password: String, security: CameraWifiSecurity) =
        withCamera { camera.connectCameraWifi(ssid, password, security) }
    fun scanBluetoothCameras() = withCamera(camera::scanBluetoothCameras)
    fun readBluetoothCamera(address: String, startTransfer: Boolean = true) {
        if (mutable.value.canSelectBluetoothCamera(camera.cameraState.value)) camera.readBluetoothCamera(address, startTransfer)
        else notice("当前任务尚未结束，请稍后连接相机")
    }
    fun connectBluetoothWifi(startOnCamera: Boolean = false) = withCamera { camera.connectBluetoothWifi(startOnCamera) }
    fun connectBluetoothSendingWifi() = withCamera(camera::connectBluetoothSendingWifi)
    fun connectBluetoothRouterWifi(ssid: String) = withCamera { camera.connectBluetoothRouterWifi(ssid) }
    fun connectBluetoothRouterSendingWifi(ssid: String) = withCamera { camera.connectBluetoothRouterSendingWifi(ssid) }
    fun reconnectSendingCamera() = withCamera(camera::reconnectSendingCamera)
    // These actions answer the current operation; its busy flag must not block its own dialog.
    fun chooseWifiNetwork(requestId: String, key: Long) = camera.chooseWifiNetwork(requestId, key)
    fun refreshWifiSelection(requestId: String) = camera.refreshWifiSelection(requestId)
    fun cancelWifiSelection(requestId: String) = camera.cancelWifiSelection(requestId)
    fun bluetoothPermissionDenied() = camera.bluetoothPermissionDenied()
    fun chooseCamera(key: String) = withCamera { camera.chooseCamera(key) }
    fun reconnectCamera() = withCamera(camera::reconnectCamera)
    fun cameraPermissionDenied() = camera.cameraPermissionDenied()
    fun openCameraFolder(entry: CameraMedia) = withCamera { camera.openCameraFolder(entry) }
    fun cameraFolderBack() = withCamera(camera::cameraFolderBack)
    fun loadMoreCameraPhotos() = withCamera(camera::loadMoreCameraPhotos)
    fun refreshCameraPhotos() = withCamera(camera::refreshCameraPhotos)
    fun selectCameraPhoto(id: String) = withCamera { camera.selectCameraPhoto(id) }
    fun selectLoadedCameraPhotos(select: Boolean) = withCamera { camera.selectLoadedCameraPhotos(select) }
    suspend fun cameraThumbnail(entry: CameraMedia, version: Long) = camera.cameraThumbnail(entry, version)
    fun importCameraPhotos() = withCamera(camera::importCameraPhotos)
    fun receiveIncomingPhotos(follow: Boolean = false) = withCamera { camera.receiveIncomingPhotos(follow) }
    fun resumeTransferBatch(id: String) = withCamera { camera.resumeTransferBatch(id) }
    fun cancelTransferBatch(id: String) = withCamera { camera.cancelTransferBatch(id) }
    fun pauseTransfer() = camera.stopTransfer(false, "传输已暂停，可继续未完成的任务")
    fun cancelCameraOperation() = camera.cancelCameraOperation()
    fun disconnectCamera() = withCamera(camera::disconnectCamera)
    fun inspectTransferCache() = withCamera(camera::inspectTransferCache)
    fun clearTransferCache() = withCamera(camera::clearTransferCache)

    fun inspectExportCache() = exportCacheOperation(clean = false)
    fun clearExportCache() = exportCacheOperation(clean = true)
    fun inspectFolderExports() = folderExportOperation(null)
    fun forgetFolderExport(id: String) = folderExportOperation(id)
    private fun folderExportOperation(id: String?) {
        if (mutable.value.busy || camera.cameraState.value.busy) return
        mutable.update { it.copy(exportCacheBusy = true) }
        viewModelScope.launch {
            try {
                val result = if (id == null) writer.inspectFolderExports() else writer.forgetFolderExport(id)
                mutable.update { it.copy(folderExports = result,
                    notice = if (id == null) "已读取文件夹导出记录；文件需打开核对" else "已移除应用内记录，文件夹中的照片未删除") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(notice = error.message ?: "无法读取文件夹导出记录") } }
            finally { mutable.update { it.copy(exportCacheBusy = false) } }
        }
    }
    private fun exportCacheOperation(clean: Boolean) {
        if (mutable.value.busy || camera.cameraState.value.busy) return
        mutable.update { it.copy(exportCacheBusy = true) }
        viewModelScope.launch {
            try {
                if (clean) {
                    val result = writer.cleanCache()
                    mutable.update { it.copy(exportCache = result.remaining.copy(
                        problems = (result.problems + result.remaining.problems).distinct()),
                        notice = "已整理 ${result.temporaryFilesRemoved} 个导出中间文件、${result.pendingImagesRemoved} 个未发布图片") }
                } else {
                    val result = writer.inspectCache()
                    mutable.update { it.copy(exportCache = result) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(notice = error.message ?: "无法整理导出中间文件") } }
            finally { mutable.update { it.copy(exportCacheBusy = false) } }
        }
    }

    fun importImages(uris: List<Uri>, newBatch: Boolean = false, requestId: String = UUID.randomUUID().toString()): Boolean {
        if (mutable.value.busy || camera.cameraState.value.busy || uris.isEmpty()) return false
        val currentBatch = mutable.value.processingBatch?.id?.takeUnless { newBatch }
        mutable.update { it.copy(importing = true, photoImportResult = null, failures = emptyList(), notice = null) }
        viewModelScope.launch {
            var importedBatch: String? = null
            val imported = mutableListOf<PhotoId>()
            try {
                val batchId = currentBatch ?: store.createProcessingBatch().also(::showCreatedBatch).id
                importedBatch = batchId
                for (uri in uris) {
                    coroutineContext.ensureActive()
                    try {
                        val id = PhotoId(UUID.randomUUID().toString())
                        store.reserveLocalPhoto(batchId, id)
                        val photo = importer.importImage(uri, photoId = id)
                        withContext(NonCancellable) { store.save(photo); imported += photo.id }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        mutable.update { it.copy(failures = it.failures + FailedPhoto(null, uri.lastPathSegment.orEmpty(),
                            error.message ?: "导入失败")) }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(notice = error.message ?: "无法建立导入批次") } }
            finally { mutable.update { it.copy(importing = false,
                photoImportResult = PhotoImportResult(requestId, importedBatch, imported.toList())) } }
        }
        return true
    }

    private fun showCreatedBatch(batch: ProcessingBatch) {
        editingSessionBatches.add(batch.id)
        mutable.update { previous -> previous.showBatch(
            (listOf(batch) + previous.processingBatches).distinctBy { it.id }, batch.id)
            .copy(notice = null) }
        persistPreferences()
    }

    fun newProcessingBatch() {
        if (mutable.value.busy || camera.cameraState.value.busy) return
        mutable.update { it.copy(batchBusy = true) }
        viewModelScope.launch {
            try { showCreatedBatch(store.createProcessingBatch()) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(notice = error.message ?: "无法新建批次") } }
            finally { mutable.update { it.copy(batchBusy = false) } }
        }
    }

    fun selectProcessingBatch(id: String) {
        if (mutable.value.busy || camera.cameraState.value.busy || mutable.value.processingBatches.none { it.id == id }) return
        if (id == mutable.value.processingBatchId) return
        mutable.update { it.showBatch(it.processingBatches, id)
            .copy(notice = null) }
        persistPreferences()
    }

    fun select(id: PhotoId) {
        if (mutable.value.recovering || mutable.value.photos.none { it.id == id }) return
        mutable.update { it.copy(selected = id) }
        persistPreferences()
    }
    fun removePhotos(ids: Set<PhotoId>) {
        val snapshot=mutable.value
        if(snapshot.busy || camera.cameraState.value.busy) { notice("当前任务结束后可移除照片"); return }
        val batchId=snapshot.processingBatchId ?: return
        val actual=ids.intersect(snapshot.photos.map { it.id }.toSet())
        if(actual.isEmpty())return
        val remaining=snapshot.photos.filterNot { it.id in actual }
        val film=syncFilmBatch(snapshot.filmBatch,remaining)
        val oldDraft=editDrafts[batchId]
        mutable.update { it.copy(batchBusy=true) }
        viewModelScope.launch {
            try {
                editSaveJobs.remove(batchId)?.let { job -> job.cancel(); job.join() }
                editDrafts[batchId]=snapshot.treatment to film
                store.removeProcessingPhotos(batchId,actual,snapshot.treatment,film)
                mutable.update { current ->
                    val batches=current.processingBatches.map { if(it.id==batchId)it.copy(photos=remaining,film=film) else it }
                    val position=snapshot.photos.indexOfFirst { it.id==snapshot.selected }.coerceAtLeast(0)
                    val selected=snapshot.selected?.takeIf { it !in actual }
                        ?: remaining.getOrNull(position.coerceAtMost(remaining.lastIndex))?.id
                    current.showBatch(batches,batchId).copy(selected=selected,
                        notice="已移除 ${actual.size} 张待处理照片，原文件保留")
                }
                persistPreferences()
            } catch(error:CancellationException) { throw error }
            catch(error:Exception) {
                if(oldDraft==null)editDrafts.remove(batchId) else editDrafts[batchId]=oldDraft
                notice(error.message ?: "照片未移除，请重试")
            } finally { mutable.update { it.copy(batchBusy=false) } }
        }
    }
    fun output(settings: JpegOutput) {
        if (!mutable.value.busy) { mutable.update { it.copy(output = settings) }; persistPreferences() }
    }
    fun destination(destination: ExportDestination) {
        if (!mutable.value.busy) { mutable.update { it.copy(destination = destination) }; persistPreferences() }
    }

    fun export(retryOnly: Boolean = false) {
        val snapshot = mutable.value
        if (snapshot.busy || camera.cameraState.value.busy || snapshot.photos.isEmpty()) return
        val previousRun = snapshot.exportRuns.firstOrNull { it.id == snapshot.exportRunId }
        val retryIds = previousRun?.items?.filter { it.state == ExportItemState.FAILED }?.map { it.photo.id }?.toSet()
            ?: snapshot.failures.mapNotNull { it.id }.toSet()
        val photos = if (retryOnly) snapshot.photos.filter { it.id in retryIds } else snapshot.photos
        if (photos.isEmpty()) {
            if (retryOnly && previousRun?.reviews != null && previousRun.reviews > 0)
                notice("保存结果需要核对，请先核对目标照片，再决定是否重试")
            return
        }
        launchExport(photos.size) {
            val batchId = requireNotNull(snapshot.processingBatchId) { "处理批次不存在" }
            store.saveLutRecipe(batchId, snapshot.lutRecipe)
            store.saveEditRecipe(batchId, snapshot.treatment, snapshot.filmBatch)
            store.saveFrameRecipe(batchId, snapshot.frame)
            val numbers = snapshot.photos.mapIndexed { index, photo -> photo.id to index + 1 }.toMap()
            exportQueue.enqueue(batchId, photos.map { photo -> ExportRequestPhoto(photo.id, photo.name,
                requireNotNull(numbers[photo.id]), photoRecipe(snapshot, photo.id)) },
                snapshot.exportOptions, snapshot.destination)
        }
    }

    /** Continue the immutable saved request, skipping SAVED and REVIEW items. */
    fun resumeExport(id: String, retryFailed: Boolean = false) {
        val snapshot = mutable.value
        if (snapshot.busy || camera.cameraState.value.busy) return
        val run = snapshot.exportRuns.firstOrNull { it.id == id && it.batchId == snapshot.processingBatchId } ?: return
        launchExport(run.items.size, retryFailed) { requireNotNull(exportQueue.snapshot(id)) }
    }

    /** Frontend must first ask the user to inspect the candidate file; null allows a later retry. */
    fun confirmExportReview(runId: String, photoId: PhotoId, savedUri: Uri?) {
        if (mutable.value.busy || mutable.value.exportRuns.none { it.id == runId && it.batchId == mutable.value.processingBatchId }) return
        viewModelScope.launch {
            try { exportQueue.confirmReview(runId, photoId, savedUri) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { notice(error.message ?: "核对结果未能保存") }
        }
    }

    fun cancelPendingExport(id: String) {
        if (mutable.value.busy || mutable.value.exportRuns.none { it.id == id && it.batchId == mutable.value.processingBatchId }) return
        viewModelScope.launch {
            try { exportQueue.stopIdle(id, cancel = true) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { notice(error.message ?: "任务未能取消") }
        }
    }

    private fun launchExport(total: Int, retryFailed: Boolean = false, request: suspend () -> ExportRun) {
        exportCancelRequested = false
        creatingExport = true
        mutable.update { it.copy(exporting = true, completed = 0, total = total, processingStage = null,
            processingPhotoId = null, exported = emptyList(), failures = emptyList(), notice = null, exportCache = null) }
        exportJob = viewModelScope.launch {
            var id: String? = null
            try {
                val run = withContext(NonCancellable) { request().also { id = it.id } }
                coroutineContext.ensureActive()
                mutable.update { it.showExportRun(run) }
                exports.requestStart(run.id, retryFailed)
            } catch (error: CancellationException) {
                mutable.update { it.copy(notice = if (exportCancelRequested)
                    "已取消后续导出，已保存的照片会保留" else "导出已暂停，可继续未完成的项目") }
            } catch (error: Exception) {
                mutable.update { it.copy(notice = error.message ?: "本批导出未能开始，已保存记录保留") }
            } finally {
                withContext(NonCancellable) {
                    try {
                        id?.let { runId ->
                            var run = exportQueue.snapshot(runId)
                            val owned = exportExecution.value.let { it.active && it.runId == runId }
                            if (!owned && run?.state == com.lumixfilmmirror.storage.ExportRunState.QUEUED) {
                                exportQueue.stopIdle(runId, cancel = exportCancelRequested)
                                run = exportQueue.snapshot(runId)
                            }
                            mutable.update { it.showExportRun(run) }
                        }
                        if (id == null) mutable.update { previous -> previous.showExportRun(
                            previous.exportRuns.firstOrNull { it.id == previous.exportRunId }) }
                    } catch (error: Exception) {
                        mutable.update { it.copy(notice = "导出已结束，但结果记录暂不可读：${error.message.orEmpty()}") }
                    }
                }
                creatingExport = false
                mutable.update { it.copy(exporting = exportExecution.value.active) }
            }
        }
    }

    fun pauseExport() {
        if (exportExecution.value.active) exports.stop()
        else { exportCancelRequested = false; exportJob?.cancel() }
    }
    fun cancelExport() {
        if (exportExecution.value.active) exports.stop(cancel = true, reason = "已取消后续导出，已保存照片保留")
        else { exportCancelRequested = true; exportJob?.cancel() }
    }
}
