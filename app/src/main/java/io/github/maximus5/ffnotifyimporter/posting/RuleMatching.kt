package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import androidx.work.Data
import androidx.work.workDataOf
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.network.ExchangeRateClient
import io.github.maximus5.ffnotifyimporter.prefs.AutoCategoriesPrefs
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.RuleEngine
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.applyApproxConversion
import io.github.maximus5.ffnotifyimporter.rules.withBuiltIns

private const val KEY_PACKAGE_NAME = "package_name"
private const val KEY_APP_LABEL = "app_label"
private const val KEY_TITLE = "title"
private const val KEY_TEXT = "text"
private const val KEY_TIMESTAMP_MILLIS = "timestamp_millis"

/** `WorkManager.Data` only holds primitives - every background worker that needs the originating
 * message reconstructs it from these same plain fields rather than trying to serialize a
 * [CapturedMessage] directly. Shared by [AutoCreateWorker] and `ReviewNotificationWorker`. */
fun capturedMessageInputData(message: CapturedMessage): Data = workDataOf(
    KEY_PACKAGE_NAME to message.packageName,
    KEY_APP_LABEL to message.appLabel,
    KEY_TITLE to message.title,
    KEY_TEXT to message.text,
    KEY_TIMESTAMP_MILLIS to message.timestampMillis,
)

fun capturedMessageFrom(data: Data): CapturedMessage = CapturedMessage(
    packageName = data.getString(KEY_PACKAGE_NAME).orEmpty(),
    appLabel = data.getString(KEY_APP_LABEL).orEmpty(),
    title = data.getString(KEY_TITLE).orEmpty(),
    text = data.getString(KEY_TEXT).orEmpty(),
    timestampMillis = data.getLong(KEY_TIMESTAMP_MILLIS, 0L),
)

private fun buildEngine(context: Context, prefs: FireflyPrefs): RuleEngine {
    val rules = RulesDb.getInstance(context).loadAllOrdered().withBuiltIns(prefs.isBuiltInRulesEnabled())
    val currencySymbols = CurrenciesDb.getInstance(context).symbolToCodeMap()
    return RuleEngine(
        rules, currencySymbols, prefs.getAllTransactionsTags(), prefs.getDefaultAccountCurrency(),
        prefs.getDefaultSourceAccount(), AutoCategoriesPrefs(context).load(),
    )
}

/**
 * The one place every real match goes through (both background workers and every UI entry point).
 * Runs [RuleEngine.match]; if the matched draft still carries a currency *symbol* the local
 * `CurrenciesDb` table didn't know (see [unresolvedCurrencySymbols]) and Firefly is configured,
 * looks it up in Firefly ([learnCurrencySymbols]) and, only if that added something, matches once
 * more so the draft comes out normalized. `CurrenciesDb` is a cache of Firefly's own currency
 * table - the user's own framing - so this is its self-fill path. A failed/empty lookup leaves the
 * first outcome untouched, exactly the pre-lookup behaviour. Suspends only when that lookup
 * actually runs, so a caller on `Dispatchers.Main.immediate` still proceeds inline otherwise.
 */
suspend fun matchMessage(context: Context, message: CapturedMessage): ParseOutcome {
    val prefs = FireflyPrefs(context)
    val outcome = buildEngine(context, prefs).match(message)
    val draft = (outcome as? ParseOutcome.Matched)?.draft ?: return outcome
    if (!prefs.isConfigured()) return outcome
    if (!learnCurrencySymbols(context, prefs, unresolvedCurrencySymbols(draft))) return outcome
    return buildEngine(context, prefs).match(message)
}

/** [matchMessage], reduced to the draft - null if no rule matched. Shared by [AutoCreateWorker]
 * and `ReviewNotificationWorker`, both of which only ever receive a message's plain fields (see
 * [capturedMessageFrom]) and have to re-derive the draft themselves. */
suspend fun matchRule(context: Context, message: CapturedMessage): TransactionDraft? =
    (matchMessage(context, message) as? ParseOutcome.Matched)?.draft

/** Same exchange-rate resolution `MessagesFragment.resolveAmountQuietly` does - no dialog/UI to
 * update along the way, since neither background worker using this has one. A failed lookup
 * silently keeps the original (still-unresolved) draft, same as `MessagesFragment`'s own
 * fallback. */
suspend fun resolveAmountQuietly(draft: TransactionDraft, prefs: FireflyPrefs): TransactionDraft {
    if (DraftIssue.LOCAL_AMOUNT_MISSING !in draft.issues) return draft
    val foreignCurrency = draft.foreignCurrencyCode ?: return draft
    return when (val result = ExchangeRateClient().fetchRate(foreignCurrency, draft.currencyCode, draft.date)) {
        is ExchangeRateClient.Result.Success -> applyApproxConversion(draft, result.rate, prefs.getApproxConversionTag())
        is ExchangeRateClient.Result.Failure -> draft
    }
}
