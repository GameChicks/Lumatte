package com.lumixfilmmirror.media

import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque

/** Reads preview byte ranges only. No sensor, strip, CFA or demosaicing interface exists here. */
internal object RawJpegExtractor {
    const val MAX_PREVIEW_BYTES = 64L * 1024 * 1024
    data class Range(val offset: Long, val length: Long)

    fun candidates(file: File): List<Range> = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 8) return@use emptyList()
        val first = input.readUnsignedShort()
        val little = when (first) { 0x4949 -> true; 0x4d4d -> false; else -> return@use emptyList() }
        fun short(): Int {
            val a = input.readUnsignedByte(); val b = input.readUnsignedByte()
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun long(): Long {
            val a = short().toLong(); val b = short().toLong()
            return if (little) a or (b shl 16) else (a shl 16) or b
        }
        val magic = short()
        if (magic != 42 && magic != 85) return@use emptyList() // TIFF / Panasonic RW2.
        val queue = ArrayDeque<Long>().apply { add(long()) }
        val visited = mutableSetOf<Long>()
        val found = mutableListOf<Range>()
        fun valid(offset: Long, count: Long) = offset >= 0 && count > 0 && offset <= input.length() - count
        fun values(type: Int, count: Long, field: Long): List<Long> {
            if (count !in 1..32 || type !in listOf(3, 4, 13)) return emptyList()
            val bytes = count * if (type == 3) 2 else 4
            val position = if (bytes <= 4) field else {
                input.seek(field); long()
            }
            if (!valid(position, bytes)) return emptyList()
            input.seek(position)
            return List(count.toInt()) { if (type == 3) short().toLong() else long() }
        }
        while (queue.isNotEmpty() && visited.size < 64) {
            val offset = queue.removeFirst()
            if (offset == 0L || !visited.add(offset) || !valid(offset, 2)) continue
            input.seek(offset)
            val count = short()
            if (!valid(offset + 2, count * 12L + 4)) continue
            var jpegOffset: Long? = null
            var jpegLength: Long? = null
            for (i in 0 until count) {
                val entry = offset + 2 + i * 12L
                input.seek(entry)
                val tag = short(); val type = short(); val amount = long()
                val field = entry + 8
                when (tag) {
                    0x002e -> if (type == 7 || type == 1) {
                        input.seek(field)
                        val start = if (amount <= 4) field else long()
                        if (valid(start, amount)) found += Range(start, amount)
                    }
                    0x0201 -> jpegOffset = values(type, amount, field).firstOrNull()
                    0x0202 -> jpegLength = values(type, amount, field).firstOrNull()
                    0x014a -> queue.addAll(values(type, amount, field)) // SubIFDs; no image strips read.
                }
            }
            if (jpegOffset != null && jpegLength != null && valid(jpegOffset, jpegLength)) {
                found += Range(jpegOffset, jpegLength)
            }
            input.seek(offset + 2 + count * 12L)
            val next = long()
            if (next != 0L) queue.add(next)
        }
        found.distinct().filter { range ->
            input.seek(range.offset)
            range.length in 4..MAX_PREVIEW_BYTES && input.readUnsignedShort() == 0xffd8
        }
    }

    fun extract(inputFile: File, range: Range, output: File, checkActive: () -> Unit = {}) {
        RandomAccessFile(inputFile, "r").use { input ->
            input.seek(range.offset)
            output.outputStream().buffered().use { stream ->
                val buffer = ByteArray(64 * 1024)
                var remaining = range.length
                while (remaining > 0) {
                    checkActive()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(read > 0) { "RAW 内嵌 JPEG 数据不完整" }
                    stream.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }
}
