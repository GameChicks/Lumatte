package com.lumixfilmmirror.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import com.lumixfilmmirror.storage.WorkspaceStore
import com.lumixfilmmirror.storage.completed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample
import java.util.UUID

/** User-started Wi-Fi camera transfer. No automatic boot/restart/reconnection. */
class CameraTransferService : Service() {
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as FilmMirrorApplication).endEditingSession()
        super.onTaskRemoved(rootIntent)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as FilmMirrorApplication).camera
    private var running: Job? = null
    private var notifications: Job? = null
    private var queueObserver: Job? = null
    private var lockRenewal: Job? = null
    private var batchId: String? = null
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val manager get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "相机媒体传输", NotificationManager.IMPORTANCE_LOW).apply {
            description = "相机传输进度、暂停和取消"
            setShowBadge(false)
        })
    }

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_BATCH)
        if (id == null || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) {
            if (running == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val actionName = intent?.action
        when (actionName) {
            PAUSE, CANCEL -> {
                if (id == batchId && id == controller.cameraState.value.activeTransferBatch) {
                    controller.stopTransfer(actionName == CANCEL,
                        if (actionName == CANCEL) "已取消后续传输，已导入照片保留" else "传输已暂停，可在工作台继续")
                }
                if (running == null) stopSelfResult(startId)
            }
            START -> {
                if (running?.isActive == true) return START_NOT_STICKY
                if (controller.cameraState.value.activeTransferBatch != id) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                running = null
                clearRunResources()
                batchId = id
                try {
                    startForeground(NOTIFICATION, notification(controller.cameraState.value),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    foreground = true
                    acquireLocks()
                    val ownedJob = controller.runInService(id, scope)
                    running = ownedJob
                    if (ownedJob == null) {
                        controller.serviceUnavailable("传输服务未能接管任务，请在工作台重试")
                        finishService()
                        return START_NOT_STICKY
                    }
                    ownedJob.invokeOnCompletion {
                        // Completion may originate from an IO callback; service operations stay on Main.
                        android.os.Handler(mainLooper).post { if (running === ownedJob) finishService() }
                    }
                    queueObserver = scope.launch {
                        try {
                            WorkspaceStore(this@CameraTransferService).transfers.collect { tasks ->
                                val batch = tasks.filter { it.batchId == id }
                                controller.updateTransferCounts(id, batch.size, batch.count { it.completed })
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { controller.serviceUnavailable(error.message ?: "无法读取传输记录") }
                    }
                    notifications = scope.launch {
                        try {
                            controller.cameraState.sample(1_000).collect { state ->
                                if (foreground) manager.notify(NOTIFICATION, notification(state))
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { controller.serviceUnavailable("通知更新失败，传输已暂停：${error.message.orEmpty()}") }
                    }
                } catch (error: Exception) {
                    controller.serviceUnavailable("后台传输启动失败：${error.message ?: "请返回工作台重试"}")
                    finishService()
                }
            }
            else -> if (running == null) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun notification(state: CameraWorkspaceState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detail = if (state.waitingForIncoming) "等待后续拍摄 · 已保存 ${state.transferred} 项"
            else if (state.receiving) "${state.transferPhase.caption()} · 已保存 ${state.transferred} 项 · 待发送 ${state.incomingCount ?: 0} 项"
            else if (state.transferTotal > 0) "${state.transferPhase.caption()} · 已完成 ${state.transferred} / ${state.transferTotal} 项" else "正在准备传输"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_transfer_notification)
            .setContentTitle(if (state.receiving) "松映 · Lumatte · 相机待发接收" else "松映 · Lumatte · 相机传输")
            .setContentText(detail)
            .setSubText(state.device?.model)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setProgress(state.transferTotal.coerceAtLeast(1), state.transferred, state.receiving || state.transferTotal == 0)
            .addAction(Notification.Action.Builder(null, "暂停", action(PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(null, if (state.followingIncoming) "停止后续接收" else "取消余下", action(CANCEL, 2)).build())
            .build()
    }

    private fun action(action: String, request: Int): PendingIntent = PendingIntent.getService(this, request,
        Intent(this, CameraTransferService::class.java).setAction(action).putExtra(EXTRA_BATCH, batchId)
            .setData(Uri.Builder().scheme("lumixfilmmirror").authority("transfer")
                .appendPath(batchId.orEmpty()).appendPath(action).build()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "LumixFilmMirror:CameraTransfer").apply { setReferenceCounted(false); acquire(30 * 60 * 1_000L) }
        wifiLock = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "LumixFilmMirror:CameraTransfer").apply { setReferenceCounted(false); acquire() }
        lockRenewal = scope.launch {
            while (isActive) {
                delay(20 * 60 * 1_000L)
                wakeLock?.acquire(30 * 60 * 1_000L)
            }
        }
    }

    private fun releaseLocks() {
        lockRenewal?.cancel()
        lockRenewal = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun clearRunResources() {
        notifications?.cancel()
        notifications = null
        queueObserver?.cancel()
        queueObserver = null
        releaseLocks()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun finishService() {
        clearRunResources()
        running = null
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        controller.stopTransfer(false, "系统结束后台传输时段，任务已暂停")
        finishService()
    }

    override fun onDestroy() {
        if (running?.isActive == true) controller.stopTransfer(false, "传输服务结束，未完成任务可继续")
        else if (batchId != null && controller.cameraState.value.activeTransferBatch == batchId)
            controller.serviceUnavailable("传输服务结束，请在工作台继续")
        clearRunResources()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "camera-transfer"
        private const val NOTIFICATION = 1001
        private const val EXTRA_BATCH = "batch-id"
        private const val START = "com.lumixfilmmirror.app.START_TRANSFER"
        private const val PAUSE = "com.lumixfilmmirror.app.PAUSE_TRANSFER"
        private const val CANCEL = "com.lumixfilmmirror.app.CANCEL_TRANSFER"
        fun startIntent(context: Context, batchId: String): Intent =
            Intent(context, CameraTransferService::class.java).setAction(START).putExtra(EXTRA_BATCH, batchId)
    }
}
