package com.lumixfilmmirror.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OfficialLutExplorerTest {
    private class Source: OfficialLutExplorerSource {
        val offsets=mutableListOf<Int>()
        var failAt: Int?=null
        var repeat=false
        var colourCalls=0
        override suspend fun page(query: LumixLabLutQuery): LumixLabLutPage {
            offsets+=query.offset
            if(query.offset==failAt)throw java.io.IOException()
            val start=if(repeat)0 else query.offset
            val items=(start until minOf(140,start+query.limit)).map { i ->
                LumixLabLut(if(i==32)"0" else "$i","Style $i",null,LumixLabImageRef("/$i.jpg",null,null,null),null)
            }
            return LumixLabLutPage(items,query.offset,query.limit,if(items.isEmpty())null else query.offset+items.size)
        }
        override suspend fun colours(lut: LumixLabLut): LutExampleColours {
            colourCalls++
            return LutExampleColours(LutColourOrigin.CREATOR_EXAMPLE,lut.code,null,emptyList(),mapOf(LutColourFamily.BLUE to 1f),LutExampleColourMode.COLOUR)
        }
    }
    @Test fun scansPastPreviousBudgetAndUsesRawOffsetsDespiteDuplicates()=runBlocking {
        val source=Source();val engine=OfficialLutExplorer(source)
        val result=engine.index("en")
        assertTrue(result.complete);assertEquals(139,result.items.size)
        assertTrue(result.items.any { it.code=="139" })
        assertEquals(listOf(0,32,64,96,128,140),source.offsets)
        engine.index("en");assertEquals(6,source.offsets.size)
    }
    @Test fun failedPageResumesAndDoesNotClaimComplete()=runBlocking {
        val source=Source();source.failAt=64;val engine=OfficialLutExplorer(source)
        val first=engine.index("en")
        assertFalse(first.complete);assertNotNull(first.problem);assertEquals(63,first.items.size)
        source.failAt=null
        assertTrue(engine.index("en").complete)
        assertEquals(listOf(0,32,64,64,96,128,140),source.offsets)
    }
    @Test fun cancellationResumesAndRepeatedServerPagesStop()=runBlocking {
        val source=Source();val engine=OfficialLutExplorer(source)
        try { engine.index("en") { if(it.items.size>=63)throw CancellationException() };fail("cancel swallowed") }
        catch(_:CancellationException) { }
        assertTrue(engine.index("en").complete)
        assertEquals(listOf(0,32,64,96,128,140),source.offsets)
        val repeated=Source().apply { repeat=true }
        val result=OfficialLutExplorer(repeated).index("en")
        assertFalse(result.complete);assertNotNull(result.problem);assertEquals(2,repeated.offsets.size)
    }
    @Test fun cachesExampleColoursButInvalidatesChangedThumbnail()=runBlocking {
        val source=Source();val engine=OfficialLutExplorer(source)
        val item=LumixLabLut("a","A",null,LumixLabImageRef("/first.jpg",null,null,null),null)
        engine.colours(item);engine.colours(item);assertEquals(1,source.colourCalls)
        engine.colours(item.copy(thumbnail=LumixLabImageRef("/second.jpg",null,null,null)))
        assertEquals(2,source.colourCalls)
    }
}
