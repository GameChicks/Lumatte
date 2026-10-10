package com.lumixfilmmirror.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Native API exposes JPEG previews only, never sensor data, bitmap previews or RAW development. */
internal object LibRawPreview {
    private val loaded: Boolean by lazy {
        try { System.loadLibrary("lfm_raw_preview"); true }
        catch (error: UnsatisfiedLinkError) { false }
    }

    suspend fun extract(input: File, directory: File): List<File> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        check(loaded) { "RAW 预览组件不可用，请改用相机 JPEG 成片" }
        val count = try {
            nativeExtract(input.absolutePath, directory.absolutePath, CancellationProbe(coroutineContext[Job]))
        } catch (error: UnsatisfiedLinkError) {
            throw IOException("RAW 预览组件不可用，请改用相机 JPEG 成片", error)
        } catch (error: OutOfMemoryError) { throw IOException("内嵌 JPEG 提取所需内存不足，请改用相机 JPEG 成片", error) }
        coroutineContext.ensureActive()
        when (count) {
            -1 -> throw IOException("内嵌 JPEG 提取已停止")
            -2 -> throw IOException("无法读取或保存 RAW 内嵌 JPEG")
            -3 -> throw IOException("内嵌 JPEG 提取所需内存不足，请改用相机 JPEG 成片")
            -4 -> throw IOException("RAW 预览数据损坏或超出读取限制，请改用相机 JPEG 成片")
        }
        check(count in 0..8) { "RAW 预览组件返回异常结果" }
        List(count) { File(directory, "libraw-preview-$it.jpg") }
    }

    private class CancellationProbe(private val job: Job?) {
        fun isCancelled(): Boolean = job?.isActive == false
    }

    private external fun nativeExtract(input: String, directory: String, cancellation: CancellationProbe): Int
}
