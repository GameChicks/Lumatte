package com.lumixfilmmirror.media

import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.StringReader
import kotlin.math.floor

/** Encoded RGB table, red-fastest indexing. 1D shaper, 3D table, or shaper then 3D. */
internal class CubeLut(val title: String, private val oneSize: Int, private val threeSize: Int,
    private val one: FloatArray, private val three: FloatArray,
    private val oneMin: FloatArray, private val oneMax: FloatArray,
    private val threeMin: FloatArray, private val threeMax: FloatArray) {
    internal val nativeData by lazy {
        NativeCubeData(oneSize, threeSize, one, three,
            oneMin + oneMax + threeMin + threeMax)
    }
    fun named(name: String) = CubeLut(name.take(200), oneSize, threeSize, one, three, oneMin, oneMax, threeMin, threeMax)
    fun apply(input: FloatArray, result: FloatArray) {
        for (channel in 0..2) {
            val value = input[channel]
            result[channel] = if (oneSize == 0) value else {
                val coordinate = coordinate(value, oneMin[channel], oneMax[channel], oneSize)
                val lower = floor(coordinate).toInt()
                val upper = minOf(lower + 1, oneSize - 1)
                val fraction = coordinate - lower
                one[lower * 3 + channel] * (1f - fraction) + one[upper * 3 + channel] * fraction
            }
        }
        if (threeSize == 0) return
        val r = coordinate(result[0], threeMin[0], threeMax[0], threeSize)
        val g = coordinate(result[1], threeMin[1], threeMax[1], threeSize)
        val b = coordinate(result[2], threeMin[2], threeMax[2], threeSize)
        val r0 = floor(r).toInt(); val g0 = floor(g).toInt(); val b0 = floor(b).toInt()
        val r1 = minOf(r0 + 1, threeSize - 1); val g1 = minOf(g0 + 1, threeSize - 1); val b1 = minOf(b0 + 1, threeSize - 1)
        val fr = r - r0; val fg = g - g0; val fb = b - b0
        fun index(x: Int, y: Int, z: Int) = (x + threeSize * (y + threeSize * z)) * 3
        val i000 = index(r0, g0, b0); val i100 = index(r1, g0, b0)
        val i010 = index(r0, g1, b0); val i110 = index(r1, g1, b0)
        val i001 = index(r0, g0, b1); val i101 = index(r1, g0, b1)
        val i011 = index(r0, g1, b1); val i111 = index(r1, g1, b1)
        for (channel in 0..2) {
            val low = (three[i000 + channel] * (1 - fr) + three[i100 + channel] * fr) * (1 - fg) +
                (three[i010 + channel] * (1 - fr) + three[i110 + channel] * fr) * fg
            val high = (three[i001 + channel] * (1 - fr) + three[i101 + channel] * fr) * (1 - fg) +
                (three[i011 + channel] * (1 - fr) + three[i111 + channel] * fr) * fg
            result[channel] = low * (1 - fb) + high * fb
        }
    }

    fun write(output: DataOutputStream) {
        output.writeInt(1); output.writeUTF(title.take(200)); output.writeInt(oneSize); output.writeInt(threeSize)
        for (array in listOf(oneMin, oneMax, threeMin, threeMax, one, three)) array.forEach(output::writeFloat)
    }

    companion object {
        private val whitespace = Regex("\\s+")
        private fun coordinate(value: Float, minimum: Float, maximum: Float, size: Int): Float =
            ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f) * (size - 1)

        fun read(input: DataInputStream): CubeLut {
            require(input.readInt() == 1) { "LUT 缓存版本不支持" }
            val title = input.readUTF()
            val oneSize = input.readInt(); val threeSize = input.readInt()
            require(oneSize == 0 || oneSize in 2..65536)
            require(threeSize == 0 || threeSize in 2..65)
            require(oneSize > 0 || threeSize > 0)
            fun array(count: Int) = FloatArray(count) { input.readFloat().also { require(it.isFinite()) } }
            val oneMin = array(3); val oneMax = array(3); val threeMin = array(3); val threeMax = array(3)
            for (channel in 0..2) require(oneMax[channel] > oneMin[channel] && threeMax[channel] > threeMin[channel])
            val result = CubeLut(title, oneSize, threeSize, array(oneSize * 3),
                array(threeSize * threeSize * threeSize * 3), oneMin, oneMax, threeMin, threeMax)
            require(input.read() == -1) { "LUT 缓存包含多余数据" }
            return result
        }

        fun parse(text: String, fallbackName: String, checkCancellation: () -> Unit = {}): CubeLut {
            checkCancellation()
            var title = fallbackName.take(200)
            var oneSize = 0; var threeSize = 0; var data: FloatArray? = null; var written = 0
            var domainMin = floatArrayOf(0f, 0f, 0f); var domainMax = floatArrayOf(1f, 1f, 1f)
            var oneRange: Pair<Float, Float>? = null; var threeRange: Pair<Float, Float>? = null
            val headers = mutableSetOf<String>()
            BufferedReader(StringReader(text.removePrefix("\uFEFF"))).use { reader ->
                var lineNumber = 0
                while (true) {
                    val raw = reader.readLine() ?: break
                    lineNumber++
                    if (lineNumber % 128 == 0) checkCancellation()
                    require(raw.length <= 4096) { "LUT 第 $lineNumber 行过长" }
                    var quoted = false
                    val comment = raw.indexOfFirst { character ->
                        if (character == '"') quoted = !quoted
                        character == '#' && !quoted
                    }
                    val line = (if (comment < 0) raw else raw.substring(0, comment)).trim()
                    if (line.isEmpty()) continue
                    val fields = line.split(whitespace)
                    val header = fields[0]
                    if (header.first().isLetter()) {
                        require(data == null && headers.add(header)) { "LUT 头部重复或位于数据之后：$header" }
                        fun values(count: Int): FloatArray {
                            require(fields.size == count + 1) { "LUT 头部格式错误：$header" }
                            return FloatArray(count) { fields[it + 1].toFloat().also { value -> require(value.isFinite()) } }
                        }
                        when (header) {
                            "TITLE" -> title = line.substring(header.length).trim().removeSurrounding("\"")
                                .ifBlank { fallbackName }.take(200)
                            "LUT_1D_SIZE" -> { require(fields.size == 2); oneSize = fields[1].toInt(); require(oneSize in 2..65536) }
                            "LUT_3D_SIZE" -> { require(fields.size == 2); threeSize = fields[1].toInt(); require(threeSize in 2..65) }
                            "DOMAIN_MIN" -> domainMin = values(3)
                            "DOMAIN_MAX" -> domainMax = values(3)
                            "LUT_1D_INPUT_RANGE" -> values(2).let { oneRange = it[0] to it[1] }
                            "LUT_3D_INPUT_RANGE" -> values(2).let { threeRange = it[0] to it[1] }
                            else -> error("不支持的 LUT 指令：$header")
                        }
                    } else {
                        require(oneSize > 0 || threeSize > 0) { "LUT 缺少尺寸" }
                        if (data == null) data = FloatArray((oneSize + threeSize * threeSize * threeSize) * 3)
                        val target = requireNotNull(data)
                        require(fields.size == 3 && written + 3 <= target.size) { "LUT 数据行数量或格式错误" }
                        for (field in fields) target[written++] = field.toFloat().also { require(it.isFinite()) }
                    }
                }
            }
            val values = requireNotNull(data) { "LUT 没有颜色数据" }
            require(written == values.size) { "LUT 颜色数据不完整" }
            require((0..2).all { domainMax[it] > domainMin[it] }) { "LUT 输入范围无效" }
            fun minimum(range: Pair<Float, Float>?) = range?.let { FloatArray(3) { it.first } } ?: domainMin.copyOf()
            fun maximum(range: Pair<Float, Float>?) = range?.let { FloatArray(3) { it.second } } ?: domainMax.copyOf()
            require(oneRange?.let { it.second > it.first } != false && threeRange?.let { it.second > it.first } != false)
            require((oneRange == null && threeRange == null) || ("DOMAIN_MIN" !in headers && "DOMAIN_MAX" !in headers)) {
                "LUT 同时声明两套输入范围，请使用单一范围格式"
            }
            checkCancellation()
            return CubeLut(title, oneSize, threeSize, values.copyOfRange(0, oneSize * 3),
                values.copyOfRange(oneSize * 3, values.size), minimum(oneRange), maximum(oneRange), minimum(threeRange), maximum(threeRange))
        }

    }
}
