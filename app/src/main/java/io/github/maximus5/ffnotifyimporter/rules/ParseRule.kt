package io.github.maximus5.ffnotifyimporter.rules

/**
 * One parsing template. A Firefly III account can have several rules (different apps, different
 * notification shapes, different kinds); an app can have several rules too (one per account it
 * can post to).
 *
 * [sourceAccountName]/[destinationAccountName]/[homeCurrency] are placeholders until a real
 * Firefly III API connection exists (see TODO.md) - free text today, real account/currency
 * references later. Nothing in `parser` or `RuleEngine` depends on that changing.
 *
 * [sourceAccountName]/[destinationAccountName] are macro templates, same mechanism and same
 * `{macro}` syntax as [descriptionTemplate]/[notesTemplate]/[categoryTemplate]/[tagsTemplate] -
 * not a separate "fixed vs. dynamic" concept. A plain account name with no `{macro}` in it is
 * just a fixed value (the common case for the side that's genuinely always the same account); if
 * the other side varies per notification (a merchant/payer name, say), write e.g.
 * `{counterparty}` referencing a named group declared in [pattern]. There is no automatic
 * fallback to any particular group name - this was tried (silently inherited from an earlier
 * draft, never actually asked for) and removed; what ends up in each field is exactly what's
 * written there, rendered.
 *
 * Exactly one of [sourceAccountName]/[destinationAccountName] is expected to render to something
 * for WITHDRAWAL/DEPOSIT; TRANSFER needs both - validated by [validateRule].
 *
 * **No `sourceAccountId`/`destinationAccountId` here, deliberately removed** - a rule used to be
 * able to cache the Firefly account ID picked via `FireflyApiClient.listAssetAccounts()`
 * alongside the name, posting (and `rules.transactionDedupHash`) preferring the ID when present so
 * a later Firefly-side rename wouldn't silently break the match. Real bug this caused, caught
 * directly by the user: two *different* rules for the same real account (e.g. a bank's own app
 * and Google/Apple Wallet, each with its own rule) don't necessarily agree on whether an ID got
 * cached - one rule's account field picked via the picker (has an ID), another typed free-text or
 * saved before the picker existed (doesn't) - so `transactionDedupHash`'s `id ?: name` hashed two
 * *different* values for the *same* real account, and cross-app dedup silently missed a real
 * duplicate. The user's own call once this was diagnosed: drop ID-based posting/hashing from
 * rules entirely rather than try to keep every rule's cached ID consistently in sync - a rule
 * always posts (and hashes) by name now, same as the counterparty side already did; a Firefly-side
 * rename just needs the rule's text updated by hand afterward, a visible failure mode instead of
 * this silent one.
 *
 * [dateFormat] is blank by default, meaning "use the notification's own arrival time as the
 * transaction date" (the original, still-default behavior). Non-blank - a `java.time.format.
 * DateTimeFormatter` pattern, e.g. `dd.MM.yy` - opts into using whatever [Groups.DATE] captures
 * instead, for banks that announce a *future* charge date separately from when the notification
 * itself arrives (e.g. "the charge will happen on 01.02.30"). Deliberately user-specified, not
 * auto-detected: unlike an amount's decimal separator, a date like "01.02.30" is genuinely
 * ambiguous (day-first or month-first) without knowing the bank's own convention - guessing would
 * risk silently posting the wrong date, see TODO.md.
 */
data class ParseRule(
    val id: String,
    val label: String,
    val packageNames: Set<String> = emptySet(),
    val kind: RuleKind,
    val sourceAccountName: String = "",
    val destinationAccountName: String = "",
    val homeCurrency: String,
    val pattern: String, // regex over title + "\n" + text, see Groups for the group conventions
    val descriptionTemplate: String = "",
    val notesTemplate: String = "",
    val categoryTemplate: String = "", // renders to one category name; blank = none
    val tagsTemplate: String = "", // renders then splits on ',' (trimmed, blanks dropped)
    val enabled: Boolean = true,
    val dateFormat: String = "",
    /** The account in this rule is a guess (e.g. a bank notification that never says which
     * card/account was used, so the rule names a fixed one). Such a rule never auto-creates and
     * its duplicate checks ignore accounts - see [DraftIssue.ACCOUNT_APPROXIMATE]. */
    val accountApproximate: Boolean = false,
    /** The notification carries no amount (e.g. "Payment received" from an employer): the pattern
     * need not define [Groups.AMOUNT], and the draft comes out with no amount and
     * [DraftIssue.AMOUNT_ENTRY_REQUIRED] - the review screen opens with the amount empty, and
     * nothing is created automatically. An amount the pattern does capture is still used. */
    val amountManual: Boolean = false,
)
