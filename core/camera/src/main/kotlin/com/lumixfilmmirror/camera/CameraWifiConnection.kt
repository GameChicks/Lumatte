package com.lumixfilmmirror.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
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
        onLost: (Network) -> Unit): CameraWifiConnection {
        require(ssid.isNotBlank() && ssid.toByteArray(Charsets.UTF_8).size <= 32) { "请输入相机显示的 Wi-Fi 名称" }
        require(password.isEmpty() || (password.length in 8..63 && password.all { it.code in 32..126 })) {
            "Wi-Fi 密码需要 8–63 位英文字符、数字或符号，请按相机屏幕输入"
        }
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (password.isNotEmpty()) setWpa2Passphrase(password)
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
                        java.io.IOException("未能连接相机 Wi-Fi，请检查名称、密码或系统连接确认"))
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
