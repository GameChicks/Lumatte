package com.lumixfilmmirror.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OfficialDiscoveryTest {
    private val palette = LutExampleColours(LutColourOrigin.CREATOR_EXAMPLE,"sample",null,emptyList(),
        mapOf(LutColourFamily.ORANGE to .8f,LutColourFamily.NEUTRAL to .2f),LutExampleColourMode.COLOUR)
    private class Source(val palette: LutExampleColours): OfficialDiscoverySource {
        var pages = 0; var examples = 0; var cancel = false; var fail = false
        override suspend fun genres() = (0..15).map { LumixLabGenre("g$it","Genre$it",null,emptyList()) }
        override suspend fun luts(query: LumixLabLutQuery): List<LumixLabLut> {
            if(cancel) throw CancellationException()
            if(fail) throw java.io.IOException()
            pages++
            return (0 until query.limit).map { i ->
                val code="${query.genreCode ?: "all"}:$i"
                LumixLabLut(code,code,null,LumixLabImageRef("/$code.jpg",null,128,128),null)
            }
        }
        override suspend fun colours(lut: LumixLabLut): LutExampleColours { examples++;return palette }
    }
    @Test fun discoversUndownloadedLutsWithinBudgetAndReusesCache() = runBlocking {
        val source=Source(palette); val engine=OfficialLutRecommender(source)
        val first=engine.recommend(palette,"en",setOf(LutRecommendationStyle.WARM),setOf("all:0"))
        assertEquals(96,first.catalogueCandidates); assertEquals(12,source.examples); assertEquals(9,source.pages)
        assertEquals(5,first.suggestions.size); assertFalse(first.suggestions.any { it.lut.code=="all:0" })
        assertTrue(first.suggestions.all { it.warnings.isNotEmpty() })
        engine.recommend(palette,"en",setOf(LutRecommendationStyle.WARM),setOf("all:0"))
        assertEquals(12,source.examples); assertEquals(9,source.pages)
        engine.recommend(palette,"ja",setOf(LutRecommendationStyle.WARM),emptySet())
        assertEquals(18,source.pages)
    }
    @Test fun networkFailureIsExplicitAndCancellationPropagates() = runBlocking {
        val source=Source(palette);source.fail=true
        val failed=OfficialLutRecommender(source).recommend(palette,"en",setOf(LutRecommendationStyle.WARM),emptySet())
        assertTrue(failed.suggestions.isEmpty());assertTrue(failed.problems.isNotEmpty())
        source.fail=false;source.cancel=true
        try { OfficialLutRecommender(source).recommend(palette,"en",setOf(LutRecommendationStyle.WARM),emptySet()); fail("cancel swallowed") }
        catch(_:CancellationException) { }
    }
}
