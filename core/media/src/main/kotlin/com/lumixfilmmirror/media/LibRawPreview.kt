package com.lumixfilmmirror.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Preview extraction is the default; sensor decoding is an explicit, serialized import option. */
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
    private external fun nativeDecode(input: String, halfSize: Boolean, workingPath:String,cancellation: CancellationProbe): android.graphics.Bitmap

    suspend fun decode(input:File,output:File,halfSize:Boolean)=withContext(Dispatchers.IO) {
        check(loaded){"RAW 解码组件不可用"}
        coroutineContext.ensureActive()
        val bitmap=try{nativeDecode(input.absolutePath,halfSize,File(output.parentFile,"working.raw10").absolutePath,CancellationProbe(coroutineContext[Job]))}
        catch(e:OutOfMemoryError){throw IOException("RAW 解码内存不足，请选择均衡或内嵌 JPEG",e)}
        catch(e:IOException) {
            coroutineContext.ensureActive()
            throw IOException(when {
                e.message.orEmpty().contains("memory",ignoreCase=true)->"RAW 解码内存不足，请选择均衡或内嵌 JPEG"
                e.message.orEmpty().contains("limit",ignoreCase=true)->"RAW 尺寸超出手机解码限制，请选择内嵌 JPEG"
                e.message.orEmpty().contains("Unsupported",ignoreCase=true)->"暂不支持此 RAW，或文件已损坏；可改选内嵌 JPEG"
                else->"RAW 原始解码失败，可改选内嵌 JPEG"
            },e)
        }
        try {
            coroutineContext.ensureActive()
            output.outputStream().buffered().use { stream ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)){"无法保存 RAW 工作图"}
            }
            coroutineContext.ensureActive()
        } finally {bitmap.recycle()}
    }
}
