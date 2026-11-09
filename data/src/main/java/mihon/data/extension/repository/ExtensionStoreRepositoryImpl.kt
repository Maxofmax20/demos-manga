package mihon.data.extension.repository

import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler

class ExtensionStoreRepositoryImpl(
    private val service: ExtensionStoreService,
    private val handler: DatabaseHandler,
) : ExtensionStoreRepository {
    override suspend fun insert(indexUrl: String): Result<Unit> {
        return service.fetch(indexUrl).mapCatching { upsert(it) }
    }

    override suspend fun insertFromPreference(indexUrl: String, name: String) {
        handler.await {
            extension_storeQueries.upsert(
                indexUrl = indexUrl,
                name = name,
                badgeLabel = name,
                signingKey = "NO_SIGNING_KEY",
                contactWebsite = indexUrl,
                contactDiscord = null,
                isLegacy = false,
                extensionListUrl = null,
            )
        }
    }

    // KMK -->
    // Repair only the known, invalid Keiyoushi URL; do not rewrite user-added Yuzono stores.
    private fun resolvedIndexUrl(url: String, name: String): String =
        if (name.equals("Keiyoushi", ignoreCase = true) &&
            url == "https://raw.githubusercontent.com/yuzono/manga-repo/repo/index.pb"
        ) {
            "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        } else {
            url
        }
    // KMK <--

    override suspend fun refreshAll() {
        try {
            handler.awaitList {
                extension_storeQueries.getAll()
            }.forEach { store ->
                // KMK -->
                service.fetch(resolvedIndexUrl(store.index_url, store.name))
                    // KMK <--
                    .mapCatching {
                        handler.await {
                            upsert(it)
                            if (store.index_url != it.indexUrl) {
                                extension_storeQueries.delete(store.index_url)
                            }
                        }
                    }
                    .onFailure {
                        logcat(LogPriority.ERROR, it) {
                            "Failed to refresh extension store '${store.name} (${store.index_url})'"
                        }
                    }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    private suspend fun upsert(store: ExtensionStore) {
        handler.await {
            extension_storeQueries.upsert(
                indexUrl = store.indexUrl,
                name = store.name,
                badgeLabel = store.badgeLabel,
                signingKey = store.signingKey,
                contactWebsite = store.contact.website,
                contactDiscord = store.contact.discord,
                isLegacy = store.isLegacy,
                extensionListUrl = store.extensionListUrl,
            )
        }
    }

    override suspend fun fetchExtensions(
        // KMK -->
        disabledRepos: Set<String>,
        // KMK <--
    ): List<Extension.Available> {
        return try {
            supervisorScope {
                handler.awaitList {
                    extension_storeQueries.getAll(::extensionStoreMapper)
                }
                    // KMK -->
                    .filterNot { it.indexUrl in disabledRepos }
                    // KMK <--
                    .map { store ->
                        async {
                            // KMK -->
                            // Fetch from the corrected store even before refreshAll persists the migration.
                            val resolvedUrl = resolvedIndexUrl(store.indexUrl, store.name)
                            val activeStore = if (resolvedUrl != store.indexUrl) {
                                store.copy(indexUrl = resolvedUrl, extensionListUrl = null)
                            } else {
                                store
                            }
                            service.getExtensions(activeStore).onFailure {
                                // KMK <--
                                this@ExtensionStoreRepositoryImpl.logcat(LogPriority.ERROR, it) {
                                    "Failed to fetch extensions for store '${store.name} (${store.indexUrl})'"
                                }
                            }
                        }
                    }
                    .awaitAll()
                    .flatMap { it.getOrDefault(emptyList()) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    override suspend fun getAll(): List<ExtensionStore> {
        return handler.awaitList { extension_storeQueries.getAll(::extensionStoreMapper) }
    }

    override fun getAllAsFlow(): Flow<List<ExtensionStore>> {
        return handler.subscribeToList { extension_storeQueries.getAll(::extensionStoreMapper) }
    }

    override fun getCountAsFlow(): Flow<Long> {
        return handler.subscribeToOne {
            extension_storeQueries
                .getCount()
        }
    }

    override suspend fun remove(indexUrl: String) {
        handler.await { extension_storeQueries.delete(indexUrl) }
    }

    private fun extensionStoreMapper(
        indexUrl: String,
        name: String,
        badgeLabel: String,
        signingKey: String,
        contactWebsite: String,
        contactDiscord: String?,
        isLegacy: Boolean,
        extensionListUrl: String?,
    ): ExtensionStore = ExtensionStore(
        indexUrl = indexUrl,
        name = name,
        badgeLabel = badgeLabel,
        signingKey = signingKey,
        contact = ExtensionStore.Contact(
            website = contactWebsite,
            discord = contactDiscord,
        ),
        isLegacy = isLegacy,
        extensionListUrl = extensionListUrl,
    )
}
