package com.lumixfilmmirror.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import com.lumixfilmmirror.media.PhotoProcessor

class FilmMirrorApplication : Application() {
    internal val editingSessionBatches = mutableSetOf<String>()
    internal var uiSessionStarted = false
    internal fun endEditingSession() {
        val live = buildSet {
            camera.cameraState.value.activeTransferBatch?.let(::add)
            exports.state.value.takeIf { it.active }?.run?.batchId?.let(::add)
        }
        editingSessionBatches.retainAll(live)
        uiSessionStarted = false
    }
    val camera: CameraController by lazy { CameraController(this) }
    private val sharedProcessor = lazy { PhotoProcessor(this) }
    val processor: PhotoProcessor get() = sharedProcessor.value
    val exports: ExportController by lazy { ExportController(this, processor) }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Android 14+ no longer sends the old running-low callbacks. UI-hidden
        // remains useful; the processor also enforces its own fixed cache budget.
        @Suppress("DEPRECATION")
        val oldMemoryPressure = Build.VERSION.SDK_INT < 34 && level in setOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        if (sharedProcessor.isInitialized() && (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || oldMemoryPressure)) {
            processor.requestMemoryTrim()
        }
    }
}
