package dev.mangaflow

import dev.mangaflow.account.CopyAccountRepository
import dev.mangaflow.account.CopyReadingOutbox
import dev.mangaflow.account.CopyShelfSyncController
import dev.mangaflow.background.ApplicationOperations
import dev.mangaflow.data.CopyAnonymousIdentityStore
import dev.mangaflow.data.CopyCommunityRepository
import dev.mangaflow.data.CopyMangaRepository
import dev.mangaflow.data.MangaMetricsRepository
import dev.mangaflow.data.ReviewRepository
import dev.mangaflow.data.SiteRankingsRepository
import dev.mangaflow.data.SourceCheckController
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.plugins.ScriptBodySource
import dev.mangaflow.data.plugins.ScriptSourcePackage
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.data.plugins.StarterSourceInstaller
import dev.mangaflow.data.sources.BaoziBodySource
import dev.mangaflow.data.sources.GuaziBodySource
import dev.mangaflow.data.sources.ManhuarenBodySource
import dev.mangaflow.data.sources.RumanhuaBodySource
import dev.mangaflow.data.sources.ZaimanhuaBodySource
import dev.mangaflow.data.sources.KomiicBodySource
import dev.mangaflow.data.sources.MangaBzBodySource
import dev.mangaflow.data.sources.MangaDexBodySource
import dev.mangaflow.data.sources.ManhuaguiBodySource
import dev.mangaflow.data.sources.PicacgBodySource
import dev.mangaflow.data.sources.createJmBodySource
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.net.RelayLinkController
import dev.mangaflow.net.NetworkAutoCheckController
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.storage.BrowseCache
import dev.mangaflow.storage.ChapterPreparationStore
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReaderCatalogStore
import dev.mangaflow.storage.UnifiedChapterStore
import dev.mangaflow.update.UpdateManager
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** One process owns stores and account writers; screens own only presentation requests. */
internal class MangaApplicationServices private constructor(context: PlatformContext) {
    private val application = applicationContext(context)
    val operations = ApplicationOperations.get(application)
    val scope = operations.scope
    val relayLink = RelayLinkController.get(application)
    private val sourcePreferences = preferences(application, "mangaflow_library")
    val copyRepository = CopyMangaRepository(identityProvider = { CopyAnonymousIdentityStore.load(application) },
        cachePreferences = { dev.mangaflow.experience.contentCachePreferences(application) })
    val komiicSource = KomiicBodySource {
        sourcePreferences.getString("body_source_komiic_domain", KomiicBodySource.BASE_URLS.first())
            ?.takeIf { it in KomiicBodySource.BASE_URLS } ?: KomiicBodySource.BASE_URLS.first()
    }
    val picacgSource = PicacgBodySource(application)
    val unifiedChapters = UnifiedChapterStore(application)
    val readerCatalogs = ReaderCatalogStore(application)
    val repository = UnifiedMangaRepository(copyRepository, unifiedChapters,
        bodySources = listOf(BaoziBodySource(), ManhuaguiBodySource(), MangaBzBodySource(
            cachePreferences = { dev.mangaflow.experience.contentCachePreferences(application) }), komiicSource,
            MangaDexBodySource(), picacgSource, GuaziBodySource(), ManhuarenBodySource(),
            RumanhuaBodySource(), ZaimanhuaBodySource()) + listOfNotNull(createJmBodySource(application)),
        catalogStore = readerCatalogs, storageCache = dev.mangaflow.storage.StorageCacheStore.get(application))
    val bodySources = BodySourceRegistry(application, repository.sources)
    val networkChecks = NetworkAutoCheckController(application, repository, bodySources, operations,
        prepareSources = ::awaitSourcesReady)
    val sourcePlugins = SourcePluginStore.get(application)
    val starterSources = StarterSourceInstaller(application, sourcePlugins, scope)
    val library = LibraryStore(application)
    val sourceChecks = SourceCheckController(application, repository, library, operations,
        prepareSources = ::awaitSourcesReady)
    val downloads = DownloadStore.get(application)
    val updates = UpdateManager.get(application)
    val community = CopyCommunityRepository(copyRepository)
    val reviews = ReviewRepository(application)
    val siteRankingsRepository = SiteRankingsRepository(application)
    val browseCache = BrowseCache(application)
    val metricsRepository = MangaMetricsRepository(application, reviews, community)
    val accountRepository = CopyAccountRepository(application)
    val copyShelfSync = CopyShelfSyncController(application, accountRepository, library, repository, scope)
    val readingOutbox = CopyReadingOutbox(application, accountRepository, scope)

    private val sourceSync = Mutex()
    private var installedSourceKey: String? = null
    private val mutableSourceConfiguration = MutableStateFlow(repository.sourceConfigurationKey())
    val sourceConfiguration = mutableSourceConfiguration.asStateFlow()
    private val mutableSourceError = MutableStateFlow<String?>(null)
    val sourceError = mutableSourceError.asStateFlow()
    private val passiveSyncAllowed = MutableStateFlow(false)
    private val passiveSyncLock = SynchronizedObject()
    private val passiveSyncLeases = mutableSetOf<Long>()
    private var passiveSyncSequence = 0L
    val preparations = ChapterPreparationStore(application, repository, downloads, scope, ::awaitSourcesReady)

    init {
        relayLink.start(scope)
        repository.setEnabledSources(bodySources.enabledSourceIds)
        scope.launch {
            try {
                sourcePlugins.ready.await()
                sourcePlugins.installed.collect { packages ->
                    try { attachSources(packages) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { mutableSourceError.value = "第三方源暂未接入，请在漫画源设置中重试。" }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableSourceError.value = "第三方源未能加载，请在漫画源设置中重试。" }
        }
        scope.launch {
            bodySources.entries.collect { entries ->
                relayLink.updateSources(entries.filter { it.enabled && !it.removed && it.isInstalled }
                    .associate { it.source.id to it.source.homeUrl })
                withContext(Dispatchers.Default) {
                    sourceSync.withLock {
                        repository.setEnabledSources(bodySources.enabledSourceIds)
                        mutableSourceConfiguration.value = repository.sourceConfigurationKey()
                    }
                }
            }
        }
        scope.launch {
            library.backupRestored.drop(1).collect {
                copyShelfSync.invalidateAfterRestore()
                try { bodySources.reload() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { mutableSourceError.value = "漫画源设置未能刷新，请重新打开应用。" }
            }
        }
        scope.launch {
            accountRepository.profile.collect {
                copyShelfSync.accountChanged()
                readingOutbox.accountChanged()
            }
        }
        scope.launch {
            combine(passiveSyncAllowed, accountRepository.profile,
                ExperiencePolicyStore.get(application).state) { allowed, owner, budget ->
                allowed && owner != null && budget.foreground
            }.distinctUntilChanged().collectLatest { allowed ->
                if (!allowed) return@collectLatest
                try {
                    while (true) {
                        copyShelfSync.checkInBackground()
                        kotlinx.coroutines.delay(5 * 60_000L)
                    }
                } finally {
                    copyShelfSync.pauseCheck()
                }
            }
        }
    }

    /** Reusable by system workers; source preparation never installs a missing runtime itself. */
    suspend fun awaitSourcesReady() {
        sourcePlugins.ready.await()
        attachSources(sourcePlugins.installed.value)
    }

    private suspend fun attachSources(packages: List<ScriptSourcePackage>) = withContext(Dispatchers.Default) {
        sourceSync.withLock {
            val key = packages.joinToString("|") { "${it.sourceId}:${it.digest}" }
            if (installedSourceKey != key) {
                repository.replaceImportedSources(packages.map { ScriptBodySource(it, sourcePlugins.runtime, application) })
                bodySources.updateInstalledSources(repository.sources)
                installedSourceKey = key
            }
            repository.setEnabledSources(bodySources.enabledSourceIds)
            mutableSourceConfiguration.value = repository.sourceConfigurationKey()
            mutableSourceError.value = null
        }
    }

    /** A retained/outgoing window can release only its own passive-check permission. */
    fun retainPassiveSync(): () -> Unit {
        val token = synchronized(passiveSyncLock) {
            (++passiveSyncSequence).also { passiveSyncLeases += it; passiveSyncAllowed.value = true }
        }
        return {
            synchronized(passiveSyncLock) {
                passiveSyncLeases.remove(token)
                passiveSyncAllowed.value = passiveSyncLeases.isNotEmpty()
            }
        }
    }

    fun resumePending() {
        sourceChecks.resume()
        downloads.resumePendingDownloads()
        preparations.resumePending()
        readingOutbox.resume()
    }

    companion object {
        private val lock = SynchronizedObject()
        @Volatile private var instance: MangaApplicationServices? = null
        fun get(context: PlatformContext): MangaApplicationServices = instance ?: synchronized(lock) {
            instance ?: MangaApplicationServices(applicationContext(context)).also { instance = it }
        }
    }
}
