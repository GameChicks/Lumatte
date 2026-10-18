package com.lumixfilmmirror.camera

import java.net.URI

/** Address returned by the selected BLE camera during this sending handshake. Not an identity proof. */
class CameraConnectionAddress internal constructor(val host: String) {
    init {
        require(Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}").matches(host)) {
            "相机返回的连接地址格式暂不支持"
        }
        CameraAddress.requireLocalHost(host)
    }
    internal val descriptionUri: URI get() = URI("http://$host:60606/Lumix/Server0/ddd")
}
