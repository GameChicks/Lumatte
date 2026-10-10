package com.lumixfilmmirror.camera

import java.net.InetAddress
import java.net.URI

internal object CameraAddress {
    fun requireLocalHost(host: String) {
        val bytes = host.split('.').map { it.toIntOrNull() }
        require(bytes.size == 4 && bytes.all { it != null && it in 0..255 }) { "相机地址必须是局域网 IPv4 地址" }
        val address = InetAddress.getByAddress(bytes.map { requireNotNull(it).toByte() }.toByteArray())
        require(address.isSiteLocalAddress || address.isLinkLocalAddress) { "相机地址不在局域网内" }
    }

    fun requireDescription(uri: URI) {
        require(uri.scheme == "http" && uri.userInfo == null && uri.fragment == null) { "相机描述地址无效" }
        requireLocalHost(requireNotNull(uri.host) { "缺少相机地址" })
        require(uri.port == -1 || uri.port in 1..65535)
    }
}
