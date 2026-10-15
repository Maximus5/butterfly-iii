package io.github.maximus5.ffnotifyimporter.backup

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.prefs.RetentionSettings
import io.github.maximus5.ffnotifyimporter.rules.AutoCategoryEntry
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionRecord
import io.github.maximus5.ffnotifyimporter.rules.CurrencyEntry
import io.github.maximus5.ffnotifyimporter.rules.ParseRule
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import io.github.maximus5.ffnotifyimporter.rules.autoCategoriesFromJson
import io.github.maximus5.ffnotifyimporter.rules.autoCategoriesToJson
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Everything *except* the Firefly III connection (base URL + personal access token) -
 * deliberately excluded, not an oversight: it's the one genuinely secret value in this app
 * (AGENTS.md's Secrets section), `KeystoreCipher`-encrypted against a key that lives in this
 * device's Android Keystore and wouldn't even decrypt on a different device or after an app
 * reinstall, and a backup file is exactly the kind of thing that ends up in a cloud folder or
 * gets shared - not somewhere a bearer token belongs, encrypted or not. The user re-enters it
 * after a restore.
 *
 * [messages]' `fireflyTransactionId` *is* included - an earlier version of this file dropped it,
 * which silently lost a restored message's "#1234" link even though the transaction genuinely
 * still existed in Firefly, a real bug fixed directly (same file, see git history). [rules] used
 * to also carry `sourceAccountId`/`destinationAccountId` here (deliberately, unlike `RulesJson`'s
 * own exclusion of them, reasoning this was a same-device safety net where the ids stayed valid) -
 * moot now, `ParseRule` doesn't have those fields at all anymore (see its own KDoc for the
 * cross-rule dedup bug that caused). An old backup file's `sourceAccountId`/`destinationAccountId`
 * keys, if present, are simply never read back - harmless, same as any other key this format
 * doesn't recognize. [createdTransactions] mirrors
 * `CreatedTransactionsDb` for the same reason - without it, the dedup log would come back empty
 * after a restore onto a fresh install, silently disabling duplicate detection for everything
 * created before the backup.
 */
data class FullBackupData(
    val monitoredPackages: Set<String>,
    val retention: RetentionSettings,
    val currencies: List<CurrencyEntry>,
    val rules: List<ParseRule>,
    val messages: List<CapturedMessage>,
    val createdTransactions: List<CreatedTransactionRecord> = emptyList(),
    /** Null for a file written before settings were backed up: restore then leaves the current
     * settings alone instead of resetting them to defaults. */
    val settings: BackupSettings? = null,
    /** The Auto categories list. Null for a file written before it existed: restore then keeps the
     * current list instead of emptying it. */
    val autoCategories: List<AutoCategoryEntry>? = null,
)

fun fullBackupToJson(data: FullBackupData): String {
    val root = JSONObject()
    root.put("monitoredPackages", JSONArray(data.monitoredPackages.toList()))
    root.put("persistHistory", data.retention.persistToDisk)
    root.put("retentionDays", data.retention.days)
    root.put(
        "currencies",
        JSONArray().apply {
            data.currencies.forEach { put(JSONObject().put("code", it.code).put("symbol", it.symbol)) }
        },
    )
    // Not rulesToJson directly, even though the two formats are identical now (ParseRule has
    // nothing left that RulesJson would need to exclude) - toBackupJson stays its own function
    // in case a future field needs the same per-format split RulesJson/FullBackupJson used to
    // need for sourceAccountId/destinationAccountId. Only `id` itself is excluded - RulesDb.add
    // always assigns a fresh one on restore regardless, so there'd be nothing to round-trip it for.
    root.put("rules", JSONArray().apply { data.rules.forEach { put(it.toBackupJson()) } })
    root.put(
        "messages",
        JSONArray().apply {
            data.messages.forEach { message ->
                put(
                    JSONObject()
                        .put("packageName", message.packageName)
                        .put("appLabel", message.appLabel)
                        .put("title", message.title)
                        .put("text", message.text)
                        .put("timestampMillis", message.timestampMillis)
                        .put("fireflyTransactionId", message.fireflyTransactionId)
                        .put("appliedRuleLabel", message.appliedRuleLabel),
                )
            }
        },
    )
    root.put(
        "createdTransactions",
        JSONArray().apply {
            data.createdTransactions.forEach { record ->
                put(
                    JSONObject()
                        .put("hashWithDescription", record.hashWithDescription)
                        .put("hashWithoutDescription", record.hashWithoutDescription)
                        .put("hashWithoutAccounts", record.hashWithoutAccounts)
                        .put("dateMillis", record.dateMillis)
                        .put("fireflyTransactionId", record.fireflyTransactionId),
                )
            }
        },
    )
    data.settings?.let { root.put("settings", it.toJson()) }
    data.autoCategories?.let { root.put("autoCategories", autoCategoriesToJson(it)) }
    return root.toString(2)
}

/**
 * Null if [json] isn't shaped like a backup at all (missing `monitoredPackages`, the one section
 * that's always present even on a completely fresh install). A malformed *section* within an
 * otherwise-valid file falls back to empty for that section rather than failing the whole
 * restore - same "one bad part doesn't sink everything" reasoning as [rulesFromJson].
 */
fun fullBackupFromJson(json: String): FullBackupData? {
    val root = try {
        JSONObject(json)
    } catch (e: JSONException) {
        return null
    }
    val monitoredPackages = root.optJSONArray("monitoredPackages")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
    } ?: return null

    val currencies = root.optJSONArray("currencies")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val entry = arr.optJSONObject(i) ?: return@mapNotNull null
            val code = entry.optString("code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CurrencyEntry(code, entry.optString("symbol", ""))
        }
    }.orEmpty()

    val rules = root.optJSONArray("rules")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toBackupRuleOrNull() }
    }.orEmpty()

    val messages = root.optJSONArray("messages")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val entry = arr.optJSONObject(i) ?: return@mapNotNull null
            val packageName = entry.optString("packageName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val timestamp = entry.optLong("timestampMillis", -1L)
            if (timestamp < 0) return@mapNotNull null
            CapturedMessage(
                packageName = packageName,
                appLabel = entry.optString("appLabel", packageName),
                title = entry.optString("title", ""),
                text = entry.optString("text", ""),
                timestampMillis = timestamp,
                fireflyTransactionId = entry.optString("fireflyTransactionId").takeIf { it.isNotBlank() },
                appliedRuleLabel = entry.optString("appliedRuleLabel").takeIf { it.isNotBlank() },
            )
        }
    }.orEmpty()

    val createdTransactions = root.optJSONArray("createdTransactions")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val entry = arr.optJSONObject(i) ?: return@mapNotNull null
            // Falls back to the old "hash" key for a backup file written before the two-hash
            // change - that file only ever had the with-description variant (the default setting).
            val hashWithDescription = entry.optString("hashWithDescription").takeIf { it.isNotBlank() }
                ?: entry.optString("hash").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val hashWithoutDescription = entry.optString("hashWithoutDescription").takeIf { it.isNotBlank() }
            val hashWithoutAccounts = entry.optString("hashWithoutAccounts").takeIf { it.isNotBlank() }
            val fireflyTransactionId = entry.optString("fireflyTransactionId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val dateMillis = entry.optLong("dateMillis", -1L)
            if (dateMillis < 0) return@mapNotNull null
            CreatedTransactionRecord(hashWithDescription, hashWithoutDescription, dateMillis, fireflyTransactionId, hashWithoutAccounts)
        }
    }.orEmpty()

    // A backup written before "persist to disk" became its own flag only has retentionDays, where
    // 0 meant "don't save" - the same mapping the on-device prefs migration applies.
    val retentionDays = root.optInt("retentionDays", 0)
    val retention = if (root.has("persistHistory")) {
        RetentionSettings(root.optBoolean("persistHistory", false), RetentionSettings.normalizeDays(retentionDays))
    } else {
        RetentionSettings.fromLegacyDays(retentionDays)
    }

    return FullBackupData(
        monitoredPackages = monitoredPackages,
        retention = retention,
        currencies = currencies,
        rules = rules,
        messages = messages,
        createdTransactions = createdTransactions,
        settings = root.optJSONObject("settings")?.let(BackupSettings::fromJson),
        autoCategories = root.optJSONArray("autoCategories")?.let(::autoCategoriesFromJson),
    )
}

/** A full backup's own rule shape - same fields as [RulesJson.kt]'s sharing format now
 * (`ParseRule` has nothing left for the two to disagree on, see [FullBackupData]'s KDoc). Still
 * excludes `id` - never round-tripped either way, `RulesDb.add` assigns a fresh one on restore
 * regardless. */
private fun ParseRule.toBackupJson(): JSONObject = JSONObject().apply {
    put("label", label)
    put("packageNames", JSONArray(packageNames.toList()))
    put("kind", kind.name)
    put("sourceAccountName", sourceAccountName)
    put("destinationAccountName", destinationAccountName)
    put("homeCurrency", homeCurrency)
    put("pattern", pattern)
    put("descriptionTemplate", descriptionTemplate)
    put("notesTemplate", notesTemplate)
    put("categoryTemplate", categoryTemplate)
    put("tagsTemplate", tagsTemplate)
    put("enabled", enabled)
    put("dateFormat", dateFormat)
    if (accountApproximate) put("accountApproximate", true)
}

private fun JSONObject.toBackupRuleOrNull(): ParseRule? {
    val kind = try {
        RuleKind.valueOf(optString("kind"))
    } catch (e: IllegalArgumentException) {
        return null
    }
    val pattern = optString("pattern").takeIf { it.isNotBlank() } ?: return null
    val homeCurrency = optString("homeCurrency", "")
    val packageNames = optJSONArray("packageNames")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
    }.orEmpty()

    return ParseRule(
        id = "",
        label = optString("label", ""),
        packageNames = packageNames,
        kind = kind,
        sourceAccountName = optString("sourceAccountName", ""),
        destinationAccountName = optString("destinationAccountName", ""),
        homeCurrency = homeCurrency,
        pattern = pattern,
        descriptionTemplate = optString("descriptionTemplate", ""),
        notesTemplate = optString("notesTemplate", ""),
        categoryTemplate = optString("categoryTemplate", ""),
        tagsTemplate = optString("tagsTemplate", ""),
        enabled = optBoolean("enabled", true),
        dateFormat = optString("dateFormat", ""),
        accountApproximate = optBoolean("accountApproximate", false),
    )
}
