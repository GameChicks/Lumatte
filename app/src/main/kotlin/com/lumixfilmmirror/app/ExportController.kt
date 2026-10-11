package com.lumixfilmmirror.app

import android.app.Application
import com.lumixfilmmirror.media.PhotoProcessor
import com.lumixfilmmirror.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class ExportExecutionState(val runId: String? = null, val sessionId: String? = null,
    val active: Boolean = false, val preparing: Boolean = false, val stopping: Boolean = false,
    val run: ExportRun? = null, val notice: String? = null) {
    fun matches(id: String, session: String) = runId == id && sessionId == session
}

/** Application-owned handoff. Only an explicit visible-app request starts the service. */
class ExportController(private val application: Application, processor: PhotoProcessor) {
    private val runner = ExportQueueRunner(application, processor)
    val store get() = runner.store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(ExportExecutionState())
    val state = mutable.asStateFlow()
    private var running: Job? = null
    private var startupWatchdog: Job? = null
    private var pendingCleanup: Job? = null
    private var attachedSession: String? = null
    private var retryFailed = false
    private var cancelRemaining = false

    suspend fun requestStart(id: String, retry: Boolean = false) = withContext(Dispatchers.Main.immediate) {
        require(UUID.fromString(id).toString() == id)
        if (running?.isCompleted == true) running = null
        check(!mutable.value.active && running == null) { "已有导出请求正在启动、执行或收尾" }
        store.initialize()
        val request = requireNotNull(store.snapshot(id)) { "导出任务不存在" }
        if (running?.isCompleted == true) running = null
        check(!mutable.value.active && running == null) { "已有导出请求正在启动、执行或收尾" }
        require(request.state != ExportRunState.CANCELLED && request.state != ExportRunState.RUNNING)
        require(request.items.any { it.state in setOf(ExportItemState.QUEUED, ExportItemState.PAUSED) ||
            (retry && it.state == ExportItemState.FAILED) }) { "没有可继续的项目；保存不明项需先核对" }
        val session = UUID.randomUUID().toString()
        retryFailed = retry
        cancelRemaining = false
        mutable.value = ExportExecutionState(id, session, active = true, preparing = true, run = request)
        try {
            application.startForegroundService(PhotoExportService.startIntent(application, id, session))
            startupWatchdog = scope.launch {
                delay(15_000)
                startFailed(id, session, "导出服务未接管请求，任务已暂停，请返回工作台继续")
            }
        } catch (error: Exception) {
            startFailed(id, session, "导出服务启动失败：${error.message ?: "请返回工作台重试"}")
            throw error
        }
    }

    /** Service/Main only. The nonce must still identify the pending user request. */
    internal fun takeOver(id: String, session: String, serviceScope: CoroutineScope): Job? {
        val current = mutable.value
        if (!current.matches(id, session) || !current.active || !current.preparing || current.stopping || running != null) return null
        startupWatchdog?.cancel(); startupWatchdog = null
        attachedSession = session
        mutable.update { it.copy(preparing = false) }
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var message: String? = null
            try {
                runner.run(id, retryFailed, cancelRemaining = { cancelRemaining }) { progress ->
                    mutable.update { if (it.matches(id, session)) it.copy(run = progress) else it }
                }
                message = "本次导出任务已结束，结果已记录"
            } catch (error: CancellationException) {
                message = mutable.value.notice ?: "导出已暂停，可继续未完成的项目"
            } catch (error: OutOfMemoryError) {
                message = "导出所需内存不足，未完成项目保留"
            } catch (error: Exception) {
                message = error.message ?: "导出未能完成，已有记录保留"
            } finally {
                withContext(NonCancellable) {
                    var result: ExportRun? = null
                    try { result = store.snapshot(id) }
                    catch (error: Exception) { message = "导出已结束，但结果记录暂不可读：${error.message.orEmpty()}" }
                    val awaitingServiceClose = attachedSession == session
                    mutable.update { if (it.matches(id, session)) it.copy(active = awaitingServiceClose, preparing = false,
                        stopping = awaitingServiceClose, run = result ?: it.run, notice = message) else it }
                }
            }
        }
        running = job
        job.invokeOnCompletion { scope.launch { if (running === job) running = null } }
        job.start()
        return job
    }

    /** Called after stopping the FGS. Keep occupancy until a cancelled native task has drained. */
    internal fun serviceClosed(id: String?, session: String?) {
        if (id == null || session == null || attachedSession != session) return
        attachedSession = null
        if (running == null || running?.isCompleted == true)
            mutable.update { if (it.matches(id, session)) it.copy(active = false, preparing = false, stopping = false) else it }
    }

    fun stop(cancel: Boolean = false, reason: String = "导出已暂停，可继续未完成的项目",
        runId: String? = null, sessionId: String? = null) {
        scope.launch {
            val current = mutable.value
            if (!current.active || (runId != null && current.runId != runId) ||
                (sessionId != null && current.sessionId != sessionId)) return@launch
            if (current.stopping && !cancel) return@launch
            if (cancel) cancelRemaining = true
            mutable.update { it.copy(stopping = true, notice = reason) }
            val job = running
            if (job != null) job.cancel()
            else stopPending(requireNotNull(current.runId), requireNotNull(current.sessionId), reason)
        }
    }

    internal fun startFailed(id: String, session: String, reason: String) {
        val current = mutable.value
        if (!current.matches(id, session) || !current.active) return
        if (running != null) { stop(reason = reason, runId = id, sessionId = session); return }
        if (current.stopping) return
        mutable.update { it.copy(preparing = false, stopping = true, notice = reason) }
        stopPending(id, session, reason)
    }

    private fun stopPending(id: String, session: String, reason: String) {
        startupWatchdog?.cancel(); startupWatchdog = null
        if (pendingCleanup?.isCompleted == false) return
        val cleanup = scope.launch(start = CoroutineStart.LAZY) {
            var result: ExportRun? = null
            var message = reason
            try {
                // A pause can be upgraded to cancellation while Room is suspended. One
                // cleanup owns all writes until the latest disposition has been applied.
                do {
                    val cancel = cancelRemaining
                    store.stopIdle(id, cancel)
                    result = store.snapshot(id)
                } while (cancelRemaining && !cancel)
                message = mutable.value.notice ?: reason
            }
            catch (error: Exception) { message = "$reason；任务记录暂不可读：${error.message.orEmpty()}" }
            mutable.update { if (it.matches(id, session)) it.copy(active = false, preparing = false,
                stopping = false, run = result ?: it.run, notice = message) else it }
        }
        pendingCleanup = cleanup
        cleanup.start()
    }
}
