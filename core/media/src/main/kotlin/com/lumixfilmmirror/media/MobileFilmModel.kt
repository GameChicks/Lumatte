package com.lumixfilmmirror.media

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Describes packaged data, not an active/validated processing backend. */
enum class FilmModelOrigin { SPEKTRAFILM, PROJECT_MONOCHROME, FOTUFILM_MOBILE }

data class MobileFilmModelInfo(val stockId: String, val printId: String, val positive: Boolean,
    val modelVersion: String, val sourceCommit: String?, val tableBytes: Long,
    val origin: FilmModelOrigin = FilmModelOrigin.SPEKTRAFILM)

data class MobileFilmModelsInfo(val models: List<MobileFilmModelInfo>, val problems: List<String>,
    val cachedModels: Int, val cachedTableBytes: Long, val cacheBudgetBytes: Long)

internal data class FilmTableDomain(val minimum: FloatArray, val maximum: FloatArray)
/** Borrowed immutable arrays: only the native bridge copies them; never mutate in Kotlin. */
internal data class NativeFilmStage(val size: Int, val values: FloatArray, val minimum: FloatArray, val maximum: FloatArray)

internal data class FotufilmHalationKernel(val sigmaMm: FloatArray, val weights: FloatArray, val matrix: FloatArray)

internal data class MobileFilmEffects(val grainActive: Boolean, val particleAreaUm2: Float,
    val particleScale: FloatArray, val densityFloor: FloatArray, val grainUniformity: FloatArray,
    val densityMaximum: FloatArray, val halationStrength: FloatArray, val halationSigmaUm: FloatArray,
    val filmFormatMm: Float, val halationBounces: Int = 3, val halationDecay: Float = .5f,
    val grainSpacingUm: Float = 0f, val scatterAmount: Float = 1f,
    val scatterCoreUm: FloatArray = floatArrayOf(2.2f, 2f, 1.6f),
    val scatterTailUm: FloatArray = floatArrayOf(9.3f, 9.7f, 9.1f),
    val scatterTailWeight: FloatArray = floatArrayOf(.78f, .65f, .67f),
    val fotufilmHalation: FotufilmHalationKernel? = null)

/** Immutable after loading. Caller-owned output avoids per-pixel arrays and shared scratch state. */
internal class FilmStageTable(val size: Int, private val domain: FilmTableDomain, private val values: FloatArray) {
    val bytes: Long get() = values.size.toLong() * Float.SIZE_BYTES
    internal val nativeData get() = NativeFilmStage(size, values, domain.minimum, domain.maximum)

    fun sample(first: Float, second: Float, third: Float, output: FloatArray) {
        fun coordinate(value: Float, channel: Int): Float {
            require(value.isFinite()) { "胶片阶段收到无效颜色" }
            return ((value - domain.minimum[channel]) / (domain.maximum[channel] - domain.minimum[channel]))
                .coerceIn(0f, 1f) * (size - 1)
        }
        val x = coordinate(first, 0)
        val y = coordinate(second, 1)
        val z = coordinate(third, 2)
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt(); val z0 = floor(z).toInt()
        val x1 = minOf(x0 + 1, size - 1); val y1 = minOf(y0 + 1, size - 1); val z1 = minOf(z0 + 1, size - 1)
        val fx = x - x0; val fy = y - y0; val fz = z - z0
        fun index(a: Int, b: Int, c: Int) = (a + size * (b + size * c)) * 3
        val i000 = index(x0, y0, z0); val i100 = index(x1, y0, z0)
        val i010 = index(x0, y1, z0); val i110 = index(x1, y1, z0)
        val i001 = index(x0, y0, z1); val i101 = index(x1, y0, z1)
        val i011 = index(x0, y1, z1); val i111 = index(x1, y1, z1)
        for (c in 0..2) {
            val low = (values[i000 + c] * (1f - fx) + values[i100 + c] * fx) * (1f - fy) +
                (values[i010 + c] * (1f - fx) + values[i110 + c] * fx) * fy
            val high = (values[i001 + c] * (1f - fx) + values[i101 + c] * fx) * (1f - fy) +
                (values[i011 + c] * (1f - fx) + values[i111 + c] * fx) * fy
            output[c] = low * (1f - fz) + high * fz
        }
    }
}

internal class MobileFilmModel(val info: MobileFilmModelInfo, val response: FilmStageTable,
    val development: FilmStageTable, val scan: FilmStageTable, val effects: MobileFilmEffects)

/** Colour stages for the matching CPU backend. Spatial effects/grain must be supplied separately. */
internal class MobileFilmColourKernel(private val model: MobileFilmModel, exposureEv: Float) {
    private val gain = 2.0.pow(exposureEv.toDouble())
    private val raw = FloatArray(3)
    private val density = FloatArray(3)

    init { require(exposureEv.isFinite() && exposureEv in -3f..3f) }

    fun response(encodedRed: Float, encodedGreen: Float, encodedBlue: Float, output: FloatArray) =
        model.response.sample(encodedRed, encodedGreen, encodedBlue, output)

    /** Accepts linear film response after spatial effects; EV is applied once, before development. */
    fun develop(linearRaw: FloatArray, outputDensity: FloatArray) {
        fun logExposure(channel: Int) = log10(maxOf(0.0, linearRaw[channel].toDouble()) * gain + 1e-10).toFloat()
        model.development.sample(logExposure(0), logExposure(1), logExposure(2), outputDensity)
    }

    /** Receives density after grain. Positive stocks already have their own direct-scan table. */
    fun scan(cmy: FloatArray, outputRgb: FloatArray) = model.scan.sample(cmy[0], cmy[1], cmy[2], outputRgb)

    fun colourOnly(encodedRgb: FloatArray) {
        response(encodedRgb[0], encodedRgb[1], encodedRgb[2], raw)
        develop(raw, density)
        scan(density, encodedRgb)
    }
}
