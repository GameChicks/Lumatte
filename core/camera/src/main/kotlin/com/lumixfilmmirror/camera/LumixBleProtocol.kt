package com.lumixfilmmirror.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Identity, Wi-Fi connection and camera-initiated sending only. */
internal object LumixBleProtocol {
    val service: UUID = UUID.fromString("054ac620-3214-11e6-ac0d-0002a5d5c51b")
    val clientId: UUID = UUID.fromString("8d08a420-3213-11e6-8aca-0002a5d5c51b")
    val friendlyName: UUID = UUID.fromString("cd7a71a0-3213-11e6-8f56-0002a5d5c51b")
    val apsClientId: UUID = UUID.fromString("bb5d2a8c-5d3b-43ad-bfc3-470cad55c1ca")
    val apsFriendlyName: UUID = UUID.fromString("7b61774f-efb4-44c6-a66d-c69e3b384c4a")
    val challenge: UUID = UUID.fromString("03e23a31-a54c-40fa-a668-de9acdc910bb")
    val ssid: UUID = UUID.fromString("e206a5c0-3214-11e6-afe4-0002a5d5c51b")
    val password: UUID = UUID.fromString("c97cf1a5-3c03-4290-8c1b-9e74b9500f54")
    val wifiService: UUID = UUID.fromString("5ae57260-3214-11e6-979e-0002a5d5c51b")
    val wifiConnectionType: UUID = UUID.fromString("0d6f1880-3217-11e6-a4cb-0002a5d5c51b")
    val wifiChoiceReply: UUID = UUID.fromString("18345be1-3217-11e6-b56c-0002a5d5c51b")
    val routerSsid: UUID = UUID.fromString("18345be0-3217-11e6-b56c-0002a5d5c51b")
    val connectionMode: UUID = UUID.fromString("e182ec40-3213-11e6-ab07-0002a5d5c51b")
    val connectionStatus: UUID = UUID.fromString("e182ec41-3213-11e6-ab07-0002a5d5c51b")
    val sendingService: UUID = UUID.fromString("6b005f20-3214-11e6-a5ac-0002a5d5c51b")
    val connectionAddress: UUID = UUID.fromString("e1392720-3215-11e6-a035-0002a5d5c51b")
    val imageTransferEvent: UUID = UUID.fromString("48669da0-3217-11e6-b08d-0002a5d5c51b")
    val sendingModeEvent: UUID = UUID.fromString("e182ec43-3213-11e6-ab07-0002a5d5c51b")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    // Exactly 16 ASCII bytes: the recovered string encoder then has no zero-padding region.
    val appName: ByteArray get() = "Lumix FilmMirror".toByteArray(Charsets.US_ASCII)

    fun identity(id: String): ByteArray {
        val uuid = UUID.fromString(id)
        return ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    }

    fun challenge(bytes: ByteArray): Int {
        require(bytes.size >= 4) { "相机返回的蓝牙连接资料不完整" }
        return ByteBuffer.wrap(bytes).int
    }

    private fun key(challenge: Int): Int = if (challenge == 0) -1875740628 else challenge

    fun encode(bytes: ByteArray, challenge: Int): ByteArray {
        require(bytes.size <= 19)
        val plain = ByteArray(20) { 0xff.toByte() }
        plain[0] = bytes.size.toByte()
        bytes.copyInto(plain, 1)
        val input = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(20)
        repeat(5) { output.putInt(input.int xor key(challenge)) }
        plain.fill(0)
        return output.array()
    }

    fun decodePassword(bytes: ByteArray, challenge: Int): String {
        require(bytes.isNotEmpty() && bytes.size <= 512 && bytes.size % 4 == 0) { "相机返回的 Wi-Fi 资料格式暂不支持" }
        val input = ByteBuffer.wrap(bytes)
        val plain = ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        while (input.hasRemaining()) plain.putInt(input.int xor key(challenge))
        val decoded = plain.array()
        try {
            val end = (1 until decoded.size).firstOrNull { decoded[it] == 0xff.toByte() } ?: decoded.size
            // The source uses byte-to-character conversion and Java trim, including NUL padding.
            return (1 until end).joinToString("") { (decoded[it].toInt() and 255).toChar().toString() }.trim { it <= ' ' }
        } finally { decoded.fill(0) }
    }

    fun decodeSsid(bytes: ByteArray): String = CameraSsidCodec.decode(bytes)

    fun routerSsid(value: CameraRouterSsid): ByteArray = value.bluetoothBytes()

    fun decodeConnectionAddress(bytes: ByteArray): CameraConnectionAddress {
        require(bytes.isNotEmpty() && bytes.size <= 64 && bytes.all { (it.toInt() and 255) <= 127 }) {
            "相机返回的连接地址格式暂不支持"
        }
        // Reference stores UTF-8 text and passes Java-trimmed text to its HTTP client.
        return CameraConnectionAddress(bytes.toString(Charsets.UTF_8).trim { it <= ' ' })
    }
}
