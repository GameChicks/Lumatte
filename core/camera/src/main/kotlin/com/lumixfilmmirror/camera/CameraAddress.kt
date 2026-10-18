package com.lumixfilmmirror.camera

import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.URI

internal object CameraAddress {
    class Literal(val bytes: ByteArray, val scope: String? = null) {
        val ipv6: Boolean get() = bytes.size == 16
        val linkLocal: Boolean get() = ipv6 && (bytes[0].toInt() and 255) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80
        val numeric: String get() = if (ipv6) (0 until 8).joinToString(":") { index ->
            (((bytes[index * 2].toInt() and 255) shl 8) or (bytes[index * 2 + 1].toInt() and 255)).toString(16)
        } else bytes.joinToString(".") { (it.toInt() and 255).toString() }
        val httpHost: String get() = if (ipv6) "[$numeric]" else numeric

        fun address(selectedInterface: NetworkInterface?): InetAddress {
            if (!ipv6) return InetAddress.getByAddress(bytes)
            if (linkLocal || scope != null) {
                require(selectedInterface != null && selectedInterface.index > 0) { "相机 IPv6 地址缺少所选 Wi-Fi 接口" }
                require(scope == null || scope == selectedInterface.name || scope == selectedInterface.index.toString()) {
                    "相机 IPv6 地址不属于所选 Wi-Fi 接口"
                }
            }
            return Inet6Address.getByAddress(null, bytes, if (linkLocal || scope != null) requireNotNull(selectedInterface).index else 0)
        }
    }

    /** Never resolves a hostname. Bracketed URI hosts retain the RFC 6874 %25 scope escape. */
    fun literal(host: String): Literal {
        require(host.length in 1..128) { "相机地址长度无效" }
        val bracketed = host.startsWith('[')
        require(bracketed == host.endsWith(']')) { "相机地址括号无效" }
        val text = if (bracketed) host.substring(1, host.lastIndex) else host
        if (':' !in text) {
            val fields = text.split('.')
            require(fields.size == 4 && fields.all { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }) {
                "相机地址必须是局域网 IP 字面量"
            }
            val octets = fields.map { requireNotNull(it.toIntOrNull()).also { value -> require(value in 0..255) } }
            val bytes = octets.map { it.toByte() }.toByteArray()
            val address = InetAddress.getByAddress(bytes)
            require(address.isSiteLocalAddress || address.isLinkLocalAddress) { "相机地址不在局域网内" }
            return Literal(bytes)
        }
        val marker = text.indexOf('%')
        val ip = if (marker >= 0) text.substring(0, marker) else text
        val scope = if (marker >= 0) {
            val start = marker + if (bracketed && text.startsWith("%25", marker)) 3 else 1
            text.substring(start).also { value ->
                require(value.isNotEmpty() && value.length <= 64 && value.all {
                    (it.isLetterOrDigit() && it.code < 128) || it in "_.-"
                }) { "相机 IPv6 接口标识无效" }
            }
        } else null
        val halves = ip.split("::")
        require(halves.size in 1..2) { "相机 IPv6 地址无效" }
        fun groups(value: String): List<Int> = if (value.isEmpty()) emptyList() else value.split(':').map { group ->
            require(group.length in 1..4 && group.all { it in "0123456789abcdefABCDEF" }) { "相机 IPv6 地址无效" }
            group.toInt(16)
        }
        val left = groups(halves[0])
        val right = if (halves.size == 2) groups(halves[1]) else emptyList()
        val count = left.size + right.size
        require(if (halves.size == 2) count < 8 else count == 8) { "相机 IPv6 地址无效" }
        val words = left + List(8 - count) { 0 } + right
        val bytes = words.flatMap { listOf((it ushr 8).toByte(), it.toByte()) }.toByteArray()
        val local = (bytes[0].toInt() and 0xfe) == 0xfc ||
            (bytes[0].toInt() and 255) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80
        require(local) { "相机 IPv6 地址必须是 ULA 或链路本地地址" }
        return Literal(bytes, scope)
    }
    fun requireLocalHost(host: String) {
        literal(host)
    }

    fun requireDescription(uri: URI) {
        require(uri.scheme == "http" && uri.userInfo == null && uri.fragment == null) { "相机描述地址无效" }
        requireLocalHost(requireNotNull(uri.host) { "缺少相机地址" })
        require(uri.port == -1 || uri.port in 1..65535)
    }

    fun description(host: String): URI {
        val value = literal(host)
        val authority = if (value.ipv6) "[${value.numeric}${value.scope?.let { "%25$it" }.orEmpty()}]" else value.numeric
        return URI("http://$authority:60606/Lumix/Server0/ddd")
    }

    fun onInterface(uri: URI, selectedInterface: NetworkInterface?): URI {
        requireDescription(uri)
        val value = literal(uri.host)
        value.address(selectedInterface)
        val authority = if (value.ipv6) "[${value.numeric}${if (value.linkLocal) "%25${requireNotNull(selectedInterface).index}" else ""}]"
            else value.numeric
        return withAuthority(uri, authority)
    }

    /** Preserve raw path/query escapes while changing only the already validated authority. */
    fun withAuthority(uri: URI, host: String): URI = URI("http://$host${if (uri.port >= 0) ":${uri.port}" else ""}" +
        uri.rawPath.orEmpty() + uri.rawQuery?.let { "?$it" }.orEmpty())
}
