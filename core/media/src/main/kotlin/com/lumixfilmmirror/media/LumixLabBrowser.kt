package com.lumixfilmmirror.media

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class LumixLabBrowserState(
    val genres: List<LumixLabGenre> = emptyList(), val genresLoading: Boolean = false, val genresError: String? = null,
    val query: LumixLabLutQuery? = null, val items: List<LumixLabLut> = emptyList(),
    val pageLoading: Boolean = false, val nextOffset: Int? = null, val pageError: String? = null,
    val detailCode: String? = null, val detail: LumixLabLutDetail? = null,
    val detailLoading: Boolean = false, val detailError: String? = null,
    val colours: Map<String, LutExampleColours> = emptyMap(),
    val coloursLoading: Boolean = false, val coloursCompleted: Int = 0, val coloursTotal: Int = 0,
    val exampleErrors: Map<String, String> = emptyMap(),
    val categoryPreviews: Map<String, List<LumixLabLut>> = emptyMap(),
    val categoryPreviewErrors: Map<String, String> = emptyMap(),
)

data class LumixLabGalleryQuery(val text: String = "", val family: LutColourFamily? = null,
    val exampleMode: LutExampleColourMode? = null, val minimumFamilyWeight: Float = .08f) {
    init { require(minimumFamilyWeight.isFinite() && minimumFamilyWeight in 0f..1f) }
}

/** Search/filter the loaded page set only. Unanalysed examples have unknown colours, never guessed tags. */
object LumixLabGalleryFilter {
    fun select(state: LumixLabBrowserState, query: LumixLabGalleryQuery): List<LumixLabLut> {
        val text = query.text.trim()
        return state.items.filter { item ->
            (text.isEmpty() || item.title.contains(text, true) || item.creator?.name?.contains(text, true) == true) &&
                (query.exampleMode == null || state.colours[item.code]?.mode == query.exampleMode) &&
                (query.family == null || (state.colours[item.code]?.familyWeights?.get(query.family) ?: 0f).let {
                    it > 0f && it >= query.minimumFamilyWeight
                })
        }
    }
}

/** Main-thread state coordinator. Supply a Main lifecycle scope; constructor sends no requests. */
@MainThread
class LumixLabBrowser(private val client: LumixLabCatalogueClient, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(LumixLabBrowserState())
    val state = mutable.asStateFlow()
    private var genreJob: Job? = null
    private var pageJob: Job? = null
    private var detailJob: Job? = null
    private var colourJob: Job? = null
    private var listRevision = 0L
    private var detailRevision = 0L
    private var genreRevision = 0L
    private val loadedQueries = linkedMapOf<LumixLabLutQuery, LumixLabBrowserState>()
    private val previewJobs = mutableMapOf<LumixLabLutQuery, Job>()
    private val loadedPreviews = linkedMapOf<LumixLabLutQuery, List<LumixLabLut>>()
    private var previewLanguage: String? = null

    /** Small real previews for the default category rows; only called after the user enters Lab. */
    fun loadCategoryPreviews(language: String) {
        if (previewLanguage != language) {
            previewJobs.values.forEach { it.cancel() }; previewJobs.clear()
            previewLanguage = language
            mutable.update { it.copy(categoryPreviews = emptyMap(), categoryPreviewErrors = emptyMap()) }
        }
        // Bound automatic preview work. Full categories and further pages remain explicit actions.
        mutable.value.genres.take(4).forEach { genre ->
            val query = LumixLabLutQuery(language, limit = 3, genreCode = genre.code)
            val saved = loadedPreviews[query]
            if (saved != null) {
                mutable.update { it.copy(categoryPreviews = it.categoryPreviews + (genre.code to saved)) }
            } else if (previewJobs[query]?.isActive != true) {
                previewJobs[query] = scope.launch {
                    try {
                        val items = client.luts(query).items
                        loadedPreviews[query] = items
                        while (loadedPreviews.size > 8) loadedPreviews.remove(loadedPreviews.keys.first())
                        if (previewLanguage == language) mutable.update { it.copy(
                            categoryPreviews = it.categoryPreviews + (genre.code to items),
                            categoryPreviewErrors = it.categoryPreviewErrors - genre.code) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (previewLanguage == language) mutable.update { it.copy(
                        categoryPreviewErrors = it.categoryPreviewErrors + (genre.code to (e.message ?: "分类例图暂不可用"))) } }
                }
            }
        }
    }

    private fun rememberLoadedQuery() {
        val current = mutable.value
        val query = current.query ?: return
        if (current.pageLoading || (current.items.isEmpty() && current.pageError != null)) return
        loadedQueries.remove(query)
        loadedQueries[query] = current
        while (loadedQueries.size > 8) loadedQueries.remove(loadedQueries.keys.first())
    }

    /** Revisit a loaded category with its continuation offset; explicit refresh still fetches anew. */
    fun selectQuery(query: LumixLabLutQuery) {
        require(query.offset == 0 && query.limit in 1..64)
        val snapshot = query.copy(countries = query.countries.toList())
        if (mutable.value.query == snapshot) return
        rememberLoadedQuery()
        val saved = loadedQueries[snapshot] ?: return refresh(snapshot)
        ++listRevision; pageJob?.cancel(); colourJob?.cancel(); closeDetail()
        mutable.update { it.copy(query = snapshot, items = saved.items, nextOffset = saved.nextOffset,
            pageLoading = false, pageError = saved.pageError, colours = saved.colours,
            coloursLoading = false, coloursCompleted = 0, coloursTotal = 0, exampleErrors = saved.exampleErrors) }
    }

    fun loadGenres() {
        genreJob?.cancel()
        val revision = ++genreRevision
        mutable.update { it.copy(genresLoading = true, genresError = null) }
        genreJob = scope.launch {
            try {
                val genres = client.genres()
                if (revision == genreRevision) mutable.update { it.copy(genres = genres) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (revision == genreRevision) mutable.update { it.copy(genresError = error.message ?: "分类加载失败") } }
            finally { if (revision == genreRevision) mutable.update { it.copy(genresLoading = false) } }
        }
    }

    fun refresh(query: LumixLabLutQuery) {
        require(query.offset == 0 && query.limit in 1..64) { "新目录需从 offset 0 开始" }
        val snapshot = query.copy(countries = query.countries.toList())
        rememberLoadedQuery()
        loadedQueries.remove(snapshot)
        pageJob?.cancel(); colourJob?.cancel()
        val revision = ++listRevision
        closeDetail()
        mutable.update { it.copy(query = snapshot, items = emptyList(), nextOffset = null, pageError = null,
            colours = emptyMap(), coloursLoading = false, coloursCompleted = 0, coloursTotal = 0, exampleErrors = emptyMap()) }
        requestPage(snapshot, revision, append = false)
    }

    /** Explicit continuation only; no auto-retry/paging loop and no claim that another page exists. */
    fun loadMore() {
        val current = mutable.value
        val query = current.query ?: return
        val offset = current.nextOffset ?: return
        if (current.pageLoading) return
        requestPage(query.copy(offset = offset), listRevision, append = true)
    }

    fun cancelPage() {
        ++listRevision
        pageJob?.cancel(); colourJob?.cancel()
        mutable.update { it.copy(pageLoading = false, coloursLoading = false) }
    }

    private fun requestPage(query: LumixLabLutQuery, revision: Long, append: Boolean) {
        mutable.update { it.copy(pageLoading = true, pageError = null) }
        pageJob = scope.launch {
            try {
                val page = client.luts(query)
                if (revision == listRevision) mutable.update { current ->
                    // Use the server's raw-item offset, not the length after duplicate suppression.
                    val merged = linkedMapOf<String, LumixLabLut>()
                    if (append) current.items.forEach { merged[it.code] = it }
                    page.items.forEach { merged[it.code] = it }
                    val unchanged = current.items.associateBy { it.code }
                    current.copy(items = merged.values.toList(), nextOffset = page.nextOffset?.takeIf { it > query.offset },
                        colours = current.colours.filterKeys { code -> unchanged[code]?.thumbnail == merged[code]?.thumbnail },
                        exampleErrors = current.exampleErrors.filterKeys { code -> unchanged[code]?.thumbnail == merged[code]?.thumbnail })
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (revision == listRevision) mutable.update { it.copy(pageError = error.message ?: "LUT 目录加载失败") } }
            finally { if (revision == listRevision) {
                mutable.update { it.copy(pageLoading = false) }
                rememberLoadedQuery()
            } }
        }
    }

    fun openDetail(code: String) {
        val language = mutable.value.query?.language ?: return
        detailJob?.cancel()
        val revision = ++detailRevision
        mutable.update { it.copy(detailCode = code, detail = null, detailLoading = true, detailError = null) }
        detailJob = scope.launch {
            try {
                val detail = client.detail(code, language)
                if (revision == detailRevision) mutable.update { it.copy(detail = detail) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (revision == detailRevision) mutable.update { it.copy(detailError = error.message ?: "LUT 详情加载失败") } }
            finally { if (revision == detailRevision) mutable.update { it.copy(detailLoading = false) } }
        }
    }

    /** Closing detail leaves the catalogue items/order and query untouched. Frontend owns scroll position. */
    fun closeDetail() {
        ++detailRevision
        detailJob?.cancel()
        mutable.update { it.copy(detailCode = null, detail = null, detailLoading = false, detailError = null) }
    }

    /** Viewport request: caller owns the returned image; state retains numeric colours only. */
    suspend fun example(item: LumixLabLut, longEdge: Int = 320): LumixLabExample {
        val revision = listRevision
        val example = client.example(item, longEdge)
        try {
            recordColours(item, example.colours, revision)
            return example
        } catch (error: Throwable) { example.image.recycle(); throw error }
    }

    private fun recordColours(item: LumixLabLut, colours: LutExampleColours, revision: Long) {
        if (revision != listRevision) return
        mutable.update { current ->
            if (current.items.none { it.code == item.code && it.thumbnail == item.thumbnail }) current
            else current.copy(colours = current.colours + (item.code to colours), exampleErrors = current.exampleErrors - item.code)
        }
    }

    /** Analyse all currently loaded candidates, including offscreen ones, one temporary image at a time. */
    fun analyseLoadedExamples() {
        if (mutable.value.coloursLoading) return
        val snapshot = mutable.value
        val revision = listRevision
        val candidates = snapshot.items.filter { it.code !in snapshot.colours }
        mutable.update { it.copy(coloursLoading = true, coloursCompleted = 0, coloursTotal = candidates.size) }
        colourJob = scope.launch {
            try {
                for (item in candidates) {
                    coroutineContext.ensureActive()
                    try {
                        val example = client.example(item)
                        try { recordColours(item, example.colours, revision) } finally { example.image.recycle() }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        if (revision == listRevision) mutable.update { current ->
                            if (current.items.none { it.code == item.code && it.thumbnail == item.thumbnail }) current
                            else current.copy(exampleErrors = current.exampleErrors +
                                (item.code to (error.message ?: "例图取色失败")))
                        }
                    }
                    if (revision == listRevision) mutable.update { it.copy(coloursCompleted = it.coloursCompleted + 1) }
                }
            } finally { if (revision == listRevision) mutable.update { it.copy(coloursLoading = false) } }
        }
    }

    /** Loading clears after this job's cleanup, so a new analysis cannot overlap its predecessor. */
    fun cancelExampleAnalysis() { colourJob?.cancel() }

    /** Cancel owned requests only. Local editing/export and caller-owned card images are unaffected. */
    fun cancelRequests() {
        ++genreRevision; genreJob?.cancel()
        cancelPage(); closeDetail()
        mutable.update { it.copy(genresLoading = false) }
    }
}
