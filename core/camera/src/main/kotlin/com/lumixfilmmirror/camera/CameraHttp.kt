package com.lumixfilmmirror.camera

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.NetworkInterface
import java.net.Proxy
import java.net.UnknownHostException
import java.net.InetAddress
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.Dns
import java.util.concurrent.TimeUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Camera requests use a selected Wi-Fi Network, never bind the whole app process. */
internal class CameraHttp(private val network: Network, val host: String, private val selectedInterface: NetworkInterface? = null) {
    private val endpoint = CameraAddress.literal(host)
    private val address = endpoint.address(selectedInterface)
    private val ipv6Client = if (endpoint.ipv6) OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname != CameraIpv6Connection.ROUTE_HOST) throw UnknownHostException("相机请求不允许 DNS 查找")
                return listOf(address)
            }
        })
        .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build() else null
    @Volatile private var sessionId: String? = null
    @Volatile private var closed = false
    private val active = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun setSession(value: String?) {
        require(value == null || (value.length <= 1024 && value.none { it == '\r' || it == '\n' }))
        sessionId = value?.takeIf { it.isNotBlank() }
    }

    fun validate(uri: URI) {
        CameraAddress.requireDescription(uri)
        val target = CameraAddress.literal(uri.host)
        target.address(selectedInterface)
        require(target.bytes.contentEquals(endpoint.bytes)) {
            "相机资源地址不属于当前相机"
        }
    }

    fun open(uri: URI): HttpURLConnection {
        check(!closed) { "相机会话已关闭，请重新连接" }
        validate(uri)
        val transport = ipv6Client?.let { client ->
            val header = endpoint.httpHost + if (uri.port >= 0 && uri.port != 80) ":${uri.port}" else ""
            CameraIpv6Connection(uri, client, header)
        } ?: (network.openConnection(CameraAddress.withAuthority(uri, endpoint.httpHost).toURL()) as HttpURLConnection)
        return transport.apply {
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
    fun close() {
        closed = true; setSession(null); interrupt()
        ipv6Client?.dispatcher?.cancelAll()
        ipv6Client?.connectionPool?.evictAll()
    }

    /** A preview cancels its own socket, never the session's transfer/command sockets. */
    suspend fun thumbnailBytes(uri: URI, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        coroutineScope {
            val connection = open(uri)
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { release(connection) }
            }
            try {
                currentCoroutineContext().ensureActive()
                val status = connection.responseCode
                if (status != 200) {
                    CameraConnectionLog.warn("LFMHttp", "response operation=thumbnail status=$status")
                    throw CameraProtocolException("HTTP $status")
                }
                val expected = connection.contentLengthLong.takeIf { it >= 0 }
                require(expected == null || expected in 1..limit.toLong()) { "缩略图响应过大或为空" }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= limit) { "缩略图响应过大" }
                        output.write(buffer, 0, count)
                    }
                }
                currentCoroutineContext().ensureActive()
                require(output.size() > 0 && (expected == null || output.size().toLong() == expected)) {
                    "缩略图尚未完整"
                }
                output.toByteArray()
            } finally { cancellation.cancel(); release(connection) }
        }
    }

    suspend fun text(uri: URI, body: String? = null, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val connection = open(uri)
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { release(connection) }
                }
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
                    val status = connection.responseCode
                    CameraConnectionLog.info("LFMHttp", "response method=${if (body == null) "GET" else "POST"} status=$status")
                    if (status !in 200..299) {
                        throw CameraProtocolException("HTTP $status")
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
                } finally { cancellation.cancel(); release(connection) }
            }
        }

    suspend fun command(mode: String, vararg fields: Pair<String, String>): String {
        val query = (listOf("mode" to mode) + fields).joinToString("&") { (key, value) ->
            "$key=${encode(value)}"
        }
        return text(URI("http://${endpoint.httpHost}/cam.cgi?$query"))
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
