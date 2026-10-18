package com.lumixfilmmirror.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.MulticastSocket
import java.net.InetSocketAddress
import java.net.URI
import java.net.SocketTimeoutException
import java.util.Locale

data class DiscoveredCamera(val network: Network, val device: CameraDevice, val networkInterfaceName: String? = null) {
    val key: String get() = "${network.networkHandle}:${device.udn.ifBlank { device.descriptionUri.toString() }}"
}

/** A current, process-local network choice. Does not infer or read a router SSID. */
data class CameraWifiNetwork(val network: Network, val localIpv4: List<String>, val isSystemDefault: Boolean,
    val localIpv6: List<String> = emptyList()) {
    val key: Long get() = network.networkHandle
}

/** SSDP searches only the selected Wi-Fi networks, and reads descriptions before offering a camera. */
class CameraDiscovery(context: Context) {
    private val context = context.applicationContext
    private val manager = requireNotNull(this.context.getSystemService(ConnectivityManager::class.java))

    fun wifiNetworks(): List<Network> = manager.allNetworks.filter { network ->
        val capabilities = manager.getNetworkCapabilities(network)
        capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun wifiOptions(): List<CameraWifiNetwork> {
        val systemDefault = manager.activeNetwork
        return wifiNetworks().distinct().sortedBy { it.networkHandle }.map { network ->
            val addresses = manager.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
            CameraWifiNetwork(network, addresses.mapNotNull { (it as? Inet4Address)?.hostAddress }.distinct(),
                network == systemDefault, addresses.mapNotNull { (it as? Inet6Address)?.hostAddress }.distinct())
        }
    }

    suspend fun discover(networks: List<Network> = wifiNetworks()): List<DiscoveredCamera> = coroutineScope {
        CameraConnectionLog.info("LFMCamera", "discovery=start selectedNetworks=${networks.size}")
        require(networks.isNotEmpty()) { "请先连接相机 Wi-Fi，或使用下方的连接入口" }
        val attempts = networks.map { network -> async(Dispatchers.IO) {
            try { Result.success(search(network)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { Result.failure(error) }
        } }.awaitAll()
        val found = attempts.flatMap { it.getOrNull().orEmpty() }.distinctBy { it.key }
        CameraConnectionLog.info("LFMCamera", "discovery=finished found=${found.size} failedNetworks=${attempts.count { it.isFailure }}")
        if (found.isEmpty() && attempts.all { it.isFailure }) throw requireNotNull(attempts.first().exceptionOrNull())
        found
    }

    /** Direct description read on the selected network. No multicast, subnet scan or gateway guess. */
    suspend fun discoverAt(network: Network, address: CameraConnectionAddress): DiscoveredCamera? =
        withContext(Dispatchers.IO) { readDescription(network, address.descriptionUri) }

    private suspend fun readDescription(network: Network, uri: URI): DiscoveredCamera? {
        var http: CameraHttp? = null
        try {
            val selectedInterface = networkInterface(network)
            val description = CameraAddress.onInterface(uri, selectedInterface)
            val transport = CameraHttp(network, description.host, selectedInterface)
            http = transport
            val device = CameraXml.device(transport.text(description), description)
            transport.validate(device.contentDirectory)
            return DiscoveredCamera(network, device, selectedInterface?.name)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return null }
        finally { http?.close() }
    }

    private suspend fun search(network: Network): List<DiscoveredCamera> = withContext(Dispatchers.IO) {
        val descriptions = linkedSetOf<URI>()
        val selectedInterface = networkInterface(network)
        val wifi = requireNotNull(context.getSystemService(WifiManager::class.java)) { "设备没有可用的 Wi-Fi 服务" }
        val lock = wifi.createMulticastLock("film-mirror-camera-discovery").apply { setReferenceCounted(false) }
        try {
            lock.acquire()
            coroutineScope {
                val ipv4 = async { multicastDescriptions(network, selectedInterface, false) }
                val ipv6 = async { multicastDescriptions(network, selectedInterface, true) }
                descriptions += ipv4.await()
                descriptions += ipv6.await()
            }
        } catch (_: java.io.IOException) {
            // A device/network may suppress multicast; still try its directly advertised local gateway.
        } finally { if (lock.isHeld) lock.release() }
        // Direct-link cameras may not answer multicast. Probe only advertised local gateway/default camera addresses.
        manager.getLinkProperties(network)?.routes?.mapNotNull { it.gateway?.hostAddress }?.forEach { host ->
            if (runCatching { CameraAddress.requireLocalHost(host) }.isSuccess) {
                runCatching { CameraAddress.onInterface(CameraAddress.description(host), selectedInterface) }
                    .getOrNull()?.let { descriptions += it }
            }
        }
        if (manager.getLinkProperties(network)?.linkAddresses?.any {
                it.address.hostAddress?.startsWith("192.168.54.") == true
            } == true) descriptions += URI("http://192.168.54.1:60606/Lumix/Server0/ddd")
        descriptions.mapNotNull { uri ->
            currentCoroutineContext().ensureActive()
            readDescription(network, uri)
        }
    }

    private fun networkInterface(network: Network): NetworkInterface? = try {
        manager.getLinkProperties(network)?.interfaceName?.let { name -> NetworkInterface.getByName(name) }
    } catch (_: java.net.SocketException) { null }

    private suspend fun multicastDescriptions(network: Network, selectedInterface: NetworkInterface?, ipv6: Boolean): List<URI> {
        val found = linkedSetOf<URI>()
        val source = if (ipv6) manager.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.filterIsInstance<Inet6Address>()?.firstOrNull { it.isLinkLocalAddress } else null
        if (ipv6 && (selectedInterface == null || selectedInterface.index <= 0 || source == null)) return emptyList()
        try {
            val socket = if (ipv6) MulticastSocket(null).apply {
                try {
                    val localAddress = Inet6Address.getByAddress(null, requireNotNull(source).address,
                        requireNotNull(selectedInterface).index)
                    bind(InetSocketAddress(localAddress, 0))
                    network.bindSocket(this)
                    networkInterface = requireNotNull(selectedInterface)
                    timeToLive = 1
                } catch (error: Exception) { close(); throw error }
            } else DatagramSocket().apply {
                try { network.bindSocket(this) } catch (error: Exception) { close(); throw error }
            }
            socket.use {
                socket.soTimeout = 250
                val host = if (ipv6) "[FF02::C]:1900" else "239.255.255.250:1900"
                val message = ("M-SEARCH * HTTP/1.1\r\nHOST: $host\r\n" +
                    "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII)
                val group = if (ipv6) Inet6Address.getByAddress(null,
                    byteArrayOf(0xff.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 12),
                    requireNotNull(selectedInterface).index) else
                    InetAddress.getByAddress(byteArrayOf(239.toByte(), 255.toByte(), 255.toByte(), 250.toByte()))
                val request = DatagramPacket(message, message.size, group, 1900)
                socket.send(request)
                val end = SystemClock.elapsedRealtime() + 3_000
                var repeated = false
                while (SystemClock.elapsedRealtime() < end) {
                    currentCoroutineContext().ensureActive()
                    if (!repeated && end - SystemClock.elapsedRealtime() < 1_500) {
                        socket.send(request)
                        repeated = true
                    }
                    val response = DatagramPacket(ByteArray(16 * 1024), 16 * 1024)
                    try { socket.receive(response) } catch (_: SocketTimeoutException) { continue }
                    descriptionLocation(response, selectedInterface)?.let { found += it }
                }
            }
        } catch (_: java.io.IOException) {
            // Each family may be unavailable independently. Gateway fallback remains available.
        }
        return found.toList()
    }

    private fun descriptionLocation(packet: DatagramPacket, selectedInterface: NetworkInterface?): URI? = runCatching {
        val lines = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).split("\r\n")
        require(Regex("^HTTP/1\\.[01]\\s+200(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(lines.first()))
        val fields = lines.drop(1).mapNotNull { line ->
            val split = line.indexOf(':')
            if (split > 0) line.substring(0, split).lowercase(Locale.ROOT) to line.substring(split + 1).trim() else null
        }.toMap()
        require(fields["st"] == "urn:schemas-upnp-org:device:MediaServer:1")
        val uri = URI(requireNotNull(fields["location"]))
        CameraAddress.requireDescription(uri)
        require(CameraAddress.literal(uri.host).bytes.contentEquals(packet.address.address))
        CameraAddress.onInterface(uri, selectedInterface)
    }.getOrNull()
}
