package dev.mangaflow

import android.content.Context
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaBakaBook
import dev.mangaflow.data.MangaBakaCatalog
import dev.mangaflow.data.MangaBakaPage
import dev.mangaflow.data.MangaBakaRelation
import dev.mangaflow.data.MangaBakaShelf
import dev.mangaflow.data.MangaBakaStack
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.normalizeReviewTitle
import dev.mangaflow.data.reviewMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface MangaBakaRoute {
    val key: String
    data class Home(val shelf: MangaBakaShelf) : MangaBakaRoute { override val key = "home:$shelf" }
    data class Work(val id: String, val relation: MangaBakaRelation = MangaBakaRelation.SIMILAR) : MangaBakaRoute {
        override val key = "work:$id:$relation"
    }
    data class Stack(val id: String) : MangaBakaRoute { override val key = "stack:$id" }
}

internal data class MangaBakaState(
    val route: MangaBakaRoute = MangaBakaRoute.Home(MangaBakaShelf.GEMS),
    val book: MangaBakaBook? = null,
    val bookResolved: Boolean = false,
    val stack: MangaBakaStack? = null,
    val books: List<MangaBakaBook> = emptyList(),
    val stacks: List<MangaBakaStack> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val page: Int = 1,
    val nextPage: Int? = null,
    val error: String? = null,
    val failedPage: Int? = null,
    val canGoBack: Boolean = false,
    val readingLoading: Boolean = false,
    val readingError: String? = null,
    val readingCandidates: List<Manga> = emptyList(),
    val searchedReading: Boolean = false,
)

/** ViewModel-owned public discovery; every outstanding response belongs to one route generation. */
internal class MangaBakaExplorer(context: Context, private val reader: MangaRepository, private val scope: CoroutineScope) {
    private val catalog = MangaBakaCatalog(context)
    private val mutableState = MutableStateFlow(MangaBakaState())
    val state = mutableState.asStateFlow()
    private val history = ArrayDeque<MangaBakaState>()
    private val home = mutableMapOf<MangaBakaShelf, MangaBakaState>()
    private var shelf = MangaBakaShelf.GEMS
    private var generation = 0L
    private var job: Job? = null
    private var readingJob: Job? = null

    fun openHome() {
        stop()
        history.clear()
        mutableState.value = home[shelf]?.copy(canGoBack = false) ?: MangaBakaState(route = MangaBakaRoute.Home(shelf))
        if (!mutableState.value.loaded) load()
    }

    fun selectShelf(value: MangaBakaShelf) {
        if (mutableState.value.route == MangaBakaRoute.Home(value)) return
        rememberHome()
        stop()
        shelf = value
        history.clear()
        mutableState.value = home[value]?.copy(canGoBack = false) ?: MangaBakaState(route = MangaBakaRoute.Home(value))
        if (!mutableState.value.loaded) load()
    }

    fun openBook(book: MangaBakaBook) = navigate(MangaBakaState(route = MangaBakaRoute.Work(book.id), book = book))
    fun openStack(stack: MangaBakaStack) = navigate(MangaBakaState(route = MangaBakaRoute.Stack(stack.id), stack = stack))

    fun selectRelation(value: MangaBakaRelation) {
        val current = mutableState.value
        val route = current.route as? MangaBakaRoute.Work ?: return
        if (route.relation == value) return
        stop()
        mutableState.value = current.copy(route = route.copy(relation = value), books = emptyList(), page = 1,
            nextPage = null, loaded = false, error = null, failedPage = null, loading = false, loadingMore = false, readingLoading = false)
        load()
    }

    fun back() {
        if (history.isEmpty()) return
        stop()
        mutableState.value = history.removeLast().copy(canGoBack = history.isNotEmpty(), loading = false,
            loadingMore = false, readingLoading = false)
        resume()
    }

    fun refresh() = load(force = true)
    fun retry() = load(force = true, page = mutableState.value.failedPage ?: 1)
    fun resume() { if (!mutableState.value.loaded && !mutableState.value.loading && mutableState.value.error == null) load() }
    fun more() {
        val current = mutableState.value
        if (!current.loading && !current.loadingMore) current.nextPage?.let { load(page = it) }
    }

    fun close() { rememberHome(); stop() }

    private fun rememberHome() {
        val current = mutableState.value
        (current.route as? MangaBakaRoute.Home)?.let {
            if (current.loaded) home[it.shelf] = current.copy(loading = false, loadingMore = false, canGoBack = false)
        }
    }

    private fun navigate(target: MangaBakaState) {
        val previous = mutableState.value
        if (target.route.key == previous.route.key) return
        rememberHome()
        stop()
        if (history.size >= 16) history.removeFirst()
        history.addLast(previous.copy(loading = false, loadingMore = false, readingLoading = false))
        mutableState.value = target.copy(canGoBack = true)
        load()
    }

    private fun stop() {
        generation++
        job?.cancel()
        readingJob?.cancel()
        mutableState.update { it.copy(loading = false, loadingMore = false, readingLoading = false) }
    }

    private fun load(force: Boolean = false, page: Int = 1) {
        job?.cancel()
        readingJob?.cancel()
        val request = ++generation
        val start = mutableState.value
        val append = page > 1
        mutableState.value = start.copy(loading = !append, loadingMore = append, error = null, failedPage = null, readingLoading = false)
        job = scope.launch {
            try {
                val result = when (val route = start.route) {
                    is MangaBakaRoute.Home -> catalog.shelf(route.shelf, page, force)
                    is MangaBakaRoute.Work -> {
                        val book = start.book.takeIf { start.bookResolved && !force } ?: catalog.book(route.id, force)
                        if (request != generation) return@launch
                        mutableState.update { it.copy(book = book, bookResolved = true) }
                        catalog.related(book, route.relation, page, force)
                    }
                    is MangaBakaRoute.Stack -> {
                        val stack = catalog.stack(route.id, force)
                        if (request != generation) return@launch
                        mutableState.update { it.copy(stack = stack) }
                        MangaBakaPage(books = stack.books)
                    }
                }
                if (request != generation) return@launch
                mutableState.update { current -> current.copy(
                    books = (if (append) start.books + result.books else result.books).distinctBy { it.id + ":" + it.relation },
                    stacks = (if (append) start.stacks + result.stacks else result.stacks).distinctBy { it.id },
                    loading = false, loadingMore = false, loaded = true, page = page, nextPage = result.nextPage, error = null, failedPage = null) }
                rememberHome()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                if (request == generation) mutableState.update { it.copy(loading = false, loadingMore = false,
                    error = error.reviewMessage("暂时无法读取 MangaBaka，请稍后重试。"), failedPage = page) }
            }
        }
    }

    /** Never pass a catalog ID to Copy; every choice here comes from Copy's real search. */
    fun findReading() {
        val current = mutableState.value
        val book = current.book ?: return
        if (current.readingLoading) return
        readingJob?.cancel()
        val request = generation
        val key = current.route.key
        mutableState.update { it.copy(readingLoading = true, readingError = null, readingCandidates = emptyList(), searchedReading = false) }
        readingJob = scope.launch {
            val candidates = linkedMapOf<String, Manga>()
            try {
                for (name in book.aliases.distinctBy(::normalizeReviewTitle).take(2)) {
                    reader.search(name).items.forEach { candidates[it.id] = it }
                }
                if (request != generation || key != mutableState.value.route.key) return@launch
                val titles = book.aliases.map(::normalizeReviewTitle).toSet()
                mutableState.update { it.copy(readingLoading = false, searchedReading = true,
                    readingCandidates = candidates.values.sortedByDescending { normalizeReviewTitle(it.title) in titles }) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                if (request == generation && key == mutableState.value.route.key) mutableState.update {
                    it.copy(readingLoading = false, searchedReading = true, readingCandidates = candidates.values.toList(),
                        readingError = error.reviewMessage("拷贝暂时无法查询，请稍后重试。"))
                }
            }
        }
    }

    suspend fun sizeBytes() = catalog.sizeBytes()
    fun release() { stop(); catalog.close() }
    suspend fun clearCache() {
        stop()
        job?.cancelAndJoin()
        readingJob?.cancelAndJoin()
        catalog.clear()
        history.clear()
        home.clear()
        mutableState.value = MangaBakaState(route = MangaBakaRoute.Home(shelf))
    }
}
