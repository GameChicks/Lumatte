package com.lumixfilmmirror.media

import android.os.SystemClock

enum class FilmExecutionBackend { GLES3, CPU }

/** Actual export work only; does not launch a benchmark or retain image/location identifiers. */
data class ExportPerformanceReport(
    val completed: Boolean,
    val totalMillis: Long,
    val stages: List<ExportStageTiming>,
    val filmBackend: FilmExecutionBackend?,
    val gpuFallbackReason: String?,
    val spatialAlgorithmVersion: String = FilmSpatialEffectsInfo.VERSION,
)

data class ExportStageTiming(val stage: String, val elapsedMillis: Long)

internal class ExportPerformanceTrace {
    private val started = SystemClock.elapsedRealtimeNanos()
    private var previous = started
    private val timings = mutableListOf<ExportStageTiming>()
    fun mark(stage: String) {
        val now = SystemClock.elapsedRealtimeNanos()
        timings += ExportStageTiming(stage, (now - previous) / 1_000_000)
        previous = now
    }
    fun report(completed: Boolean, backend: FilmExecutionBackend?, reason: String?) =
        ExportPerformanceReport(completed, (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000,
            timings.toList(), backend, reason)
}
