package io.github.maximus5.ffnotifyimporter.network

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * Pure request-building/response-parsing for the accounts-list and create-transaction calls -
 * kept separate from [FireflyApiClient] (which needs a real network call) so this part is
 * plain-Kotlin unit testable, same reasoning as [FireflyUrl].
 */

/** `GET /v1/accounts`'s body is `{"data": [{"id": "...", "attributes": {"name": "...", "active":
 * true, ...}}, ...], "meta": {...}}` - pulls out just id+name, skipping any entry missing either
 * (malformed rather than failing the whole list) and any account deactivated in Firefly
 * (`"active": false`; a missing flag counts as active). */
fun parseAccountList(body: String): List<FireflyAccount> = try {
    val data = JSONObject(body).optJSONArray("data")
    if (data == null) {
        emptyList()
    } else {
        (0 until data.length()).mapNotNull { i ->
            val entry = data.optJSONObject(i) ?: return@mapNotNull null
            val id = entry.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val attributes = entry.optJSONObject("attributes")
            val name = attributes?.optString("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!attributes.optBoolean("active", true)) return@mapNotNull null
            FireflyAccount(id, name)
        }
    }
} catch (e: JSONException) {
    emptyList()
}

/** `GET /v1/categories`'s body is the same shape as [parseAccountList]'s (`{"id": "...",
 * "attributes": {"name": "...", ...}}` entries - confirmed against `CategoryTransformer`'s current
 * source, not assumed) - pulls out just id+name the same way, for `ui.CreateTransactionFragment`'s
 * category picker (see TODO.md). */
fun parseCategoryList(body: String): List<FireflyCategory> = try {
    val data = JSONObject(body).optJSONArray("data")
    if (data == null) {
        emptyList()
    } else {
        (0 until data.length()).mapNotNull { i ->
            val entry = data.optJSONObject(i) ?: return@mapNotNull null
            val id = entry.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = entry.optJSONObject("attributes")?.optString("name")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            FireflyCategory(id, name)
        }
    }
} catch (e: JSONException) {
    emptyList()
}

/** `GET /v1/tags` (`Tag\ShowController@index` -> `TagTransformer`, confirmed against Firefly's
 * current source, not assumed): the same `{"data": [{"id": ..., "attributes": {"tag": "...", ...}}],
 * "meta": {"pagination": ...}}` shape as the category list, with the name under `tag`. Only the
 * names are needed - they feed the tags field's suggestions on the create/edit screen. */
fun parseTagList(body: String): List<String> = try {
    val data = JSONObject(body).optJSONArray("data")
    if (data == null) {
        emptyList()
    } else {
        (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.optJSONObject("attributes")?.optString("tag")?.takeIf { it.isNotBlank() }
        }
    }
} catch (e: JSONException) {
    emptyList()
}

/** `GET /v1/currencies`'s body is the same `{"id": ..., "attributes": {"code": ..., "enabled":
 * ..., ...}}` shape as [parseAccountList]/[parseCategoryList] - confirmed against
 * `CurrencyTransformer`'s current source for the `code`/`enabled` field names, not assumed.
 * `ui.TransactionCreationFragment`'s "Default account currency" picker source - the user's own
 * request, to pick from the currencies actually enabled in the user's own Firefly instance rather
 * than type a code by hand (or this app's own separate, locally-maintained `CurrenciesDb` table,
 * which `ui.RuleEditFragment`'s own currency picker already uses for a different purpose - that
 * one's a user-maintained symbol<->code lookup, not a live "what does my Firefly actually have
 * switched on" list). Skips any entry missing a `code`, same "malformed entry, not the whole
 * list" shape every other list parser here uses. */
fun parseCurrencyList(body: String): List<FireflyCurrency> = try {
    val data = JSONObject(body).optJSONArray("data")
    if (data == null) {
        emptyList()
    } else {
        (0 until data.length()).mapNotNull { i ->
            val entry = data.optJSONObject(i) ?: return@mapNotNull null
            val attributes = entry.optJSONObject("attributes") ?: return@mapNotNull null
            val code = attributes.optString("code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FireflyCurrency(
                code,
                attributes.optBoolean("enabled", false),
                attributes.optString("name"),
                attributes.optString("symbol"),
                attributes.optBoolean("primary", false) || attributes.optBoolean("default", false),
            )
        }
    }
} catch (e: JSONException) {
    emptyList()
}

/** `meta.pagination.total_pages` - defaults to 1 (i.e. "no more pages") for anything that isn't
 * shaped the way a real paginated response is, so a malformed response doesn't spin forever. */
/** True when [body] is a Firefly list answer (a JSON object with a `data` array, possibly empty). */
fun isListResponse(body: String): Boolean = try {
    JSONObject(body).optJSONArray("data") != null
} catch (e: JSONException) {
    false
}

fun totalPages(body: String): Int = try {
    JSONObject(body).optJSONObject("meta")?.optJSONObject("pagination")?.optInt("total_pages", 1) ?: 1
} catch (e: JSONException) {
    1
}

/** The one `transactions[]` split object shared by [buildTransactionRequestBody] (create) and
 * [buildTransactionUpdateRequestBody] (edit) - same fields either way, only the wrapper around it
 * differs. */
private fun buildTransactionSplit(request: FireflyTransactionRequest): JSONObject = JSONObject().apply {
    put("type", request.type)
    put("date", request.dateIso)
    put("amount", request.amount)
    put("description", request.description)
    put("currency_code", request.currencyCode)
    // Firefly's own docs: "Submit either this or source_name" - an ID, when we have one,
    // survives a Firefly-side account rename that would silently break a name-only match, so
    // it's sent *instead of* the name, not alongside it.
    if (request.sourceId != null) put("source_id", request.sourceId) else request.sourceName?.let { put("source_name", it) }
    if (request.destinationId != null) {
        put("destination_id", request.destinationId)
    } else {
        request.destinationName?.let { put("destination_name", it) }
    }
    request.categoryName?.let { put("category_name", it) }
    if (request.tags.isNotEmpty()) put("tags", JSONArray(request.tags))
    request.notes?.let { put("notes", it) }
    request.foreignAmount?.let { put("foreign_amount", it) }
    request.foreignCurrencyCode?.let { put("foreign_currency_code", it) }
    request.externalId?.let { put("external_id", it) }
}

/**
 * Builds the JSON body `POST /v1/transactions` expects - a `transactions` array wrapping a single
 * split (Firefly supports splitting one transaction into several, this client never does), plus
 * `error_if_duplicate_hash: true` as a free extra safety net on top of this app's own dedup check
 * (see TODO.md) - Firefly rejects the request outright if *it* also thinks this is a duplicate.
 */
fun buildTransactionRequestBody(request: FireflyTransactionRequest): String =
    JSONObject()
        .put("error_if_duplicate_hash", true)
        .put("transactions", JSONArray().put(buildTransactionSplit(request)))
        .toString()

/**
 * Builds the JSON body `PUT /v1/transactions/{id}` expects, for the editing feature (the user's
 * own request) - same single-split shape as [buildTransactionRequestBody], no
 * `error_if_duplicate_hash` (store-only, not one of `UpdateRequest`'s recognized top-level keys -
 * confirmed against its current source, not assumed). Confirmed genuinely *partial*: `UpdateRequest.
 * getTransactionData()`'s own comment is "default response is to update nothing in the
 * transaction" - every field is copied into Firefly's update data only if `array_key_exists` in
 * what's sent here, so this app's own form only ever touches the fields it actually exposes
 * (amount/currency/date/accounts/description/notes/category/tags) - anything else already on the
 * transaction (budget, piggy bank, SEPA fields, reconciliation state, ...) is left exactly as it
 * was, since this never includes those keys at all.
 */
fun buildTransactionUpdateRequestBody(request: FireflyTransactionRequest): String =
    JSONObject()
        .put("transactions", JSONArray().put(buildTransactionSplit(request)))
        .toString()

/** `POST /v1/transactions`'s success body is `{"data": {"id": "...", ...}}` - the created
 * transaction's ID, for this app's own "created transactions" log. */
fun parseCreatedTransactionId(body: String): String? = try {
    JSONObject(body).optJSONObject("data")?.optString("id")?.takeIf { it.isNotBlank() }
} catch (e: JSONException) {
    null
}

/** `GET /v1/accounts/{id}/transactions`'s body is `{"data": [{"id": "<group id>", "attributes":
 * {"transactions": [{"type", "date", "amount", "currency_code", "foreign_amount",
 * "foreign_currency_code", "description", "source_id", "source_name", "destination_id",
 * "destination_name", "notes", "tags", "category_name", ...}, ...]}}, ...], "meta": {...}}` -
 * exact field names confirmed against `TransactionGroupTransformer`'s current source, not assumed
 * (`notes`/`tags`/`category_name`/`type`: the user's own request, to show an already-linked
 * transaction's real current fields - and, for `type`, let the edit screen know its real kind -
 * rather than this app's own rule-derived guess). Flattens every group's splits into one list
 * (each keeping its own group's id - this app's own transactions never split, but Firefly/a
 * manual entry might), skipping anything missing a field this app actually needs (malformed
 * entry, not the whole list). */
fun parseTransactionList(body: String): List<FireflyTransactionCandidate> = try {
    val data = JSONObject(body).optJSONArray("data")
    if (data == null) {
        emptyList()
    } else {
        (0 until data.length()).flatMap { i ->
            val group = data.optJSONObject(i) ?: return@flatMap emptyList()
            val groupId = group.optString("id").takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
            val splits = group.optJSONObject("attributes")?.optJSONArray("transactions") ?: return@flatMap emptyList()
            (0 until splits.length()).mapNotNull { j -> parseTransactionSplit(groupId, splits.optJSONObject(j)) }
        }
    }
} catch (e: JSONException) {
    emptyList()
}

/** `GET /v1/transactions/{id}`'s body is `{"data": {"id": "<group id>", "attributes": {...same
 * shape as one entry of [parseTransactionList]'s "data" array...}}}` - a single object, not an
 * array (`ShowController::show` wraps the result in a Fractal `Item`, not a `Collection` -
 * confirmed against its current source, not assumed). Used to fetch the full fields of a
 * *locally* found duplicate (`CreatedTransactionsDb` only ever returns an id), so the
 * duplicate-confirm dialog can show real info for a local match the same way it already can for a
 * remote one. Only the first split, same "this app's own transactions never split" reasoning as
 * [parseTransactionList] - a locally-recorded id is always something this app itself created. */
fun parseSingleTransaction(body: String): FireflyTransactionCandidate? = try {
    val group = JSONObject(body).optJSONObject("data")
    val groupId = group?.optString("id")?.takeIf { it.isNotBlank() }
    val splits = group?.optJSONObject("attributes")?.optJSONArray("transactions")
    if (groupId == null || splits == null || splits.length() == 0) {
        null
    } else {
        parseTransactionSplit(groupId, splits.optJSONObject(0))
    }
} catch (e: JSONException) {
    null
}

private fun parseTransactionSplit(groupId: String, split: JSONObject?): FireflyTransactionCandidate? {
    if (split == null) return null
    val dateRaw = split.optString("date").takeIf { it.isNotBlank() } ?: return null
    // Carbon's toAtomString() - ISO-8601 *with* a numeric offset (+01:00), not the bare
    // Z-suffix form Instant.parse expects - hence OffsetDateTime.parse(...).toInstant().
    val date = try {
        OffsetDateTime.parse(dateRaw).toInstant()
    } catch (e: java.time.format.DateTimeParseException) {
        return null
    }
    val amount = split.optString("amount").toBigDecimalOrNull() ?: return null
    val currencyCode = split.optString("currency_code").takeIf { it.isNotBlank() } ?: return null
    val tags = split.optJSONArray("tags")?.let { array ->
        (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } }
    }.orEmpty()
    return FireflyTransactionCandidate(
        groupId = groupId,
        date = date,
        amount = amount,
        currencyCode = currencyCode,
        foreignAmount = split.optString("foreign_amount").takeIf { it.isNotBlank() }?.toBigDecimalOrNull(),
        foreignCurrencyCode = split.optStringOrNull("foreign_currency_code"),
        description = split.optString("description", ""),
        sourceId = split.optStringOrNull("source_id")?.takeIf { it != "0" },
        sourceName = split.optStringOrNull("source_name"),
        destinationId = split.optStringOrNull("destination_id")?.takeIf { it != "0" },
        destinationName = split.optStringOrNull("destination_name"),
        notes = split.optStringOrNull("notes"),
        category = split.optStringOrNull("category_name"),
        tags = tags,
        type = split.optString("type", "withdrawal"),
    )
}

/** org.json quirk, caught directly (a transaction with no category set came back in the dialog as
 * the literal text "Category: null"): plain `optString(name)` returns the *string* `"null"`, not a
 * real Kotlin `null`, when the field's JSON value is `null` rather than absent -
 * `org.json.JSONObject.NULL` is a real sentinel object, not Java/Kotlin `null`, so `optString`'s
 * own internal `object != null` check still passes, and that sentinel's own `toString()` happens
 * to be `"null"`. `isNull(name)` is org.json's own correct way to ask "is this JSON null or
 * missing" - checked first here so a genuinely-absent category reads the same as an explicitly-null
 * one, both -> a real `null`, not the four-letter word. */
private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

private fun String.toBigDecimalOrNull(): BigDecimal? = try {
    BigDecimal(this)
} catch (e: NumberFormatException) {
    null
}
