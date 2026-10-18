package com.lumixfilmmirror.camera

import java.util.Locale

internal data class CameraResourceDescription(val mimeType: String, val profile: String?, val originalName: String?)

/** Standard four fields plus the three-field compatibility branch found in Sync's parser. */
internal object CameraProtocolInfo {
    fun parse(value: String): CameraResourceDescription {
        val fields = fields(value)
        if (fields.size < 3) return CameraResourceDescription("", null, null)
        val mime: String
        val attributes: String
        if (fields.size == 4) {
            mime = fields[2].trim()
            attributes = fields[3]
        } else {
            val tail = fields[2].split(';', limit = 2)
            mime = tail[0].trim()
            attributes = tail.getOrNull(1).orEmpty()
        }
        val properties = properties(attributes)
        return CameraResourceDescription(mime, properties["PANASONIC.COM_PN"], properties["ORIGINALFILENAME"])
    }

    private fun fields(value: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var quote: Char? = null
        var previous: Char? = null
        for (index in value.indices) {
            val char = value[index]
            if (quote != null) {
                if (char == quote) {
                    var next = index + 1
                    while (next < value.length && value[next].isWhitespace()) next++
                    if (next == value.length || value[next] in ";:") quote = null
                }
            } else if ((char == '\'' || char == '"') && previous == '=') quote = char
            else if (char == ':') {
                result += value.substring(start, index)
                start = index + 1
                if (result.size == 3) break
            }
            if (!char.isWhitespace()) previous = char
        }
        result += value.substring(start)
        return result
    }

    private fun properties(value: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var offset = 0
        while (offset < value.length) {
            while (offset < value.length && (value[offset].isWhitespace() || value[offset] == ';')) offset++
            val keyStart = offset
            while (offset < value.length && value[offset] != '=' && value[offset] != ';') offset++
            if (offset == value.length) break
            if (value[offset] == ';') { offset++; continue }
            val key = value.substring(keyStart, offset).trim().uppercase(Locale.ROOT)
            offset++
            while (offset < value.length && value[offset].isWhitespace()) offset++
            val start = offset
            val quote = value.getOrNull(start)?.takeIf { it == '\'' || it == '"' }
            var end = start
            var quotedEnd: Int? = null
            if (quote != null) {
                end++
                while (end < value.length) {
                    if (value[end] == quote) {
                        var next = end + 1
                        while (next < value.length && value[next].isWhitespace()) next++
                        if (next == value.length || value[next] == ';') { quotedEnd = end; break }
                    }
                    end++
                }
            }
            if (quotedEnd != null) {
                result[key] = value.substring(start + 1, quotedEnd)
                offset = quotedEnd + 1
            } else if (quote != null) {
                // Keep an unfinished quoted value opaque instead of rescanning its suffix as new fields.
                result[key] = value.substring(start).trim()
                offset = value.length
            } else {
                end = value.indexOf(';', start).takeIf { it >= 0 } ?: value.length
                result[key] = value.substring(start, end).trim()
                offset = end
            }
        }
        return result
    }
}
