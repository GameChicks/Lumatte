package com.lumixfilmmirror.storage

import android.content.Context
import com.lumixfilmmirror.media.PhotoProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Screen-independent serial execution, owned by the application's photo export service. */
class ExportQueueRunner(context: Context, private val processor: PhotoProcessor) {
    private val sources = WorkspaceStore(context)
    val store = ExportQueueStore(context)

    suspend fun run(id: String, retryFailed: Boolean = false, cancelRemaining: () -> Boolean = { false },
        onProgress: suspend (ExportRun) -> Unit = {}) {
        check(runGate.tryLock()) { "已有批量导出正在执行" }
        var claimed = false
        var stopped: Boolean? = null
        try {
            val request = withContext(NonCancellable) { store.begin(id, retryFailed) { claimed = true } }
            onProgress(request)
            val catalogue = processor.loadCatalogue()
            for (item in request.items.filter { it.state == ExportItemState.QUEUED }) {
                coroutineContext.ensureActive()
                val photo = item.photo
                try {
                    val source = withContext(Dispatchers.IO) {
                        requireNotNull(sources.source(photo.id.value)) { "导入照片记录不可用" }.also {
                            check(it.jpeg.isFile) { "导入副本缺失，请重新导入原照片" }
                        }
                    }
                    processor.export(source, photo.recipe, catalogue, request.options, request.destination,
                        frameNumber = photo.frameNumber,
                        onStage = { stage ->
                            store.checkpoint(id, photo.id, stage)
                            onProgress(requireNotNull(store.snapshot(id)))
                        },
                        onTargetCreated = { uri -> store.targetCreated(id, photo.id, uri) },
                        onPublished = { uri -> store.saved(id, photo.id, uri) })
                } catch (error: CancellationException) { throw error }
                catch (error: OutOfMemoryError) {
                    store.failed(id, photo.id, "当前尺寸所需内存不足，请降低图片尺寸后重新导出失败项")
                } catch (error: LinkageError) {
                    store.failed(id, photo.id, "影像引擎暂不可用")
                } catch (error: Exception) {
                    store.failed(id, photo.id, error.message ?: "导出失败")
                }
                onProgress(requireNotNull(store.snapshot(id)))
            }
        } catch (error: CancellationException) {
            stopped = cancelRemaining()
            throw error
        } catch (error: Throwable) {
            stopped = false
            throw error
        } finally {
            try {
                if (claimed) withContext(NonCancellable) { store.finish(id, stopped) }
            } finally { runGate.unlock() }
        }
    }

    companion object { private val runGate = Mutex() }
}
