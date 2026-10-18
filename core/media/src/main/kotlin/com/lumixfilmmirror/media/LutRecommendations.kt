package com.lumixfilmmirror.media

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

enum class LutRecommendationStyle { NATURAL, WARM, COOL, MONOCHROME }
enum class LutRecommendationInput { SRGB_JPEG, VLOG, OTHER, UNKNOWN }

data class LutRecommendationOptions(
    val styles: Set<LutRecommendationStyle> = setOf(LutRecommendationStyle.NATURAL, LutRecommendationStyle.WARM, LutRecommendationStyle.COOL),
    val inputOverrides: Map<String, LutRecommendationInput> = emptyMap(),
    val allowUnknownInput: Boolean = false,
    val perStyle: Int = 3,
)
data class RecommendedLut(val id: String, val name: String, val style: LutRecommendationStyle,
    val reasons: List<String>, val warnings: List<String>, val expectedPhotoStyle: String?)
data class LutRecommendationResult(val recommendations: List<RecommendedLut>, val skipped: Map<String, String>,
    val analysedCandidates: Int, val samplePixels: Int,
    val method: String = "local-colour-preview-v2", val inputProblem: String? = null)

/** Conservative lack-of-colour check, not scene recognition. Small areas of real colour are retained. */
internal fun colourRecommendationInputProblem(bitmap: Bitmap, checkActive: () -> Unit): String? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val step = maxOf(1, kotlin.math.ceil(sqrt(pixels.size / 4096.0)).toInt())
    var count = 0; var coloured = 0; var chroma = 0.0
    for (y in 0 until bitmap.height step step) {
        checkActive()
        for (x in 0 until bitmap.width step step) {
            val pixel = pixels[y * bitmap.width + x]
            val r = (pixel ushr 16) and 255; val g = (pixel ushr 8) and 255; val b = pixel and 255
            val high = maxOf(r,g,b); val difference = (high-minOf(r,g,b))/255.0
            chroma += difference; count++
            if (difference > .035 && high > 20) coloured++
        }
    }
    return if (count > 0 && coloured.toDouble()/count < .02 && chroma/count < .012)
        "这张照片为黑白或几乎没有色彩，暂不进行色彩推荐。可换一张彩色照片，或返回 LUT 列表手动选择。" else null
}

/** Cached mathematical LUT features + actual photo samples. No network, content AI or full-image render. */
internal class LutRecommender(context: Context) {
    private val directory = File(context.cacheDir, "lut-recommendation-v1")
    private data class Features(val tint: Float, val saturation: Float, val lift: Float, val colourResponse: Float)
    private val memory = object : LinkedHashMap<CubeLut, Features>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CubeLut, Features>?) = size > 64
    }
    private data class Candidate(val asset: LutAsset, val input: LutRecommendationInput, val features: Features)
    private data class Effect(val candidate: Candidate, val tint: Double, val saturation: Double, val lift: Double,
        val clipping: Double, val inputChroma: Double, val outputChroma: Double, val contrastRetention: Double)

    fun recommend(bitmap: Bitmap, assets: List<LutAsset>, options: LutRecommendationOptions,
        checkActive: () -> Unit): LutRecommendationResult {
        require(options.perStyle in 1..5 && options.styles.isNotEmpty())
        require(assets.size <= 256) { "请分组推荐，每次最多 256 个 LUT" }
        val styles = options.styles.filter { it != LutRecommendationStyle.MONOCHROME }.toSet()
        val inputProblem = colourRecommendationInputProblem(bitmap, checkActive)
        if (inputProblem != null || styles.isEmpty()) return LutRecommendationResult(emptyList(), emptyMap(), 0, 0,
            inputProblem = inputProblem ?: "色彩推荐不包含黑白方向，请选择自然、暖调或冷调。")
        val skipped = linkedMapOf<String, String>()
        val candidates = assets.mapNotNull { asset ->
            checkActive()
            val input = options.inputOverrides[asset.id] ?: when (asset.photoStyle?.uppercase(java.util.Locale.ROOT)) {
                "STD", "NAT" -> LutRecommendationInput.SRGB_JPEG
                "VLOG", "V-LOG", "V_LOG" -> LutRecommendationInput.VLOG
                null, "" -> LutRecommendationInput.UNKNOWN
                else -> LutRecommendationInput.OTHER
            }
            if (input == LutRecommendationInput.VLOG || input == LutRecommendationInput.OTHER ||
                (input == LutRecommendationInput.UNKNOWN && !options.allowUnknownInput)) {
                skipped[asset.id] = if (input == LutRecommendationInput.UNKNOWN) "输入约定未确认"
                    else "输入约定与当前 sRGB JPEG 不兼容"
                null
            } else try {
                val feature = features(asset, checkActive)
                if (feature.colourResponse < .12f) { skipped[asset.id] = "黑白或单色 LUT 不参与色彩推荐"; null }
                else Candidate(asset, input, feature)
            }
            catch (_: IllegalArgumentException) { skipped[asset.id] = "LUT 特征数据无效"; null }
        }
        if (candidates.isEmpty()) return LutRecommendationResult(emptyList(), skipped, 0, 0)
        // The shortlist is bounded independently of catalogue size and requested style count.
        val shortlist = styles.flatMap { style -> candidates.sortedBy { featureCost(it.features, style) }
            .take(6) }.distinctBy { it.asset.id }.take(24)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val step = maxOf(1, kotlin.math.ceil(sqrt(pixels.size / 4096.0)).toInt())
        val samples = ArrayList<Int>(4096)
        for (y in 0 until bitmap.height step step) {
            checkActive()
            for (x in 0 until bitmap.width step step) samples += pixels[y * bitmap.width + x]
        }
        val evaluated = shortlist.mapNotNull { candidate ->
            try { evaluate(candidate, samples, checkActive).takeIf {
                val usable = it.clipping <= .18 && it.contrastRetention >= .18
                if (!usable) skipped[candidate.asset.id] = "在当前照片上裁切或压缩反差过强"
                usable
            } } catch (_: IllegalArgumentException) { skipped[candidate.asset.id] = "LUT 实际采样无效"; null }
        }
        val results = styles.sortedBy { it.ordinal }.flatMap { style ->
            evaluated.filter { effect ->
                val monochrome = effect.candidate.features.colourResponse < .12f ||
                    (effect.inputChroma > .045 && effect.outputChroma < maxOf(.025, effect.inputChroma * .18))
                when (style) {
                    LutRecommendationStyle.MONOCHROME -> false // Retained enum value is not a colour recommendation mode.
                    LutRecommendationStyle.WARM -> !monochrome && effect.tint > .006
                    LutRecommendationStyle.COOL -> !monochrome && effect.tint < -.006
                    LutRecommendationStyle.NATURAL -> !monochrome && abs(effect.tint) < .12 &&
                        (effect.inputChroma < .045 || effect.outputChroma >= effect.inputChroma * .45)
                }
            }
                .sortedWith(compareBy<Effect> { effectCost(it, style) }.thenBy { it.candidate.asset.id })
                .take(options.perStyle).map { effect ->
                    val reasons = mutableListOf<String>()
                    if (effect.lift > .035) reasons += "在这张照片的小图上提升亮度"
                    else if (effect.lift < -.035) reasons += "在这张照片的小图上压低亮度"
                    if (effect.tint > .015) reasons += "相较原图偏向暖调"
                    else if (effect.tint < -.015) reasons += "相较原图偏向冷调"
                    if (effect.saturation > .04) reasons += "增强颜色饱和度"
                    else if (effect.saturation < -.04) reasons += "降低颜色饱和度"
                    if (reasons.isEmpty()) reasons += "整体亮度与色彩变化较轻"
                    val warnings = mutableListOf<String>()
                    if (effect.clipping > .01) warnings += "部分采样颜色出现新增裁切，建议降低强度并预览"
                    if (effect.candidate.input == LutRecommendationInput.UNKNOWN) warnings += "输入约定未确认，结果仅供试看"
                    effect.candidate.asset.photoStyle?.let { warnings += "LUT 标注的输入照片风格为 $it，请确认照片来源" }
                    RecommendedLut(effect.candidate.asset.id, effect.candidate.asset.name, style,
                        reasons, warnings, effect.candidate.asset.photoStyle)
                }
        }
        return LutRecommendationResult(results, skipped, shortlist.size, samples.size)
    }

    private fun featureCost(f: Features, style: LutRecommendationStyle): Double = when (style) {
        LutRecommendationStyle.NATURAL -> abs(f.tint.toDouble()) * 2 + abs(f.lift.toDouble()) + abs(f.saturation - 1.0)
        LutRecommendationStyle.WARM -> abs(f.tint - .06) + abs(f.lift.toDouble()) * .3
        LutRecommendationStyle.COOL -> abs(f.tint + .06) + abs(f.lift.toDouble()) * .3
        LutRecommendationStyle.MONOCHROME -> f.colourResponse.toDouble()
    }
    private fun effectCost(e: Effect, style: LutRecommendationStyle): Double = e.clipping * 5 +
        maxOf(0.0, .7 - e.contrastRetention) * .5 + when (style) {
        LutRecommendationStyle.NATURAL -> abs(e.tint) * 2 + abs(e.lift) + abs(e.saturation)
        LutRecommendationStyle.WARM -> abs(e.tint - .06) + abs(e.lift) * .3 + abs(e.saturation) * .2
        LutRecommendationStyle.COOL -> abs(e.tint + .06) + abs(e.lift) * .3 + abs(e.saturation) * .2
        LutRecommendationStyle.MONOCHROME -> e.candidate.features.colourResponse.toDouble()
    }
    private fun evaluate(candidate: Candidate, pixels: List<Int>, checkActive: () -> Unit): Effect {
        val input = FloatArray(3); val output = FloatArray(3)
        var tint = 0.0; var sat = 0.0; var lift = 0.0; var clipped = 0
        var inputChroma = 0.0; var outputChroma = 0.0
        var inputLight = 0.0; var outputLight = 0.0; var inputSquare = 0.0; var outputSquare = 0.0
        pixels.forEachIndexed { index, pixel ->
            if (index % 128 == 0) checkActive()
            input[0] = ((pixel ushr 16) and 255) / 255f
            input[1] = ((pixel ushr 8) and 255) / 255f
            input[2] = (pixel and 255) / 255f
            candidate.asset.table.apply(input, output)
            require(output.all { it.isFinite() }) { "LUT 推荐采样产生无效数值" }
            if ((0..2).any { input[it] in .02f.. .98f && (output[it] <= .001f || output[it] >= .999f) }) clipped++
            for (c in 0..2) output[c] = output[c].coerceIn(0f, 1f)
            tint += (output[0] - output[2]) - (input[0] - input[2])
            sat += saturation(output) - saturation(input)
            lift += light(output) - light(input)
            inputChroma += saturation(input); outputChroma += saturation(output)
            val a = light(input).toDouble(); val b = light(output).toDouble()
            inputLight += a; outputLight += b; inputSquare += a*a; outputSquare += b*b
        }
        val n = pixels.size.toDouble().coerceAtLeast(1.0)
        val inputVariance = maxOf(0.0, inputSquare/n - (inputLight/n)*(inputLight/n))
        val outputVariance = maxOf(0.0, outputSquare/n - (outputLight/n)*(outputLight/n))
        val contrast = if (inputVariance < .0001) 1.0 else sqrt(outputVariance/inputVariance)
        return Effect(candidate, tint/n, sat/n, lift/n, clipped/n, inputChroma/n, outputChroma/n, contrast)
    }
    private fun features(asset: LutAsset, checkActive: () -> Unit): Features {
        memory[asset.table]?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        val table = asset.table.nativeData
        digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(table.oneSize).putInt(table.threeSize).array())
        val block = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        val floats = block.asFloatBuffer()
        for (values in listOf(table.bounds, table.one, table.three)) {
            var offset = 0
            while (offset < values.size) {
                checkActive()
                val count = minOf(floats.capacity(), values.size - offset)
                floats.clear(); floats.put(values, offset, count)
                digest.update(block.array(), 0, count * 4)
                offset += count
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val file = AtomicFile(File(directory, "$hash-srgb-v2.bin"))
        try {
            DataInputStream(file.openRead()).use {
                if (it.readInt() == 2) {
                    val result = Features(it.readFloat(), it.readFloat(), it.readFloat(), it.readFloat())
                    if (listOf(result.tint, result.saturation, result.lift, result.colourResponse).all { n -> n.isFinite() } && it.read() == -1)
                        return result.also { memory[asset.table] = it }
                }
            }
        } catch (_: java.io.IOException) { /* Discardable cache, computed from the actual table below. */ }
        val input = FloatArray(3); val output = FloatArray(3)
        var tint = 0f; var lift = 0f
        for (i in 1..7) {
            checkActive(); input.fill(i / 8f); asset.table.apply(input, output)
            tint += output[0] - output[2]; lift += light(output) - light(input)
        }
        var saturationSum = 0f
        for (i in 0..2) { checkActive(); input.fill(.2f); input[i] = .8f; asset.table.apply(input, output); saturationSum += saturation(output) }
        // Equal-luminance colour probes remove a constant sepia/cool cast from the B&W decision.
        val inputVectors = mutableListOf<Pair<Float, Float>>()
        val outputVectors = mutableListOf<Pair<Float, Float>>()
        for (channel in 0..2) for (sign in listOf(-1f, 1f)) {
            checkActive(); input.fill(.5f); input[channel] += sign * .16f
            val delta = .5f - light(input)
            for (c in 0..2) input[c] += delta
            asset.table.apply(input, output)
            inputVectors += (input[0]-input[1]) to (input[2]-input[1])
            outputVectors += (output[0]-output[1]) to (output[2]-output[1])
        }
        fun spread(vectors: List<Pair<Float,Float>>): Double {
            val x = vectors.map { it.first }.average(); val y = vectors.map { it.second }.average()
            return vectors.sumOf { (it.first-x)*(it.first-x)+(it.second-y)*(it.second-y) }
        }
        val response = sqrt(spread(outputVectors)/spread(inputVectors).coerceAtLeast(1e-9)).toFloat()
        val result = Features(tint / 7, saturationSum / 1.8f, lift / 7, response)
        require(listOf(result.tint, result.saturation, result.lift, result.colourResponse).all { it.isFinite() })
        memory[asset.table] = result
        if (!directory.isDirectory && !directory.mkdirs()) return result
        val stream = try { file.startWrite() } catch (_: java.io.IOException) { return result }
        try {
            val data = DataOutputStream(stream)
            data.writeInt(2); data.writeFloat(result.tint); data.writeFloat(result.saturation); data.writeFloat(result.lift)
            data.writeFloat(result.colourResponse); data.flush()
            file.finishWrite(stream)
        } catch (_: java.io.IOException) { file.failWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
        return result
    }
    private fun light(rgb: FloatArray) = .2126f * rgb[0] + .7152f * rgb[1] + .0722f * rgb[2]
    private fun saturation(rgb: FloatArray) = rgb.maxOrNull()!! - rgb.minOrNull()!!
}
