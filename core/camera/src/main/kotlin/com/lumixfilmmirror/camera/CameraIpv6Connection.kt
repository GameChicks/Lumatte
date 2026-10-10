package com.lumixfilmmirror.camera

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Small adapter for the APIs used by camera XML/downloads; IPv4 retains Android's transport. */
internal class CameraIpv6Connection(
    private val uri: URI,
    private val client: OkHttpClient,
    private val hostHeader: String,
) : HttpURLConnection(uri.toURL()) {
    private val headers = linkedMapOf<String, String>()
    private val body = ByteArrayOutputStream()
    @Volatile private var call: Call? = null
    @Volatile private var response: Response? = null
    @Volatile private var disconnected = false

    override fun setRequestProperty(key: String, value: String) = synchronized(this) {
        check(!connected && !disconnected) { "相机请求已开始或已关闭" }
        headers.keys.firstOrNull { it.equals(key, ignoreCase = true) }?.let(headers::remove)
        headers[key] = value
    }

    override fun getOutputStream(): OutputStream {
        check(doOutput && !connected && !disconnected) { "相机请求不能写入" }
        return object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, count: Int) = synchronized(this@CameraIpv6Connection) {
                check(!connected && !disconnected) { "相机请求已关闭" }
                require(count >= 0 && body.size().toLong() + count <= 16L * 1024 * 1024) { "相机请求过大" }
                body.write(bytes, offset, count)
            }
        }
    }

    override fun connect() {
        val pending = synchronized(this) {
            check(!disconnected) { "相机请求已关闭" }
            if (connected) return
            val content = if (doOutput) body.toByteArray() else null
            if (content != null) {
                val expected = if (fixedContentLengthLong >= 0) fixedContentLengthLong else fixedContentLength.toLong()
                require(expected < 0 || expected == content.size.toLong()) { "相机请求长度不一致" }
            }
            val wireUri = CameraAddress.withAuthority(uri, ROUTE_HOST)
            val request = Request.Builder().url(wireUri.toString()).apply {
                headers.forEach { (name, value) -> header(name, value) }
                // The synthetic route is never sent in Host or resolved through system DNS.
                header("Host", hostHeader)
                method(requestMethod, content?.toRequestBody())
            }.build()
            val sent = AtomicBoolean(false)
            val operation = client.newBuilder()
                .connectTimeout(connectTimeout.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)
                .addNetworkInterceptor { chain ->
                    // OkHttp may follow a 503 Retry-After: 0 even with failure retries disabled.
                    // Camera GET commands may change state; never send one twice inside this call.
                    if (!sent.compareAndSet(false, true)) throw IOException("相机请求未自动重试，请按现有队列处理")
                    chain.proceed(chain.request())
                }
                .build().newCall(request)
            call = operation
            connected = true
            body.reset()
            operation
        }
        val result = pending.execute()
        synchronized(this) {
            if (disconnected) { result.close(); throw IOException("相机请求已关闭") }
            response = result
        }
    }

    private fun reply(): Response {
        connect()
        return response ?: throw IOException("相机响应尚未取得")
    }

    override fun getResponseCode(): Int = reply().code
    override fun getContentLengthLong(): Long = reply().body?.contentLength() ?: -1
    override fun getHeaderField(name: String?): String? = if (name == null) null else reply().header(name)
    override fun getInputStream(): InputStream {
        val result = reply()
        if (result.code >= 400) throw FileNotFoundException("相机 HTTP ${result.code}")
        return result.body?.byteStream() ?: throw IOException("相机返回空响应")
    }

    override fun disconnect() {
        synchronized(this) { disconnected = true; body.reset() }
        call?.cancel()
        response?.close()
    }

    override fun usingProxy(): Boolean = false

    companion object { const val ROUTE_HOST = "camera.invalid" }
}
