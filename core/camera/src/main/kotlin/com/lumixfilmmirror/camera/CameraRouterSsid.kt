package com.lumixfilmmirror.camera

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Literal user input, without Android's quoted-SSID wrapper or Unicode normalization. */
class CameraRouterSsid private constructor(val value: String) {
    companion object {
        fun parse(value: String): CameraRouterSsid {
            CameraSsidCodec.validate(value)
            return CameraRouterSsid(value)
        }
    }

    internal fun bluetoothBytes(): ByteArray {
        val bytes = CameraSsidCodec.encode(value)
        // C5119d pads String.length (UTF-16 units) to 32 before UTF-8 encoding.
        // Multibyte names therefore have a payload longer than 32 bytes.
        return bytes.copyOf(bytes.size + 32 - value.length)
    }
}

internal object CameraSsidCodec {
    fun validate(value: String) {
        require(value.isNotBlank() && value.none { Character.isISOControl(it) }) {
            "Wi-Fi 名称不能为空或含换行等控制字符，请按原名称输入"
        }
        require(value.length <= 32) { "Wi-Fi 名称最多 32 字节，请勿缩短或截断原名称" }
        require(encode(value).size in 1..32) { "Wi-Fi 名称最多 32 字节；中文等字符会占多个字节，请勿缩短或截断原名称" }
    }

    fun encode(value: String): ByteArray = try {
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("Wi-Fi 名称含不完整字符，请重新输入", error)
    }

    fun decode(bytes: ByteArray): String {
        val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
        require(end in 1..32) { "相机返回的 Wi-Fi 名称长度暂不支持" }
        val value = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, end)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("相机返回的 Wi-Fi 名称不是完整 UTF-8，请使用手动连接入口", error)
        }
        validate(value)
        return value
    }
}
