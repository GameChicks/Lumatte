package com.lumixfilmmirror.camera

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Camera requests use a selected Wi-Fi Network, never bind the whole app process. */
internal class CameraHttp(private val network: Network, val host: String) {
    init { CameraAddress.requireLocalHost(host) }
    @Volatile private var sessionId: String? = null
    @Volatile private var closed = false
    private val active = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun setSession(value: String?) {
        require(value == null || (value.length <= 1024 && value.none { it == '\r' || it == '\n' }))
        sessionId = value?.takeIf { it.isNotBlank() }
    }

    fun validate(uri: URI) {
        require(uri.scheme == "http" && uri.host.equals(host, ignoreCase = true) && uri.userInfo == null) {
            "相机资源地址不属于当前相机"
        }
    }

    fun open(uri: URI): HttpURLConnection {
        check(!closed) { "相机会话已关闭，请重新连接" }
        validate(uri)
        return (network.openConnection(uri.toURL()) as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Panasonic MIL DLNA CP UPnP/1.0")
            setRequestProperty("Accept-Encoding", "identity")
            sessionId?.let { setRequestProperty("X-SESSION_ID", it) }
            active += this
            if (closed) { release(this); error("相机会话已关闭，请重新连接") }
        }
    }

    fun release(connection: HttpURLConnection) {
        active.remove(connection)
        connection.disconnect()
    }

    fun interrupt() { active.toList().forEach(::release) }
    fun close() { closed = true; setSession(null); interrupt() }

    suspend fun text(uri: URI, body: String? = null, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val connection = open(uri)
            try {
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                currentCoroutineContext().ensureActive()
                if (connection.responseCode !in 200..299) {
                    throw CameraProtocolException("HTTP ${connection.responseCode}")
                }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 16 * 1024 * 1024) { "相机 XML 响应过大" }
                        output.write(buffer, 0, count)
                    }
                }
                output.toString(Charsets.UTF_8.name())
            } finally { release(connection) }
        }

    suspend fun command(mode: String, vararg fields: Pair<String, String>): String {
        val query = (listOf("mode" to mode) + fields).joinToString("&") { (key, value) ->
            "$key=${encode(value)}"
        }
        return text(URI("http://$host/cam.cgi?$query"))
    }

    companion object {
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

        /** req_acc_e wire encoding: padded UTF-16 code units, challenge XOR, little-endian hex. */
        fun challengeEncode(value: String, challenge: Int): String {
            val padded = value.padEnd((value.length + 3) / 4 * 4, '\u00ff')
            val key = (if (challenge == 0) -1875740628 else challenge).toLong() and 0xffffffffL
            return padded.chunked(4).joinToString("") { block ->
                var word = 0L
                block.forEach { word = (word shl 8) or it.code.toLong() }
                word = word xor key
                val swapped = ((word and 255) shl 24) or ((word and 0xff00) shl 8) or
                    ((word ushr 8) and 0xff00) or ((word ushr 24) and 255)
                String.format(Locale.ROOT, "%08x", swapped)
            }
        }
    }
}
