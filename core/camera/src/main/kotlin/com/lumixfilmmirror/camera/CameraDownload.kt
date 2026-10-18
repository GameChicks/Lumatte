package com.lumixfilmmirror.camera

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class DownloadProgress(val bytes: Long, val totalBytes: Long?)

internal suspend fun downloadCameraResource(
    http: CameraHttp,
    resource: CameraResource,
    directory: File,
    onProgress: suspend (DownloadProgress) -> Unit,
    durable: Boolean = false,
): File = withContext(Dispatchers.IO) {
    check(directory.isDirectory || directory.mkdirs()) { "无法创建传输目录" }
    val key = MessageDigest.getInstance("SHA-256").digest(resource.uri.toString().toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val partial = File(directory, "$key.part")
    val validatorFile = File(directory, "$key.validator")
    val validator = validatorFile.takeIf { it.exists() }?.readText()?.takeIf {
        it.length <= 1024 && it.none { char -> char == '\r' || char == '\n' }
    }
    // Resume only with an HTTP validator. A camera may reuse a URL for a newer photo.
    // DIDL res@size is catalogue metadata, not an HTTP framing guarantee. Some
    // cameras report zero or a stale estimate, including for a generated JPEG.
    val resume = if (!validator.isNullOrBlank() && partial.exists()) partial.length() else 0L
    val connection = http.open(resource.uri)
    try {
        if (resume > 0) {
            connection.setRequestProperty("Range", "bytes=$resume-")
            connection.setRequestProperty("If-Range", validator)
        }
        currentCoroutineContext().ensureActive()
        val code = connection.responseCode
        CameraConnectionLog.info("LFMDownload", "response status=$code resumeBytes=$resume catalogueBytes=${resource.bytes}")
        if (code == 416 && resume > 0) {
            // An interrupted task may already have received the last byte; fetch afresh once.
            check(!partial.exists() || partial.delete())
            validatorFile.delete()
            http.release(connection)
            return@withContext downloadCameraResource(http, resource, directory, onProgress, durable)
        }
        if (code != 200 && code != 206) throw CameraProtocolException("下载 HTTP $code")
        var start = 0L
        var total: Long? = null
        val contentLength = connection.contentLengthLong.takeIf { it >= 0 }
        if (code == 206) {
            val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(
                connection.getHeaderField("Content-Range").orEmpty().trim())
                ?: throw CameraProtocolException("无效的下载 Content-Range")
            start = range.groupValues[1].toLong()
            val end = range.groupValues[2].toLong()
            val remoteTotal = range.groupValues[3].toLong()
            require(start == resume && end >= start && end == remoteTotal - 1) { "相机返回了不同的下载区间" }
            require(contentLength == null || contentLength == remoteTotal - start) { "相机下载区间与响应长度不一致" }
            total = remoteTotal
        } else {
            total = contentLength
        }
        val nextValidator = (connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
            ?: connection.getHeaderField("Last-Modified"))?.takeIf {
                it.isNotBlank() && it.length <= 1024 && it.none { char -> char == '\r' || char == '\n' }
            }
        require(start == 0L || nextValidator == null || nextValidator == validator) { "相机下载文件已变化，请重新下载" }
        val expected = total
        CameraConnectionLog.info("LFMDownload", "framing=${if (code == 206) "range" else if (expected != null) "content-length" else "stream"} " +
            "expectedBytes=$expected catalogueMismatch=${expected != null && resource.bytes != null && expected != resource.bytes}")
        var received = start
        connection.inputStream.use { input ->
            FileOutputStream(partial, start > 0).use { output ->
                // On HTTP 200 the old partial is truncated BEFORE its validator is replaced.
                if (nextValidator != null) validatorFile.writeText(nextValidator) else validatorFile.delete()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    received += count
                    require(expected == null || received <= expected) { "相机返回数据超过文件尺寸" }
                    onProgress(DownloadProgress(received, expected))
                }
                output.fd.sync()
            }
        }
        currentCoroutineContext().ensureActive()
        require(expected == null || received == expected) { "传输中断，文件尚未完整" }
        require(received > 0) { "相机返回空文件" }
        val finished = File(directory, if (durable) "complete.download" else "${UUID.randomUUID()}.download")
        require(!finished.exists()) { "已有完成的传输文件，请先恢复其导入" }
        check(partial.renameTo(finished)) { "无法保存完整的传输文件" }
        validatorFile.delete()
        CameraConnectionLog.info("LFMDownload", "completed bytes=$received catalogueMismatch=${resource.bytes != null && received != resource.bytes}")
        finished
    } catch (error: Exception) {
        // Type and byte counts only: no URI, file name, credentials or server reply.
        CameraConnectionLog.warn("LFMDownload", "failed type=${error.javaClass.simpleName} partialBytes=${partial.length()}")
        throw error
    } finally { http.release(connection) }
}
