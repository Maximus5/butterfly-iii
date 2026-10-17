package io.github.maximus5.ffnotifyimporter.posting

import io.github.maximus5.ffnotifyimporter.network.FireflyAccount
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.FireflyCategory
import io.github.maximus5.ffnotifyimporter.network.FireflyCurrency
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs

/**
 * Firefly's category and tag names, cached in memory for the whole process so that the screens
 * offering them (transaction create/edit, rule editor, transaction settings) do not each ask the
 * server every time they open.
 *
 * Fresh for [TTL_MILLIS]; after that the next call refetches, and if that fails the stale list is
 * still returned (a failure only reaches the caller when there is nothing cached at all). The cached
 * list is replaced only by a request that finished successfully: a failed or half-read refresh
 * never clears or shrinks it. The cache belongs to one server address - a different base URL discards it. Memory only, on purpose: the
 * names can be personal and the app stores nothing about the server's data on disk beyond what the
 * user opted into. [remember] adds what a just-saved transaction used, so the next suggestion list
 * already has a brand-new category or tag.
 */
object FireflySuggestions {

    private const val TTL_MILLIS = 10 * 60 * 1000L

    private class Entry<T>(val baseUrl: String, val fetchedAt: Long, val values: List<T>)

    /** Called once for every request that failed after all its retries (kind = what was being loaded,
     * message = what the user may read). Set by the app to show a short notice; the diagnostic log
     * has the details. A failure with a usable cached list still counts - the refresh did fail. */
    @Volatile var onLoadFailed: ((kind: Kind, message: String) -> Unit)? = null

    enum class Kind { CATEGORIES, TAGS, ASSET_ACCOUNTS, CURRENCIES }

    @Volatile private var categoryEntry: Entry<FireflyCategory>? = null
    @Volatile private var tagEntry: Entry<String>? = null
    @Volatile private var assetAccountEntry: Entry<FireflyAccount>? = null
    @Volatile private var currencyEntry: Entry<FireflyCurrency>? = null

    /** Asset accounts only (`listAssetAccounts`) - the ones a transaction can be booked from/to.
     * Accounts are created outside this app (unlike categories and tags, which [remember] keeps
     * current), so a screen that offers them passes [forceRefresh] = true when it opens: the list
     * is fetched anew every time, and the cached one is only the fallback if that fails. The
     * process outlives the visible app (the notification listener keeps it alive), so the TTL
     * alone left a just-added account missing for up to 10 minutes. */
    suspend fun assetAccounts(prefs: FireflyPrefs, forceRefresh: Boolean = false): FireflyApiClient.Result<List<FireflyAccount>> =
        load(Kind.ASSET_ACCOUNTS, prefs, assetAccountEntry, { it.listAssetAccounts() }, { assetAccountEntry = it }, forceRefresh)

    /** Only the currencies switched on in the user's Firefly (the API has no server-side filter). */
    suspend fun enabledCurrencies(prefs: FireflyPrefs): FireflyApiClient.Result<List<FireflyCurrency>> =
        load(Kind.CURRENCIES, prefs, currencyEntry, { client ->
            when (val result = client.listCurrencies()) {
                is FireflyApiClient.Result.Success -> FireflyApiClient.Result.Success(result.value.filter { it.enabled })
                is FireflyApiClient.Result.Failure -> result
            }
        }, { currencyEntry = it })

    /** [forceRefresh]: an editing screen passes true when it opens, so a category made in Firefly's own
     * web UI is offered at once (the cache is only the fallback if the request fails). */
    suspend fun categories(prefs: FireflyPrefs, forceRefresh: Boolean = false): FireflyApiClient.Result<List<FireflyCategory>> =
        load(Kind.CATEGORIES, prefs, categoryEntry, { it.listCategories() }, { categoryEntry = it }, forceRefresh)

    suspend fun tags(prefs: FireflyPrefs, forceRefresh: Boolean = false): FireflyApiClient.Result<List<String>> =
        load(Kind.TAGS, prefs, tagEntry, { it.listTags() }, { tagEntry = it }, forceRefresh)

    private suspend fun <T> load(
        kind: Kind,
        prefs: FireflyPrefs,
        current: Entry<T>?,
        fetch: suspend (FireflyApiClient) -> FireflyApiClient.Result<List<T>>,
        store: (Entry<T>) -> Unit,
        forceRefresh: Boolean = false,
    ): FireflyApiClient.Result<List<T>> {
        val baseUrl = prefs.getBaseUrl()
        val cached = current?.takeIf { it.baseUrl == baseUrl }
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.fetchedAt < TTL_MILLIS) {
            return FireflyApiClient.Result.Success(cached.values)
        }
        return when (val result = fetch(prefs.apiClient())) {
            is FireflyApiClient.Result.Success -> {
                store(Entry(baseUrl, System.currentTimeMillis(), result.value))
                result
            }
            is FireflyApiClient.Result.Failure -> {
                onLoadFailed?.invoke(kind, result.message)
                if (cached != null) FireflyApiClient.Result.Success(cached.values) else result
            }
        }
    }

    /** Adds [category] and [tags] to the cached lists (case-insensitive, no duplicates) when those
     * lists exist - nothing to do when they were never loaded. */
    fun remember(prefs: FireflyPrefs, category: String?, tags: List<String>) {
        val baseUrl = prefs.getBaseUrl()
        if (!category.isNullOrBlank()) {
            categoryEntry?.takeIf { it.baseUrl == baseUrl }?.let { entry ->
                if (entry.values.none { it.name.equals(category, ignoreCase = true) }) {
                    categoryEntry = Entry(baseUrl, entry.fetchedAt, entry.values + FireflyCategory("", category))
                }
            }
        }
        tagEntry?.takeIf { it.baseUrl == baseUrl }?.let { entry ->
            val added = tags.filter { tag -> tag.isNotBlank() && entry.values.none { it.equals(tag, ignoreCase = true) } }
            if (added.isNotEmpty()) tagEntry = Entry(baseUrl, entry.fetchedAt, entry.values + added)
        }
    }
}
