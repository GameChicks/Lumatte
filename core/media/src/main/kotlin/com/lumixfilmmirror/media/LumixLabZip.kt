package com.lumixfilmmirror.media

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Bounded in-memory member selection; no extraction onto the filesystem. */
internal object LumixLabZip {
    fun cube(bytes: ByteArray, checkCancellation: () -> Unit): ByteArray {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte() ||
            bytes[2] != 3.toByte() || bytes[3] != 4.toByte()) {
            throw LumixLabProtocolException("Lumix Lab 未返回受支持的 ZIP 文件")
        }
        var cube: ByteArray? = null
        var members = 0
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { input ->
            val block = ByteArray(16 * 1024)
            while (true) {
                checkCancellation()
                val entry = input.nextEntry ?: break
                if (++members > 64) throw LumixLabProtocolException("LUT ZIP 成员过多")
                val name = entry.name
                if (name.isBlank() || name.length > 1024 || name.startsWith("/") ||
                    name.contains('\\') || name.contains(':') || name.any { it.code < 32 } ||
                    name.split('/').any { it == ".." || it == "." }) {
                    throw LumixLabProtocolException("LUT ZIP 成员名称无效")
                }
                val isCube = !entry.isDirectory && name.endsWith(".cube", ignoreCase = true)
                if (isCube && cube != null) throw LumixLabProtocolException("LUT ZIP 含多个 cube，无法明确选择")
                val output = if (isCube) ByteArrayOutputStream() else null
                // Read ignored members as well: closeEntry otherwise inflates them without our bounds/cancellation.
                while (true) {
                    checkCancellation()
                    val count = input.read(block)
                    if (count < 0) break
                    total += count
                    if (total > 16L * 1024 * 1024) throw LumixLabProtocolException("LUT ZIP 解压数据过大")
                    if (output != null) {
                        if (output.size() + count > 4 * 1024 * 1024) throw LumixLabProtocolException("下载的 cube 文件过大")
                        output.write(block, 0, count)
                    }
                }
                input.closeEntry() // Also completes the ZIP member CRC check.
                if (output != null) cube = output.toByteArray().takeIf { it.isNotEmpty() }
                    ?: throw LumixLabProtocolException("下载的 cube 文件为空")
            }
        }
        checkCancellation()
        return cube ?: throw LumixLabProtocolException("LUT ZIP 未含 cube 文件；尚不支持 VLT")
    }
}
