package com.lumixfilmmirror.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import com.lumixfilmmirror.model.PhotoProcessingStage
import com.lumixfilmmirror.storage.ExportItemState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample
import java.util.UUID

/** User-started local photo processing; no boot, sticky restart or camera control. */
class PhotoExportService : Service() {
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as FilmMirrorApplication).endEditingSession()
        super.onTaskRemoved(rootIntent)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as FilmMirrorApplication).exports
    private val manager get() = getSystemService(NotificationManager::class.java)
    private var running: Job? = null
    private var updates: Job? = null
    private var lockRenewal: Job? = null
    private var timeLimit: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var runId: String? = null
    private var sessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "照片处理与导出", NotificationManager.IMPORTANCE_LOW).apply {
            description = "胶片／LUT 处理进度、暂停与取消"
            setShowBadge(false)
        })
    }

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_RUN)
        val session = intent?.getStringExtra(EXTRA_SESSION)
        if (!validId(id) || !validId(session)) {
            if (running == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val actionName = intent?.action
        when (actionName) {
            PAUSE, CANCEL -> {
                controller.stop(cancel = actionName == CANCEL,
                    reason = if (actionName == CANCEL) "已取消后续导出，已保存照片保留" else "导出已暂停，可在工作台继续",
                    runId = id, sessionId = session)
                if (running == null) stopSelfResult(startId)
            }
            START -> {
                val request = controller.state.value
                if (!request.matches(requireNotNull(id), requireNotNull(session)) || !request.active ||
                    !request.preparing || request.stopping) {
                    if (running == null && !request.active) stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                if (running?.isCompleted == false) return START_NOT_STICKY
                running = null
                clearResources()
                runId = id; sessionId = session
                try {
                    val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    startForeground(NOTIFICATION, notification(controller.state.value), type)
                    foreground = true
                    acquireLock()
                    val job = controller.takeOver(requireNotNull(id), requireNotNull(session), scope)
                    if (job == null) { finishService(); return START_NOT_STICKY }
                    running = job
                    job.invokeOnCompletion { Handler(mainLooper).post { if (running === job) finishService() } }
                    updates = scope.launch {
                        try { controller.state.sample(1_000).collect { state ->
                            if (foreground && state.matches(requireNotNull(id), requireNotNull(session)))
                                manager.notify(NOTIFICATION, notification(state))
                        } }
                        catch (error: CancellationException) { throw error }
                        catch (error: Exception) { controller.stop(reason = "通知更新失败，导出已暂停",
                            runId = id, sessionId = session) }
                    }
                    // Also bound older systems that do not deliver the modern timeout callback.
                    timeLimit = scope.launch {
                        delay(330 * 60 * 1_000L)
                        timeout("本次后台处理时段结束，未完成项目可在工作台继续")
                    }
                } catch (error: Exception) {
                    controller.startFailed(requireNotNull(id), requireNotNull(session),
                        "导出服务未能启动：${error.message ?: "请返回工作台重试"}")
                    finishService()
                }
            }
            else -> if (running == null) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun notification(state: ExportExecutionState): Notification {
        val run = state.run
        val stage = run?.items?.firstOrNull { it.state == ExportItemState.RUNNING }?.stage
        val caption = when {
            state.stopping -> "正在停止后续处理"
            state.preparing -> "正在准备处理"
            else -> when (stage) {
                PhotoProcessingStage.PREPARING -> "准备照片"
                PhotoProcessingStage.RENDERING -> "处理影像"
                PhotoProcessingStage.FRAMING -> "添加画框"
                PhotoProcessingStage.ENCODING -> "生成 JPG"
                PhotoProcessingStage.PUBLISHING -> "保存照片"
                null -> "准备下一张"
            }
        }
        val total = run?.items?.size ?: 0
        val open = PendingIntent.getActivity(this, 1002, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_transfer_notification)
            .setContentTitle("松映 · Lumatte · 照片导出")
            .setContentText("$caption · 已保存 ${run?.saved ?: 0} / $total · 失败 ${run?.failed ?: 0} · 待核对 ${run?.reviews ?: 0}")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setProgress(total.coerceAtLeast(1), run?.completed ?: 0, total == 0)
            .addAction(Notification.Action.Builder(null, "暂停", action(PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(null, "取消余下", action(CANCEL, 2)).build()).build()
    }

    private fun action(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, PhotoExportService::class.java).setAction(action).putExtra(EXTRA_RUN, runId).putExtra(EXTRA_SESSION, sessionId)
            .setData(Uri.Builder().scheme("lumixfilmmirror").authority("export").appendPath(runId.orEmpty())
                .appendPath(sessionId.orEmpty()).appendPath(action).build()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun acquireLock() {
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "LumixFilmMirror:PhotoExport").apply { setReferenceCounted(false); acquire(30 * 60 * 1_000L) }
        lockRenewal = scope.launch { while (isActive) { delay(20 * 60 * 1_000L); wakeLock?.acquire(30 * 60 * 1_000L) } }
    }

    private fun clearResources() {
        updates?.cancel(); updates = null
        timeLimit?.cancel(); timeLimit = null
        lockRenewal?.cancel(); lockRenewal = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun finishService() {
        clearResources(); running = null; stopSelf()
        controller.serviceClosed(runId, sessionId)
    }
    private fun timeout(reason: String) {
        controller.stop(reason = reason, runId = runId, sessionId = sessionId)
        // Stop the FGS promptly; native cancellation drains separately before Room finalization.
        finishService()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { timeout("系统结束后台导出时段，未完成项目可继续") }
    override fun onDestroy() {
        if (running != null) controller.stop(reason = "导出服务结束，未完成项目可继续", runId = runId, sessionId = sessionId)
        clearResources(); scope.cancel()
        controller.serviceClosed(runId, sessionId)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "photo-export"
        private const val NOTIFICATION = 1002
        private const val EXTRA_RUN = "export-run"
        private const val EXTRA_SESSION = "export-session"
        private const val START = "com.lumixfilmmirror.app.START_EXPORT"
        private const val PAUSE = "com.lumixfilmmirror.app.PAUSE_EXPORT"
        private const val CANCEL = "com.lumixfilmmirror.app.CANCEL_EXPORT"
        private fun validId(value: String?) = value != null && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        fun startIntent(context: Context, id: String, session: String): Intent =
            Intent(context, PhotoExportService::class.java).setAction(START).putExtra(EXTRA_RUN, id).putExtra(EXTRA_SESSION, session)
    }
}
