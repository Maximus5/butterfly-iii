package io.github.maximus5.ffnotifyimporter.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin wrapper over Firefly III's REST API - plain [HttpURLConnection] + `org.json`
 * ([FireflyUrl]'s [parseAboutVersion]), not OkHttp/Retrofit. Same "avoid a new dependency when
 * the framework already covers it" reasoning already used for the planned rules export/import
 * JSON format (see TODO.md) - a handful of endpoints doesn't justify a full HTTP client library.
 */
class FireflyApiClient(
    baseUrl: String,
    private val token: String,
    private val allowCleartext: Boolean = false,
    private val log: ((String) -> Unit)? = null,
) {

    private val baseUrl = normalizeBaseUrl(baseUrl)

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    /**
     * Hits `GET /api/v1/about` - the lightest authenticated endpoint Firefly has - to confirm the
     * URL/token actually work before the user saves them. Returns the reported Firefly version on
     * success, purely so "Test connection" can show something more convincing than just "OK".
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            retrying("GET", "/api/v1/about") {
                val connection = openConnection("/api/v1/about", "GET")
                val code = connection.responseCode
                if (code in 200..299) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    Result.Success(parseAboutVersion(body).orEmpty())
                } else {
                    Result.Failure(errorMessage(connection, code, "GET", "/api/v1/about"))
                }
            }
        } catch (e: IOException) {
            ioFailure(e, "GET", "/api/v1/about")
        }
    }

    /**
     * Every asset account (`?type=asset`) the user has in Firefly - the rule editor's planned
     * asset-account picker source (see TODO.md): Firefly only auto-creates the *counterparty*
     * side of a transaction by name, the asset side must already exist, so a rule's asset-side
     * field needs to pick from this list rather than stay free text. Follows `meta.pagination`
     * across every page rather than assuming the first 50 is everything.
     */
    suspend fun listAssetAccounts(): Result<List<FireflyAccount>> = withContext(Dispatchers.IO) {
        try {
            val accounts = mutableListOf<FireflyAccount>()
            var page = 1
            var totalPagesSeen: Int
            do {
                val body = retrying("GET", "/api/v1/accounts") {
                    val connection = openConnection("/api/v1/accounts?type=asset&page=$page", "GET")
                    val code = connection.responseCode
                    if (code !in 200..299) return@withContext Result.Failure(errorMessage(connection, code, "GET", "/api/v1/accounts"))
                    connection.inputStream.bufferedReader().use { it.readText() }
                }
                if (!isListResponse(body)) return@withContext unreadable("GET", "/api/v1/accounts", page)
                accounts += parseAccountList(body)
                totalPagesSeen = totalPages(body)
                page++
            } while (page <= totalPagesSeen)
            record("GET", "/api/v1/accounts", "ok, ${page - 1} page(s), ${accounts.size} items")
            Result.Success(accounts)
        } catch (e: IOException) {
            ioFailure(e, "GET", "/api/v1/accounts")
        }
    }

    /**
     * Every category (`GET /v1/categories`) the user has in Firefly -
     * `ui.CreateTransactionFragment`'s category picker source (see TODO.md): a category picked
     * from this list can't be posted as a typo'd/misspelled new one the way the free-text
     * `ParseRule.categoryTemplate` path already risks. Same pagination/response shape as
     * [listAssetAccounts] (`ShowController@index`, confirmed against Firefly's current
     * `routes/api.php`, not assumed).
     */
    suspend fun listCategories(): Result<List<FireflyCategory>> = withContext(Dispatchers.IO) {
        try {
            val categories = mutableListOf<FireflyCategory>()
            var page = 1
            var totalPagesSeen: Int
            do {
                val body = retrying("GET", "/api/v1/categories") {
                    val connection = openConnection("/api/v1/categories?page=$page", "GET")
                    val code = connection.responseCode
                    if (code !in 200..299) return@withContext Result.Failure(errorMessage(connection, code, "GET", "/api/v1/categories"))
                    connection.inputStream.bufferedReader().use { it.readText() }
                }
                if (!isListResponse(body)) return@withContext unreadable("GET", "/api/v1/categories", page)
                categories += parseCategoryList(body)
                totalPagesSeen = totalPages(body)
                page++
            } while (page <= totalPagesSeen)
            record("GET", "/api/v1/categories", "ok, ${page - 1} page(s), ${categories.size} items")
            Result.Success(categories)
        } catch (e: IOException) {
            ioFailure(e, "GET", "/api/v1/categories")
        }
    }

    /** Every tag name (`GET /v1/tags`, paginated the same way as [listCategories]) - read-only, so
     * it goes through [withNetworkRetry] like the other lists. Feeds the tags field's suggestions. */
    suspend fun listTags(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val tags = mutableListOf<String>()
            var page = 1
            var totalPagesSeen: Int
            do {
                val body = retrying("GET", "/api/v1/tags") {
                    val connection = openConnection("/api/v1/tags?page=$page", "GET")
                    val code = connection.responseCode
                    if (code !in 200..299) return@withContext Result.Failure(errorMessage(connection, code, "GET", "/api/v1/tags"))
                    connection.inputStream.bufferedReader().use { it.readText() }
                }
                if (!isListResponse(body)) return@withContext unreadable("GET", "/api/v1/tags", page)
                tags += parseTagList(body)
                totalPagesSeen = totalPages(body)
                page++
            } while (page <= totalPagesSeen)
            record("GET", "/api/v1/tags", "ok, ${page - 1} page(s), ${tags.size} items")
            Result.Success(tags)
        } catch (e: IOException) {
            ioFailure(e, "GET", "/api/v1/tags")
        }
    }

    /**
     * Every currency Firefly knows about (`GET /v1/currencies`, `ShowController@index`, confirmed
     * against `routes/api.php`'s current source) - the endpoint itself has no server-side "enabled
     * only" filter (`repository->getAll()`, unconditional), so [parseCurrencyList]'s own `enabled`
     * field is what callers actually filter on. `ui.TransactionCreationFragment`'s "Default
     * account currency" picker source - see TODO.md for why this setting exists. Same
     * `meta.pagination`-following shape as [listAssetAccounts]/[listCategories].
     */
    suspend fun listCurrencies(): Result<List<FireflyCurrency>> = withContext(Dispatchers.IO) {
        try {
            val currencies = mutableListOf<FireflyCurrency>()
            var page = 1
            var totalPagesSeen: Int
            do {
                val body = retrying("GET", "/api/v1/currencies") {
                    val connection = openConnection("/api/v1/currencies?page=$page", "GET")
                    val code = connection.responseCode
                    if (code !in 200..299) return@withContext Result.Failure(errorMessage(connection, code, "GET", "/api/v1/currencies"))
                    connection.inputStream.bufferedReader().use { it.readText() }
                }
                if (!isListResponse(body)) return@withContext unreadable("GET", "/api/v1/currencies", page)
                currencies += parseCurrencyList(body)
                totalPagesSeen = totalPages(body)
                page++
            } while (page <= totalPagesSeen)
            record("GET", "/api/v1/currencies", "ok, ${page - 1} page(s), ${currencies.size} items")
            Result.Success(currencies)
        } catch (e: IOException) {
            ioFailure(e, "GET", "/api/v1/currencies")
        }
    }

    /** `POST /v1/transactions` - returns the created transaction's ID on success, for this app's
     * own "created transactions" log (dedup + "already created" state, see TODO.md).
     *
     * Deliberately **not** wrapped in [withNetworkRetry], unlike every read-only call in this
     * class - confirmed against Firefly's own `StoreController::store()` source: there's no
     * `Idempotency-Key` mechanism, only a server-side content-based duplicate check
     * (`DuplicateTransactionException`) that's a heuristic on Firefly's own terms, not a
     * transport-level guarantee. The failure mode that matters is a timeout *after* the server
     * already processed the write but before the response made it back here - retrying in that
     * case would risk silently creating a second real transaction, exactly what this app's own
     * dedup machinery exists to prevent. A failed create surfaces the error and stops; the caller
     * decides whether to try again, same as before this file had any retry logic at all. */
    suspend fun createTransaction(request: FireflyTransactionRequest): Result<String> = withContext(Dispatchers.IO) {
        try {
            val connection = openConnection("/api/v1/transactions", "POST")
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(buildTransactionRequestBody(request).toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val id = parseCreatedTransactionId(body)
                if (id != null) Result.Success(id) else noTransactionId(body, "POST", "/api/v1/transactions")
            } else {
                Result.Failure(errorMessage(connection, code, "POST", "/api/v1/transactions"))
            }
        } catch (e: IOException) {
            ioFailure(e, "POST", "/api/v1/transactions")
        }
    }

    /** `GET /v1/search/transactions?query=...` - Firefly's own operator-trigger search (see
     * [dedupSearchQuery]'s KDoc), *not* scoped to an account id - this app frequently only has an
     * account *name* (typed rather than picked from [listAssetAccounts]), and that's already
     * enough to post a transaction, so it's enough to search for one too. Used by
     * `MessagesFragment`'s remote duplicate check (see TODO.md's dedup history) once the local
     * dedup log already came up empty - a second device/app notifying about the same real payment
     * wouldn't be in *this* app's own log at all. Follows `meta.pagination` the same way
     * [listAssetAccounts] does. Response shape is identical to [listAssetAccounts]'s sibling
     * account-transactions endpoint (both go through the same `TransactionGroupTransformer`
     * server-side), so [parseTransactionList] is reused unchanged. */
    suspend fun searchTransactionsByQuery(query: String): Result<List<FireflyTransactionCandidate>> =
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val candidates = mutableListOf<FireflyTransactionCandidate>()
                var page = 1
                var totalPagesSeen: Int
                do {
                    val body = retrying("GET", "/api/v1/search/transactions") {
                        val connection = openConnection("/api/v1/search/transactions?query=$encodedQuery&page=$page", "GET")
                        val code = connection.responseCode
                        if (code !in 200..299) return@withContext Result.Failure(errorMessage(connection, code, "GET", "/api/v1/search/transactions"))
                        connection.inputStream.bufferedReader().use { it.readText() }
                    }
                    candidates += parseTransactionList(body)
                    totalPagesSeen = totalPages(body)
                    page++
                } while (page <= totalPagesSeen)
                Result.Success(candidates)
            } catch (e: IOException) {
                ioFailure(e, "GET", "/api/v1/search/transactions")
            }
        }

    /** `GET /v1/transactions/{id}` - the full fields of one known transaction (confirmed
     * `ShowController::show()` wraps a single `Item`, not a `Collection`, against its current
     * source - `data` is one object, not an array, hence [parseSingleTransaction] instead of
     * [parseTransactionList]). Used to fetch real info for a *locally* found duplicate
     * (`CreatedTransactionsDb` only ever returns an id, no fields) so the duplicate-confirm
     * dialog can always show what the found transaction actually looks like, the same way it
     * already can for a remote match - and, per the user's own request, as a live existence check
     * for a cached id before trusting it (`posting.findDuplicate`): [id] can go stale if the
     * transaction was deleted by hand in Firefly after this app's own log recorded it.
     *
     * A 404 is treated as [Result.Success] with a `null` value, same as "malformed/empty
     * response" already was - confirmed against `ShowController::show()`'s current source: Laravel
     * route-model-binding on a nonexistent id never reaches the method body at all (404 via
     * `ModelNotFoundException`), and an existing-but-inaccessible one hits an explicit
     * `throw new NotFoundHttpException()` - both are a clean, unambiguous "there is definitely no
     * such transaction", not a failure worth retrying or reporting as an error. Any other non-2xx
     * (auth, server error) stays a real [Result.Failure] - that's "couldn't find out", not "found
     * out it doesn't exist", and callers need to tell those apart (see `DuplicateCheckResult.
     * SearchFailed`'s KDoc). */
    suspend fun getTransaction(id: String): Result<FireflyTransactionCandidate?> = withContext(Dispatchers.IO) {
        try {
            retrying("GET", "/api/v1/transactions/$id") {
                val connection = openConnection("/api/v1/transactions/$id", "GET")
                val code = connection.responseCode
                when {
                    code in 200..299 -> {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        Result.Success(parseSingleTransaction(body))
                    }
                    code == 404 -> Result.Success(null)
                    else -> Result.Failure(errorMessage(connection, code, "GET", "/api/v1/transactions/$id"))
                }
            }
        } catch (e: IOException) {
            ioFailure(e, "GET", "/api/v1/transactions/$id")
        }
    }

    /** `PUT /v1/transactions/{id}` - the editing feature (the user's own request). Returns the
     * updated transaction's id on success, same response shape as [createTransaction] (confirmed
     * against `UpdateController::update()`'s current source - same `Item`-wrapped resource, so
     * [parseCreatedTransactionId] already handles it unchanged). A genuine *partial* update - see
     * [buildTransactionUpdateRequestBody]'s own KDoc for why nothing not sent here gets touched.
     *
     * Deliberately **not** wrapped in [withNetworkRetry], same reasoning as [createTransaction]:
     * no idempotency guarantee on a write, and a timeout after the server already applied the
     * edit but before the response arrived would otherwise risk a confusing silent retry rather
     * than a clear "did this save or not" to the user. A failed update surfaces the error and
     * stops. */
    suspend fun updateTransaction(id: String, request: FireflyTransactionRequest): Result<String> = withContext(Dispatchers.IO) {
        try {
            val connection = openConnection("/api/v1/transactions/$id", "PUT")
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(buildTransactionUpdateRequestBody(request).toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val resultId = parseCreatedTransactionId(body)
                if (resultId != null) Result.Success(resultId) else noTransactionId(body, "PUT", "/api/v1/transactions/$id")
            } else {
                Result.Failure(errorMessage(connection, code, "PUT", "/api/v1/transactions/$id"))
            }
        } catch (e: IOException) {
            ioFailure(e, "PUT", "/api/v1/transactions/$id")
        }
    }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        if (isCleartextUrl(baseUrl) && !allowCleartext) throw CleartextBlockedException()
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/vnd.api+json")
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        return connection
    }

    /** One line for the diagnostic log ([log]): which call, and only how it ended. Never the request or
     * response body (bank data), never a header or the token, never the query string (a search carries
     * descriptions and amounts), never an exception's own message (it can carry the server address). */
    private fun record(method: String, path: String, detail: String) {
        log?.invoke("api $method ${path.substringBefore('?')}: $detail")
    }

    /** [withNetworkRetry] that logs every failed attempt. */
    private suspend inline fun <T> retrying(method: String, path: String, block: () -> T): T =
        withNetworkRetry(onFailedAttempt = { attempt, e -> record(method, path, "attempt $attempt failed: ${e.javaClass.simpleName}") }, block = block)

    private fun ioFailure(e: IOException, method: String, path: String): Result.Failure {
        record(method, path, "failed: ${e.javaClass.simpleName}")
        return Result.Failure(e.message ?: e.javaClass.simpleName)
    }

    private fun noTransactionId(body: String, method: String, path: String): Result.Failure {
        record(method, path, "no transaction id in the response")
        return Result.Failure("No transaction id in response: $body")
    }

    /** A 2xx answer that is not a Firefly list (a proxy's HTML page, a cut-off body): an error, not
     * an empty page - otherwise a half-loaded list would be cached as if it were complete. */
    private fun unreadable(method: String, path: String, page: Int): Result.Failure {
        record(method, path, "unreadable response (page $page)")
        return Result.Failure("Unexpected response from the server (page $page)")
    }

    private fun errorMessage(connection: HttpURLConnection, code: Int, method: String, path: String): String {
        record(method, path, if (code in 300..399) "redirect $code" else "HTTP $code")
        if (code in 300..399) return redirectMessage(code, connection.getHeaderField("Location"))
        val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return "$code ${connection.responseMessage.orEmpty()}".trim() +
            errorBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
    }
}

/** Redirects are never followed: the request carries the bearer token, and a redirect to another
 * host (or a write silently re-sent as a GET) must not be acted on automatically. The server's
 * `Location` is shown so the user can correct the Firefly URL instead. */
internal fun redirectMessage(code: Int, location: String?): String =
    "The server redirected the request ($code)" +
        location?.takeIf { it.isNotBlank() }?.let { " to $it" }.orEmpty() +
        " - redirects are not followed, so the token is never sent anywhere else. Set the Firefly URL to the final address."

/** Thrown instead of sending the bearer token over plain `http://` while the user hasn't opted in
 * ("Allow unencrypted HTTP" on the Firefly connection screen) - an [IOException] so every caller's
 * existing catch turns it into a `Result.Failure`, but never retried (see [withNetworkRetry]). */
class CleartextBlockedException :
    IOException("Plain http:// is blocked - use https://, or enable \"Allow unencrypted HTTP\" in the Firefly connection settings")
