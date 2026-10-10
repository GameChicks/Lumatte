package com.lumixfilmmirror.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
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
        return suspendCancellableCoroutine { continuation ->
            val released = AtomicBoolean(false)
            val delivered = AtomicBoolean(false)
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
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (delivered.compareAndSet(false, true)) {
                        val connection = CameraWifiConnection(network, ::release)
                        continuation.resume(connection, onCancellation = { _, value, _ -> value.close() })
                    }
                }
                override fun onUnavailable() {
                    if (delivered.compareAndSet(false, true)) continuation.resumeWithException(
                        java.io.IOException("未能连接相机 Wi-Fi，请检查名称、安全类型、密码或系统连接确认"))
                    release()
                }
                override fun onLost(network: Network) {
                    if (!released.get()) onLost(network)
                }
            }
            continuation.invokeOnCancellation { release() }
            handler.post {
                if (!released.get() && continuation.isActive) {
                    try {
                        registered = true
                        manager.requestNetwork(request, callback, handler, 30_000)
                    } catch (error: Exception) {
                        if (delivered.compareAndSet(false, true)) continuation.resumeWithException(error)
                        release()
                    }
                }
            }
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
