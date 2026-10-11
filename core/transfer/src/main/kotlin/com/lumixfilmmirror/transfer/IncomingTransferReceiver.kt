package com.lumixfilmmirror.transfer

import com.lumixfilmmirror.camera.*
import com.lumixfilmmirror.storage.*
import kotlinx.coroutines.*
import java.io.IOException

/** Drains this sending burst. BLE triggers and reconnection are separate from file ownership. */
class IncomingTransferReceiver(private val store: WorkspaceStore, private val files: TransferRunner) {
    suspend fun run(id: String, session: LumixCameraSession,
        onCount: (Int) -> Unit = {},
        onPhase: (TransferTask, TransferPhase) -> Unit = { _, _ -> },
        onProgress: (TransferTask, DownloadProgress) -> Unit = { _, _ -> },
        awaitNext: (suspend () -> Unit)? = null,
        onWaiting: (Boolean) -> Unit = {}): TransferResult {
        try {
            return coroutineScope {
                var run = requireNotNull(store.incoming(id)) { "接收记录缺失" }
                val directory = session.preparedIncomingDirectory()
                require(!run.terminal && run.belongsTo(session.device) && run.accessMode == session.accessMode &&
                    run.directoryId == directory.objectId) { "请连接此接收记录对应的相机和发送模式" }
                run = run.copy(state = "RUNNING", error = null,
                    acknowledgement = if (run.acknowledgement == "IN_FLIGHT") "UNKNOWN" else run.acknowledgement)
                store.saveIncoming(run)
                val heartbeat = launch {
                    while (isActive) { session.keepTransferAlive(); delay(1_000) }
                }
                var imported = 0
                var videos = 0
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val current = run.currentTaskId?.let { taskId ->
                            requireNotNull(store.task(taskId)) { "待发送文件记录缺失，未替换其任务 ID" }
                        }
                        if (current != null) {
                            check(current.state != "CANCELLED") { "此文件接收已取消，请结束相机发送后重新选择" }
                            if (!current.completed) {
                                val result = files.runIncomingItem(run, current, session, onPhase, onProgress)
                                imported += result.imported
                                videos += result.savedVideos
                            }
                            val saved = requireNotNull(store.task(current.id))
                            check(saved.completed) { saved.error ?: "待发送文件尚未保存，接收已暂停" }
                            files.requireSavedResult(saved)
                            if (run.acknowledgement == "NONE") {
                                run = run.copy(acknowledgement = "READY")
                                store.saveIncoming(run)
                            }
                            onPhase(saved, TransferPhase.CONFIRMING_CAMERA)
                            run = settleSavedFile(run, saved, session, directory, onCount)
                        }

                        var head = firstPending(session, directory, onCount)
                        if (current != null && head?.matchesPreviousMedia(current.expectedMedia()) == true) {
                            // A successful GET/confirmation can precede the camera's directory update.
                            for (attempt in 0 until 5) {
                                delay(400)
                                head = firstPending(session, directory, onCount)
                                if (head?.matchesPreviousMedia(current.expectedMedia()) != true) break
                            }
                            if (head?.matchesPreviousMedia(current.expectedMedia()) == true) {
                                throw IOException("文件已保存，但相机仍列出同一待发送文件；接收已暂停，不重复下载或重发确认")
                            }
                        }
                        if (head == null) {
                            if (awaitNext != null) {
                                run = run.copy(state = "WAITING", error = null)
                                store.saveIncoming(run)
                                onWaiting(true)
                                try { awaitNext() } finally { onWaiting(false) }
                                currentCoroutineContext().ensureActive()
                                run = run.copy(state = "RUNNING")
                                store.saveIncoming(run)
                                continue
                            }
                            store.saveIncoming(run.copy(state = "COMPLETE", error = null))
                            return@coroutineScope TransferResult(imported, 0, 0, videos)
                        }
                        val next = requireNotNull(head)
                        require(!next.isFolder && next.transferResource != null) {
                            if (next.isVideo) "发送目录首项未提供可传输的完整视频资源，接收已暂停，未保存封面或发送确认"
                            else "发送目录首项没有支持的原始资源，未跳过该项"
                        }
                        val previouslySaved = store.incomingSavedTasks(run).firstOrNull {
                            next.matchesPreviousMedia(it.expectedMedia())
                        }
                        if (previouslySaved != null) {
                            throw IOException("此待发送文件已有保存记录，接收已暂停以避免重复；请结束相机发送后使用普通传输核对")
                        }
                        store.appendIncoming(run, session.device, next)
                        run = requireNotNull(store.incoming(id))
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("接收流程结束")
                } finally { heartbeat.cancel() }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                store.stopBatch(id, (error as? TransferStop)?.cancelRemaining == true,
                    error.message ?: "接收已暂停，已保存结果保留")
            }
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) { store.stopBatch(id, false, error.message ?: "接收已暂停") }
            throw error
        }
    }

    private suspend fun firstPending(session: LumixCameraSession, directory: CameraIncomingDirectory,
        onCount: (Int) -> Unit): CameraMedia? {
        val count = session.incomingContentCount()
        onCount(count)
        if (count == 0) return null
        val page = session.browse(directory.objectId, count = 1)
        require(page.entries.size == 1) { "相机报告有待发送文件，但首项列表暂不可用" }
        return page.entries.single()
    }

    private suspend fun settleSavedFile(run: IncomingTransfer, task: TransferTask, session: LumixCameraSession,
        directory: CameraIncomingDirectory, onCount: (Int) -> Unit): IncomingTransfer {
        if (run.acknowledgement in setOf("CONFIRMED", "ADVANCED")) return run
        val head = firstPending(session, directory, onCount)
        val stillFirst = head?.matchesPreviousMedia(task.expectedMedia()) == true
        if (run.confirmationUnknown && stillFirst) {
            throw IOException("文件已保存，但相机确认响应未知；不会自动重发。请在相机结束发送后核对已保存结果")
        }
        if (directory.mode == CameraAccessMode.AUTO_UPLOAD || !stillFirst) {
            // ADVANCED means no directory acknowledgement was sent/proven, not an invented ACK success.
            return run.copy(acknowledgement = "ADVANCED").also { store.saveIncoming(it) }
        }
        check(run.acknowledgement == "READY" && directory.mode == CameraAccessMode.MARKED_TRANSFER)
        val sending = run.copy(acknowledgement = "IN_FLIGHT")
        store.saveIncoming(sending) // Commit BEFORE crossing the network boundary.
        val confirmed = try {
            session.confirmSavedIncoming(directory, task.expectedMedia())
        } catch (error: Exception) {
            withContext(NonCancellable) { store.saveIncoming(sending.copy(acknowledgement = "UNKNOWN")) }
            throw error
        }
        val settled = sending.copy(acknowledgement = if (confirmed) "CONFIRMED" else "ADVANCED")
        withContext(NonCancellable) { store.saveIncoming(settled) }
        return settled
    }
}
