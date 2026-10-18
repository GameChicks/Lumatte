package com.lumixfilmmirror.media

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/** Server references only. A path is metadata, not permission to fetch an arbitrary URL. */
data class LumixLabImageRef(val path: String, val originalPath: String?, val width: Int?, val height: Int?)
data class LumixLabCreator(val code: String?, val name: String?, val icon: LumixLabImageRef?)
data class LumixLabGenreTop(val region: String?, val thumbnail: LumixLabImageRef?)
data class LumixLabGenre(val code: String, val title: String, val thumbnail: LumixLabImageRef?,
    val top: List<LumixLabGenreTop>)
data class LumixLabLut(val code: String, val title: String, val creator: LumixLabCreator?,
    val thumbnail: LumixLabImageRef?, val downloadCount: Int?)
data class LumixLabLutDetail(val lut: LumixLabLut, val overview: String?, val photoStyle: String?,
    val locale: String?, val grid17: String?, val grid33: String?)
/** The caller owns image after successful return and must recycle it when the card is discarded. */
data class LumixLabExample(val lutCode: String, val image: Bitmap, val colours: LutExampleColours)

/** Language is a service value; obtain it through LumixLabLanguages with a configured Android Context. */
data class LumixLabLutQuery(val language: String, val offset: Int = 0, val limit: Int = 20,
    val genreCode: String? = null, val creatorCode: String? = null,
    val countries: List<String> = emptyList())

/** The response declares no total. nextOffset is a continuation hint, not proof of another page. */
data class LumixLabLutPage(val items: List<LumixLabLut>, val offset: Int, val requestedLimit: Int,
    val nextOffset: Int?)

class LumixLabHttpException(val statusCode: Int) : IOException("Lumix Lab 请求失败（HTTP $statusCode）")
class LumixLabProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Source-derived community API only. No account storage, camera Network, remote control or UI.
 * Constructing this client and loading local LUTs never sends a request.
 */
class LumixLabCatalogueClient {
    private val requestGate = Semaphore(4)
    private val exampleGate = Semaphore(2)
    // Own ordinary Internet client. Default TLS/hostname checks remain intact.
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun genres(): List<LumixLabGenre> = withContext(Dispatchers.Default) {
        LumixLabJson.genres(post("community/genres", null))
    }

    /** created/desc is confirmed in the official list caller; no speculative sort options. */
    suspend fun luts(query: LumixLabLutQuery): LumixLabLutPage {
        require(query.offset >= 0 && query.limit in 1..64) { "目录分页参数无效" }
        // Freeze a caller-owned list before suspension. This is a local resource bound, not an API claim.
        val countries = query.countries.toList()
        require(countries.size <= 64) { "地区筛选过多" }
        val body = JSONObject().put("language", field(query.language))
            .put("offset", query.offset.toString()).put("limit", query.limit.toString())
            .put("order", JSONObject().put("key", "created").put("order", "desc"))
        query.genreCode?.let { body.put("genre_code", field(it)) }
        query.creatorCode?.let { body.put("creator_code", field(it)) }
        if (countries.isNotEmpty()) body.put("countries", JSONArray(countries.map(::field)))
        return withContext(Dispatchers.Default) {
            val items = LumixLabJson.luts(post("community/luts/filter", body))
            val next = (query.offset.toLong() + items.size).takeIf { items.isNotEmpty() && it <= Int.MAX_VALUE }?.toInt()
            LumixLabLutPage(items, query.offset, query.limit, next)
        }
    }

    suspend fun detail(code: String, language: String): LumixLabLutDetail {
        val requested = field(code)
        val body = JSONObject().put("code", requested).put("language", field(language))
        return withContext(Dispatchers.Default) {
            val detail = LumixLabJson.detail(post("community/lut", body))
            if (detail.lut.code != requested) throw LumixLabProtocolException("Lumix Lab 返回的 LUT 编号不一致")
            detail
        }
    }

    /** The creator's uploaded thumbnail, never the user's own LUT-processed photo. No disk cache yet. */
    suspend fun example(lut: LumixLabLut, longEdge: Int = 320): LumixLabExample {
        require(longEdge in 64..1280) { "创作者例图长边需在 64～1280 像素之间" }
        val code = field(lut.code)
        val path = lut.thumbnail?.path ?: throw LumixLabProtocolException("该 LUT 没有创作者例图")
        // Recovered callers prepend the official origin to root-relative paths. Never guess another CDN.
        require(path.length <= 4096 && path.none { it.code < 32 }) { "例图地址无效" }
        val url = ORIGIN.resolve(path) ?: throw LumixLabProtocolException("例图地址无效")
        if (url.scheme != "https" || url.host != HOST || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            (!path.startsWith("/") && !path.startsWith("https://"))) {
            throw LumixLabProtocolException("例图地址不属于已确认的官方 HTTPS 来源")
        }
        var produced: Bitmap? = null
        try {
            return exampleGate.withPermit {
                // No community application marker is attached to image requests.
                val bytes = responseBytes(Request.Builder().url(url).header("Accept", "image/*").get().build(),
                    MAX_IMAGE, ResponseKind.IMAGE)
                withContext(Dispatchers.Default) {
                    currentCoroutineContext().ensureActive()
                    val image = LutExampleDecoder.decode(bytes, longEdge).also { produced = it }
                    val colours = LutGalleryColours.analyse(image, LutColourOrigin.CREATOR_EXAMPLE,
                        "lumix-lab:$code")
                    currentCoroutineContext().ensureActive()
                    LumixLabExample(code, image, colours)
                }
            }
        } catch (error: Throwable) { produced?.recycle(); throw error }
    }

    private fun field(value: String): String {
        require(value.isNotBlank() && value.length <= 256 && value.none { it.code < 32 }) { "目录筛选值无效" }
        return value
    }

    suspend fun creatorAvatar(creator: LumixLabCreator): Bitmap? {
        val path = creator.icon?.path ?: return null
        require(path.length <= 4096 && path.none { it.code < 32 })
        val url = ORIGIN.resolve(path) ?: throw LumixLabProtocolException("头像地址无效")
        require(url.scheme == "https" && url.host == HOST && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            (path.startsWith("/") || path.startsWith("https://"))) { "头像不属于官方 HTTPS 来源" }
        var produced: Bitmap? = null
        try { return exampleGate.withPermit {
            val bytes = responseBytes(Request.Builder().url(url).header("Accept", "image/*").get().build(),
                MAX_IMAGE, ResponseKind.IMAGE)
            withContext(Dispatchers.Default) { LutExampleDecoder.decode(bytes, 96).also { produced = it } }
        } } catch (error: Throwable) { produced?.recycle(); throw error }
    }

    /** Only the confirmed grid17 download. ZIP decoding never writes server-selected file paths. */
    internal suspend fun downloadCube(detail: LumixLabLutDetail): ByteArray {
        if (detail.grid17.isNullOrBlank()) throw LumixLabProtocolException("该 LUT 没有已确认可用的 grid17 文件")
        val request = communityRequest("community/luts/download", JSONObject()
            .put("code", field(detail.lut.code)).put("key", "grid17"), "application/zip")
        val bytes = responseBytes(request, 8 * 1024 * 1024, ResponseKind.ZIP)
        return withContext(Dispatchers.Default) {
            val active = currentCoroutineContext()
            LumixLabZip.cube(bytes) { active.ensureActive() }
        }
    }

    private fun communityRequest(path: String, body: JSONObject?, accept: String): Request {
        // Only these fixed routes may receive the shared public application marker.
        require(path in setOf("community/genres", "community/luts/filter", "community/lut", "community/luts/download"))
        return Request.Builder().url(BASE + path)
            .header("Accept", accept)
            // Fixed application marker in h6.e, not an account credential or recovered user token.
            .header("lutapp-api-key", "111")
            // genres has no Retrofit body argument; send zero bytes, not an invented JSON object.
            .post((body?.toString() ?: "").toRequestBody(JSON)).build()
    }

    private suspend fun post(path: String, body: JSONObject?): JSONObject {
        val bytes = responseBytes(communityRequest(path, body, "application/json"), MAX_JSON, ResponseKind.JSON)
        return withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                JSONObject(decoder.decode(ByteBuffer.wrap(bytes)).toString())
            } catch (error: java.nio.charset.CharacterCodingException) {
                throw LumixLabProtocolException("Lumix Lab 目录编码无效", error)
            } catch (error: JSONException) {
                throw LumixLabProtocolException("Lumix Lab 目录格式无效", error)
            }
        }
    }

    private enum class ResponseKind { JSON, IMAGE, ZIP }
    private suspend fun responseBytes(request: Request, maximum: Int, kind: ResponseKind): ByteArray =
        requestGate.withPermit { withContext(Dispatchers.IO) { coroutineScope {
            check(request.url.scheme == "https" && request.url.host == HOST && request.url.port == 443)
            val active = currentCoroutineContext()
            val call = client.newCall(request)
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                active.ensureActive()
                call.execute().use { response ->
                    if (!response.isSuccessful) throw LumixLabHttpException(response.code)
                    val responseBody = response.body ?: throw LumixLabProtocolException("Lumix Lab 返回空响应")
                    val type = responseBody.contentType()
                    val correctType = when (kind) {
                        ResponseKind.JSON -> type?.type == "application" &&
                            (type.subtype == "json" || type.subtype.endsWith("+json"))
                        ResponseKind.IMAGE -> type?.type == "image"
                        ResponseKind.ZIP -> type?.type == "application" &&
                            type.subtype in setOf("zip", "x-zip-compressed", "octet-stream")
                    }
                    if (!correctType) {
                        throw LumixLabProtocolException("Lumix Lab 响应类型不符")
                    }
                    val expected = responseBody.contentLength().takeIf { it >= 0 }
                    if (expected != null && expected !in 1..maximum.toLong()) {
                        throw LumixLabProtocolException("Lumix Lab 响应为空或过大")
                    }
                    val output = ByteArrayOutputStream()
                    responseBody.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            active.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > maximum) throw LumixLabProtocolException("Lumix Lab 响应过大")
                            output.write(buffer, 0, count)
                        }
                    }
                    active.ensureActive()
                    if (output.size() == 0 || (expected != null && output.size().toLong() != expected)) {
                        throw LumixLabProtocolException("Lumix Lab 响应不完整")
                    }
                    output.toByteArray()
                }
            } catch (error: IOException) {
                active.ensureActive() // Socket cancellation remains coroutine cancellation, not an offline error.
                throw error
            } finally { cancellation.cancel() }
        } } }

    companion object {
        private const val HOST = "lumix-base.jpn.panasonic.com"
        private const val BASE = "https://$HOST/lutapi/v2/"
        private const val MAX_JSON = 2 * 1024 * 1024
        private const val MAX_IMAGE = 4 * 1024 * 1024
        private val ORIGIN = "https://$HOST/".toHttpUrl()
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
