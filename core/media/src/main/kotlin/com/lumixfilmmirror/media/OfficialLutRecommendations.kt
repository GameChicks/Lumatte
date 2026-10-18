package com.lumixfilmmirror.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

data class OfficialLutSuggestion(val lut: LumixLabLut, val style: LutRecommendationStyle,
    val reasons: List<String>, val warnings: List<String>)
data class OfficialLutSuggestions(val suggestions: List<OfficialLutSuggestion>, val catalogueCandidates: Int,
    val analysedExamples: Int, val problems: List<String>, val method: String = "creator-example-discovery-v1")

/** An example is an aesthetic reference, not a measurement of the LUT's effect on the user's photo. */
internal object LutExampleSimilarity {
    fun distance(a: LutExampleColours, b: LutExampleColours): Double {
        require(a.familyWeights.values.all { it.isFinite() && it >= 0f } &&
            b.familyWeights.values.all { it.isFinite() && it >= 0f })
        // Hellinger distance tolerates missing colour families and does not privilege large images.
        fun total(c: LutExampleColours) = c.familyWeights.values.sum().toDouble()
        val at = total(a); val bt = total(b)
        if (at <= 0 || bt <= 0) return Double.POSITIVE_INFINITY
        return LutColourFamily.entries.sumOf { family ->
            val delta = sqrt((a.familyWeights[family] ?: 0f) / at) - sqrt((b.familyWeights[family] ?: 0f) / bt)
            delta * delta
        } / 2
    }

    fun style(c: LutExampleColours): LutRecommendationStyle {
        if (c.mode == LutExampleColourMode.NEAR_MONOCHROME) return LutRecommendationStyle.MONOCHROME
        fun weight(vararg families: LutColourFamily) = families.sumOf { (c.familyWeights[it] ?: 0f).toDouble() }
        val warmth = weight(LutColourFamily.RED, LutColourFamily.ORANGE, LutColourFamily.YELLOW) -
            weight(LutColourFamily.CYAN, LutColourFamily.BLUE)
        return if (abs(warmth) < .18) LutRecommendationStyle.NATURAL
            else if (warmth > 0) LutRecommendationStyle.WARM else LutRecommendationStyle.COOL
    }
}

/**
 * Explicit discovery only. Fetch metadata across categories and small creator examples, never LUT files.
 * Memory caches contain no user image or LUT binary. Kept on the processor so reopening costs no requests
 * for 30 minutes; lifecycle/process death releases them. A future frontend can use this without Compose.
 */
internal interface OfficialDiscoverySource {
    suspend fun luts(query: LumixLabLutQuery): List<LumixLabLut>
    suspend fun genres(): List<LumixLabGenre>
    suspend fun colours(lut: LumixLabLut): LutExampleColours
}

internal class OfficialLutRecommender(private val source: OfficialDiscoverySource) {
    constructor(client: LumixLabCatalogueClient) : this(object : OfficialDiscoverySource {
        override suspend fun luts(query: LumixLabLutQuery) = client.luts(query).items
        override suspend fun genres() = client.genres()
        override suspend fun colours(lut: LumixLabLut): LutExampleColours {
            val result = client.example(lut, 128)
            return try { result.colours } finally { result.image.recycle() }
        }
    })
    private val gate = Mutex()
    private data class Snapshot(val language: String, val at: Long, val items: List<LumixLabLut>)
    private var snapshot: Snapshot? = null
    private val colours = object : LinkedHashMap<String, LutExampleColours>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LutExampleColours>?) = size > 128
    }

    suspend fun recommend(photo: LutExampleColours, language: String, styles: Set<LutRecommendationStyle>,
        excludedCodes: Set<String>, refresh: Boolean = false): OfficialLutSuggestions = gate.withLock {
        require(styles.isNotEmpty())
        coroutineContext.ensureActive()
        val problems = mutableListOf<String>()
        val now = System.nanoTime()
        var items = snapshot?.takeIf { !refresh && it.language == language && now - it.at in 0..TTL }?.items
        if (items == null) {
            val collected = linkedMapOf<String, LumixLabLut>()
            suspend fun page(genre: String?, limit: Int) {
                try {
                    source.luts(LumixLabLutQuery(language, limit = limit, genreCode = genre)).forEach {
                        collected.putIfAbsent(it.code, it)
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { problems += "部分官方目录暂时无法读取" }
            }
            val completed = withTimeoutOrNull(8_000) {
                page(null, 32)
                val genres = try { source.genres().take(8) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { problems += "官方分类暂时无法读取"; emptyList() }
                for (genre in genres) page(genre.code, 8)
                true
            }
            if (completed == null) problems += "目录读取超时，本次使用已读取的候选"
            items = collected.values.take(96)
            if (items.isNotEmpty()) snapshot = Snapshot(language, now, items)
        }
        val candidates = items.filter { it.code !in excludedCodes && it.thumbnail != null }
        // Spread examples over the catalogue; avoid filling every slot with only the newest category.
        val positions = if (candidates.size <= 12) candidates.indices.toList()
            else (0 until 12).map { it * (candidates.size - 1) / 11 }
        val suggestions = mutableListOf<Pair<OfficialLutSuggestion, Double>>()
        var analysed = 0
        val completed = withTimeoutOrNull(10_000) { for (position in positions) {
            coroutineContext.ensureActive()
            val lut = candidates[position]
            val key = "${lut.code}:${lut.thumbnail?.path}"
            val example = try {
                colours[key] ?: source.colours(lut).also { colours[key] = it }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { problems += "部分创作者例图暂时无法读取"; continue }
            analysed++
            val style = LutExampleSimilarity.style(example)
            if (style !in styles) continue
            val distance = LutExampleSimilarity.distance(photo, example)
            if (!distance.isFinite()) continue
            suggestions += OfficialLutSuggestion(lut, style, listOf("创作者例图的色彩分布与这张照片相近"),
                listOf("尚未下载：依据例图发现风格，不能代表实际试套效果；使用前会检查输入照片风格")) to distance
        }; true }
        if (completed == null) problems += "例图分析超时，本次显示已完成的结果"
        OfficialLutSuggestions(suggestions.sortedWith(compareBy<Pair<OfficialLutSuggestion, Double>> { it.second }
            .thenBy { it.first.lut.code }).take(5).map { it.first }, items.size, analysed, problems.distinct())
    }

    private companion object { const val TTL = 30L * 60 * 1_000_000_000 }
}
