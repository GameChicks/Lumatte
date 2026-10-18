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
    val resume = if (!validator.isNullOrBlank() && partial.exists() &&
        (resource.bytes == null || partial.length() < resource.bytes)) partial.length() else 0L
    val connection = http.open(resource.uri)
    try {
        if (resume > 0) {
            connection.setRequestProperty("Range", "bytes=$resume-")
            connection.setRequestProperty("If-Range", validator)
        }
        currentCoroutineContext().ensureActive()
        val code = connection.responseCode
        if (code == 416 && resume > 0) {
            // An interrupted task may already have received the last byte; fetch afresh once.
            check(!partial.exists() || partial.delete())
            validatorFile.delete()
            http.release(connection)
            return@withContext downloadCameraResource(http, resource, directory, onProgress, durable)
        }
        if (code != 200 && code != 206) throw CameraProtocolException("下载 HTTP $code")
        var start = 0L
        var total = resource.bytes
        if (code == 206) {
            val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(
                connection.getHeaderField("Content-Range").orEmpty().trim())
                ?: throw CameraProtocolException("无效的下载 Content-Range")
            start = range.groupValues[1].toLong()
            val end = range.groupValues[2].toLong()
            val remoteTotal = range.groupValues[3].toLong()
            require(start == resume && end >= start && end == remoteTotal - 1) { "相机返回了不同的下载区间" }
            require(total == null || total == remoteTotal) { "相机文件尺寸已发生变化，请刷新列表" }
            total = remoteTotal
        } else {
            connection.contentLengthLong.takeIf { it >= 0 }?.let { length ->
                require(total == null || total == length) { "相机文件尺寸已发生变化，请刷新列表" }
                total = length
            }
        }
        val nextValidator = connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
            ?: connection.getHeaderField("Last-Modified")
        val expected = total
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
        finished
    } finally { http.release(connection) }
}
