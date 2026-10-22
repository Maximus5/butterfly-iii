package io.github.maximus5.ffnotifyimporter.rules

import java.math.BigDecimal
import java.time.Instant

/** A problem with a [TransactionDraft] worth surfacing before anything gets posted anywhere. */
enum class DraftIssue {
    /** Only a foreign-currency amount was found; the home-currency amount needs manual entry. */
    LOCAL_AMOUNT_MISSING,

    /** [ParseRule.dateFormat] is set and [Groups.DATE] was captured, but the captured text didn't
     * parse against that format (typo in the format, or the text just doesn't match) - [date]
     * falls back to the notification's own arrival time rather than blocking the match, but this
     * flags that the fallback happened instead of the date silently being wrong with no warning. */
    DATE_UNPARSABLE,

    /** The account side(s) [kind] requires an asset account for are blank - most notably
     * `rules.BuiltInRules`'s generic fallback rules, which leave both
     * [ParseRule.sourceAccountName]/[destinationAccountName] blank on purpose (they can't know
     * which real Firefly account any given app's money should land in; a human fills it in on
     * `ui.CreateTransactionFragment`'s own create screen instead). **Fixed a real bug, the user's
     * own catch**: `posting.AutoCreateWorker` used to try posting such a draft anyway - Firefly's
     * API rejects a transaction with no source/destination account every time, so this silently
     * burned through all of `AutoCreateWorker.MAX_ATTEMPTS` retries (with exponential backoff)
     * before finally showing a failure notification, which from the user's side looked like
     * nothing happened at all for a long stretch. `AutoCreateWorker` now checks this flag and
     * shows the same review notification `posting.ReviewNotificationWorker` would (auto-create
     * off) immediately instead of ever attempting to post. */
    ACCOUNT_SELECTION_REQUIRED,

    /** The rule is marked [ParseRule.accountApproximate]: its account is a guess, not what the
     * notification said. Auto-create stops at a review notification for such a draft (a human
     * picks the account), and every duplicate check for it compares amount/currency/date only,
     * never accounts or description - so the same payment reported by a source that *does* know
     * the account (a wallet app) is recognised as the same one. */
    ACCOUNT_APPROXIMATE,

    /** No amount to post yet: the rule is marked [ParseRule.amountManual] and the notification gave
     * none (or the draft was made by hand for a message no rule matched). The user types it on the
     * review screen; automatic creation and review notifications skip such a draft. */
    AMOUNT_ENTRY_REQUIRED,
}

/** What a [ParseRule] detected in a notification - "what we matched", now postable via
 * `network.FireflyApiClient.createTransaction` (see `ui.MessagesFragment`'s "Создать" button). */
data class TransactionDraft(
    val kind: RuleKind,
    val date: Instant,
    val amount: BigDecimal?, // in homeCurrency; null if only a foreign amount was found
    val currencyCode: String, // the rule's homeCurrency
    val foreignAmount: BigDecimal?,
    val foreignCurrencyCode: String?,
    val sourceName: String?,
    // Always null from RuleEngine.buildDraft - ParseRule no longer caches a Firefly account id at
    // all (see its own KDoc for the real cross-rule dedup bug that caused). Only ever set by
    // ui.CreateTransactionFragment's own one-shot account picker, for posting precision on that
    // one transaction being created right now - preferred over sourceName when posting
    // (FireflyJson.buildTransactionRequestBody), but never fed into transactionDedupHash, which
    // compares by name only regardless of whether either side happens to carry one.
    val sourceAccountId: String?,
    val destinationName: String?,
    val destinationAccountId: String?, // same as sourceAccountId above, mirrored for the other side
    val description: String,
    val notes: String,
    val category: String?,
    val tags: List<String>,
    val issues: Set<DraftIssue>,
    /** Label of the rule this draft came from, for display only (notifications, the transaction
     * screen) and for `CapturedMessage.appliedRuleLabel`; null if the rule has no label. */
    val ruleLabel: String? = null,
    /** The rule's captured `counterparty` group (whitespace-normalized), null if it has none. The
     * key "Auto categories" learns and looks up by. */
    val counterparty: String? = null,
    /** True when the rule's own category template produced [category] - then Auto categories
     * neither substituted nor may learn from an edit of it. */
    val categoryFromRule: Boolean = false,
)
