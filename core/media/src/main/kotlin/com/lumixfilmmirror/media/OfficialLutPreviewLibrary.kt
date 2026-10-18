package com.lumixfilmmirror.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Small session-only table cache. Never imports into the persistent LUT library. */
internal class OfficialLutPreviewLibrary(private val client: LumixLabCatalogueClient) {
    private val gate = Mutex()
    private val tables = object : LinkedHashMap<String, LutAsset>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LutAsset>?) = size > 8
    }

    suspend fun asset(code: String, language: String): LutAsset = gate.withLock {
        val key = "$language:$code"
        tables[key]?.let { return it }
        val detail = client.detail(code, language)
        val cube = client.downloadCube(detail)
        val asset = withContext(Dispatchers.Default) {
            require(cube.size in 1..(4 * 1024 * 1024))
            val active = coroutineContext
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(cube)).toString()
            val table = CubeLut.parse(text, detail.lut.title) { active.ensureActive() }.named(detail.lut.title)
            require(table.nativeData.oneSize == 0 && table.nativeData.threeSize == 17) {
                "试用文件并非已确认的 grid17 三维 LUT"
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(cube).joinToString("") { "%02x".format(it.toInt() and 255) }
            LutAsset("preview-lab-$hash", detail.lut.title, false, table, detail.photoStyle)
        }
        coroutineContext.ensureActive()
        tables[key] = asset
        asset
    }
}
