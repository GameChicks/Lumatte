package com.lumixfilmmirror.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class OfficialLutIndex(val items: List<LumixLabLut>, val complete: Boolean, val problem: String? = null)

internal interface OfficialLutExplorerSource {
    suspend fun page(query: LumixLabLutQuery): LumixLabLutPage
    suspend fun colours(lut: LumixLabLut): LutExampleColours
}

/** User-opened catalogue exploration. Pages metadata and small examples, never downloads LUT tables. */
class OfficialLutExplorer internal constructor(private val source: OfficialLutExplorerSource) {
    constructor(client: LumixLabCatalogueClient) : this(object : OfficialLutExplorerSource {
        override suspend fun page(query: LumixLabLutQuery) = client.luts(query)
        override suspend fun colours(lut: LumixLabLut): LutExampleColours {
            val example=client.example(lut,128)
            return try { example.colours } finally { example.image.recycle() }
        }
    })
    private val indexGate=Mutex()
    private val colourGate=Mutex()
    private var language: String?=null
    private var created=0L
    private var offset=0
    private var complete=false
    private val items=linkedMapOf<String,LumixLabLut>()
    private val pages=mutableSetOf<List<String>>()
    private val colours=object : LinkedHashMap<String,LutExampleColours>(512,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,LutExampleColours>?)=size>512
    }

    /** No fixed candidate cap. The server's empty final page ends traversal; cancellation keeps a resumable cursor. */
    suspend fun index(locale: String,onPage: suspend (OfficialLutIndex)->Unit = {}): OfficialLutIndex = indexGate.withLock {
        val now=System.nanoTime()
        if(language!=locale || now-created !in 0..(30L*60*1_000_000_000)) {
            language=locale;created=now;offset=0;complete=false;items.clear();pages.clear()
        }
        if(items.isNotEmpty() || complete)onPage(OfficialLutIndex(items.values.toList(),complete))
        while(!complete) {
            coroutineContext.ensureActive()
            val page=try { withTimeoutOrNull(20_000) { source.page(LumixLabLutQuery(locale,offset,32)) }
                ?: return@withLock OfficialLutIndex(items.values.toList(),false,"Lab 目录读取超时，可重试；当前结果未覆盖全库。") }
            catch(error:CancellationException) { throw error }
            catch(_:Exception) {
                return@withLock OfficialLutIndex(items.values.toList(),false,"Lab 目录暂时无法继续读取，可重试；当前结果未覆盖全库。")
            }
            if(page.items.isNotEmpty() && !pages.add(page.items.map { it.code }.sorted())) {
                return@withLock OfficialLutIndex(items.values.toList(),false,"Lab 返回重复分页，已停止继续请求；当前结果未覆盖全库。")
            }
            page.items.forEach { items[it.code]=it }
            if(page.items.isEmpty() || page.nextOffset==null)complete=true
            else if(page.nextOffset<=offset) {
                return@withLock OfficialLutIndex(items.values.toList(),false,"Lab 分页位置未前进，请稍后重试。")
            } else offset=page.nextOffset
            onPage(OfficialLutIndex(items.values.toList(),complete))
        }
        OfficialLutIndex(items.values.toList(),true)
    }

    suspend fun colours(lut: LumixLabLut): LutExampleColours {
        coroutineContext.ensureActive()
        val key="${lut.code}:${lut.thumbnail?.path}"
        colourGate.withLock { colours[key] }?.let { return it }
        val result=withTimeoutOrNull(20_000) { source.colours(lut) }
            ?: throw java.io.IOException("创作者例图读取超时")
        coroutineContext.ensureActive()
        colourGate.withLock { colours[key]=result }
        return result
    }
}
