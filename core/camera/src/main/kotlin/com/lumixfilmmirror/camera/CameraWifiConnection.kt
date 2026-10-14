package com.lumixfilmmirror.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/** Retains the system network request for the whole camera session. Closing releases only our request. */
class CameraWifiConnection internal constructor(val network: Network, private val release: () -> Unit) : Closeable {
    override fun close() = release()
}

object CameraWifiConnector {
    fun supportsWpa3(context: Context): Boolean =
        context.applicationContext.getSystemService(WifiManager::class.java)?.isWpa3SaeSupported == true

    /** Validate before releasing an existing connection, and again before constructing a request. */
    fun validateRequest(context: Context, ssid: String, password: String, security: CameraWifiSecurity) {
        CameraSsidCodec.validate(ssid)
        security.passwordProblem(password)?.let { throw IllegalArgumentException(it) }
        check(security != CameraWifiSecurity.WPA3_PERSONAL || supportsWpa3(context)) {
            "此手机当前无法请求 WPA3 网络；若已在系统设置连接，可使用查找相机入口"
        }
    }

    /** Observes an existing network; never requests a replacement or changes system routing. */
    fun useExisting(context: Context, network: Network, onLost: (Network) -> Unit): CameraWifiConnection {
        val manager = requireNotNull(context.applicationContext.getSystemService(ConnectivityManager::class.java))
        fun available() = manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        check(available()) { "所选 Wi-Fi 已断开，请先在系统设置中连接路由器" }
        val watcher = watch(context, network) { onLost(network) }
        if (!available()) { watcher.close(); error("所选 Wi-Fi 已断开，请重新连接") }
        return CameraWifiConnection(network) { watcher.close() }
    }

    suspend fun connect(context: Context, ssid: String, password: String,
        security: CameraWifiSecurity = CameraWifiSecurity.legacy(password),
        onLost: (Network) -> Unit): CameraWifiConnection {
        validateRequest(context, ssid, password, security)
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            when (security) {
                CameraWifiSecurity.WPA2_PERSONAL -> setWpa2Passphrase(password)
                CameraWifiSecurity.WPA3_PERSONAL -> setWpa3Passphrase(password)
                CameraWifiSecurity.OPEN -> Unit
            }
        }.build()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
        val manager = requireNotNull(context.applicationContext.getSystemService(ConnectivityManager::class.java))
        val handler = Handler(Looper.getMainLooper())
        val startedAt = SystemClock.elapsedRealtime()
        CameraConnectionLog.info("LFMWifi", "requestStarted systemTimeoutMs=90000 addressTimeoutMs=105000 security=$security")
        try {
            // S5 confirmation in the supplied successful trace took about 63 s. Keep the
            // system selection request alive across dialogs, but retain a bounded deadline.
            return withTimeout(105_000L) {
                suspendCancellableCoroutine { continuation ->
                    val released = AtomicBoolean(false)
                    val delivered = AtomicBoolean(false)
                    var offeredNetwork: Network? = null
                    var wifiReady = false
                    var addressReady = false
                    var registered = false
                    lateinit var callback: ConnectivityManager.NetworkCallback
                    fun release() {
                        if (released.compareAndSet(false, true)) handler.post {
                            if (registered) {
                                runCatching { manager.unregisterNetworkCallback(callback) }
                                registered = false
                            }
                        }
                    }
                    fun deliverReadyNetwork() {
                        val network = offeredNetwork ?: return
                        if (!released.get() && wifiReady && addressReady && delivered.compareAndSet(false, true)) {
                            CameraConnectionLog.info("LFMWifi", "networkReady elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                            val connection = CameraWifiConnection(network, ::release)
                            continuation.resume(connection, onCancellation = { _, value, _ -> value.close() })
                        }
                    }
                    callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (released.get()) return
                            if (delivered.get()) {
                                // The caller owns one exact Network; never switch it behind an active session.
                                offeredNetwork?.takeIf { it != network }?.let(onLost)
                                return
                            }
                            // onAvailable alone does not guarantee capabilities or IP configuration.
                            offeredNetwork = network
                            wifiReady = false
                            addressReady = false
                        }
                        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                            if (network != offeredNetwork) return
                            wifiReady = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            deliverReadyNetwork()
                        }
                        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                            if (network != offeredNetwork) return
                            addressReady = properties.linkAddresses.any { !it.address.isAnyLocalAddress && !it.address.isLoopbackAddress }
                            deliverReadyNetwork()
                        }
                        override fun onUnavailable() {
                            CameraConnectionLog.warn("LFMWifi", "networkUnavailable elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                            if (delivered.compareAndSet(false, true)) continuation.resumeWithException(
                                java.io.IOException("未能连接相机 Wi-Fi，请检查名称、安全类型、密码或系统连接确认"))
                            release()
                        }
                        override fun onLost(network: Network) {
                            if (!released.get() && network == offeredNetwork) {
                                CameraConnectionLog.warn("LFMWifi", "requestedNetwork=lost")
                                if (delivered.compareAndSet(false, true)) {
                                    continuation.resumeWithException(java.io.IOException("相机 Wi-Fi 在网络地址准备完成前已断开，请重新连接"))
                                    release()
                                } else onLost(network)
                            }
                        }
                    }
                    continuation.invokeOnCancellation { release() }
                    handler.post {
                        if (!released.get() && continuation.isActive) {
                            try {
                                registered = true
                                manager.requestNetwork(request, callback, handler, 90_000)
                            } catch (error: Exception) {
                                if (delivered.compareAndSet(false, true)) continuation.resumeWithException(error)
                                release()
                            }
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw java.io.IOException("相机 Wi-Fi 尚未准备好网络地址，请确认系统连接弹窗后重试")
        }
    }

    fun watch(context: Context, network: Network, onLost: () -> Unit): Closeable {
        val manager = requireNotNull(context.applicationContext.getSystemService(ConnectivityManager::class.java))
        val handler = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) { if (!closed.get() && lost == network) onLost() }
        }
        manager.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback, handler)
        return Closeable {
            if (closed.compareAndSet(false, true)) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}
