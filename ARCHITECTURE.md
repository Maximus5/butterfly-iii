# ARCHITECTURE.md

Architecture, module layout, and real bugs found while building
Butterfly III. Keep this up to date - update it in the same
commit as any code change that changes a module, data flow, or rule
format.

## Overview

Android app, no backend of its own:

1. A `NotificationListenerService` observes notifications posted by
   other apps (bank apps).
2. Each notification is matched against user-defined rules (which
   source app, which text pattern, how to extract amount/payee/etc.).
3. A match is turned into a transaction and posted to a Firefly III
   instance's REST API, using the base URL + personal access token the
   user entered in the app's settings screen.

Nothing is ever sent off the device; Firefly III itself (not part of
this repo) will be the system of record for the resulting
transactions, once posting to it exists. Locally, message content is
in-memory only by default - nothing written to disk - unless the user
turns on "Save history to local storage" (see below).

Current iteration (fifth milestone, in progress): capture + dedup, a
settings area (monitored apps + opt-in local history), a full
rule-matching flow - create/edit/duplicate/reorder rules in Settings,
"test match" a captured message by tapping it on the Messages screen -
and the start of the Firefly III connection itself (base URL/token,
Test connection, posting-behavior settings; see `prefs`/`network`
below and TODO.md for what's still missing before a rule match can
actually become a transaction). `rules` still has no *direct*
dependency on Firefly - `ParseRule.sourceAccountName`/
`destinationAccountName`/`homeCurrency` are still free-text
placeholders until the rule editor's asset-account picker lands (see
TODO.md) - single Gradle module `app`, Kotlin, classic Android Views
(no Compose), minSdk 26.

A deliberate two-layer split for the matching engine, settled after
some back-and-forth on an earlier draft that mixed them:
`parser` knows regex mechanics and nothing else (not even that
`amount` is a meaningful group name); `rules` is where
transaction/Firefly-flavored meaning (kinds, accounts, currencies,
description/notes templates) lives, on top of what `parser` extracts.
Other decisions worth remembering because they're not obvious from the
code alone:
- A notification is always matched as `title + "\n" + body` in one
  regex - never title and body matched separately. `extractGroups`
  compiles with `RegexOption.DOT_MATCHES_ALL` so a plain `.`/`.*`/`.+`
  in the pattern can cross that joining newline too - without it, a
  pattern expecting to span "the notification text" (as the rule
  editor's field hint literally promises: "matched against title +
  text") would silently fail to match whenever what it's trying to
  span happens to land on both sides of the title/body boundary, for a
  reason invisible from the pattern text itself (real case: `Zahlung.*?
  (?<amount>...)` never matched because "Zahlung" was in the title and
  the amount was in the body). `validateRule`'s trial-compile uses the
  same option, so a pattern that's only invalid without it never slips
  through as "valid" at save time.
- No per-rule `counterparty` title/text fallback logic - counterparty
  extraction is just whatever the pattern's `counterparty` group
  captures from the combined input; if a bank puts it in the title,
  write the pattern to look there.
- Rule list order *is* priority: `RuleEngine` returns the first rule
  (app-filtered, in list order) whose pattern matches and yields a
  parsable amount. No `priority` field, no "ambiguous match" outcome.
- `description`/`notes`/`category`/`tags` are all macro templates
  (`{groupName}`/`{title}`/`{body}`), not separate "is this dynamic or
  static" fields - a template with no `{macro}` in it just behaves like
  a plain fixed string, so templates are a strict superset of "plain
  string" at zero extra cost, and keeps one mechanism instead of two.
  `{title}`/`{body}` (`MacroTemplate.FIXED_MACROS`) are always
  available regardless of what the pattern declares. There is no
  `{appname}` macro (an earlier version had one, removed) - a rule is
  always scoped to exactly one app (`ParseRule.packageNames` is written
  as a singleton set by the editor), so which app it is never varies
  per match; a macro that can only ever resolve to one fixed value adds
  indirection without adding flexibility. The rule editor's insert-macro
  picker instead offers the app's name as a plain literal string to
  insert (see the `RuleEditFragment.showMacroPicker` note below).
  `tags` is the odd one out structurally: its template renders
  to one string, which then gets split on `,` (trimmed, blanks
  dropped) into `TransactionDraft.tags: List<String>`. No default text
  in any of these for a new rule - user feedback was that a silently
  pre-filled default is confusing, better left blank and explicit.
- `RuleKind` is `WITHDRAWAL`/`DEPOSIT`/`TRANSFER` - no `_OUT`/`_IN`
  split (an earlier version had one). Direction comes directly from
  which of `sourceAccountName`/`destinationAccountName` is filled in,
  not from the kind; `TRANSFER` requires both.
- `sourceAccountName`/`destinationAccountName` are macro templates -
  the exact same `{macro}` mechanism as `descriptionTemplate`/etc, not
  a separate "fixed name, with an automatic fallback to whatever the
  pattern's `counterparty` group captured" behavior. That automatic
  fallback existed in an earlier version (silently inherited from a
  third-party draft the user shared early on, never actually
  requested, and confusing once visible - "where did I ask for this")
  and was removed outright; there is no implicit behavior here at all
  now. If a side varies per notification (a merchant/payer name), the
  rule author writes e.g. `{counterparty}` in that field explicitly,
  same as they would in a description template - a plain name with no
  `{}` in it is just a fixed value, same "templates are a strict
  superset of plain strings" reasoning as the other template fields.
  This is still true at the data level - `ParseRule`/`RuleEngine` treat
  both fields identically, no model-level "which side is the asset"
  concept exists. The *editor* now constrains one side per `RuleKind`
  to a Firefly-asset-account picker instead of free macro text (see the
  `RuleEditFragment`/`FireflyApiClient` notes below) - a UI-level
  requirement driven by what Firefly's own API will actually accept,
  not a `ParseRule` shape change.
  A rule only ever picks from the *monitored* apps list (not every
  installed app) in the UI - it can't match anything else anyway.

Application id: `io.github.maximus5.butterflyiii`; Kotlin package: `io.github.maximus5.ffnotifyimporter`.

## Module layout

Single `:app` module, packages under
`io.github.maximus5.ffnotifyimporter`:

- `data` - the core, Android-independent pure-Kotlin logic, unit
  tested without instrumentation:
  - `CapturedMessage` (app/title/text/timestamp, plus a nullable
    `fireflyTransactionId` - set once a transaction has been created
    from this message, see `ui.MessagesFragment`/`MessageStore.
    setTransactionId` below). Deliberately *not* part of what
    identifies a message anywhere else in this file (dedup, removal,
    `MessageHistoryDb` lookups all key on the other five fields) -
    it's exactly the field those call sites want to change in place,
    so including it in identity would make "find this message and
    update its id" impossible to express as a lookup.
  - `MessageStore` - the in-memory, time-windowed dedup store
    (`MessageStoreTest`). `MessageStore.shared` is the one instance
    used by the listener service and the UI. `add` dedups, notifies
    listeners, and returns the recorded `CapturedMessage` (or null if
    dropped as a duplicate) - changed from a plain `Boolean` specifically
    for `posting.AutoCreateScheduler` (see the `posting` section below):
    it needs the *exact* `timestampMillis` `add` actually stored, and
    re-deriving "now" a second time at the call site could race against
    a second notification arriving in between and land on a different
    value than what was stored internally - returning the already-
    computed message avoids that race entirely.
    **Fixed a real ordering bug, caught directly by the user right
    after the `postTime` fix above**: the list is kept sorted
    newest-first, which `add` used to maintain simply by always
    inserting at index 0 - safe only as long as every new
    `timestampMillis` is guaranteed larger than every previous one,
    true back when it was always `clock()` ("now", monotonic). Once
    `timestampMillis` became `sbn.postTime` (see `notifications`
    below), that guarantee broke: a notification replayed on listener
    reconnect can be *captured* after a genuinely newer one but carry
    an *older* `postTime`, and inserting it at the front put it above
    entries it's actually older than. `add` now finds the correct
    sorted position (`indexOfFirst { it.timestampMillis <= now }`) and
    inserts there instead of unconditionally at the front - `restore`
    (below) is unaffected, it already relied on its caller pre-sorting
    `historical`, not on this. `restore` bulk-seeds
    already-deduplicated entries
    (loaded from disk at startup) without re-running the dedup check;
    `remove` deletes one entry (matched by value equality - there's no
    separate id) for the Messages screen's delete action.
    `setTransactionId(message, id)` replaces the entry matching
    [message]'s other five fields with a copy carrying `id`, in place
    (same list position) - used instead of full value-equality matching
    specifically because the field being changed can't be part of the
    match.
    **`title`/`text` are `.trim()`'d right inside `add`, the user's own
    request**: Kotlin's plain `trim()` (whitespace per `Char.
    isWhitespace()` - spaces, tabs, newlines) runs once, here, before
    the dedup comparison and before the `CapturedMessage` is built -
    the one real entry point both a captured notification
    (`notifications.BankNotificationListenerService`) and the debug
    injector (`ui.AddTestMessageFragment`) go through, so everything
    downstream (dedup here and in `rules.transactionDedupHash`, rule
    pattern matching, display) can trust the text is already
    normalized rather than re-trimming itself. A whitespace-only
    difference (a stray leading/trailing blank line some notification
    sources emit) now counts as the same duplicate it always should
    have. The optional `onNewMessage`/`onMessageRemoved`/
    `onMessageUpdated` hooks are how persistence attaches itself
    without `MessageStore` knowing persistence exists. Dedup key is
    `(packageName, title, text)` - *notification*-level, same app
    only. It says nothing about two *different* apps notifying about
    the same real-world transaction (e.g. a bank's own app plus
    Google/Apple Wallet both firing for one card tap) - that's the
    separate, now-built transaction-level dedup, see `rules.
    transactionDedupHash`/`CreatedTransactionsDb` below and TODO.md.
  - `MessageHistoryDb` - the on-disk mirror, plain `SQLiteOpenHelper`
    (no ORM), one `messages` table. Only ever written to while
    "Save history to local storage" is on. `MessageHistoryDb.getInstance(context)` is
    the one shared instance (a `SQLiteOpenHelper` per call would leak
    connections). `DB_VERSION` 1->2 added
    `firefly_transaction_id` via a real `ALTER TABLE ... ADD COLUMN`
    migration - this table's schema had never actually changed before,
    so this was the first real test of the promise its `onUpgrade`
    comment had been making since the `RulesDb` data-loss incident (see
    Known bugs/gotchas below): additive only, never drop+recreate.
    `updateTransactionId(message)` mirrors `MessageStore.
    setTransactionId` on disk, matching the row the same "every column
    except the one being changed" way.
  - `RetentionController` (`RetentionControllerTest` for the pure
    cutoff-date math) - applies the user's history setting
    (`prefs.RetentionSettings`: `persistToDisk` + a window of
    1/3/5/7/30 days, default off + 5) to every append-only store: the
    in-memory `MessageStore` (`MessageStore.deleteOlderThan`, run on
    startup/setting change and after each new message - the message
    just added is exempt, so a test message with a deliberately old
    date doesn't vanish instantly), `CreatedTransactionsDb`
    (`deleteOlderThan` by transaction date, also run after each
    successful create via `purgeCreatedTransactions`), and - only while
    `persistToDisk` is on - `MessageHistoryDb`. The window always
    applies; only the on-disk mirror of the message list is optional
    (the dedup log is on disk regardless, it's the duplicate-detection
    safety net).
    `applyOnStartup` (called once, from `FfNotifyImporterApp`) may
    call `MessageStore.restore`; `applyOnSettingChanged` (called from
    the settings UI and after a backup restore) never does, since
    `restore` has no dedup against what's already in memory and
    calling it twice would duplicate entries already shown. Instead it
    rewrites the disk mirror from memory (memory is always a superset
    of disk), or clears it when persisting is off.
    Wires all three `MessageStore` hooks (`onNewMessage`/
    `onMessageRemoved`/`onMessageUpdated`); the latter two only call
    into `MessageHistoryDb` while persisting, and are nulled out
    otherwise.
- `parser` - regex mechanics only, zero knowledge of transactions,
  Firefly, or what any group name means:
  - `extractGroups(pattern, input): Map<String,String>?` /
    `namedGroupNames(pattern)` (`NamedGroupMatcherTest`).
  - `parseAmount(raw)` (`AmountParserTest`) - no configured decimal
    separator (there used to be a per-rule one; removed after user
    feedback that it was unnecessary friction). Auto-detects instead:
    the *last* separator character (`,`/`.`/`'`/space/NBSP) is the
    decimal point only if exactly two digits follow it - real currency
    amounts are written with 0 or 2 decimal digits, never anything
    else, so that distinguishes a decimal point from a thousands
    separator (`"12,50"` -> 12.50, but `"1,234"` -> 1234, not 1.234).
    Known gap: single-digit cents (`"12,5"`) aren't detected - hasn't
    come up with real bank notifications, which always pad to two
    digits.
  - `parseDate(raw, pattern)` (`DateParserTest`) - the opposite call on
    auto-detection from `parseAmount`: a date-only string like
    `01.02.30` genuinely can't be disambiguated (day-first vs.
    month-first) without knowing the bank's own convention, so this
    takes an explicit `DateTimeFormatter` pattern (`ParseRule.
    dateFormat`, a `rules`-layer concept - `parser` itself has no
    opinion on it) rather than guessing. Null on a malformed pattern or
    a non-matching `raw`, never throws - the caller (`RuleEngine`)
    decides what that means. Resolves to noon UTC on the parsed date
    (no time of day - a bank announcing a future charge never gives
    one), a fixed but arbitrary choice that sidesteps any
    timezone-driven day-rollover surprise a midnight value could cause.
- `rules` - the Firefly-*flavored* domain, still Firefly-API-free
  (string placeholders - see Overview):
  - `ParseRule` - one parsing template scoped to one app
    (`sourceAccountName`/`destinationAccountName`/`homeCurrency` are
    placeholders; `pattern` matched against `parser`'s combined-input
    convention; `descriptionTemplate`/`notesTemplate` are macro
    templates, blank by default - no silently pre-filled text).
    `dateFormat` is blank by default (= the old, still-default
    behavior: the transaction's date is when the notification arrived)
    - a `java.time.format.DateTimeFormatter` pattern the user types in
      (e.g. `dd.MM.yy`), for banks that announce a *future* charge date
      separately from when the notification itself arrives (real case:
      Bank DE - "Die Abbuchung über €99,99 für 00000 findet am
      01.02.30 statt"). Deliberately user-specified, not auto-detected
      like `parseAmount`'s decimal separator - a date like `01.02.30`
      is genuinely ambiguous (day-first or month-first) without the
      bank's own convention, and guessing wrong risks silently posting
      the wrong date; see TODO.md.
  - `Groups` - the named-group names `rules` knows to look for
    (`amount`, `currency`, `foreignAmount`, `foreignCurrency`,
    `counterparty`, `date`) - a convention `parser` has no opinion on.
    `date` only does anything when `ParseRule.dateFormat` is also set
    (see `RuleEngine.buildDraft` below); otherwise it's just another
    name available to `{macro}` templates, same as `counterparty`.
  - `renderTemplate`/`templateMacroNames`/`FIXED_MACROS`
    (`MacroTemplateTest`) - `{x}` macro substitution for
    description/notes/account names/etc, using the match's captured
    groups plus always-available `{title}`/`{body}`. `{{x}}` (doubled
    braces) escapes a literal `{x}` that isn't meant as a macro at all
    (e.g. a real Firefly tag named `{User}`) - the user's own request:
    before this existed, `x` colliding with a real declared group or
    `FIXED_MACROS` got silently substituted with no way to say "I mean
    the literal text". `renderTemplate`'s regex tries the escaped-pair
    alternative first at each position (standard leftmost-alternative-
    wins behavior for `\{\{(\w+)\}\}|\{(\w+)\}`), and `templateMacroNames`
    only ever counts the plain-macro capture group, never the escaped
    one. `RuleEditFragment`'s "available macros" hint gained a second,
    static line pointing at the `{{word}}` syntax. That hint (now
    "Macros for the fields below", macros found in the typed regex plus
    `title`/`body`) sits under the template buttons; the regex field
    itself carries a separate `helperText` listing the recognized group
    names (`amount` required, `currency`, `foreignAmount`,
    `foreignCurrency`, `date`), and its end icon (dropdown) opens the
    existing-pattern picker - which replaced a standalone "Pick
    existing..." button, leaving only "Pick message..." (left) and
    "Test..." (right-aligned) in the button row.
  - `TransactionDraft`/`DraftIssue`/`ParseOutcome`/`NoMatchReason` -
    what a match produced (or why it didn't match) - postable now
    (`ui.MessagesFragment`'s "Создать" button, see its entry above),
    via a plain mapping onto `network.FireflyTransactionRequest`, not a
    `rules` -> `network` dependency (the mapping lives in the UI layer
    that already depends on both). `sourceAccountId`/
    `destinationAccountId` still exist on this class, but `RuleEngine.
    buildDraft` always leaves them null now - `ParseRule` no longer
    caches a Firefly account id at all (see its own KDoc for the real
    cross-rule dedup bug that caused). Only `ui.CreateTransactionFragment`'s
    own one-shot account picker ever sets one, for posting precision on
    that single transaction; `transactionDedupHash` deliberately ignores
    both fields regardless (name only, see its own KDoc).
  - `applyApproxConversion` (`ApproxConversionTest`) - pure
    `amount = foreignAmount * rate` (`HALF_UP`, 2 decimals) plus
    appending the approx-conversion tag, given a rate the caller
    already fetched (`network.ExchangeRateClient`, see the `network`
    section above) - `rules` still has no dependency on `network`.
  - `transactionDedupHash`/`CreatedTransactionsDb`
    (`TransactionDedupTest`, `FullBackupJsonTest`) - the
    transaction-level dedup check, a different problem from
    `MessageStore`'s notification-level dedup (see TODO.md's dedup
    history for why both exist). The hash is pure - canonical-scale
    amount, currency, description only when `includeDescription` is
    true, and only the account side(s) `RuleValidation.validateRule`
    actually requires a name for *this draft's kind* (by name only, not
    id-or-name - see the third fix below for why): source only for
    `WITHDRAWAL`, destination only for `DEPOSIT`, both for `TRANSFER`.
    **Fixed a second real bug, found right after the
    first one below**: it used to always hash both sides regardless of
    kind - harmless comparing two local hashes against each other
    (the kind-irrelevant side is always equally blank for the same
    rule), but once a comparison involves data from *outside* this
    app (see `remoteCandidateMatches` below) the irrelevant side can
    have a real value sitting there that this app never asserted
    anything about, and the hash rejected an otherwise-exact match
    over it - caught directly by the user testing a `WITHDRAWAL` rule
    with no destination name configured, matched against Firefly's own
    stored transaction (whose destination was a real account, not
    blank). The user's own direct call, deliberately not routed around
    with a smaller, more defensive patch: "не мудри, меняй
    transactionDedupHash" - a kind-aware workaround at every call site
    would have been the "мудри" version. **Breaking change, accepted
    deliberately**: a `CreatedTransactionsDb` row written under the old
    always-both-sides formula, for the uncommon case where the
    kind-irrelevant side was actually non-blank, won't be found by a
    lookup computed under this one - no migration for this, same as
    the two-hash fix below couldn't recover data the old single-hash
    schema never kept either.
    **Fixed a third real bug, caught directly on-device from two
    different rules auto-creating separate transactions for the exact
    same real payment**: account side(s) used to hash `accountId ?:
    accountName` (preferring a cached Firefly account id, when
    `ParseRule` had one, over the plain name) - the actual root cause
    once diagnosed: two *different* rules for the same real account
    (a bank app's own rule and a wallet app's rule, say) don't
    necessarily agree on whether an id got cached for it - one picked
    via `RuleEditFragment`'s account picker (had one), the other
    free-typed or saved before the picker existed (didn't) - so the two
    rules' drafts hashed to two *different* values for the identical
    real account, and genuine cross-app duplicates went uncaught
    despite everything else (amount, currency, description, window)
    matching exactly. The user's own call once this was traced: stop
    hashing by id at all, and go further - `ParseRule` doesn't cache a
    Firefly account id anywhere anymore (see its own KDoc), so there's
    no stale/inconsistent cache left to reintroduce this. **Breaking
    change, accepted deliberately, same as the other two above**: a row
    written while id-preference was still in effect, for a draft that
    happened to have an id, hashes differently now than it did then.
    `CreatedTransactionsDb` is the usual
    plain-SQLite log (one row per successful `createTransaction()`
    call), queried by
    `findWithinWindow(hash, includeDescription, date, windowMinutes): String?`
    centered on the new transaction's own date, not "now", so two apps
    notifying about the same payment seconds apart land in the same
    window regardless of which one gets processed first - returns the
    matched row's Firefly id (most recent match if more than one,
    `ORDER BY date_millis DESC`), not just whether one exists, so the
    caller can offer "this is already created, link it" as a real
    answer (see `MessagesFragment` below) instead of only "create
    anyway"/"don't".
    **Fixed a real bug, caught by the user asking directly "what
    happens if I untoggle 'Also compare description'?"**: with a single
    hash column, the answer was "every row already written instantly
    stops matching anything" - a SHA-256 digest has no partial/fuzzy
    comparison, so changing *any* input (here, whether description was
    folded in) changes the whole digest, for every row, immediately,
    regardless of whether that row's own description actually differs
    from anything. Fix (the user's own call, not a hedge): the table now
    always stores *both* hash variants
    (`hash_with_description`/`hash_without_description`) on every write,
    computed unconditionally via two `transactionDedupHash` calls
    (`true`/`false`) regardless of the current setting - `recordCreated`
    takes both, `findWithinWindow` picks which column to compare against
    based on the *lookup's* `includeDescription` flag. A lookup under
    either setting can now match a row written under the other. Rows
    written before this fix only have one variant (migrated in place to
    `hash_with_description`, the longstanding default setting - see
    `CreatedTransactionsDb.onUpgrade`'s KDoc) - `hash_without_description`
    stays `NULL` for those, an honest "unknown" rather than a
    recomputed-from-nothing guess (there's no way to get the original
    description text back out of a one-way hash). `DB_VERSION` 1->2,
    additive `ALTER TABLE ... ADD COLUMN` - deliberately *not*
    `RENAME COLUMN` for the pre-existing `hash` column even though it's
    now semantically "with description", since SQLite only gained
    `RENAME COLUMN` in 3.25, newer than what ships on this app's minSdk
    26 devices; `COL_HASH_WITH_DESCRIPTION`'s Kotlin name maps to the
    old physical `hash` column instead.
  - `RemoteTransactionCandidate`/`remoteCandidateMatches`
    (`TransactionDedupTest`) - the *remote* half of dedup, the user's
    own design: the local check above only ever catches a transaction
    *this app* created, never one a second device (or a different app's
    direct Firefly integration) posted for the same real payment.
    Needed as its own function rather than just calling
    `transactionDedupHash` on `candidate` directly for two reasons:
    `candidate` isn't a `TransactionDraft` (different shape - a
    `groupId`, no `foreignAmount`/asset-counterparty ids - see
    `network.FireflyTransactionCandidate`, which `MessagesFragment`
    maps into this plain, `network`-free form; `notes`/`category`/`tags`
    *are* on both now, added so an already-linked transaction's editor
    (`ui.CreateTransactionFragment`'s edit mode, see `ui` section below)
    can start from its real current fields, not just a rule-derived
    guess), and
    `transactionDedupHash` deliberately doesn't cover date at all (the
    window check here is its own explicit thing, same reason
    `CreatedTransactionsDb.findWithinWindow` does its own SQL `BETWEEN`
    rather than folding date into the hash).
    `remoteCandidateMatches(draft, candidate, includeDescription,
    windowMinutes)` reshapes `candidate` into a throwaway
    `TransactionDraft` and compares `transactionDedupHash` output
    directly rather than re-deriving the same amount/currency/
    description/account comparison a second way - this is also why
    making `transactionDedupHash` itself kind-aware (above) was enough
    on its own to fix a real miss the user caught, with nothing extra
    needed here: whichever side doesn't matter for `draft.kind` never
    enters the comparison, however it's filled in on either side. The
    one remaining wrinkle: Firefly assigns every account (even a
    counterparty this app only ever captured by name) a real id, so the
    throwaway draft only keeps the candidate's id for a side `draft`
    *also* has one for - otherwise it's compared by name instead, same
    as `draft` itself is, so a real counterparty match isn't rejected
    just because this app never had an id for it.
    `RemoteTransactionCandidate` also now carries `kind: RuleKind`
    (`TransactionGroupTransformer`'s own `type` field,
    `withdrawal`/`deposit`/`transfer`, confirmed against its current
    source - `network.FireflyTransactionCandidate.type` stays the raw
    string, mapped to `RuleKind` by `posting.DuplicateChecker`'s private
    `ruleKindFrom`, same layering reason `FireflyTransactionRequest.type`
    isn't `RuleKind` either) - needed once `ui.CreateTransactionFragment`
    edit mode (see `ui` section below) had to know which account side is
    the asset side for an already-existing transaction, not just a
    freshly-matched draft's own `kind`.
    **Fixed a third real bug, a foreign-currency false negative the
    user caught on a HKD Amex notification**: when `draft` has
    `foreignAmount`/`foreignCurrencyCode` set, its own `amount`/
    `currencyCode` are only this app's *own* approximate conversion
    (`applyApproxConversion`, a Frankfurter-fetched exchange rate this
    app picked independently) - not a value Firefly's own stored amount
    is likely to match exactly. `remoteCandidateMatches` now compares
    the foreign amount/currency directly in that case instead (both
    sides' one precise, bank-reported value), and builds the throwaway
    draft with `draft`'s own (already-validated-identical) amount/
    currency rather than `candidate`'s, so `transactionDedupHash`'s own
    unconditional amount/currency contribution can't independently
    re-reject what the foreign-aware comparison already confirmed. Same
    reasoning applies to `network.dedupSearchQuery` (see below) - the
    remote search itself now queries by `foreign_amount_is`/
    `foreign_currency_is` instead of `amount_is`/`currency_is` whenever
    both are known, for the same "approx conversion isn't a reliable
    match key" reason.
  - `RuleEngine.match(message)` (`RuleEngineTest`) - the
    first-rule-in-order-that-matches algorithm described above. Takes
    an optional `currencySymbols: Map<String,String>` (symbol -> code,
    from `CurrenciesDb.symbolToCodeMap()`) and runs captured
    `currency`/`foreignCurrency` values through
    `normalizeCurrency`/`CurrencyNormalizationTest` before comparing
    against `homeCurrency` - banks print symbols ("€50.00") far more
    often than ISO codes, and without this every symbol-only
    notification would look "foreign" even when it's actually the
    home currency. `buildDraft`'s `date` defaults to
    `Instant.ofEpochMilli(message.timestampMillis)`; when the pattern
    captured `Groups.DATE` *and* `rule.dateFormat` is non-blank, it
    tries `parser.parseDate(captured, rule.dateFormat)` instead - a
    successful parse replaces the date outright, a failed one (typo'd
    format, or the text just doesn't match it) flags
    `DraftIssue.DATE_UNPARSABLE` and keeps the notification's own time,
    same "flag it, don't guess, don't block the match" shape
    `LOCAL_AMOUNT_MISSING` already uses.
    `buildDraft` also flags `DraftIssue.ACCOUNT_SELECTION_REQUIRED`
    whenever `kind` requires an account side (source for WITHDRAWAL,
    destination for DEPOSIT, both for TRANSFER) that rendered blank -
    the normal, every-time case for a `BuiltInRules` match, but also
    possible for a user-authored rule whose account-name template
    technically isn't blank (`RuleValidation` requires that) but
    happens to render empty for this particular match. `posting.
    AutoCreateWorker` reads this to skip a doomed Firefly POST
    entirely - see the `posting` section below for the real bug this
    fixed.
    `renderOptional` (the local helper behind `sourceName`/
    `destinationName`/`category`) now also collapses any run of
    whitespace down to one space (`whitespaceRun`, a top-level
    `Regex("\\s+")`), on top of its existing `.trim()` - **fixed a real
    cross-app dedup/account-fragmentation bug, the user's own catch**:
    the same real merchant came through with a single space in a bank
    notification but a double space in Google Wallet's own
    notification for the same payment. Left alone, that's not just a
    dedup miss (`rules.remoteCandidateMatches`'s whole reason to exist
    is catching a duplicate from a second device/app) - Firefly
    auto-creates expense/revenue accounts by exact name, so the two
    differently-spaced names would have ended up as two separate
    accounts in Firefly for what's really one counterparty.
    `RuleEngine` also takes an optional `allTransactionsTags: String`
    constructor parameter (default blank, same "callers that don't
    care can ignore it" shape `currencySymbols` already has) - the
    user's own request: comma-separated, one or more tags merged into
    *every* draft's own `tagsTemplate` output (each skipped if already
    present, same already-present check `rules.applyApproxConversion`'s
    own `approxTag` merge uses) so tags wanted on every transaction
    don't have to be repeated inside every single rule's own tags
    template. A plain `String`, not a `FireflyPrefs`/`Context`
    reference, for the same "rules stays free of any Android/Firefly
    dependency" reason `currencySymbols` is injected as plain data
    rather than read from `CurrenciesDb` internally - every caller that
    constructs a `RuleEngine` (`ui.MessagesFragment`'s two call sites,
    `ui.CreateTransactionFragment`, `posting.RuleMatching.matchRule` -
    shared by `AutoCreateWorker`/`ReviewNotificationWorker`) now also
    passes `FireflyPrefs.getAllTransactionsTags()`.
    `ui.TransactionCreationFragment`'s own field for this ("Add tags
    for all transactions (comma separated)") sits after the
    dedup-window field's own divider, grouped with
    "Approximate-conversion tag" (moved there from the top of the
    screen) rather than with auto-create/dedup above - the user's own
    layout call, kept out of `RuleEditFragment`'s "Test results"
    preview (that dialog only ever reads the matched
    `outcome.groups`, never the draft's own `tags`, so passing it there
    would have had no visible effect).
    `RuleEngine` similarly takes an optional `defaultAccountCurrency:
    String` (same plain-injection shape) - substituted for
    `ParseRule.homeCurrency` in `buildDraft` (both for the foreign-
    amount comparison and the draft's own `currencyCode`) whenever a
    rule leaves its own blank. **Fixed a real gap this exposed, the
    user's own catch**: `rules.validateRule` used to unconditionally
    require `homeCurrency` on *every* rule (`"Currency can't be
    empty"`), which would have silently blocked this fallback from
    ever actually being reachable for a user-authored rule - that
    check is gone now, a blank `homeCurrency` is a deliberate choice to
    lean on the fallback, not something forgotten. Every real caller
    that constructs a `RuleEngine` also now passes `FireflyPrefs.
    getDefaultAccountCurrency()` alongside `getAllTransactionsTags()`.
    **If that is blank too** (no rule currency, no default), the
    captured currency itself is used as the home currency: the amount
    and currency become the transaction's own, and nothing goes to
    foreign. Before v0.179 a blank home currency never equalled the
    captured "EUR", so the whole amount was pushed into
    `foreignAmount` with `LOCAL_AMOUNT_MISSING` (real bug, user's catch).
  - `BuiltInRules` (`BuiltInRulesTest`) - two hardcoded `ParseRule`
    constants, `CURRENCY_BEFORE_AMOUNT`/`CURRENCY_AFTER_AMOUNT`
    ("Default: Currency before amount"/"Default: Currency after
    amount"), not `RulesDb` rows - see the object's own KDoc for the
    full reasoning (grounded in a real 14-rule export the user
    supplied: the currency/amount regex fragments are reused verbatim
    from it, and it directly disproved an earlier keyword-based
    direction-detection design, which is why both default to
    `RuleKind.WITHDRAWAL` and lean on `ui.CreateTransactionFragment`'s
    kind picker - see the `ui` section below - instead). Both
    `homeCurrency = ""` (leans on `defaultAccountCurrency` above) and
    both `sourceAccountName`/`destinationAccountName` blank (a built-in
    rule can't know which real Firefly account any app's money should
    land in - the create screen's own required-field validation makes
    the user fill that in by hand). `packageNames = emptySet()` needed
    no `RuleEngine` change at all - `packageNames.isEmpty()` already
    meant "any app" before these existed.
    `List<ParseRule>.withBuiltIns(builtInRulesEnabled: Boolean)` - the
    one place "append the built-ins, in order, after every real rule"
    lives, so `RuleEngine`'s own existing "first rule in list order
    that matches and yields a parsable amount wins" already gives
    "built-ins only used if nothing user-defined matched" for free, no
    engine change needed there either. `builtInRulesEnabled` is a plain
    `Boolean`, not a `FireflyPrefs`/`Context` reference - every real
    match call site (`ui.MessagesFragment`'s two, `ui.
    CreateTransactionFragment.loadForCreate`, `posting.RuleMatching.
    matchRule`) reads `FireflyPrefs.isBuiltInRulesEnabled()` (new, one
    shared flag for both rules - they always move together, default
    **on**: the whole point is giving a user with a blank/non-matching
    Rules list something that works without a settings trip first) and
    calls `RulesDb.getInstance(context).loadAllOrdered().withBuiltIns(...)`
    in place of the bare `loadAllOrdered()` call these all used before.
    `ui.BackupFragment`/`backup.BackupManager` deliberately do *not* call
    this - export/import/backup is about user-authored rules only, the
    built-ins are already in every build, not data to carry across
    devices. `RulesListFragment`'s own display of these two rows (a
    third, UI-only append, independent of this function) is covered in
    the `ui` section below.
  - `CurrencyEntry`/`CurrenciesDb` - another user-maintained
    placeholder table (code<->symbol, e.g. `EUR`<->`€`), same idea and
    same "plain SQLite, no ORM, `getInstance(context)` singleton"
    shape as `RulesDb`/`MessageHistoryDb`. Seeded with EUR/USD/GBP on
    first creation; the user edits it from Settings -> Currencies.
    Treated as a **local cache of Firefly's own currency table** (the
    user's own framing), so it also fills itself: every real match goes
    through `posting.matchMessage` (`RuleMatching.kt`, used by both
    workers and every UI entry point), which - when the matched draft
    still carries a currency *symbol* nobody translated
    (`unresolvedCurrencySymbols`: not a 3-letter code) and Firefly is
    configured - runs `learnCurrencySymbols`: one read-only
    `listCurrencies()` crawl, then adds a row only when exactly one
    *enabled* Firefly currency carries that symbol (a shared symbol like
    "$" is left for the user to map by hand) and the table has no row
    for that code yet (one symbol per code - never overwrite the user's
    own mapping). Added something -> match again so the draft comes out
    normalized; failed/empty lookup -> original outcome, unchanged.
    Pure parts (`learnableSymbolEntry`) in `CurrencyLearningTest`.
  - `validateRule(rule)` (`RuleValidationTest`) - checks the pattern
    compiles and defines `amount`, at least one app is picked, and the
    right source/destination account is filled per kind (see the
    `RuleKind` note above). The rule editor calls this before letting a
    save through. Deliberately *doesn't* check that every macro
    referenced in a template resolves to a declared group - that used
    to be a save-blocking "Unknown macro(s): ..." error, but it doubled
    up with `renderTemplate`'s own graceful handling (an unknown macro
    renders as literal text) and blocked saving over something
    harmless, e.g. simplifying a pattern down to just `amount` while a
    template field still mentions a macro from an earlier, richer draft
    of it. `currency`/`counterparty`/`foreignAmount`/`foreignCurrency`
    are all optional in the pattern for the same reason the fields that
    would reference them are optional. A non-blank `dateFormat` is
    checked against `DateTimeFormatter.ofPattern` - catches a typo'd
    format at save time rather than only as a `DATE_UNPARSABLE`
    `DraftIssue` every single time the rule matches afterward.
  - `RulesDb` - plain SQLite (no ORM), one row per rule plus an
    explicit `sort_order` column - the only priority mechanism
    `RuleEngine` uses, so `moveUp`/`moveDown` just swap two rows'
    `sort_order`. `duplicate` shifts every later row's order up by one
    and inserts the copy right after the original, in a transaction.
    `distinctPatterns()` is the "pick an existing pattern" source for
    the rule editor - a derived query over this same table, newest
    first, one entry per distinct pattern; no separate library table.
    Still no built-in/canned patterns *in this table* - but
    `RuleEditFragment.showPatternPicker` always appends two hardcoded
    illustrative examples (`EXAMPLE_PATTERN`/`EXAMPLE_PATTERN_TITLE_ANCHORED`)
    after whatever's actually saved, so the very first rule a user ever
    creates isn't a blank textarea with zero clue about named-group
    syntax, and they stay available as a syntax reference even once real
    rules exist (used to be an empty-state-only fallback; removed once
    any real rule existed, which the user flagged as the example
    disappearing right when they'd want to compare against it).
    `EXAMPLE_PATTERN`'s `currency` group accepts a prefixed-dollar form
    like `US$`/`A$` (`[A-Z]{1,3}\$`), *or* a bare three-letter ISO code
    (`[A-Z]{3}`), *or* a bare symbol (`[€£$¥]`) - banks print symbols
    and prefixed-dollar forms far more than plain ISO codes, same
    reason `CurrenciesDb`/`normalizeCurrency` exist - and its `counterparty`
    group is `(?<counterparty>.+?)\.?$` - lazily matched with an
    optional trailing literal period stripped, not plain `.+`, because
    a notification's sentence-ending "." isn't part of the merchant
    name (real example: "...at Shop 9012 Markt GmbH-Zw." was capturing the
    period into `counterparty` before this).
    `EXAMPLE_PATTERN_TITLE_ANCHORED` is the second example, added after a
    real rule hit a classic regex pitfall head-on: a *greedy* `.*`
    between a literal title anchor and the amount group
    (`Zahlung erfolgreich\s*.*(?<amount>[\d.,]+)`) backtracks from the
    *end* of the string, so the amount group is tried starting as late
    as possible first - against "...45,30 € an Shop 5678 GmbH
    gezahlt.", that settled for `amount="0"` (just the last digit of
    "45,30"), which `parseAmount` then rejects (`signum() > 0`), landing
    as a silent `AMOUNT_UNPARSABLE` rather than a visibly wrong capture.
    The fix pattern uses `[^\d]*` instead - it can't overshoot past the
    number it's skipping to, since it stops at the *first* digit
    regardless of which one that is - and the example also demonstrates
    `(?:gezahlt)?` before the optional trailing period, so a known
    trailing verb doesn't end up inside `counterparty` (the same
    "sentence words leaking into a lazy capture group" class of problem
    as the trailing-period fix above, different symptom). Both pitfalls
    and the fix are covered by `NamedGroupMatcherTest` (regex-engine
    level, not UI-level - this is a property of Java's backtracking
    semantics, nothing `rules`/`ui` can special-case around; the fix has
    to live in the pattern itself).
    `RulesDb.getInstance(context)` is the one shared instance (same
    reasoning as `MessageHistoryDb.getInstance`).
    `DB_VERSION` has been bumped five times already as the rule shape
    settled (2: `account_name`/`counterparty_account_name` columns ->
    `source_account_name`/`destination_account_name`; 3: added
    `category_template`/`tags_template`; 4: dropped
    `decimal_separator` - see `parseAmount` above; 5:
    source/destination_account_name became `NOT NULL`; 6: added
    nullable `source_account_id`/`destination_account_id`, see
    `ParseRule`'s KDoc) - `onUpgrade` just drops and recreates the
    table, fine for a personal, not-yet-relied-upon rules list; revisit
    if that's ever not true.
    Not unit tested - like `MessageHistoryDb`, it's thin glue over real
    `SQLiteOpenHelper`/Android framework classes that don't run under
    plain JVM unit tests.
- `prefs` - thin `SharedPreferences` wrappers for configuration (not
  notification content, so - unlike captured messages by default -
  persisted across restarts/reboots on purpose):
  - `MonitoredAppsPrefs` - which package names to monitor.
  - `RetentionPrefs` - `isPersistToDisk` (default off: captured
    messages live in memory only and are lost on restart) and
    `getRetentionDays` (default 5, one of 1/3/5/7/30; the window for
    everything the app appends over time, in memory and on disk).
    Replaced the old single "days, 0 = off" value; `RetentionPrefs`
    migrates it once on first read (`RetentionSettings.fromLegacyDays`:
    1+ days -> persisting on with that window, 0 -> off with the
    default window), the old `retention_days` key is then removed.
  - `NotificationPrefs` - whether this app's own notifications
    (`posting.AutoCreateNotifications`/`ReviewNotifications`) post at
    all (`isNotificationsEnabled`, default **on** - these already
    existed and always fired before this screen did) and whether their
    text includes amount/currency/description or a generic line
    instead (`isShowFinancialInfoEnabled`, default **off** - the user's
    own choice, for Google Play's financial-app content guidelines).
    `ui.NotificationSettingsFragment` is the UI.
  - `SecureSettings` (file `firefly_connection`) - the Firefly III
    connection only: base URL, token, cleartext opt-in. **Never backed up**
    (manual or Auto Backup; see the Auto Backup note below - explicit
    `<exclude>`, enforced by `BackupRulesTest`). `FireflyPrefs` (file
    `firefly_settings`, since v0.241) holds everything else and *delegates*
    the connection accessors to it so existing call sites didn't change; the
    9 plain settings were moved over from `firefly_connection` once
    (`migrateFromConnectionFile`, `FireflyPrefsMigrationTest`).
  - `FireflyPrefs` - the Firefly III connection + posting-behavior
    settings, one facade class for both even though its UI is now split
    across two screens (`ui.FireflyConnectionFragment` - base URL,
    personal access token; `ui.TransactionCreationFragment` - the
    default account currency, all-transactions tags, approximate-
    conversion tag name, auto-create toggle, dedup toggle + include-
    description toggle + window (minutes, default 5) - see the `ui`
    section below for why they're separate screens now). Base URL
    and token are the only genuinely secret values in this app
    (AGENTS.md's Secrets section) - encrypted through `KeystoreCipher`
    before being stored, in an otherwise plain `SharedPreferences`
    (every other field here is unencrypted, same as every other prefs
    class). *Not* built on
    `androidx.security:security-crypto`'s `EncryptedSharedPreferences`/
    `MasterKey` - checked before using them and found every API in that
    library deprecated as of 1.1.0 with no further releases planned.
  - `KeystoreCipher` - the replacement: AES-GCM via a key generated and
    held *inside* Android Keystore (`KeyGenParameterSpec`/
    `KeyProperties.PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, alias
    `firefly_prefs_key`) - the key material itself never leaves
    hardware-backed storage, only `Cipher` operations cross that
    boundary. One key, reused for both secret strings; stores
    `ivBase64:cipherBase64` as the pref value, since GCM needs the
    exact IV it encrypted with to decrypt and generates a fresh random
    one every call. `decrypt` returns null (never throws) on anything
    it can't make sense of - corrupt/foreign-format stored value, key
    unavailable - so a caller treats that the same as "nothing stored
    yet". Not unit tested - real Android Keystore, same reason
    `RulesDb`/`MessageHistoryDb` aren't.
- `network` - the Firefly III API client, `rules`-API-free (string
  placeholders are what `rules` still uses - see the `ParseRule` note
  above):
  - `FireflyUrl.kt` - pure helpers kept separate from the real network
    call specifically so they're plain-Kotlin unit testable
    (`FireflyUrlTest`): `normalizeBaseUrl` (strips a trailing slash),
    `hostOf` (for the Settings row's "Connected to X" subtitle),
    `isCleartextUrl` (drives the in-UI `http://` warning),
    `parseAboutVersion` (pulls the version string out of
    `GET /api/v1/about`'s `{"data": {"version": ...}}` response, used
    to confirm Test Connection actually talked to a real Firefly
    instance and not just got a 200 from something else on that URL),
    `transactionUrl` (the *web UI* page for a created transaction -
    `{baseUrl}/transactions/show/{id}`, a different URL shape from
    every other helper here, which all build `/api/v1/...` paths - the
    route itself was checked against Firefly's current `routes/web.php`
    on GitHub, not assumed; see `MessagesFragment`'s "Open Transaction"
    button below). `dedupSearchDateRange(centerDate, windowMinutes)` -
    the `date_after`/`date_before` values (plain `yyyy-MM-dd`) that
    generously cover a ±window around a transaction's date, for
    `dedupSearchQuery` below; the precise minute-level check happens
    client-side afterwards (`rules.remoteCandidateMatches`), this just
    avoids pulling unrelated history over the network.
    `dedupSearchQuery(amount, currencyCode, foreignAmount,
    foreignCurrencyCode, centerDate, windowMinutes, sourceName,
    destinationName, description, includeDescription)` -
    builds the query string for `FireflyApiClient.
    searchTransactionsByQuery` (`FireflyUrlTest`): Firefly's own
    operator-trigger search syntax (`field:value`/`field:"quoted
    value"`, built on the `gdbots/query-parser` composer dependency -
    confirmed both the operator names
    (`amount_is`/`currency_is`/`foreign_amount_is`/`foreign_currency_is`/
    `date_after`/`date_before`/`source_account_is`/
    `destination_account_is`/`description_is`)
    against `OperatorQuerySearch`'s current source and the quoting
    syntax against `gdbots/query-parser`'s own docs, not guessed).
    Deliberately scoped by *name*, not a Firefly account id - see
    `FireflyApiClient.searchTransactionsByQuery`'s KDoc below for why
    that's a deliberate fix, not the original design. Null (skip the
    remote check entirely) when the amount is unresolved or neither
    account side has a name to search on - searching on amount+date
    alone could coincidentally match an unrelated transaction.
    When `foreignAmount`/`foreignCurrencyCode` are both known,
    `foreign_amount_is`/`foreign_currency_is` are used *instead of*
    `amount_is`/`currency_is` - a real false negative the user caught on
    a foreign-currency (HKD) notification, same "approx conversion isn't
    a reliable match key" reasoning as `rules.remoteCandidateMatches`
    above; see its KDoc for the full rationale, shared between both
    sides of this search.
  - `FireflyApiClient` - plain `HttpURLConnection` + `org.json`, not
    OkHttp/Retrofit - same "avoid a new dependency when the framework
    already covers it" reasoning as the planned rules export/import
    JSON format (see TODO.md); a handful of endpoints doesn't justify a
    full HTTP client library. `org.json` at runtime is the real
    Android-bundled implementation, but JVM unit tests run against a
    stub `android.jar` where its methods throw "not mocked" - a
    `testImplementation("org.json:json:...")` real-implementation jar
    is what actually makes `FireflyUrlTest`/`FireflyJsonTest` exercise
    real JSON parsing instead of silently no-op'ing.
    `testConnection()` (`GET /api/v1/about`), `listAssetAccounts()`
    (`GET /api/v1/accounts?type=asset`, follows `meta.pagination.
    total_pages` across every page rather than assuming the first 50
    is everything; `parseAccountList` drops accounts deactivated in
    Firefly, `attributes.active == false`, so pickers and the defaults
    autofill never offer them), and `createTransaction(FireflyTransactionRequest)`
    (`POST /v1/transactions`) all exist now - nothing in the UI calls
    the latter two yet (see TODO.md for what still has to exist first:
    an asset-account picker, a dedup log, the "Создать" button).
    `FireflyJson.kt` holds the request-building/response-parsing for
    those two, pulled out the same way `FireflyUrl.kt` was, for the
    same plain-Kotlin-unit-testable reason (`FireflyJsonTest`).
    Request/response field names and shapes (`TransactionSplitStore`,
    `AccountRead`, the `TransactionTypeProperty` enum being exactly
    `withdrawal`/`deposit`/`transfer`, lowercase) were checked against
    Firefly's own OpenAPI spec before writing any of this, not
    guessed - `FireflyTransactionRequest.type` is that exact literal
    string, not `rules.RuleKind`, to keep `network` genuinely decoupled
    from `rules` (the caller maps `RuleKind.name.lowercase()`).
    `createTransaction` always sends `error_if_duplicate_hash: true` -
    a free extra safety net on top of this app's own planned dedup
    check (see TODO.md), since Firefly rejects the request outright if
    *it* also thinks this is a duplicate.
    `searchTransactionsByQuery(query)` - `GET
    /v1/search/transactions?query=...` (the free-text operator-search
    endpoint, `query` built by `dedupSearchQuery` above and
    URL-encoded), paginated the same way `listAssetAccounts` is.
    **Replaced an earlier account-id-scoped version** (`GET
    /v1/accounts/{id}/transactions?start=...&end=...`) that required a
    Firefly account id on at least one side of the draft to even run -
    a real on-device bug the user caught directly: this app frequently
    only has an account *name* (a rule's source/destination typed as
    free text rather than picked from `listAssetAccounts`), and posting
    a transaction by name alone is already fully supported
    (`FireflyTransactionRequest`), so the remote dedup check silently
    never ran at all for exactly the rules most likely to need it,
    while creation itself worked fine. The free-text endpoint's query
    syntax was deliberately avoided the first time this was built
    (not verified, judged not worth the risk) - properly verified this
    time against `OperatorQuerySearch`'s current source (the operator
    names) and `gdbots/query-parser`'s own docs (the quoting syntax),
    see `dedupSearchQuery`'s KDoc. Response parsing
    (`FireflyJson.parseTransactionList`) is unchanged - both endpoints
    return the identical shape (same `TransactionGroupTransformer`
    server-side) - flattens every returned group's splits into one
    list, each keeping its own group's id - this app's own transactions
    never split, but a real one in Firefly might; field names (`date`
    as Carbon's `toAtomString()`, `amount`, `currency_code`,
    `description`,
    `source_id`/`source_name`/`destination_id`/`destination_name`)
    confirmed against `TransactionGroupTransformer`'s current source.
    Used by `MessagesFragment`'s remote duplicate check below once the
    local dedup log already came up empty.
    `getTransaction(id)` - `GET /v1/transactions/{id}`, the full fields
    of one already-known transaction (confirmed `ShowController::show()`
    wraps a single Fractal `Item`, not a `Collection`, against its
    current source - `data` is one object, not an array, hence a
    separate `FireflyJson.parseSingleTransaction` rather than reusing
    `parseTransactionList`). The user's own request: a *locally* found
    duplicate (`CreatedTransactionsDb.findWithinWindow` only ever
    returns a bare id, no fields at all) used to show nothing but that
    id in the duplicate-confirm dialog, unlike a remote match (which
    already carries full fields from the search response) - this fetches
    the missing fields on demand so the dialog always shows the same
    level of detail (date/amount/description/accounts) regardless of
    which path found the match. `null` on success with no usable data
    (malformed/empty response), same "missing data isn't a failed call"
    distinction `searchTransactionsByQuery` already draws via
    `DuplicateCheckResult`. **Fixed a real "stale cache" bug, the user's
    own catch**: a `404` is now also mapped to this same `null`-on-success
    shape, confirmed against `ShowController::show()`'s current source
    (Laravel route-model-binding on a nonexistent id never reaches the
    method body - `ModelNotFoundException` -> 404 - and an
    existing-but-inaccessible one hits an explicit
    `throw new NotFoundHttpException()`, both unambiguous "definitely
    doesn't exist"). This is what lets `posting.findDuplicate` (below)
    tell a confirmed-gone cached id apart from a real error.
    `FireflyTransactionCandidate`/`RemoteTransactionCandidate` also now
    carry `notes`/`category`/`tags` (parsed from the split's own
    `notes`/`category_name`/`tags` fields, confirmed against
    `TransactionGroupTransformer`'s current source, not assumed) -
    previously missing entirely, so any dialog built from a verified
    candidate could only ever show a subset of what `buildMatchedBody`
    (the rule-derived version) showed, forcing a choice between "real
    data, incomplete" and "complete, possibly wrong". The user's own
    catch: a transaction's description/notes/category/tags can be
    edited by hand directly in Firefly after this app posted it, and the
    rule-derived draft has no way to know that happened.
    **Fixed a real parsing bug, caught directly on-device the same
    day**: a category-less transaction showed up in the dialog as the
    literal text "Category: null" instead of being omitted. `org.json`
    quirk - `JSONObject.optString(name)` returns the *string* `"null"`,
    not a real Kotlin `null`, when the field's JSON value is explicit
    `null` rather than absent (`JSONObject.NULL` is a real sentinel
    object, so `optString`'s own internal non-null check still passes,
    and that sentinel's `toString()` happens to be `"null"`).
    `JSONObject.optStringOrNull` (new, checks `isNull(name)` first -
    org.json's own correct way to ask) replaces the bare `optString` for
    every genuinely-nullable split field (`foreign_currency_code`,
    `source_name`/`destination_name`, `notes`, `category_name`) -
    `foreign_amount` already happened to be safe by accident (`"null"`
    isn't valid `BigDecimal` syntax, so `toBigDecimalOrNull()` silently
    produced a real `null` anyway), but the others had no such luck.
    `updateTransaction(id, FireflyTransactionRequest)` (`PUT
    /v1/transactions/{id}`, routed to `UpdateController@update`,
    confirmed against `routes/api.php`'s current source) - the user's
    own request: edit an already-linked transaction's fields in place
    instead of only ever being able to create a new one. Reuses
    `parseCreatedTransactionId` unchanged (an update's response is the
    same `Item`-wrapped resource shape a create's is) and a new
    `FireflyJson.buildTransactionUpdateRequestBody`, which shares
    `buildTransactionRequestBody`'s split-building (`buildTransactionSplit`,
    factored out of both) but skips the `error_if_duplicate_hash`
    wrapper key - not a key `UpdateRequest` recognizes, confirmed against
    its current source. That source also confirms `UpdateRequest.
    getTransactionData()` is a genuine *partial* update - every field
    group (`integerFields`/`stringFields`/`textareaFields`/`dateFields`/
    `booleanFields`/`arrayFields`/`floatFields`) is only copied into the
    update if the submitted JSON has that exact key at all
    (`array_key_exists`), so this app's update request - which never
    includes fields it doesn't expose (budget, piggy bank, SEPA fields,
    `internal_reference`, reconciliation state, ...) - leaves all of
    those untouched automatically, with no need for this app to fetch
    and round-trip them itself. **Deliberately not wrapped in
    `withNetworkRetry`**, same reasoning as `createTransaction` above -
    confirmed non-idempotent the same way (no `Idempotency-Key`, same
    content-based `DuplicateTransactionException` heuristic would also
    apply if a retried update happened to reproduce another existing
    transaction's fields). Firefly's own `preventUpdateReconciled`
    validator rejects `source`/`destination`/`amount`/`foreign_amount`
    changes outright on a reconciled transaction - deliberately *not*
    pre-detected or restricted client-side (the user's own choice, asked
    directly): this app just lets that request fail and shows Firefly's
    real error, rather than guessing which fields are locked.
  - `withNetworkRetry` (`network/NetworkRetry.kt`) - up to 3 attempts,
    exponential backoff (500ms/1000ms), retrying only on `IOException`
    (a genuine network-level failure - no connection, timeout, DNS) and
    never on an HTTP error response (4xx/5xx reached the server and got
    an answer, retrying blindly won't change that). `internal suspend
    inline fun` - has to be `inline` so a `return@withContext` inside the
    retried block (the existing "bail out of the whole paginated fetch on
    a non-2xx page" pattern already used throughout this file) still
    compiles; `internal` because a public inline function can't access a
    non-public top-level property (`RETRY_BASE_DELAY_MILLIS`), and there
    was no reason for either to be public anyway. Wraps every read-only
    call in this class (`testConnection`/`listAssetAccounts`/
    `listCategories`/`searchTransactionsByQuery`/`getTransaction`) and
    `ExchangeRateClient.fetchRate` - a repeated `GET` is always safe.
    **Deliberately does not wrap `createTransaction`** - confirmed against
    Firefly's `StoreController::store()` source: there's no
    `Idempotency-Key` mechanism, only a server-side content-based
    duplicate check (`DuplicateTransactionException`) that's a heuristic
    on Firefly's own terms, not a transport-level guarantee. The failure
    mode that matters is a timeout *after* the server already processed
    the write - retrying that risks silently creating a second real
    transaction, exactly what this app's dedup machinery exists to
    prevent. The user's own explicit call after asking "is this
    idempotent?" rather than assuming either way.
    `listCategories()` - `GET /v1/categories` (`ShowController@index`,
    confirmed against Firefly's current `routes/api.php`, not assumed),
    same paginated shape and `FireflyJson.parseCategoryList` parsing as
    `listAssetAccounts`/`parseAccountList` (`CategoryTransformer`'s
    current source confirms the `attributes.name` field, not guessed).
    `ui.CreateTransactionFragment`'s category picker source - see the
    `ui` section below for why this exists (a free-typed category can
    be posted misspelled with nothing flagging it, unlike the
    asset-account side which already had a picker).
    `listCurrencies()` - `GET /v1/currencies` (`ShowController@index`,
    confirmed against Firefly's current `routes/api.php`) - unlike
    `listAssetAccounts`/`listCategories`, this endpoint has **no
    server-side "enabled only" filter** at all (`repository->getAll()`,
    confirmed against the controller's current source - every currency
    Firefly has ever known about, enabled or not), so `FireflyJson.
    parseCurrencyList`'s own `enabled` field (`CurrencyTransformer`'s
    current source confirms the name, not guessed) is what callers
    filter on client-side. `ui.TransactionCreationFragment`'s "Default
    account currency" picker source (see TODO.md for why that setting
    exists) - deliberately a *different* data source from `ui.
    RuleEditFragment`'s own currency picker, which reads this app's
    own locally-maintained `rules.CurrenciesDb` symbol<->code table
    instead - that one's for typing a rule's `homeCurrency` by hand
    without a typo, this one's "what does my actual Firefly instance
    have switched on right now".
  - Cleartext (`http://`) support is opt-in (AI-Review P1). Android blocks
    it by default at API 28+, and `res/xml/network_security_config.xml`
    allows it globally (`<base-config cleartextTrafficPermitted="true" />`)
    rather than for a domain, since the Firefly host is whatever the user
    types in Settings and a network security config can't be toggled at
    runtime. The real gate is in `FireflyApiClient.openConnection`: for an
    `http://` base URL it throws `CleartextBlockedException` (an
    `IOException`, never retried by `withNetworkRetry`) *before* the bearer
    token is sent, unless `FireflyPrefs.isAllowCleartext()` is on - the
    "Allow unencrypted HTTP" switch on `FireflyConnectionFragment`, shown
    only for an `http://` URL, off by default, and Save refuses an `http://`
    URL while it's off. An install that already had an `http://` URL saved
    is migrated to on once (`FireflyPrefs.migrateAllowCleartext`). Every
    call site builds its client through `FireflyPrefs.apiClient()` so none
    can forget the flag.
  - Server change clears the token (AI-Review): the saved token belongs to
    the saved server, so it must never reach a different one (least of all
    an `http://` one). `isServerChange(saved, new)` (`FireflyUrl.kt`, ignores
    trailing slash/case, false on first-time setup) drives the guard in
    `FireflyConnectionFragment` via `confirmServerChangeIfNeeded()`: when the
    URL field loses focus - and again at the top of Test connection and Save,
    because tapping a button does not take focus from the field - a changed
    URL with no freshly typed token and a saved token asks "saved token will
    be cleared, continue?" (not cancelable by tapping outside). Continue
    persists the new URL and calls `FireflyPrefs.clearToken()` immediately, so
    saved URL and token never disagree, and flags the token field as required;
    Cancel restores the previously saved URL. Typing a new token skips the
    dialog (it replaces the old one anyway).
    Test connection and Save share one `validateInputs()`, same order in both:
    the barrier first, then URL-blank / `http://`-without-opt-in / token-missing
    errors, all shown at once on the fields' own `TextInputLayout` sublines
    (never `EditText.error` - its tooltip covers the helper text and adds a red
    icon inside the field). The `http://` risk text is that same URL-field error
    (`updateUrlState()`, shown for any `http://` URL: "blocked" wording while the
    switch is off, a plain warning once on) - no separate warning view; the
    "Allow unencrypted HTTP" switch sits directly under the field.
  - Redirects are never followed (AI-Review P2): `FireflyApiClient.openConnection`
    sets `instanceFollowRedirects = false`, because `HttpURLConnection` would
    otherwise re-send the `Authorization` header to a different host on a
    same-scheme redirect, and would turn a redirected POST/PUT into a GET. Any
    3xx becomes a `Result.Failure` (`redirectMessage`, showing the `Location`
    so the user can correct the Firefly URL) - never retried, nothing re-sent.
    Typical trigger: a saved `http://` URL whose server redirects to `https://`.
  - `ExchangeRateClient`/`ExchangeRateUrl` - same split as
    `FireflyApiClient`/`FireflyUrl` (real call vs. plain-Kotlin
    unit-testable URL-building/response-parsing, `ExchangeRateUrlTest`),
    talking to Frankfurter (`api.frankfurter.dev/v2`, a free, keyless,
    ECB-based rate API - completely unrelated to Firefly's own HTTP API,
    just living in the same `network` package since it's this app's
    other outbound HTTP call) rather than Firefly's own built-in
    exchange-rate table - see TODO.md's `LOCAL_AMOUNT_MISSING` entry for
    why: Firefly silently falls back to a rate of 1.0 for a pair it has
    no data for, with nothing in the response flagging that. `GET
    /rate/{base}/{quote}?date=YYYY-MM-DD` (the notification's own date,
    not "today"), response shape `{"date","base","quote","rate"}` -
    verified against the live API, not assumed. No API key. `rate` is
    read via `optString`+`BigDecimal(String)`, not `optDouble`, to avoid
    a double round-trip on a value about to be multiplied against a
    real money amount. The computation itself
    (`rules.applyApproxConversion` - `amount = foreignAmount * rate`,
    `HALF_UP` to 2 decimals, plus appending
    `FireflyPrefs.getApproxConversionTag()` to the draft's tags) lives
    in `rules`, not `network`, and takes a plain `BigDecimal` rate - so
    `rules` still has no dependency on `network`, same direction as
    `network` not depending on `rules`; the caller (currently only
    `MessagesFragment`, see its entry below) is the one bridging both.
- Per-app language (system "App language" page, Android 13+):
  `res/xml/locales_config.xml` lists `en` and `ru`, referenced by
  `android:localeConfig` on `<application>`. Without it the app is absent
  from that page and just follows the system language. No in-app picker and
  no `AppCompatDelegate` (Android 12 and older keep following the system
  language). `LocalesConfigTest` fails when the list and the `values-*`
  folders differ - add a language to both together. Shipped: en, ru, de
  (German uses the formal "Sie"; Firefly III's own German terms -
  "Buchung" for a transaction, Ausgabe/Einnahme/Umbuchung for the
  three kinds).
- Android Auto Backup (distinct from the `backup` module's user-driven
  export/restore below; AI-Review P1). `allowBackup` stays on, but
  `res/xml/backup_rules.xml` (Android 11 and older, `fullBackupContent`) and
  `res/xml/data_extraction_rules.xml` (Android 12+, cloud backup *and*
  device transfer) are an **allowlist**: only `rules.db`, `monitored_apps`,
  `notifications`, `retention`, `setup_checklist` and `firefly_settings` (the
  plain posting settings, no secrets) are included, so any
  storage added later is excluded until someone opts it in. Deliberately
  left out: `message_history.db` and `created_transactions.db` (captured
  bank-notification text and created-transaction records must not leave the
  device through the user's cloud account), `firefly_connection`
  (`SecureSettings`: server URL + token - Keystore-encrypted, so unreadable on
  another device anyway - explicitly `<exclude>`d; the plain settings that used
  to share that file moved to `firefly_settings` in v0.241 and are included), `currencies.db` (a cache of
  the user's Firefly instance, refetched) and `startup_migrations`.
  `BackupRulesTest` checks the two XML files agree, the sensitive stores are
  absent, and every `DB_NAME`/`PREFS_NAME`/`getSharedPreferences("...")` in
  the sources is classified as included or excluded - adding a new store
  fails it until a decision is made.
  How to verify on a device: **`adb backup` is not representative** - on a
  debuggable build it dumped everything (history, transaction log, Firefly
  connection) despite the allowlist. Use the real path instead: temporarily
  `bmgr transport com.android.localtransport/.LocalTransport` (so nothing
  reaches the user's Google account), `bmgr backupnow <package>`, then read the
  `file_backup_helper: Name:` lines from `adb logcat` for that run, and switch
  back with `bmgr transport com.google.android.gms/.backup.BackupTransportService`.
  Checked this way on v0.176: only `rules.db` and the four allowlisted prefs.
- `backup` - export/import and full backup/restore, added directly in
  response to a real data-loss incident (see "Known bugs / gotchas"
  below) - the user asked for both in the same breath once it happened:
  a way to share rules, *and* a way to not lose everything again. Two
  genuinely different tools, not one feature with two buttons - kept
  as separate pure-JSON files/UI entry points because their semantics
  differ (append vs. replace) and conflating them would make it easy
  to pick the wrong one by accident:
  - `RulesJson.kt` (`rulesToJson`/`rulesFromJson`, `RulesJsonTest`) -
    the Rules screen's own Export/Import buttons
    (`RulesListFragment`), a *sharing* format for one or more rules
    between devices/people. Import always appends as new rows
    (`RulesDb.add` assigns a fresh id) - never a replace - so sharing a
    rule can't accidentally wipe rules someone already built
    themselves. Deliberately excludes `id`: a row id is this device's
    internal identity, meaningless elsewhere. (Used to also exclude
    `sourceAccountId`/`destinationAccountId` - moot now, `ParseRule`
    doesn't carry a Firefly account id at all anymore, see its own
    KDoc.) An entry is skipped only when it lacks a valid `kind` or a
    non-blank `pattern`; a blank/missing `homeCurrency` is valid (means
    "use the default account currency", same as `RuleValidation`) - both
    this parser and `FullBackupJson`'s used to reject it, silently
    dropping such rules on import/restore (v0.194 fix).
  - `FullBackupJson.kt` (`FullBackupData`, `fullBackupToJson`/
    `fullBackupFromJson`, `FullBackupJsonTest`) + `BackupManager`
    (Android-dependent orchestration, not unit tested, same reason
    `RulesDb` isn't) - the new "Backup & restore" Settings row
    (`BackupFragment`), covering monitored apps, currencies, rules, the
    plain settings (`BackupSettings`: the 9 `FireflyPrefs` settings + the 3
    `NotificationPrefs` flags, in an optional `settings` section - an older
    file without it restores *no* settings rather than resetting them; never
    the connection, `SecureSettings`),
    message history (plus its `persistHistory`/`retentionDays`
    setting; an old file with only `retentionDays`, 0 = off, still
    maps correctly, and restore re-applies the setting through
    `RetentionController.applyOnSettingChanged`), *and* the
    `CreatedTransactionsDb` dedup log in one file. Has its own per-rule shape (`toBackupJson`/
    `toBackupRuleOrNull`) now, *not* a reuse of `RulesJson`'s - the two
    formats are for genuinely different jobs and shouldn't share one
    shape just because a rule is a rule (and, as of the account-id
    removal above, happen to be textually identical now - kept separate
    anyway in case a future field needs the per-format split again). A
    full backup is a same-device safety net, unlike `RulesJson`'s
    cross-device/cross-person sharing format - it doesn't even touch
    the Firefly connection's base URL, so restoring onto a materially
    different instance was never really its job - and `RulesDb.add`
    always assigns a fresh row id on restore regardless of what's in
    the file, so `id` still has nothing to round-trip for either way.
    The backup file is plain, unencrypted JSON and the message history in
    it holds full notification texts (amounts, card digits, merchants), so
    the `backup_explanation` text on `BackupFragment` says so explicitly
    (AI-Review) - the user chooses where the file goes.
    **Fixed a real data-loss bug, caught directly by the user testing a
    just-made backup**: an earlier version of this file dropped
    `messages`' `fireflyTransactionId` on the "Firefly-instance-specific
    derived id, excluded even here" theory, and a restored message
    silently lost its "#1234" link even though the transaction still
    existed in Firefly. `fullBackupToJson`/`fullBackupFromJson` carry
    `fireflyTransactionId` straight through now. `createdTransactions`
    (`rules.CreatedTransactionRecord`, `CreatedTransactionsDb.loadAll`/
    `clearAll`) exists for the same underlying reason: without backing
    it up too, the dedup log would come back empty after a restore onto
    a fresh install, silently disabling duplicate detection for
    everything created before the backup. Deliberately excludes
    the Firefly connection (base URL/
    token): the one real secret in this app, `KeystoreCipher`-encrypted
    against an Android Keystore key that wouldn't even decrypt on a
    different device or after a reinstall, and a backup file is exactly
    the kind of thing that ends up in a cloud folder or gets shared -
    not somewhere a bearer token belongs. `BackupManager.restore`
    *replaces* every store it touches (clears first, then re-adds) -
    restoring puts the device back exactly how the backup found it,
    not merged on top of whatever's currently there. `BackupFragment`
    confirms with the user before calling it (`AlertDialog`,
    "This can't be undone") - the "ask before a destructive/hard-to-
    reverse action" step that was missing from the old `RulesDb`
    `onUpgrade` pattern that prompted this whole feature. Its "Create
    Backup…" button label (renamed from "Export backup…") distinguishes
    it from the rules-only shortcuts below it on the same screen.
    `BackupFragment` also hosts a second "Export Rules…"/"Import
    Rules…" entry point onto `RulesListFragment`'s own export/import -
    literally the same `rulesToJson`/`rulesFromJson`/`RulesDb` calls,
    not a reimplementation, just reachable from here too so a user
    looking for "Backup & restore" doesn't have to already know rules
    export/import lives on a different screen. No `reload()` call after
    import here (unlike `RulesListFragment`'s) - this screen has no
    rule list to refresh.
  - Both read/write via Storage Access Framework
    (`ActivityResultContracts.CreateDocument`/`OpenDocument`,
    `application/json`) - no storage permission needed, no app-private
    file path to explain to the user, the system file picker is the
    whole UI for "where does this go"/"where do I get this from". Every
    completion toast (backup export/restore, rules export/import, both
    screens) includes the file name when it's known
    (`ui.displayNameOf(context, uri)`, `OpenableColumns.DISPLAY_NAME`
    via `ContentResolver.query`) - SAF deliberately doesn't expose a
    real filesystem *path* for a `content://` Uri, only a display name,
    and even that isn't guaranteed (a `DocumentsProvider` can simply not
    report one); each toast string has a `_named` variant
    (`backup_export_success_named`, etc.) used only when a name actually
    came back, falling back to the plain no-name message otherwise -
    "if we know it", not a guess.
- `posting` - the shared machinery behind actually posting a
  transaction, used by both the manual "Создать" button
  (`ui.MessagesFragment`) and automatic creation (below); depends on
  both `rules` and `network`, same layering `ui.MessagesFragment`
  itself already had - this package exists so that dependency doesn't
  have to live in `ui` alone anymore:
  - `DuplicateChecker.kt` (`DuplicateCheckResult`, `checkForDuplicate`,
    `findDuplicate`, `FireflyTransactionCandidate.toRemoteCandidate`) -
    the local-then-remote dedup lookup, pulled out of
    `ui.MessagesFragment` (where it used to live as private members)
    specifically so `AutoCreateWorker` could call the exact same logic
    a manual create already goes through, rather than re-deriving the
    same comparison a second way and risking the two paths drifting
    apart over time. Pure extraction, no behavior change -
    `MessagesFragment` now imports these as top-level functions.
    **Fixed a real "desync" bug, the user's own catch and report**: a
    local-log (`CreatedTransactionsDb`) hit used to be trusted blindly -
    returned as `Found` straight from the cached id, with nothing ever
    confirming Firefly's side still actually has it. Deleting a
    transaction by hand in Firefly (e.g. because it was wrong) left this
    app's own log none the wiser - "Check if created"/the preemptive
    create-flow check kept presenting a dead reference ("Transaction #X
    matches", with "Open" pointing nowhere). `findDuplicate` now runs a
    live check before trusting a local hit: still there -> `Found` (with
    real fields from that same call, skipping the separate fetch
    `MessagesFragment` used to need); confirmed gone -> the cached id is
    stale, falls through to the remote search exactly as if the local
    log had found nothing; couldn't tell (network/server error) ->
    `SearchFailed` immediately.
    `LinkedTransactionCheck`/`verifyLinkedTransaction(prefs, id)` - **the
    one shared method the user explicitly asked for** after catching
    this same "does a known id still exist" check implemented three
    separate times that didn't even agree with each other
    (`findDuplicate`'s own inline version above, plus two independent
    copies in `ui.MessagesFragment` - `verifyLinkedThenShow`'s inline
    check and a `staleLinkedIdOrNull` helper, the latter of which threw
    away a found candidate's fields entirely even though it had just
    fetched them). `findDuplicate`'s local-hit check above,
    `MessagesFragment.verifyLinkedThenShow` (tapping an already-linked
    message), `MessagesFragment.checkIfCreated` (checking a known linked
    id before ever searching), and `MessagesFragment.fetchCandidateInfo`
    (fetching a known id's fields for the duplicate-confirm dialog) all
    go through this one function now - a 404 means the same thing, a
    found candidate carries the same fields, everywhere. Returns
    `Found(candidate)`/`Gone`/`CheckFailed(message)` - `Gone` is only
    ever a confirmed `404` (verified against `ShowController::show()`'s
    source, not guessed), never collapsed into a generic failure.
    `DuplicateCheckResult.SearchFailed` changed meaning for *every*
    caller, not just `checkIfCreated` - the user's own explicit
    instruction: it used to be treated the same as `None` everywhere
    else ("we don't know, so assume no duplicate and proceed"), but now
    that a `SearchFailed` can mean "a local hit exists but couldn't be
    verified", that's backwards - a duplicate is *more* likely to be
    real, not less, so every caller now blocks and surfaces the error
    instead: `MessagesFragment.checkThenOpenCreateOrShowFound`/
    `startCreate` show the existing `check_search_failed_title` dialog
    instead of opening the review screen; `CreateTransactionFragment.
    onCreateClicked` shows the same dialog and re-enables "Create
    transaction" instead of posting; `AutoCreateWorker` retries this
    specific check (`Result.retry()`/`MAX_ATTEMPTS`, see its own entry
    below for why a failed `createTransaction` call itself gets no
    such retry) rather than silently posting on an unverified dedup
    check.
  - `AutoCreateScheduler`/`AutoCreateWorker`/`AutoCreateNotifications` -
    automatic transaction creation, gated by `FireflyPrefs.
    isAutoCreateEnabled()` (a long-standing setting that previously had
    no code reading it at all - see TODO.md). Design decided with the
    user directly before building: a likely duplicate is *skipped*, not
    posted and not auto-resolved (no human in the loop here to make
    that call, unlike the manual path's "create anyway" confirmation),
    surfaced as a system notification so the user can still act on it
    by hand; a genuine success also gets a system notification, since
    there's no in-app dialog to show it in when the app isn't even
    open.
    `AutoCreateScheduler.scheduleIfEnabled(context, message)` is the one
    entry point - called right after a genuinely new message is
    recorded, from both `notifications.BankNotificationListenerService`
    (a real notification) and `ui.AddTestMessageFragment` (a manually-
    injected test message, "exactly like a real notification would" per
    its own KDoc, deliberately includes this too).
    **The user's own explicit requirement, and the reason this needed a
    real scheduler/worker rather than just launching a coroutine per
    message**: two notifications can arrive essentially simultaneously,
    and auto-create must never process them in parallel - a race
    between two concurrent duplicate checks could let both
    independently decide "not a duplicate" and both post the same real
    payment, exactly the failure mode dedup exists to prevent in the
    first place. Every message is enqueued into one single WorkManager
    unique work chain (`WorkManager.enqueueUniqueWork`,
    `ExistingWorkPolicy.APPEND_OR_REPLACE`, `AutoCreateScheduler.
    UNIQUE_WORK_NAME`) regardless of origin - appending makes each new
    request a dependency of whatever's already queued/running in that
    chain, so WorkManager itself guarantees strict one-at-a-time,
    in-arrival-order execution with no manual locking needed on this
    app's side, and it survives process death (WorkManager persists its
    own queue), so a burst that arrives right before the app is killed
    still finishes in order once it restarts.
    **Fixed a real "every future auto-create silently never runs
    again" bug, root-caused on the user's own device, not guessed**:
    pulled `androidx.work.workdb` straight off the device (`adb
    run-as` + `sqlite3`, `WorkSpec`/`WorkName` tables) once a long
    on-device debugging session (OS permission, in-app notification
    toggle, per-channel OS block, battery restriction, and the
    matching logic itself, all individually confirmed clean, plus a
    second, untouched device working fine with the identical build)
    had ruled out everything *except* something specific to that one
    device's own accumulated state. The query showed two `AutoCreateWorker`
    `WorkSpec` rows sharing the same `WorkName` ("auto_create"), one
    with `run_attempt_count = 6` in a terminal `FAILED` state (a
    leftover from *before* `AutoCreateWorker.post`'s own
    retry-until-`MAX_ATTEMPTS` loop was removed - see that class's own
    KDoc), the other `run_attempt_count = 0`, also `FAILED`, despite
    never having actually run. Root cause: plain `ExistingWorkPolicy.
    APPEND` makes a new request a dependent of the chain's current
    *tail*, whatever state that tail is in - and WorkManager cascades
    a `FAILED`/`CANCELLED` tail's terminal state straight to every
    dependent without ever invoking them. Once that one job failed,
    every later message got silently enqueued as a dependent of an
    already-dead tail - `doWork` never ran again for any of them, no
    error, no notification, nothing, until the chain was cleared.
    Fixed two ways: `ExistingWorkPolicy.APPEND_OR_REPLACE` instead of
    `APPEND` - behaves identically while the tail hasn't finished, but
    starts a fresh chain instead of appending once the tail *has*
    reached a terminal state, so this specific poisoning can't happen
    again; and `FfNotifyImporterApp.purgePoisonedAutoCreateChainOnce`
    - a one-time `WorkManager.cancelUniqueWork(UNIQUE_WORK_NAME)` on
    startup, guarded by a `startup_migrations` prefs flag, to
    retroactively un-poison a chain that got stuck this way on an
    install that hit the bug before this fix shipped (harmless even
    when nothing was actually stuck - at worst drops one in-flight
    attempt, same as any other dropped auto-create outcome).
    `AutoCreateWorker` (`CoroutineWorker`) re-checks `isAutoCreateEnabled`/
    `isConfigured` itself (not just trusting the scheduler's check at
    enqueue time, since the setting or connection could have changed
    while a message sat behind an earlier one in the chain), re-parses
    the message fresh via `RuleEngine.match` (input data only carries
    the plain packageName/appLabel/title/text/timestampMillis fields,
    `WorkManager.Data` only holds primitives - the `CapturedMessage` is
    reconstructed from those), resolves `DraftIssue.LOCAL_AMOUNT_MISSING`
    the same way `MessagesFragment.resolveAmountQuietly` does, then
    calls `posting.checkForDuplicate`.
    **Fixed a real "looks like nothing happened" bug, the user's own
    catch on-device**: a draft whose `kind` requires an account side
    that's blank (`DraftIssue.ACCOUNT_SELECTION_REQUIRED` - see
    `rules.TransactionDraft`'s own KDoc; the exact, 100%-of-the-time
    case for a `rules.BuiltInRules` match, which leaves both account
    sides blank on purpose) used to be posted anyway - Firefly's API
    rejects a transaction with no source/destination account every
    time, so this silently burned through all `MAX_ATTEMPTS` retries
    (exponential backoff between each) before finally showing a
    failure notification, which from the user's side looked like
    total silence for a long stretch after adding a debug test
    message. `doWork` now checks this flag right after resolving the
    amount and, if set, skips straight to `ReviewNotifications.
    notifyMatch` (the exact same notification `ReviewNotificationWorker`
    shows when auto-create is off) instead of ever attempting to
    post - a human has to pick the account by hand regardless, no
    point retrying something structurally incapable of succeeding.
    A failed dedup-check *verification* (`DuplicateCheckResult.
    SearchFailed` - a local-cache hit that couldn't be confirmed
    against Firefly) retries (`Result.retry()`, exponential backoff)
    up to `MAX_ATTEMPTS` (5) - the actual "WorkManager for retrying
    failed API calls" use AGENTS.md's own stack note was written for,
    and safe specifically because nothing has been written to Firefly
    yet at that point, a repeated *read*. Capped rather than left to
    WorkManager's default indefinite retry: since every message
    shares one `APPEND` chain, a stuck-retrying item would otherwise
    block every later notification's auto-create attempt forever (a
    dead/revoked token would silently stall the entire feature, not
    just one message) - exceeding the cap gives up (`Result.failure()`)
    instead, unblocking the chain for whatever's queued behind it, and
    notifies the user once via `AutoCreateNotifications.notifyFailed`
    rather than retrying forever in silence. A `Constraints.
    setRequiredNetworkType(NetworkType.CONNECTED)` constraint means a
    request sitting in the chain without connectivity just waits
    instead of burning through those attempts on a guaranteed-to-fail
    call - once connectivity returns, the whole backlog drains in
    order.
    **`post`'s own `createTransaction` call never retries, on purpose -
    fixed a real inconsistency, the user's own catch**: an earlier
    version gave a failed `createTransaction` call the identical
    `Result.retry()`/`MAX_ATTEMPTS` treatment - directly contradicting
    the non-idempotency reasoning already settled and documented on
    `network.FireflyApiClient.createTransaction` itself (deliberately
    never wrapped in `network.withNetworkRetry`, precisely because a
    lost response *after* Firefly already wrote the transaction would
    retry into a genuine second one - that KDoc's own "the caller
    decides whether to try again" means a *human*, the same way
    `updateTransaction`'s own failure just surfaces and stops, no
    retry at all). Automatically retrying at the Worker layer carries
    the exact same risk as retrying inside `FireflyApiClient` would
    have - same non-idempotent write either way - so `post` now
    matches `updateTransaction`'s posture instead: one failure, one
    `notifyFailed` notification, `Result.failure()`, stop. The user
    resolves it by hand afterward (`checkIfCreated`/manual create),
    the same human-supervised path every other ambiguous case in this
    app already goes through.
    `AutoCreateNotifications` posts to one `NotificationChannel`
    (`IMPORTANCE_DEFAULT` - not `_HIGH`, no heads-up warranted for a
    background bookkeeping result; not `_LOW` either, a skipped-
    duplicate or a failure deserves more than a silent badge), created
    lazily on first use. **Tap targets (v0.207, the user's own bug
    report)**: for the success and skipped-duplicate notifications both
    the whole-notification tap and the "Open transaction" button open
    the app's own editor for the relevant transaction -
    `CreateTransactionActivity.editIntent(message, id)` on top of
    `MainActivity` (`PendingIntent.getActivities`, so Back stays in
    the app; request code unique per message, extras don't count for
    `PendingIntent` identity) - the created one for a success, the
    *existing* one the skip was against for a skipped duplicate. They
    used to open the app's message list (body) and Firefly's web page
    (button), which is not where "open the created transaction"
    should land. The failure notification still just opens the app.
    **Second action "Open in Firefly III" (v0.208)**: the same two
    notifications also carry an `ACTION_VIEW` button for Firefly III's own
    web page (`network.transactionUrl`), next to the in-app editor
    button (`webIntent`). **Every tap on those two notifications (body,
    either button) dismisses them (v0.210)**: an action button never
    auto-cancels (only a body tap does, via `setAutoCancel`), and once the
    transaction exists the notification has nothing left to say - so
    all three PendingIntents go through `posting.DismissAndOpenActivity`,
    an invisible (`Theme.NoDisplay`) activity that cancels the
    notification id, then starts the real target (the editor alone - no `MainActivity` is
    launched under it since v0.224, so Back returns to where the user was
    and the list does not flash - or the web `ACTION_VIEW`). The
    trampoline has `taskAffinity=""` (v0.227): otherwise it joined the app's
    existing task (a root-screen Back only backgrounds the task since
    Android 12), whose list came to the front before the editor started and finishes. An activity,
    not a receiver/service, so the notification-trampoline restriction
    doesn't apply. The review notifications are unchanged (they stay
    until a transaction is actually linked). **Tap on a review
    notification (v0.226)** still goes through `MainActivity` (the check's
    dialogs live in `MessagesFragment`), but the toolbar and list are
    hidden behind a blank panel while the check runs
    (`MainActivity.beginReviewCheck`, decided from the unconsumed check
    extras in `onCreate`/`onNewIntent`, so the list never draws); after
    500 ms the panel adds "taking longer than usual". When the check ends
    by opening the editor, `MessagesFragment.showTestMatch(...,
    fromNotification = true)` finishes `MainActivity`, so Back from the
    editor leaves the app instead of landing on the list; when it ends in
    a dialog, the list is revealed behind it and stays. **That finishing
    only happens if the app was in the background at the tap (v0.231)**:
    if the user was already on the app's screen, the same tap used to drop
    Back/Save to the launcher (the task held only the editor). Now
    `MainActivity.openedWhileVisible` (from `AppVisibility`, an
    `ActivityLifecycleCallbacks` counter of started activities - at the
    moment the recreated `MainActivity.onCreate` runs the previous instance
    is only paused, so a non-zero count means "was on screen") keeps this
    screen under the editor, and Back/Save return to it.
    **Fixed a real "can't tell what happened without opening Firefly"
    gap, caught directly on-device**: two notifications from the same
    app (Google Wallet), worded differently enough that `posting.
    DuplicateChecker`'s hash didn't match them to each other, each
    silently auto-created its own Firefly transaction for what was the
    same real payment - the notification text alone (just the app name
    and a bare `#id`) gave no way to tell *why*, short of comparing both
    transactions by hand in Firefly's own UI. `auto_create_success_text`/
    `auto_create_skipped_text` now include the parsed amount/currency/
    description (`"%appLabel% · %amount% %currency% · \"%description%\"
    → #%id%"`, one compact line, the user's own format pick) - for
    `notifySkippedDuplicate` specifically, the *incoming* (skipped)
    draft's own fields, not the existing transaction's, since that's the
    data this worker actually parsed and is what needs comparing against
    what "Open transaction" shows. **The skipped-duplicate notification
    is opt-in** (`NotificationPrefs.isNotifySkippedDuplicatesEnabled`,
    off by default, switch "Notify about skipped duplicates" on the
    Notifications settings screen, autosaved and disabled while
    notifications are off, like the financial-info switch; the user's own call - a
    skipped duplicate is usually a second app reporting the same
    payment, so mostly noise): `AutoCreateWorker` still skips the
    duplicate either way, the setting only gates the notification.
    Requires `POST_NOTIFICATIONS` on
    API 33+ (declared in the manifest; `posting.
    isNotificationPermissionGranted(context)` is the one shared check,
    always true below API 33 where the permission doesn't exist) - a
    declined/missing permission is caught as a `SecurityException`
    around `NotificationManagerCompat.notify` and silently skipped, same
    "doesn't block the feature from working, just from being visible"
    shape `BankNotificationListenerService`'s own notification-listener
    access already has. Requested from three places, not just one -
    **fixed a real "the user has no idea this is silently broken" gap**:
    requesting only when the switch (now on `ui.TransactionCreationFragment`
    - see the `ui` section below for why it moved off the original
    `FireflySettingsFragment`) is actually toggled on missed the case
    where it was already on (enabled in a build before this permission
    check existed, or the permission later revoked by hand in system
    settings) - that user would never see the permission dialog again
    and would never know auto-create's own notifications had gone
    silent. Now requested (a) from `TransactionCreationFragment` when
    the switch is toggled on, (b) from that same screen's
    `onViewCreated` if it's already on when the screen opens, and (c)
    from `MainActivity.onCreate`, every app start,
    for a user who never opens Firefly III settings again at all.
    All three outcome notifications (and `ReviewNotifications.notifyMatch`
    below) are now also gated on `prefs.NotificationPrefs.
    isNotificationsEnabled()` (default **on**, checked once inside
    `AutoCreateNotifications.show` and `ReviewNotifications.notifyMatch`
    rather than at each call site) - the user's own request, a dedicated
    `ui.NotificationSettingsFragment` settings screen (new row on the
    Settings list, right after Firefly III settings) for turning this
    app's own notifications off entirely without digging into system
    channel settings. The same screen's second toggle,
    `isShowFinancialInfoEnabled()` (default **off** - the user's own
    explicit choice, for Google Play's financial-app content guidelines),
    swaps `auto_create_success_text`/`auto_create_skipped_text`/
    `review_match_text`'s amount/currency/description for a generic,
    app-label-only line when off; `notifyFailed` never carried financial
    info to begin with, so it's untouched by this toggle. A third button
    on that screen opens `Settings.ACTION_APP_NOTIFICATION_SETTINGS`
    (+`Settings.EXTRA_APP_PACKAGE`) directly, for the channel-level mute/
    importance/sound controls this screen deliberately doesn't duplicate.
    **Needs real-device verification** (not yet done, see TODO.md):
    nothing here can be exercised by a JVM unit test (WorkManager,
    system notifications, and the notification-listener path itself all
    need a real device/emulator) - in particular, confirming two
    notifications posted back-to-back are still handled strictly one at
    a time, which is the entire reason this was built as a WorkManager
    chain instead of a plain per-message coroutine launch.
  - `RuleMatching.kt` - `capturedMessageInputData`/`capturedMessageFrom`/
    `matchRule`/`resolveAmountQuietly`, top-level functions extracted
    from `AutoCreateWorker` so `ReviewNotificationWorker` (below) could
    call the exact same rule-match + amount-resolution logic rather than
    duplicating it a second way. Pure extraction, no behavior change -
    `AutoCreateWorker` now calls these instead of its own private
    copies.
  - `ReviewNotificationScheduler`/`ReviewNotificationWorker`/
    `ReviewNotifications` - the counterpart to `AutoCreateScheduler`/
    `AutoCreateWorker`/`AutoCreateNotifications` for when auto-create is
    *off*. Raised by the user: a rule match used to surface nowhere
    outside the app itself when auto-create was disabled - the user had
    to open Messages and tap in to notice it. `ReviewNotificationScheduler.
    scheduleIfNeeded(context, message)` is called from the exact same two
    places as `AutoCreateScheduler.scheduleIfEnabled`, right alongside
    it - exactly one of the two ever does anything for a given message,
    since they gate on opposite states of `isAutoCreateEnabled()`.
    `ReviewNotificationWorker` re-parses the message via `RuleMatching.
    matchRule`/`resolveAmountQuietly` (same as `AutoCreateWorker`), and
    on a match with a resolved amount, posts a system notification
    (`ReviewNotifications`, its own `review_match` channel, separate from
    `auto_create`'s so the two are independently mutable/configurable)
    with an app/amount/currency/description summary and a "Create
    transaction" action (and whole-notification tap). Deliberately does
    **not** join `AutoCreateScheduler`'s unique `APPEND` work chain:
    there's no shared mutable state here (no Firefly `POST`, no
    dedup-log write) for two in-flight instances to race over.
    **One notification per real payment (v0.205, the user's own
    request)**: when the bank app and its wallet both notify about the
    same payment, only the first review notification is posted. Each
    posted notification carries its dedup hashes (with and without
    description) and the draft's date in its `extras`; `notifyMatch`
    (`@Synchronized`, so two workers can't both pass the check) reads
    the review notifications still showing (`NotificationManager.
    activeNotifications`, so a swiped/cancelled one drops out by itself
    - no separate map to maintain, nothing lost when the process dies)
    and posts nothing if one has the same `transactionDedupHash` (the
    variant picked by `isDedupIncludeDescription()`) within
    `getDedupWindowMinutes()` - the window keeps a genuinely separate,
    identical payment hours later from being swallowed. Pure logic in
    `posting/ShadeDedup.kt` (`ShadeDedupTest`). Only the shade is
    deduplicated: the Messages list still records every message, and
    `AutoCreateNotifications` (auto-create on) is unaffected.
    **Fixed two real bugs, the user's own catch/report on a real
    duplicate round-trip**:
    - The action used to deep-link straight into
      `ui.CreateTransactionActivity.intent(context, message)` with no
      duplicate check at all - deliberate by the original design (left
      entirely to the screen's own on-submit `checkForDuplicate`, same
      reasoning `AutoCreateNotifications`'s own entry above never needed
      since that path already dedups before posting). In practice that
      meant a message that actually *was* a duplicate of something
      already created walked the user into a blank create screen that
      only found out when Firefly's own server-side `error_if_duplicate_
      hash` check rejected the `POST` outright - a bare rejection error,
      not the richer "Use Created"/"Open"/"Create anyway"/"Cancel"
      choice a list tap on the same message would have shown. The action
      now opens `ui.MainActivity.checkMessageIntent(context, message)`
      instead - `MainActivity.onNewIntent`/`MessagesFragment.
      consumePendingMessageCheck` (called from the fragment's own
      `onViewCreated`, covering the common case where standard launch
      mode recreates the activity rather than reusing it) reconstruct
      the message from the intent's extras, look it up in `MessageStore`
      by the same identity fields `setTransactionId` already matches on
      (falling back to the reconstructed message itself if it's no
      longer in the store - process killed and restarted with retention
      off, say), and run it through `showTestMatch` - the *exact* same
      preemptive-check code path a tap on the message's own row in the
      list already runs. A likely duplicate now shows the same
      duplicate-confirm dialog a list tap gets; nothing found still ends
      up at `CreateTransactionActivity`, same as before. `AlertDialog`
      needs a foreground `Activity` to show in, and `MessagesFragment`
      already owns every piece of UI this check can show, which is why
      this redirects through `MainActivity` rather than running the
      check from inside the notification action's own `PendingIntent`
      receiver.
    - `setAutoCancel(true)` only ever dismisses a notification on a tap
      of its *content* area, not an *action* button's own tap (what
      "Create transaction" is) - so the notification sat in the shade
      even after a transaction was successfully created from it.
      `setAutoCancel` is gone now; `ReviewNotifications.cancel(context,
      message)` is called explicitly instead, from every place that
      actually links a transaction to a message (`CreateTransactionFragment.
      onSaveSuccess` for a create or edit, `MessagesFragment`'s "Use
      Created"/"Use anyway" button handlers for a dedup hit, and
      `MessagesFragment.deleteMessage` - deleting a message from the list,
      single or multi-select, also drops its notification, v0.209) - a no-op
      when there's nothing to cancel (a plain in-app tap with no review
      notification behind it, or one already dismissed). Both `notifyMatch`
      and `cancel` compute the notification id via a private
      `notificationId(message)` that always zeroes `CapturedMessage.
      fireflyTransactionId` before hashing, rather than relying on every
      caller to remember to pass a pre-link message - `cancel` is always
      called *after* the link is set, and `CapturedMessage`'s default
      `hashCode()` (used as the notification id) would otherwise change
      the moment that field was set, making `cancel` silently miss the
      very notification it's meant to dismiss.
    - **Fixed a third real bug, the user's own catch on a real two-
      notification round-trip**: `createTransactionIntent`'s
      `PendingIntent.getActivity(context, requestCode, ...)` used a
      hardcoded `requestCode = 0` for every message. `PendingIntent`
      identity/caching is `(component, requestCode, action/data/
      categories)` - **extras are not part of that comparison at all** -
      and `MainActivity.checkMessageIntent`'s target has no
      distinguishing action/data, just extras, so a *second* review
      notification's action silently got handed back the *first* one's
      already-cached `PendingIntent`, extras included, instead of a new
      one carrying its own message's identity. On-device symptom:
      tapping the second notification's "Create transaction" opened
      straight into editing the *first* message's already-linked
      transaction and saved there, while the second notification's own
      entry was never touched at all (stayed in the shade, never
      linked). Fixed by passing `notificationId(message)` - already a
      stable, unique-per-message int, used for the notification id
      itself - as the request code too, so every message's action gets
      its own distinct `PendingIntent`.
- `notifications` - `BankNotificationListenerService`
  (`NotificationListenerService` subclass): for every posted
  notification, checks `MonitoredAppsPrefs`, pulls `EXTRA_TITLE`/
  `EXTRA_TEXT` from the notification extras, and if either is
  non-empty, resolves the app's current label via `AppLabelResolver`
  and calls `MessageStore.shared.add(...)` - a non-null (genuinely new,
  not a notification-level duplicate) result is handed straight to
  `posting.AutoCreateScheduler.scheduleIfEnabled` and `posting.
  ReviewNotificationScheduler.scheduleIfNeeded` right after.
  **Fixed a real duplicate-capture bug, caught directly on-device**:
  `onNotificationPosted` isn't only called for a genuinely new
  notification - the system also replays every notification still
  sitting unswiped in the shade whenever this listener (re)connects,
  which happens on its own whenever Android kills and restarts the
  service (no foreground service, see TODO.md). `add(...)` is now
  timestamped with `sbn.postTime` (the system's own record of when the
  notification was actually posted, unchanged across a replay) instead
  of `System.currentTimeMillis()` ("now", different every time) - an
  hour-old unswiped notification replayed on reconnect used to look
  brand new and get captured (and auto-created) a second time, well
  outside any dedup window; same `postTime` on both captures now
  collapses it correctly regardless of how much wall-clock time passed
  in between. This also makes `TransactionDraft.date`'s own default
  (`RuleEngine.buildDraft`, from `message.timestampMillis`) and
  `CreatedTransactionsDb`'s dedup-window check more correct as a side
  effect - both now reflect when the payment notification actually
  happened, not when this app's listener happened to (re)process it.
  Requires the user to grant notification-listener access manually in
  system settings - there is no runtime permission dialog for this,
  only `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
- `ui`:
  - `MainActivity` - home screen, hosts `MessagesFragment` plus a
    toolbar with a settings action - *unless* notification-listener
    access isn't granted yet (`ui.isNotificationListenerAccessGranted`),
    in which case `NotificationAccessConsentFragment` (see its own
    entry under `AppsFragment` below) takes that spot instead, on
    *every* launch this remains true, not just a true first run (no
    separate "has seen onboarding" flag - the permission's own granted
    state already is that signal). The user's own call, after a first
    version only surfaced this disclosure behind a banner in Settings ->
    Monitored apps: the app does nothing useful without this
    permission, so it belongs front and center on a fresh install, not
    something to stumble onto. `MainActivity.showMessages()` is how
    that fragment swaps itself out once dismissed/granted, when it's
    sitting here as the activity's root fragment rather than pushed on
    `SettingsActivity`'s own back stack (see
    `NotificationAccessConsentFragment`'s own KDoc for how it tells the
    two apart). `MessagesFragment` subscribes to
    `MessageStore.shared` while visible (`onStart`/`onStop`) and
    renders the current snapshot, newest first.
    `MessagesAdapter` shows "#<id>" (`messageTransactionId`, bold,
    `colorPrimary`) next to the received time once a row's
    `fireflyTransactionId` is set, `View.GONE` otherwise - the one
    place in the UI that surfaces the link without having to reopen the
    test-match dialog. Each row also has an overflow (⋮) `PopupMenu`
    now, same shape as `RulesListFragment`'s (see its own notes below
    for why that's the reference pattern) - "Create transaction"
    (`ID_CREATE_TRANSACTION`, the user's own request: a direct shortcut
    into the exact same flow a plain tap on the row already runs
    [`showTestMatch`] - disabled whenever `fireflyTransactionId` is
    already set, since a tap there opens the *editor* instead, so this
    item's own "Create transaction" label would be misleading), "Open
    in Firefly III", "Check if created", "Forget linked transaction",
    "Copy to clipboard" (below), and "Delete notification" (the user's
    own rename - `action_delete`'s plain
    "Delete" risked reading as deleting the *Firefly transaction*
    rather than this app's own captured-notification entry; a dedicated
    `messages_action_delete` string replaces the shared `action_delete`
    for every message-delete surface - this menu item, both single/multi
    delete-confirm dialogs, and `menu_selection.xml`'s own title -
    without touching `action_delete` itself, still shared by
    `CurrenciesFragment`/`RuleEditFragment`/`RulesListFragment` where
    that ambiguity doesn't exist). "Open in Firefly III"
    (`verifyThenOpenTransaction` - see its own entry above for why it's
    no longer a direct `openTransaction` shortcut) and "Forget linked
    transaction" are both disabled (`MenuItem.isEnabled = false`), not
    hidden, when the row has no `fireflyTransactionId` yet -
    visible-but-disabled makes clear the action exists and why it can't
    be used right now, same reasoning as disabled form controls
    elsewhere. "Forget linked transaction" - the user's own request,
    after "Check if created" could confirm a cached id was gone with no
    way to actually act on that (its own not-found dialog has nothing to
    clear *to*): `forgetLinkedTransactionIfGone` runs the same shared
    `posting.verifyLinkedTransaction` first - confirmed gone -> clears
    the link (`MessageStore.setTransactionId` to `null`) immediately and
    says so in a Toast, no confirmation needed since there's nothing
    left to protect; a check failure shows the real reason and touches
    nothing, same "don't guess" rule every dedup check in this file
    already follows. **Fixed a real "stuck forever" gap, the user's own
    catch**: a still-*valid* link used to just get left alone
    unconditionally, with no way out - but a live link can still be the
    *wrong* one (a false-positive dedup match linking this message to
    someone else's real, still-existing transaction), and once linked, a
    tap only ever opens the editor for that transaction, never the
    create screen - so there was no way back to create the actually-
    correct one short of fixing it by hand in Firefly first. A still-
    valid link now shows an explicit confirm dialog
    (`messages_forget_transaction_confirm_*`, "Forget anyway"/Cancel,
    Cancel rightmost per house convention) instead of silently refusing
    - unlinking here never touches Firefly itself, only this app's own
    record of which transaction a message points to, so allowing it
    outright once the user's explicitly confirmed is safe. "Delete" (now
    "Delete notification") still asks for confirmation (same
    strings/dialog shape as the multi-select delete below, just one
    message instead of a selected set).
    **Fixed two more real gaps, the same terminology catch**: the
    confirm dialog used to say "Delete message(s)?" regardless of
    single/multi, a different term from the menu item/button that
    triggers it ("Delete notification") - renamed to
    `delete_notification_confirm_title`/`delete_notifications_confirm_title`
    ("Delete notification?"/"Delete %d notification(s)?", single vs.
    multi) for consistent wording throughout. `confirmDeleteSingle` also
    now shows *which* notification is about to go, right under the
    title (app label bold, then title/text - the same fields the row
    itself already shows, not a new format to learn) - previously a
    bare "are you sure" with no way to confirm which one without backing
    out and checking the row first. `confirmDeleteSelected`'s title now
    carries the actual count instead of a generic "(s)".
    Hidden (`MessagesAdapter.setSelectionMode`) while a multi-select is
    in progress, since the two interaction modes would otherwise compete
    for the same tap.
    - "Check if created" (`MessagesFragment.checkIfCreated`) - an
      explicit, user-initiated search, distinct from the preemptive
      check `showTestMatch`/`startCreate` already run before posting.
      **Fixed a real "ignores what it already knows" bug, the user's own
      catch/report**: when `message` already has a linked id, that's a
      direct answer on its own - the old code searched by content
      anyway, which can genuinely disagree with the known-good id (the
      user's own example: editing a transaction's description/notes by
      hand in Firefly can make it stop matching this app's own
      hash/query), so it would find and offer a *different* transaction
      instead, ignoring the one it already knew was right. Now checks
      the linked id first via `posting.verifyLinkedTransaction` (see the
      `posting` section above): still there -> shown directly, no search
      at all; confirmed gone -> falls through to the search below,
      carrying the gone id so a replacement match says so explicitly
      (same `staleLinkedId` warning `verifyLinkedThenShow` uses); a
      check failure stops and shows the error immediately. Only once
      there's no linked id (or it's confirmed gone) does this re-parse
      the message fresh (`RuleEngine.match`, same as `showTestMatch`)
      and call `findDuplicate` directly - the same local-then-remote
      logic `checkForDuplicate` uses, but *not* gated by `FireflyPrefs.
      isDedupEnabled()`, since that toggle is about whether *creating*
      pauses for a dedup check, a different question from whether this
      separate "just tell me" action should run at all. Not found:
      `showSearchNotFoundDialog` shows the exact
      query string that was searched (`network.dedupSearchQuery` -
      useful for debugging on its own, prompted by a real session where
      the search's actual behavior needed to be inspected by hand) and
      offers to open the same search, pre-filled, in Firefly's own web
      UI (`network.searchUrl`, confirmed against `routes/web.php`'s
      `search.index`/`SearchController::index` reading a `search` GET
      parameter, not assumed). Each listed raw candidate's field dump
      (below) is labeled (`id=`/`date=`/`amount=`/`desc=`/`src=`/`dst=`)
      rather than bare positional values, the user's own fix after
      finding some fields unlabeled; the amount is also rounded to 2
      decimal places for display (`setScale`), not the raw scale
      Firefly's JSON happened to carry. **User's own call**: when
      exactly one raw candidate came back, a "Use anyway" button (between
      "Open search in Firefly" and Cancel, which stays last per the
      house convention) links the message to it directly, same as "Use
      Created" does elsewhere - one candidate makes the client-side
      rejection more likely a field-comparison quirk than a genuinely
      different transaction, so skip the round trip through Firefly's
      own web search. **Fixed a real native-AlertDialog quirk, caught
      directly from a screenshot**: adding "Use anyway" made this a
      3-button dialog, and native `setNegativeButton`/`setNeutralButton`/
      `setPositiveButton` slots stack vertically (each right-aligned)
      once the combined label text doesn't fit one row - the exact same
      problem `dialog_duplicate_confirm_3.xml` was built to avoid.
      `dialog_search_not_found.xml` replaces the native buttons with the
      same one-row `LinearLayout`+`layout_weight` pattern;
      `useAnywayButton` is `View.GONE` (not just text-less) when there's
      no single candidate, so the remaining two buttons fill the row via
      weight the same way any 2-button case does. Found: the same
      `showDuplicateConfirmDialog` the create flow uses, with
      `showCreateAnyway = false` - inflates `dialog_duplicate_confirm_3.xml`
      (three actions in one row) instead of `dialog_duplicate_confirm_4.xml`
      (the 2x2 grid, used when `showCreateAnyway` is true - see its own
      entry below) rather than toggling a fourth button's visibility, and
      swaps in check-specific title/message strings, since "post one
      anyway" isn't a meaningful option for a pure lookup.
      **Fixed a real "the app is lying to me" bug**: `DuplicateCheckResult`
      used to collapse "genuinely zero matches" and "the remote search's
      HTTP call itself failed" into the same `None` case (deliberate for
      the create-flow callers, where a failed best-effort check should
      just fall through to posting normally either way) - but
      `checkIfCreated` reused that same collapsed result, so an API
      failure silently showed "Not found in Firefly" too, indistinguishable
      from a real miss. Caught directly by the user: pasting the exact
      query this app had just searched with into Firefly's own web
      search box found the transaction immediately, proving the search
      itself wasn't the problem. A new `SearchFailed(message)` case
      carries the real HTTP failure reason - `checkIfCreated` shows that
      verbatim (`check_search_failed_title`) instead of presenting it as
      a non-match; the create-flow callers originally treated `SearchFailed`
      exactly like `None` (same best-effort fallback) - since changed,
      see `DuplicateChecker.kt`'s entry above for why.
      Follow-up test showed it wasn't actually `SearchFailed` though -
      the search succeeded and returned exactly one raw candidate, which
      `remoteCandidateMatches` then rejected. So `DuplicateCheckResult.
      None` carries the actual `rawCandidates`
      (`List<RemoteTransactionCandidate>`, not just a count) returned
      before filtering, and the not-found dialog lists each one's real
      field values (date, amount, currency, description, source/
      destination id-or-name) right next to the query that was searched
      with - seeing the two side by side is what actually shows *why* a
      match failed (case, whitespace, a stale date, ...) instead of
      needing another debug round-trip. Root cause of *this* particular
      rejection turned out to be the kind-aware `transactionDedupHash`
      bug documented above (the user's own `WITHDRAWAL` rule, a real
      destination name sitting on Firefly's side that this hash wasn't
      supposed to care about at all) - fixed there, not here.
      Also simplified around the same time, per the user's own
      observation: `dedupSearchQuery` used to always emit both
      `date_after`/`date_before`, even when they were the identical
      date (the common case, default 5-minute window never crosses
      midnight) - now emits a single `date_on` instead in that case,
      falling back to the `date_after`/`date_before` pair only when the
      window actually spans two different days.
      **Manual-only retry without description, the user's own call**:
      if `FireflyPrefs.isDedupIncludeDescription()` is on and the first
      `findDuplicate` call comes back `None`, `checkIfCreated` retries
      once more with `includeDescription = false` before giving up. A
      `Found`/`FoundMultiple` on the retry is shown with
      `descriptionMismatch = true`, which prepends an explicit warning
      (`check_found_description_mismatch_warning`) to the duplicate-
      confirm dialog - deliberately *not* silently treated as "good
      enough", since a weaker match still needs a human looking at it
      to judge whether it's really the same payment. The create-flow
      callers (`checkForDuplicate`, feeding `startCreate`/
      `showTestMatch`) deliberately do *not* retry this way - posting on
      top of a transaction that only matched once description was
      dropped, with no human in the loop to catch a wrong guess, would
      risk linking the wrong transaction; a manual lookup always has a
      human looking at the result either way, so showing the weaker
      match (with a clear warning) is the right trade-off only there.
      **Unknown accounts in the manual lookup (v0.204, the user's own
      report)**: a message whose rule leaves both account sides blank
      used to produce no query at all (`dedupSearchQuery` -> `null`), so
      Firefly was never asked, "not found" was reported and the dialog's
      "open search" button opened an empty search page. Now
      `checkIfCreated` (a) gets the blank side filled from the global
      "Default source account" - since v0.206 by `RuleEngine` itself when
      the draft is built (see the "Default Source account" paragraph
      under `CreateTransactionFragment`), (b) if that is blank
      too, searches unscoped (`dedupSearchQuery(allowUnscoped = true)`) and
      treats an unknown account side as a wildcard
      (`remoteCandidateMatches(ignoreUnknownAccounts = true)`), and (c)
      never offers an empty search - with no query the dialog says so and
      the open-search button is hidden. The create-flow callers keep the
      old "no account -> skip the remote check" behavior.
      **Always show found-transaction info, the user's own request** -
      "как ты сделал для ошибки, когда найденная не совпадает по
      счету": the not-found dialog's per-candidate field dump (above)
      already showed real field values for a *rejected* candidate;
      `showDuplicateConfirmDialog` (used by all three callers, not just
      `checkIfCreated`) now always appends the same level of detail
      (date/amount(+foreign)/description/source/destination,
      `buildFoundTransactionInfo`, labeled with `showTestMatch`'s own
      `test_match_label_*` strings rather than a new, denser format) for
      the transaction it's actually offering as *the* match - a bare
      `#<id>` on its own gave the user no way to independently judge the
      match without leaving the app. **Fixed a real inconsistency, the
      user's own catch**: `buildFoundTransactionInfo` used to be plain
      text, reading differently from `buildMatchedBody`'s bold-label
      style elsewhere in the same fragment - now built via the same
      shared `boldField` helper both use.
      **Fixed a real "offers a pointless choice" bug, the user's own
      catch**: "Use Created" used to always show, even when `foundId`
      already *is* the message's own linked id - a pure no-op in that
      case (the common one now that `checkIfCreated` checks the linked
      id first, see its own entry above, and shows it here directly once
      confirmed still valid). The button is hidden (`View.GONE`, the
      row/grid's other buttons reflow to fill the space - both
      `dialog_duplicate_confirm_3.xml`'s single row and `_4.xml`'s 2x2
      already use `layout_weight`, so this needed no layout change)
      whenever `foundId == message.fireflyTransactionId`.
      `DuplicateCheckResult.Found`/`FoundMultiple` now carry an optional
      `candidate: RemoteTransactionCandidate?` - already populated for
      free for a remote-search hit, `null` for a local-log hit (which
      only ever has a bare id). `showDuplicateConfirmDialog` is now
      `suspend`: when `knownCandidate` is null it calls
      `fetchCandidateInfo`, which goes through `posting.
      verifyLinkedTransaction` (see the `posting` section above) to
      fetch the missing fields - a locally-recorded
      duplicate gets the same display as a remote one, just one extra
      API round-trip first. Best-effort: a failed fetch (or a genuinely
      missing transaction) just shows the dialog without the info block,
      same as before this existed, rather than failing to show anything.
      Long-press starts
    multi-select (tap toggles more items in or out); its delete action
    calls `MessageStore.shared.remove` for each selected message after
    a confirmation dialog. Multi-select is a hand-rolled toggle of
    `MainActivity`'s own single `Toolbar` - swapping its title to
    "N selected", enabling the up/back arrow (`onSupportNavigateUp`
    cancels selection), and swapping its menu to a copy + delete action
    (`menu_selection.xml` vs. the normal `menu_main.xml`, chosen in
    `onCreateOptionsMenu` based on whether a selection-delete callback
    is currently set) via `MainActivity.enterSelectionMode`/
    `updateSelectionCount`/`exitSelectionMode`, which `MessagesFragment`
    drives. System back (button or gesture, not just the toolbar's own
    up arrow) also cancels the selection instead of exiting the app -
    an `OnBackPressedCallback` registered once in `onCreate`, enabled
    only between `enterSelectionMode`/`exitSelectionMode` so normal
    back behavior applies the rest of the time.
    **"Copy to clipboard"** - the user's own request: the per-row
    overflow menu (`ID_COPY_SINGLE`, between "Forget linked
    transaction" and "Delete notification") copies one message, the
    selection toolbar's `ic_copy` action (`action_copy_selected`,
    `MainActivity.onSelectionCopy`) copies every selected one -
    `MessagesFragment.copySelectedToClipboard` reads
    `MessagesAdapter.selectedMessagesInOrder()` (on-screen order, not
    the backing `Set`'s undefined iteration order - this is the one
    place selection order is actually user-visible, unlike a delete's
    count or per-item removal, neither of which cares). Both funnel
    into `copyMessagesToClipboard(messages: List<CapturedMessage>)`:
    `"${title}\n${text}"` per message, joined with a blank line
    (`"\n\n"`) between entries when there's more than one - the user's
    own exact format, no app-added header/separator/timestamp.
    `android.content.ClipboardManager`/`ClipData.newPlainText`, not
    `Intent.ACTION_SEND` - a plain clipboard copy was the explicit ask,
    not a share sheet. **Fixed a real "icons don't match" catch, the
    user's own screenshot**: `action_delete_selected` used the stock
    framework `@android:drawable/ic_menu_delete` - a visibly different,
    older style sitting right next to `ic_copy`'s own clean Material
    glyph in the same toolbar. New `ic_delete.xml` (same template as
    `ic_copy.xml` - 24dp, `?attr/colorOnSurface` tint, the standard
    Material "delete" glyph) replaces it; `menu_rules_selection.xml`'s
    own Delete has no drawable to match at all (text-only, `🗑️`
    emoji prefix, buried in the overflow rather than shown directly in
    that toolbar - a deliberate, separate, earlier decision, "keep the
    one irreversible action furthest from the rest" - not touched by
    this). The toolbar action ends the selection afterward
    (`clearSelectionAndExit`) - the user's own follow-up call: copy is
    a one-shot "got what I needed" action here, unlike
    `RulesListFragment`'s own enable/disable/duplicate, which stay in
    selection mode since those are more likely to be followed by
    another bulk action on the same selection. This replaced a standard `androidx.appcompat.view.ActionMode`
    (`startSupportActionMode`) after a real, visible bug: with a
    manually-set `Toolbar` on a `NoActionBar` theme, AppCompat's
    `ActionMode` doesn't replace that toolbar - it renders its own
    contextual bar as an *extra* bar stacked above it, and the combined
    height of both bars pushed the list down enough to cover the first
    row. Reusing the one real `Toolbar` for both states has no such
    ambiguity - it's the same view in the same layout slot throughout,
    just different content. A plain tap *outside* of that selection mode
    runs `RuleEngine.match` against the tapped message - a *fresh* match
    (nothing linked yet, Firefly configured) runs the same preemptive
    `checkForDuplicate` every other create path does *before* deciding
    what to show: found -> the duplicate-confirm dialog; not found (or
    the amount couldn't be resolved to check with, or the remote check
    failed) -> `CreateTransactionActivity` opens directly (see its own
    entry below). **Fixed a real "the screen doesn't even try" gap,
    caught directly on-device right after `CreateTransactionActivity`
    shipped**: the first version of this just opened the review screen
    unconditionally for a fresh match, skipping the check entirely - a
    transaction that genuinely already existed (confirmed separately by
    "Check if created" finding it) went unnoticed until the user tapped
    "Create transaction" on the review screen itself. An already-linked
    *live* message (`verifyLinkedThenShow`, below) and a no-match/
    not-configured tap are the only cases left that still show a dialog
    here at all - everything else (a fresh match, or a stale link just
    confirmed gone) ends up at `CreateTransactionActivity` one way or
    another instead. The no-match/not-configured dialog shows the
    result (source/destination account, amounts/
    currencies, rendered description/notes, issues) in a dialog - "test
    match". The dialog's
    *title* is the outcome itself: the matched rule's own label, or
    "No match" - not a generic "Test match" every time regardless of
    what happened, which made the matched-vs-not distinction easy to
    miss at a glance. The body is a `SpannableStringBuilder` with each
    field's label (`Source:`, `Amount:`, ...) in bold - plain
    `getString(R.string.x, value)` concatenation read as one
    same-weight wall of text; this needed splitting the old combined
    "Label: %1$s" string resources into label-only strings so code can
    bold just the label span. `Date:` (`formatDraftDate`, system
    date+time format, same as `MessagesAdapter`) shows for every match,
    not only when `ParseRule.dateFormat` overrides it - there was no
    date field in this dialog at all before that feature existed, so a
    wrong override would've been invisible until a transaction actually
    posted with it. When the draft has `LOCAL_AMOUNT_MISSING`
    (foreign-currency message, no home-currency amount),
    `resolveApproxConversion` (suspend, not fire-and-forget - see below
    for why) fetches a real rate from `network.ExchangeRateClient` for
    the message's own date and swaps the already-shown dialog's message
    in place (`AlertDialog.setMessage`, works after `.show()`) once it
    resolves - a computed amount + the approx tag on success
    (`rules.applyApproxConversion`), or the original body plus an
    explicit "lookup failed: <reason>" line on failure.

    This dialog only ever shows for a no-match or an unconfigured
    connection now - plain `OK`, no secondary buttons at all
    (`showMatchedDialog`). An already-linked message, and a fresh match
    with Firefly configured, both skip it entirely:
    - Already linked (`message.fireflyTransactionId != null` and
      Firefly configured): **fixed a real "pretends everything is fine"
      bug, the user's own catch/report (screenshotted)** - this path
      used to trust `message.fireflyTransactionId` blindly, with zero
      verification, so a transaction deleted by hand in Firefly left
      "Open Transaction" pointing at a dead link with nothing on screen
      explaining why. `showTestMatch` routes this case through
      `verifyLinkedThenShow` first, which checks via the one shared
      `posting.verifyLinkedTransaction` (`LinkedTransactionCheck` -
      `Found(candidate)`/`Gone`/`CheckFailed`, see the `posting` section
      below): still there -> **the user's own later request** - opens
      `CreateTransactionActivity` straight into *edit* mode
      (`editIntent`, see that class's own entry below) on the live
      `candidate`'s real current fields, no dialog at all; confirmed
      gone -> control falls through to `checkThenOpenCreateOrShowFound`
      (the same fresh-match search-then-show/create flow below), now
      carrying a `staleLinkedId` parameter so a replacement match (if
      search finds one) shows `check_linked_transaction_gone_warning`
      ("⚠ Linked transaction #X no longer exists in Firefly") instead of
      presenting the new match as if nothing had ever been linked;
      nothing found falls through to `CreateTransactionActivity` in
      *create* mode, same as any other fresh match. **Fixed a real
      "changes state before the user agreed to anything" bug, the
      user's own catch, same day**: the stale link used to be cleared
      (`MessageStore.setTransactionId(message, null)`) the instant the
      `404` was confirmed, before anything was even shown - a plain
      Cancel on whatever came next would've silently unlinked the
      message with no action behind it. Nothing clears it proactively
      now; the link only ever changes as a side effect of the user's
      own choice - "Use Created" overwrites it with the newly found id,
      a successful create or edit overwrites it with its own
      transaction's id, and a Cancel leaves the stale id exactly as it
      was, re-verified the same way next time rather than silently
      forgotten. A verification failure (network/server error, *not*
      confirmed gone) shows the error and stops - same "don't guess"
      rule every dedup check in this file follows.
      `MessagesFragment.checkIfCreated` runs the equivalent
      linked-id-first check before its own content search, same
      reasoning: it used to always run a full content search regardless
      of whether `message` already had a linked id, so an edited
      transaction whose content no longer hashed/queried the same could
      make it report a *different* id as the match, ignoring the
      known-good one entirely. It now checks the linked id first via
      the same `verifyLinkedTransaction`: still there -> shown directly
      (`showDuplicateConfirmDialog` with the live candidate), the search
      below skipped entirely; confirmed gone -> falls through to the
      search, now carrying the gone id as `staleLinkedId` so a
      replacement match says so explicitly, same warning as above.
      "Open Transaction" (same `network.transactionUrl`/`ACTION_VIEW` as
      the success dialog's own button, factored into one
      `openTransaction(context, id)` helper every "open" button in this
      fragment calls) - **fixed a real "silent dead link" bug, the
      user's own catch, found on the first real end-to-end round-trip
      test (create -> auto-create posts it -> delete it by hand in
      Firefly)**: Firefly's own web UI doesn't show a 404 for a deleted
      transaction, it silently redirects to the home page, so an
      `ACTION_VIEW` launch alone gives the user no sign anything was
      wrong. Every "open" action (the duplicate-confirm dialog's, "Open
      in Firefly III" on the overflow menu) goes through
      `verifyThenOpenTransaction` first - the same shared
      `posting.verifyLinkedTransaction`, confirmed-gone -> an explicit
      `open_transaction_gone_*` dialog instead of the ambiguous
      redirect, same "don't guess" rule every dedup check in this file
      already follows - and only calls `openTransaction` itself once
      existence is confirmed.
      **Superseded, the user's own later request, kept for context**:
      this used to be a read-only info dialog with "Open
      Transaction"/"Create Again" buttons (`buildCandidateBody` built
      its body from the live candidate's fields, same bold-label style
      `buildMatchedBody` uses) instead of opening straight into the
      editor. "Create Again" itself went through its own history first -
      no confirmation at all originally (`startCreate` posted straight
      from the tap), then an explicit "Create again? This will post a
      second transaction" dialog (`confirmCreateAgain`), then dropped
      the dialog in favor of opening `CreateTransactionActivity` for
      review before posting (same rule as every other manual create
      path: bypassing review is only ever allowed for
      `posting.AutoCreateWorker`'s background path) with an
      `create_tx_already_linked_warning` banner at the top warning that
      creating would post a second transaction. All of that is gone now:
      once a tap opens the editor directly, "fix the wrong transaction"
      replaces "create a second one and leave the original behind" as
      the answer to an already-linked message being wrong - the user's
      own call, made explicitly when asked. `buildCandidateBody`,
      `startCreate`, `confirmCreateAgain`, and the already-linked
      warning banner/string are all deleted, not kept dead.
    - Nothing linked yet (and Firefly configured): this dialog shows up
      as its *found* variant (`showDuplicateConfirmDialog`, same as
      every other create path's duplicate hit) if the preemptive check
      above finds one, and doesn't show at all otherwise -
      `checkThenOpenCreateOrShowFound` redirects straight to
      `CreateTransactionActivity` instead (see above, and that class's
      own entry below). **History, superseded but kept for context**:
      this dialog used to offer a neutral "Создать" button here,
      starting disabled ("Checking…") until a preemptive dedup check
      resolved. **Fixed a real user complaint at the time**: the
      original design only ran the dedup check *after* the tap, so the
      exact same button could silently post straight through, silently
      reuse an existing transaction, or pop up a second "possible
      duplicate" dialog - no way to tell which in advance, a "blind
      click" by the user's own description, demonstrated with a real
      duplicate it created by mistake. That whole concern is moot now:
      the new screen is itself the "don't act blind" step - the user
      reviews/edits every field before ever tapping its own always-
      enabled "Create transaction" button, which then runs the same
      `checkForDuplicate` check synchronously on that tap (see
      `CreateTransactionFragment`'s own entry), not preemptively on
      open.

    `checkForDuplicate(context, draft)` (shared by both paths above, and
    by `posting.AutoCreateWorker` - see the `posting` package section
    above for why it now lives there rather than as a private member
    here) is the dedup logic itself, returning a `DuplicateCheckResult`
    (`None`/`Found(id)`/`FoundMultiple(mostRecentId, count)`) instead of
    directly branching to a dialog - local log first
    (`rules.CreatedTransactionsDb.findWithinWindow`, skipped entirely
    when `FireflyPrefs.isDedupEnabled()` is off) using
    `rules.transactionDedupHash` (source/destination id-or-name +
    canonical-scale amount + currency + description-if-the-toggle-says-so,
    see its own KDoc and TODO.md's dedup history) and the current
    `isDedupIncludeDescription()` flag, so the lookup compares against
    whichever of the two stored hash columns matches the setting right
    now (see `CreatedTransactionsDb`'s own KDoc for why both are always
    written). Only if *that* comes up empty does it also ask Firefly
    itself (`FireflyApiClient.searchTransactionsByQuery`, see the
    `network` section above) - the local log alone can't catch a
    duplicate a second device or app's own integration created, since
    it never touches this app's log at all. Scoped by whichever of
    `draft`'s source/destination *name* is known - deliberately not a
    Firefly account id (see `FireflyApiClient.searchTransactionsByQuery`'s
    KDoc for the real on-device gap this fixed: a rule whose account was
    typed as free text rather than picked from `showAccountPicker`, see
    the `RuleEditFragment` notes below, has no id at all, so an
    id-scoped search used to skip it entirely even though posting by
    name works fine). Filtered client-side via
    `rules.remoteCandidateMatches` (see the `rules` section above).
    Zero remote matches -> `None`; exactly one -> `Found`; more than one
    -> `FoundMultiple`, which both call sites turn into an *additional*
    Toast warning (`create_remote_duplicates_found`) on top of the same
    duplicate-confirm dialog a single match gets (using the most recent
    match as the dialog's "found" id) rather than replacing it - the
    user's own call, since more matches is a stronger signal, not a
    reason to short-circuit the usual flow. A failed remote call maps to
    `None`, same "flag it, don't guess, but don't block on a
    best-effort extra check" reasoning as everywhere else in this app -
    the local check already ran and found nothing.

    A likely duplicate (local or remote)
    shows the found transaction's id in the message itself
    (`create_duplicate_confirm_message`, `#%1$s`) and offers *four*
    actions, not the two an "are you sure?" confirm would have - a
    deliberate answer the user gave directly when asked, not assumed.
    Four is more than `AlertDialog.Builder`'s positive/negative/neutral
    slots can hold, so `showDuplicateConfirmDialog` builds it as a
    plain custom view (`dialog_duplicate_confirm_4.xml`, a 2x2 grid of
    borderless buttons - same "native combo doesn't do what's needed"
    reasoning as the rule editor's "Test results" dialog; a stack of
    four full-width rows, tried first, left too much dead vertical
    space for such short labels) instead of the native button bar:
    "Use Created" (links the *found*
    id - `findWithinWindow`'s return value, not a fresh post - to this
    message via `MessageStore.setTransactionId`, since the dedup check
    already did the work of locating it), "Open" (right after Use
    Created - `network.transactionUrl` on the found id, same helper the
    success dialog uses - deliberately does *not* dismiss the dialog,
    since opening Firefly to go check is "let me verify first", not a
    decision yet), "Create anyway", and "Cancel" - always last, by
    explicit request, regardless of which of the other three ends up
    next to it as this evolves.
    (Auto-create, see the `posting` package section above, does
    skip-and-notify on a likely duplicate instead of asking anything -
    there's no human in the loop there to ask.) **Fixed a real "create
    anyway bypasses review" gap, the user's own catch**: "Create anyway"
    used to call a private `postTransaction` straight from here, posting
    the draft exactly as matched/found with no review/edit step -
    `postTransaction` is gone now (along with the `FireflyTransactionRequest`/
    `CreatedTransactionsDb`/`transactionDedupHash` imports it alone
    needed), and "Create anyway" instead opens `CreateTransactionActivity`
    (see its own entry below) for the user to review before it actually
    posts, same as every other manual create path - the user's explicit
    rule is that bypassing this screen is only ever allowed for
    `posting.AutoCreateWorker`'s background path, never for a manual tap,
    even on an already-confirmed duplicate. `showDuplicateConfirmDialog`
    itself no longer takes a `draft` parameter at all as a result -
    nothing left in it needs one.
  - `CreateTransactionActivity`/`CreateTransactionFragment` - two
    modes, same fields/layout, decided purely by which factory
    (`intent`/`editIntent`) built the launching `Intent`:
    - **Create** (`intent`) - the pre-create review/edit screen
      (TODO.md's dedup history). Opened by `MessagesFragment.showTestMatch`/
      `checkThenOpenCreateOrShowFound` for a fresh match (nothing linked
      yet, Firefly configured), and by "Create anyway"
      (`showDuplicateConfirmDialog`) - every manual *create* path ends
      up here, per the user's explicit rule that only
      `posting.AutoCreateWorker`'s background path is allowed to post
      without review. `CreateTransactionFragment.loadForCreate`
      re-matches the message fresh (`RuleEngine.match`) and populates
      the form from the resulting `TransactionDraft`
      (`populateFields`). **`kindText` is plain, non-interactive text in
      both modes - a tap-to-pick design (an `AlertDialog.
      setSingleChoiceItems` over `RuleKind.entries`) shipped first,
      then was reverted after the user tried it on a real device and
      judged it unintuitive ("нифига пользователю не уперлось")**. In
      its place: both account fields are always pickers in create mode
      (`enableAccountPickersForCreate`, unlike edit mode's
      `updateAccountFieldModes`, which still only makes the one side
      implied by the already-fixed `kind` a picker), and whichever
      side(s) the user actually fills in with a real Firefly asset
      account - picked from the dropdown, or typed by hand and matched
      against the cached list - is what determines `kind`
      (`inferKindFromAccounts`: source-only -> WITHDRAWAL,
      destination-only -> DEPOSIT, both -> TRANSFER), not the other way
      around. Re-runs live, on every account-field change
      (`addTextChangedListener` plus the picker's own `onPicked`
      callback), updating `kindText`'s label whenever the result is
      unambiguous; an indeterminate result (neither side resolved yet)
      just leaves the last-known `kind` showing rather than guessing.
      `buildDraft` re-resolves it one final time at submit and refuses
      to save (`create_tx_no_asset_account_error` on both account
      fields) if it's still indeterminate then - the "ругаться" the
      user asked for, but only once, at submit, not on every keystroke.
      **Fixed a real "two unexplained red icons" bug, the user's own
      catch/screenshot**: every field error on this screen (this one
      included) used to be set via plain `EditText.setError` - its
      native red glyph visually clashed with the account fields' own
      custom end icon (the dropdown picker) trying to occupy the same
      spot, and its explanatory tooltip only ever shows once the field
      has focus, which a `createTransactionButton` tap elsewhere never
      gives it - so a failed validation landed as two unexplained red
      icons with nothing else on screen. Every `.error` assignment in
      `buildDraft` now targets the wrapping `TextInputLayout` instead
      (`amountLayout`/`descriptionLayout`/`sourceAccountLayout`/
      `destinationAccountLayout` - the first two needed a new
      `android:id` added, they'd never needed one before) - Material's
      own error treatment draws a red-outlined box plus the message as
      caption text directly below the field, always visible with no
      focus needed, and leaves an already-set custom end icon alone
      rather than fighting it for the same space. Cleared at the top
      of every `buildDraft` call so a since-fixed field doesn't keep
      showing a stale error from the previous attempt.
      `isAssetAccountName` treats a *picked* account as asset
      regardless of whether `assetAccounts` has loaded yet (the picker
      dialog itself is only ever built from `FireflyApiClient.
      listAssetAccounts`, so picking one is self-evidently true); typed
      text needs the list already cached, which `loadForCreate` now
      prefetches eagerly (`prefetchAssetAccountsForInference`, silent
      best-effort - the on-tap lazy fetch the picker already had is
      the fallback if this races or fails) instead of waiting for the
      picker's own first tap, purely so this inference also works for
      an account name a rule's own template already filled in, or one
      the user types without ever opening a picker. This is **also**
      what makes a single `WITHDRAWAL`-only default safe for
      `rules.BuiltInRules`'s generic fallback rules (see the "built-in
      fallback rules" history below) - the user corrects it just by
      putting the real account on whichever side it actually
      belongs, no separate kind-correction step needed at all.
      Edit mode's `kindText` is never touched by any of this - `kind`
      stays fixed to the real transaction's own type, the user's own
      explicit call (changing kind on a transaction Firefly already
      has isn't supported). `staleLinkedId`, when present
      (`CreateTransactionActivity.intent`'s own parameter, *not* folded
      into `message.fireflyTransactionId` - see below for why), shows
      `alreadyLinkedWarningText` (red/`?attr/colorError`, bold,
      `create_tx_stale_linked_warning` - "⚠ Previously linked
      transaction #X was not found in Firefly") at the top of the form -
      purely informational, doesn't block anything, since the screen's
      own on-submit duplicate check (`onCreateClicked` ->
      `checkForDuplicate` -> `confirmCreateAnyway` on a hit) already
      re-finds whatever's actually there now and asks the real "are you
      sure" right there. **Superseded, the user's own later call, kept
      for context**: this banner used to have a second variant,
      `create_tx_already_linked_warning` ("⚠ This message is already
      linked to transaction #X. Creating will post a second
      transaction"), shown whenever `message.fireflyTransactionId` was
      non-null - reachable back when "Create Again" opened this screen
      on an already-linked message. Once a tap on a *live* link opens
      straight into **edit** mode instead (below), that case can't reach
      *this* mode at all any more, so the warning (and the string
      itself) is gone - `message.fireflyTransactionId` is never even
      read here now, and `messageFrom(intent)` doesn't carry it across
      the Activity boundary at all.
      **Fixed a real "warns about a transaction we just proved doesn't
      exist" bug, the user's own catch, same end-to-end round-trip
      test**: `checkThenOpenCreateOrShowFound` used to pass the original
      `CapturedMessage` straight through to `CreateTransactionActivity.
      intent` on every path, including the one reached *after*
      confirming a previously-linked id is gone (`verifyLinkedThenShow`'s
      `Gone` branch, falling through here) - that message still carried
      the now-disproven `fireflyTransactionId`. `checkThenOpenCreateOrShowFound`
      now builds a `cleanMessage` (`fireflyTransactionId = null`) once
      and uses it for every path onward - safe unconditionally, since
      every call site reachable there already knows there's no
      *currently valid* link by construction (either never had one, or
      just disproved it).
    - **Edit** (`editIntent`) - the user's own request: a tap that finds
      a *currently valid* linked transaction
      (`MessagesFragment.verifyLinkedThenShow`'s `Found` branch) opens
      straight into editing that real transaction, instead of the old
      read-only "Open Transaction"/"Create Again" info dialog (see the
      `ui` section above for that history). "Create Again" itself is
      gone entirely - the user's own call: if the linked transaction is
      wrong, fix it here instead of creating a second one and leaving
      the original behind. `CreateTransactionFragment.loadForEdit`
      re-verifies `transactionId` itself on load, via the same shared
      `posting.verifyLinkedTransaction` - not redundant even though
      `verifyLinkedThenShow` just checked it: it closes the race where
      the transaction gets deleted in the gap between the list-row tap
      and this screen's own network round-trip. Still there ->
      populates the form from the live `RemoteTransactionCandidate`'s
      real current fields (`populateFieldsFromCandidate`, kept as its
      own function rather than unifying with `populateFields` behind a
      shared interface - the two source shapes differ enough that
      forcing one on both costs more than the duplication saves) and
      `kind` comes from `candidate.kind` (`RemoteTransactionCandidate`'s
      own field, see the `rules`/`posting` sections above) rather than a
      freshly re-parsed draft's. **Fixed a real "unreadable amount" bug,
      the user's own catch/screenshot**: `candidate.amount`/
      `foreignAmount` are parsed straight off Firefly's own JSON, which
      carries whatever scale its `DECIMAL(32,12)` column stores (e.g.
      the literal string `"61.160000000000"`) - dropped straight into
      the amount field via `toPlainString()`, that's what showed up,
      unreadable and not what a fresh draft's own already-clean amount
      ever looks like. New `formatAmountForDisplay` (strips trailing
      zeros, then pads back to a 2-decimal minimum if stripping went
      below that - cents, what most currencies use) replaces the bare
      `toPlainString()` call for both fields here and in `populateFields`
      (cheap insurance there too, even though a notification-parsed
      draft amount isn't known to hit this); confirmed gone -> toasts and finishes,
      nothing left to edit; a check failure shows the error and
      finishes too. Saving (`onSaveClicked` -> `save`) calls the new
      `FireflyApiClient.updateTransaction` (`PUT`, see the `network`
      section above) instead of `createTransaction` (`POST`), and
      **skips the duplicate check entirely** - unlike `onCreateClicked`,
      there's no "might this already exist" question when the whole
      point is editing a transaction already confirmed to exist.
      `onSaveSuccess` (shared with create's own `onSaveSuccess` path)
      still refreshes the `CreatedTransactionsDb` dedup hash for
      `transactionId` even on an edit - the edited fields are exactly as
      likely to need matching against later as a freshly-created
      transaction's, so the stored hash shouldn't keep reflecting only
      the transaction's *original* fields. Firefly's own
      `preventUpdateReconciled` validator (see the `network` section
      above) isn't pre-checked here either - same reasoning, the user's
      own choice: let the API reject it and show the real error, rather
      than guessing client-side which fields a reconciled transaction
      has locked.
    A single-fragment-host `Activity` (its own class, not reusing
    `SettingsActivity` despite the identical shape - that class is
    named/documented as the settings area specifically, and this screen
    has no multi-level back-stack navigation to need `openScreen` for,
    just one screen). `intent(context, message, staleLinkedId)`/
    `editIntent(context, message, transactionId)`/`.messageFrom(intent)`
    carry the message across the Activity boundary as its own plain
    fields (packageName/appLabel/title/text/timestampMillis - no
    `fireflyTransactionId`, see above for why), not a serialized
    `TransactionDraft` -
    `CreateTransactionFragment` re-parses it fresh via `RuleEngine.match`
    once it's running (create mode only - edit mode never needs to,
    it already has the real transaction), the same "reconstruct from
    primitives, don't try to serialize a `BigDecimal`/`Instant`-carrying
    data class across a process/Activity boundary" approach
    `posting.AutoCreateWorker` already uses for the identical reason.
    Every `TransactionDraft` field is editable - the user's own explicit
    scope call, not just category: amount, currency, foreign amount/
    currency, date/time, source/destination account, description,
    notes, category, tags, in both modes. `kind` itself is never a
    field you set directly in either mode - see the entry above
    (`inferKindFromAccounts`/the account pickers it's derived from, in
    create mode; fixed to the real transaction's own type in edit
    mode) for the full history, including the earlier tap-to-pick
    design this replaced.
    **Two separate date/time fields, not one combined button, the
    user's own design call**: `transactionDateInput`/
    `transactionTimeInput` (both non-focusable `TextInputEditText`s,
    `inputType="none"` - a picker trigger, not free text) replace the
    old single borderless `dateTimeButton` - visually inconsistent
    with every other field on this screen being a boxed
    `TextInputLayout`, the user's own catch.
    `showDatePicker`/`showTimePicker` each only ever update their own
    half of `selectedTimestampMillis` (picking a new date keeps the
    already-chosen time of day and vice versa) - `updateDateTimeFields`
    writes both fields' text after either one changes.
    `AddTestMessageFragment` was later converted to the same two-field
    date/time pair (`messageDateInput`/`messageTimeInput`).
    **Long date format once the field had room for it, the user's own
    follow-up**: `transactionDateInput` now shows `DateFormat.
    getLongDateFormat(context)` (locale-ordered/-translated, e.g.
    "October 6, 2026" in en, "6 октября 2026 г." in ru - same system
    API `MessagesAdapter`'s own date column already uses for its short
    form), plus a short weekday name, joined with ", " - both pieces
    locale-translated (the weekday via its own separate
    `SimpleDateFormat("EEE", Locale.getDefault())` call, since
    `getLongDateFormat` returns a fixed `java.text.DateFormat`
    instance with no weekday of its own to extend). The user chose
    this over three fixed-English alternatives offered side by side
    (a literal "Oct 6, 2026, Tue", weekday-first, and day-month-year).
    **Fixed a real "uneven row height" regression the long format
    itself caused, the user's own catch/screenshot**: the longer text
    wrapped to two lines at the old equal 1:1 width split, making the
    Date field visibly taller than Time's - `android:layout_weight`
    is now 3:1 (Date:Time, `transactionTimeInput`'s own field narrowed
    per the user's explicit ask, "22:05" never needed as much room as
    "October 6, 2026, Tue"), and both `TextInputEditText`s get
    `maxLines="1"`/`ellipsize="end"` - a true fixed single-line height
    regardless of content width, not just "usually fits" from the
    wider weighting alone (an edge case - a long locale month name
    plus large accessibility font scaling - would otherwise still
    wrap and reintroduce the same row-height mismatch).
    **"Default Source account", the user's own onboarding-focused
    request** (field on `ui.TransactionCreationFragment`, own
    `FireflyApiClient.listAssetAccounts()`-backed picker): since v0.206
    applied **when the draft is built** (`RuleEngine(defaultSourceAccount
    = FireflyPrefs.getDefaultSourceAccount())`, wired in
    `posting.RuleMatching.buildEngine`), not as a create-screen pre-fill
    any more - so the create form (via `populateFields`), the tap-time
    and manual duplicate checks, and the test-match dialog all see the
    same account (the old pre-fill left the tap-time check with no
    account to search on, so a message whose rule names no account went
    straight to the create screen even when the transaction already
    existed). Only when the rule left *both* sides blank, never when
    just one side is - a legitimate DEPOSIT rule that already filled in
    a real destination must not get a source added too, or
    `inferKindFromAccounts` would flip `kind` to TRANSFER. The default
    becomes the source of a withdrawal/transfer and the destination of
    a deposit. **Automatic creation is unchanged, the user's own
    explicit safety requirement**: the draft still carries
    `DraftIssue.ACCOUNT_SELECTION_REQUIRED` (computed from the *rule's
    own* accounts), so `AutoCreateWorker` still stops at a review
    notification instead of posting. (The default currency works the
    same way: `RuleEngine.defaultAccountCurrency`.)
    **Both currency fields (home and foreign) now pick from a list
    too, the user's own request**: `currencyCodeLayout`/
    `foreignCurrencyCodeLayout` get the same end-icon `AlertDialog`
    picker `TransactionCreationFragment`'s "Default account currency"
    field already has, sourced from `FireflyApiClient.listCurrencies()`
    filtered to `enabled` (not this app's own `CurrenciesDb` table) -
    same reasoning as that field's own KDoc. Both stay freely typeable
    too, the picker is a shortcut, not a lock, same as every other
    Firefly-sourced picker in this app.
    **Fixed a real "orphaned foreign currency" gap, the user's own
    catch**: `buildDraft` used to save whatever was typed in the
    foreign-currency field even after the foreign-amount field was
    cleared back to blank - a foreign currency code with no foreign
    amount to describe. `foreignCurrencyCode` is now forced to `null`
    whenever `foreignAmount` doesn't parse, regardless of what's still
    typed in that field.
    **"Rule: <label>" row, create mode only**: `ruleLabelText`, right
    below `alreadyLinkedWarningText`'s own slot at the top of the
    screen (hidden/`gone` in edit mode - there's no "which rule
    matched" concept once the fields come from Firefly's own live
    data instead) - the user's own request, so it's visible at a
    glance which rule fired before trusting (or correcting) what it
    filled in.
    Visual style and the asset-account picker itself
    are deliberately copied from `RuleEditFragment` (the user's own
    suggested starting point): an end-icon `TextInputLayout` opening an
    `AlertDialog.setSingleChoiceItems` picker, `FireflyApiClient.
    listAssetAccounts()` cached once per screen session - only the
    asset side of source/destination gets it (`updateAccountFieldModes`,
    same per-kind which-side-is-asset mapping `RuleEditFragment` has),
    the counterparty side stays plain free text since Firefly
    auto-creates it by name regardless. The category field gets the
    same picker shape fed by the new `FireflyApiClient.listCategories()`
    instead (see the `network` section above) - unlike the account
    picker, no id-staleness tracking needed on pick, since Firefly's
    create-transaction endpoint only ever takes a category *name*, no
    id (confirmed via `FireflyJson.buildTransactionRequestBody`).
    **Suggestions while typing (v0.232)**: the category field is a
    `MaterialAutoCompleteTextView` and the tags field a
    `TagsAutoCompleteTextView` (comma-aware, built on the same Material
    base so it keeps the outlined look - the framework's
    `MultiAutoCompleteTextView` loses it): typing "Clo" shows a
    non-modal list of matching categories next to the field (the end
    icon still opens the full modal picker); for tags the list is for
    the tag after the last comma, and picking one inserts "tag, ".
    `loadSuggestions` fills both from Firefly in the background
    (`listCategories` and the new `listTags()` - `GET /v1/tags`,
    paginated like categories, name under `attributes.tag`,
    `parseTagList`; checked against Firefly's `TagTransformer`/route
    source) and stays silent on failure - the fields work as plain text
    without it. **Since v0.233** the two lists come from
    `posting.FireflySuggestions`, a process-wide in-memory cache (10
    minutes, per server address; a failed refetch falls back to the stale
    list; `remember` adds the category/tags of a just-saved transaction;
    deliberately not written to disk - the names can be personal), the
    same cache feeds the category picker, and the suggestions are also on
    the rule editor's category and tags *template* fields and on the two
    tag fields of the Transactions settings (the approximate-conversion
    tag and "tags for all transactions"). Matching is
    `suggestionMatches` (the entry or any word of it starts with what is
    typed, case-insensitive, unit-tested); `TagsAutoCompleteTextView`
    leaves out tags already in the field; the tag fields of the
    Transactions settings and the rule editor are saved through
    `normalizeTagList` (each tag trimmed, empty entries such as the
    trailing ", " a picked suggestion leaves are dropped). **Calmer list
    (v0.234/0.235)**, in `SuggestAutoCompleteTextView` (the base of both
    field kinds): it appears only after typing has paused for 500 ms, and
    a list longer than 3 rows only from 2 typed characters on (so one
    letter does not drop a long list), at most 6 rows (the rest
    scrolls). **The list opens above or below the field - the side with
    more room - and keeps that side while the field has focus**,
    shrinking to fit: the framework's own drop-down picks the side from
    the *current* list height (`PopupWindow.tryFitVertical`: fits below,
    else above), so it jumped between sides as the matches changed;
    therefore the view draws its own `PopupWindow` (`showDropDown`/
    `dismissDropDown`/`isPopupShowing` overridden). Gotchas found
    on-device: (1) `dismissDropDown()` also makes `AutoCompleteTextView`
    ignore the next filtering result, so it must not clear the old list
    before the delay; (2) `ArrayAdapter.clear()` + `addAll()` notify
    twice, the first time empty - closing the list on an empty
    notification killed it before the real result (use
    `onFilterComplete(0)` instead), and unregistering a `DataSetObserver`
    from inside its own notification crashes (`IndexOutOfBounds` in
    `DataSetObservable`); (3) rows must be measured with the row's own
    `layout_height`, `View.measure` ignores it. (4) inside a dialog the list used
    to land on the keyboard: `showAtLocation` coordinates are relative to the *parent window* (the
    dialog's smaller, offset window, not the screen), and neither `getWindowVisibleDisplayFrame` nor
    the IME inset described the keyboard there. The list is now placed relative to the field's own
    window and its room is limited to that window (plus the IME inset when an activity dispatches one);
    the system keeps a dialog above the keyboard, so staying inside it keeps the list off the keyboard. **Accounts (v0.236)**:
    the two account fields of the create/edit screen use the same view
    and suggest the user's *asset* accounts (`FireflySuggestions.
    assetAccounts` - the same cache the account picker now uses), on the
    asset side only (`assetSideInputs`, set by `setAccountFieldMode`:
    both fields in create mode, the kind's asset side in edit mode); the
    counterparty side gets none, by the user's own decision. Tapping a
    suggestion goes through the same `onPicked` as the modal picker
    (`SuggestAutoCompleteTextView.onSuggestionPicked`), so the account id
    is remembered and `inferKindFromAccounts` runs. **Everywhere a field
    has a picker, it also suggests (v0.242)**: the rule editor's
    asset-side source/destination account (same `assetSideInputs` rule;
    the counterparty side stays a macro template) and home currency (the
    codes of the local `CurrenciesDb`, what its picker lists), the
    Transactions settings' default source account and default account
    currency, and the create/edit screen's two currency codes (Firefly's
    *enabled* currencies via `FireflySuggestions.enabledCurrencies`, the
    same cache family). **Asset accounts are refetched on every screen
    open (v0.242)**: they are created outside the app, so the 10-minute
    TTL (and the process outliving the visible app - the notification
    listener keeps it alive) left a just-added account out of the
    suggestions and pickers; `FireflySuggestions.assetAccounts(prefs,
    forceRefresh = true)` is called by the rule editor, the Transactions
    settings and the create/edit screen, the cached list being only the
    fallback when the request fails. **Rule editor accounts block (v0.242)**: Type now
    sits in one "Accounts" group directly above Source/Destination, whose
    labels follow the kind ("Source asset account" on an asset side,
    "Source account" on a counterparty side; `setAccountFieldMode`), and
    an asset-side field whose text is not one of the loaded asset
    accounts - typically the `{counterparty}` template a copied rule
    still carries after Withdrawal -> Transfer - gets a soft helper-text
    note (`updateAssetAccountHints`; never an error, saving is not
    blocked, nothing shown until the account list is known). The date-format field is left out: its five
    presets are a picker only, nothing to complete.
    `PickedAccount(id, name)` + `pick?.takeIf { it.name == currentText }
    ?.id` at build time (not a live `TextWatcher`) is the exact same
    stale-id-after-manual-edit handling `RuleEditFragment.buildDraft`
    already uses.
    If the draft has `DraftIssue.LOCAL_AMOUNT_MISSING`, the same
    exchange-rate fetch `MessagesFragment.resolveApproxConversion` used
    to run kicks off on open and fills the amount field once it
    resolves - but only if that field is still blank at that point, not
    unconditionally, since the user may have already typed an amount by
    hand while the async fetch was in flight and that shouldn't get
    silently overwritten. A failed rate fetch shows a Toast
    (`create_tx_rate_failed`) and leaves the amount blank for manual
    entry - never a silent no-op.
    A foreign-currency message therefore opens this screen immediately
    from the message list (`MessagesFragment.checkThenOpenCreateOrShowFound`
    skips the tap-time rate lookup and duplicate check when the draft
    has no local amount): the duplicate check for those happens on the
    Create click, with the amount the user actually sees. The tap-time
    chain (`showTestMatch` -> `matchMessage` -> link verify / duplicate
    check) is guarded by `openingMessage`, so repeat taps while it is in
    flight are ignored (previously each tap queued its own chain and
    every one opened its own screen when the slow network calls
    finished), and a "Checking…" Toast shows only if the chain takes
    longer than 1.5 s.
    This paragraph describes create mode's submit (`onCreateClicked`);
    edit mode's "Save" (`onSaveClicked`) validates the form the same way
    but skips straight to posting - see the `editIntent` entry above for
    why. "Create transaction" is always enabled (no preemptive "Checking…"
    dance the way the old dialog's neutral button had - see
    `MainActivity`'s own entry above for why that's no longer needed):
    tapping it validates the form (amount parses, description non-blank,
    source/destination present where the kind requires them - same
    per-kind requirement `rules.RuleValidation.validateRule` already
    encodes, just against a built `TransactionDraft` instead of a
    `ParseRule`) then runs the same `posting.checkForDuplicate` every
    other create path shares. A clean/best-effort-failed check posts
    directly; a likely duplicate shows a plain two-button confirm
    dialog (`create_duplicate_confirm_title`/`_message`, reusing the
    existing strings - Cancel rightmost/last per the house convention,
    since this genuinely is a dialog, unlike the rest of this screen) -
    deliberately simpler than `MessagesFragment.showDuplicateConfirmDialog`'s
    four-action grid (no "Use Created"/"Open" here - the whole point of
    this screen already *is* "look before you post", so "create
    anyway or don't" is the only decision left to make). A successful
    post records both `CreatedTransactionsDb` hash variants, links the
    id to the message (`MessageStore.setTransactionId`, same "latest
    wins" reasoning as everywhere else that call happens), and finishes
    the activity; a failure re-enables the button and shows the real
    failure reason in a plain dialog.
  - `SettingsActivity` - everything else, as a stack of fragments
    pushed via plain `FragmentManager` back-stack transactions (no
    Navigation Component - the flow is small and linear enough not to
    need it). Toolbar title: `openScreen(fragment, titleRes)` sets it
    once at push time, but that alone isn't enough - popping back to a
    screen doesn't re-run it, which used to leave the *departed*
    screen's title showing (a real user-reported bug) instead of
    resetting to whichever screen is now actually visible; tracking this
    via `addOnBackStackChangedListener` (reset to "Settings" only when
    the back stack emptied out completely) covered the single-level
    case but not returning to `RulesListFragment` one level down from
    `RuleEditFragment`, and was one more moving part than needed anyway.
    Fixed by having each fragment that can be a "return to" target
    re-assert its own title itself, from its own `onResume`
    (`SettingsActivity.setScreenTitle(titleRes)`, a small helper
    `openScreen` now also calls internally) - correct regardless of how
    the fragment became visible again (back-pop, or an activity
    recreation restoring it from the back stack without going through
    `openScreen` at all), not dependent on back-stack bookkeeping.
    `setBackGuard(guard)` lets whichever fragment is on top
    intercept *both* the toolbar's up arrow (`onSupportNavigateUp`) and
    system back (an `OnBackPressedCallback`, toggled enabled/disabled
    by the same call) with its own logic instead of the default
    pop-or-finish - same two-triggers-one-handler shape as
    `MainActivity`'s `onSelectionCancel`, just generalized into a
    reusable setter here since more than one fragment hosted by this
    activity can plausibly need it (so far: `RuleEditFragment`'s
    unsaved-changes confirm, see its own entry below). `null` restores
    the default. `SettingsRootFragment` is the menu (Firefly III
    connection, transaction creation, notifications, monitored apps,
    currencies, rules, message history, backup & restore, about, added
    one row at a time as each is built - Firefly III connection sits
    first since it's the gateway to actually doing anything with a rule
    match now that posting is underway, transaction creation and
    notifications right after it since they're the other two halves of
    "what happens when a rule matches"; currencies sits above rules
    since rules depend on it, same reasoning as monitored apps coming
    first; about sits last, after backup & restore - nothing to
    configure, just name/version/GitHub link, so it belongs at the very
    bottom of the list; add test message is no longer its own row at
    all, see `MessageHistoryFragment`'s own entry below).
    **History**: this row was briefly named "Firefly III settings"
    (renamed from "Firefly III connection" at the time, since the
    screen then covered far more than the connection itself) - now
    split back into two screens (below), so "Firefly III connection" is
    accurate again, this time for a different reason: an AI-Review
    security finding about the token field, not a naming preference.
    `rowAboutSubtitle` now shows "Version x.yy" right on this list
    (`updateAboutSubtitle`, same `PackageManager.getPackageInfo`
    `versionName` lookup `AboutFragment` already did, reusing its
    `about_version` string) - the user's own request, so the running
    build's version is visible without opening About first.
    Linking to:
    - `FireflyConnectionFragment`/`TransactionCreationFragment` (see the
      `prefs`/`network` package notes above for `FireflyPrefs`/
      `FireflyApiClient`) - **split out of one combined
      `FireflySettingsFragment`, the user's own call, prompted by an
      AI-Review finding**: the token field's `password_toggle` reveal
      icon let anyone looking at the phone read the *saved* token back
      out just by opening the screen, and that risk had nothing to do
      with the non-secret fields (approx-conversion tag, auto-create,
      dedup) sitting on the same form - splitting them means only the
      genuinely sensitive half needs any extra protection at all.
      `FireflyConnectionFragment` (base URL, token, "Test connection")
      gets two defenses:
      - The token field is now **write-once** - `onViewCreated` never
        loads the real saved token back into the field at all. **Fixed
        a real "looks just empty, not protected" gap, the user's own
        catch**: a plain empty field with no explanation looked
        indistinguishable from "nothing saved yet" - `updateTokenHint`
        instead sets the field's own `android:hint` (not the
        `TextInputLayout`'s floating label, which stays the static
        "Personal access token" - same label-plus-inline-placeholder
        shape `urlInput`'s own hint/example pair already uses) to a
        masked placeholder (`firefly_field_token_saved_hint` - literal
        •••• characters plus "saved - leave blank to keep it") whenever
        a token already exists, so there's no "read it back by opening
        the screen" regardless of the reveal icon (kept, for checking a
        freshly-*typed* value before saving - a much lower-stakes
        reveal than an old secret nobody asked to see again), while
        still showing *something* distinct from "unconfigured". `save()`
        only calls `FireflyPrefs.setToken` when the field actually has
        something typed, leaving the stored token untouched otherwise;
        `testConnection()` falls back to the saved token the same way
        when the field is blank, so testing a URL-only change doesn't
        force retyping the token first.
      - `FLAG_SECURE` (`onResume`/`onPause`) blocks screenshots/screen
        recording - system or a malicious accessibility service - while
        this screen is open. Driven by a `protectScreenshotsSwitch`,
        default **on** every time the screen opens and deliberately
        *not* a persisted `FireflyPrefs` setting (no way to leave
        "documentation mode" on by accident) - the user's own follow-up
        request, after asking how to still capture a screenshot of this
        specific screen (for documentation). Window flags are
        Activity-wide, so `onPause` always clears it unconditionally,
        regardless of the switch - leaving this screen never leaves the
        flag set behind it for whatever `SettingsActivity` shows next.
        Placed at the *bottom* of the form, below Save and behind its
        own divider - the user's own layout call, out of the way of the
        fields someone actually opens this screen to edit.
      `TransactionCreationFragment` (row title shortened to
      "Transactions", the user's own call - auto-create, dedup, the
      default account currency, all-transactions tags, approx tag)
      carries no such protection, same explicit Save-button shape as
      before, not autosave-per-field like `MessageHistoryFragment`'s
      radio group, since several of these fields are text the user is
      still typing/correcting rather than a single tap choice. Field
      order: auto-create, a divider, the three dedup fields, a second
      divider, then the default account currency field, then a third
      divider, then the all-transactions tags field and approx tag
      together (see the `rules` section above for what each actually
      does) - the user's own layout call, grouping the two tag fields
      together at the bottom rather than splitting one off at the top
      the way the original combined screen had it.
      "Default account currency" (`FireflyPrefs.getDefaultAccountCurrency`,
      see its own KDoc) picks from `FireflyApiClient.listCurrencies()`'s
      own `enabled` currencies, same end-icon `TextInputLayout` +
      `AlertDialog.setSingleChoiceItems` shape every other Firefly-
      sourced picker in this app uses (asset accounts, categories) -
      field stays freely typeable either way, same as those. Own
      caption below it explains it's used whenever a rule leaves its
      own currency blank - deliberately a *different* `FireflyCurrency`
      data source from `RuleEditFragment`'s own currency picker
      (`rules.CurrenciesDb`, this app's locally-maintained symbol<->code
      table) - that one's for typing a rule's `homeCurrency` by hand
      without a typo, this one's "what's actually switched on in my
      real Firefly instance right now". **Fixed a real "field got too
      tall" layout bug, the user's own catch**: both tag fields' own
      explanatory text used to be the `TextInputEditText`'s own inline
      `android:hint` (an example value shown inside the box when
      empty) - fine for a short example, but long enough here that it
      visibly grew the field's own height for no benefit over a plain
      caption. Both are now a separate `TextView` *below* the field
      instead (`firefly_field_all_transactions_tags_hint`/
      `firefly_field_approx_tag_hint`), same small-gray-caption style
      `firefly_protect_screenshots_hint` already uses under its own
      switch. "Test connection" runs
      `FireflyApiClient.testConnection()` on a coroutine
      (`viewLifecycleOwner.lifecycleScope`) against whatever's
      currently typed in the URL/token fields (falling back to the
      saved token per the write-once note above) - not necessarily
      what's saved - so the user can verify before committing. The
      Settings row's own subtitle ("Connected to Firefly III via X" / "Firefly III connection not configured"; the row itself is titled "Server")
      only reads `hostOf(getBaseUrl())`, not a live connection check - a
      cheap, offline-friendly signal that *something* is saved, not
      proof it still works.
    - `NotificationSettingsFragment` (new row, right after Firefly III
      connection - grouping it near the auto-create toggle it's a
      sibling concept to, even though the two are otherwise
      independent) - `prefs.NotificationPrefs`'s two switches, same
      autosave-per-tap shape as `MessageHistoryFragment`'s radio group
      (below) rather than `TransactionCreationFragment`'s explicit Save,
      since there's no typed/validated text here. See the `posting`
      section above (`AutoCreateNotifications`/`ReviewNotifications`)
      for what the two toggles actually gate. `showFinancialInfoSwitch`
      is disabled (not hidden - same "visible but not usable"
      convention the per-row overflow menu items in `MessagesFragment`
      use) whenever `showNotificationsSwitch` is off, since there's
      nothing left for it to apply to. A third row's button opens
      `Settings.ACTION_APP_NOTIFICATION_SETTINGS` directly.
    - `AppsFragment` (reused as-is, just hosted here instead of a tab;
      lists every launchable installed app via `queryIntentActivities`
      on `ACTION_MAIN`/`CATEGORY_LAUNCHER`, which needs the
      `<queries>` entry in the manifest to see apps on API 30+; loads
      on a background dispatcher, only once the screen is actually
      opened, not eagerly). A search field above the list filters by
      label or package name (case-insensitive substring) via
      `AppsAdapter.setFilter`; the adapter keeps the full list apart
      from the visible one and mirrors checkbox changes into both, so
      clearing the filter never reverts a toggled app. Its "grant access" banner button (shown
      when `isNotificationListenerAccessGranted` is false - the one
      shared check `MainActivity`'s own gate above, this banner, and
      `NotificationAccessConsentFragment`'s auto-dismiss all use) opens
      `NotificationAccessConsentFragment` instead of jumping straight to
      `Settings.
      ACTION_NOTIFICATION_LISTENER_SETTINGS` the way it used to - a
      Google Play review requirement for any app requesting notification-
      listener access: an explicit in-app disclosure of what the app
      does with notification content (receives from every installed
      app, analyzes only the monitored ones, extracts amount/currency/
      date/description on-device, never sends raw notification text
      off-device, sends only the recognized transaction to the user's
      screen, not left to that screen's own generic permission wording.
      The text opens with a bold paragraph (`notification_access_consent_intro`,
      styled via `<b>` and `getText`) that explains which permission the
      app needs and how it is granted on the system page (switch for the
      app name + confirmation) - explanatory, not pushy; the disclosure
      body follows it.
      screen, not left to that screen's own generic permission wording.
      Declining (`consentNotNowButton`) just pops back to the same
      banner via `parentFragmentManager.popBackStack()` - `AppsFragment`
      is still right behind it on `SettingsActivity`'s back stack,
      nothing lost.
    - `MessageHistoryFragment` (the "Save history to local storage"
      switch - off by default, hint text spells out that off means
      lost on app/device restart and that created Firefly
      transactions are unaffected either way - plus the 1/3/5/7/30-day
      window radio group, always enabled; both write to
      `RetentionPrefs` and call `RetentionController
      .applyOnSettingChanged`; the Settings row's subtitle shows both,
      e.g. "In memory only · kept for 5 days", via a `plurals`
      resource since Russian has four forms) - also now the entry point to
      `AddTestMessageFragment` (below) via an `addTestMessageButton`,
      moved off its own top-level Settings row - the user's own call:
      a debug tool fits better grouped with the one screen it most
      directly feeds (this one, the captured-message history it injects
      test messages into) than as a permanent fixture on the main
      Settings list.
    - `AddTestMessageFragment` - a debug tool: picks from the
      monitored apps list and calls `MessageStore.shared.add(...)`
      directly with user-entered title/text, exactly like
      `BankNotificationListenerService` would for a real notification
      (same dedup, same persistence-if-enabled, same
      `posting.AutoCreateScheduler.scheduleIfEnabled` call on a
      genuinely new result) - lets rule processing be tested without
      waiting for a real bank notification. Reached from
      `MessageHistoryFragment` now (see above), not its own row.
      A "Delay adding by 10 seconds" checkbox (v0.225) leaves the
      screen at once and injects the message 10 s later from a main-looper
      `Handler` on the application context, so the tester can background
      the app or open another one first and watch what a real
      notification does with the app not in front (e.g. the notification
      tap back stack). An untouched date/time becomes "now" at injection.
      "Add" is a toolbar button (`addSaveMenu`, disabled with no monitored
      apps); after a successful add the screen closes via `leaveScreen()`
      (back to the Messages list when opened from the setup checklist),
      while a dropped duplicate keeps the form open.
      Two tap-to-open fields, `messageDateInput`/`messageTimeInput` (same
      look and behavior as the Create transaction screen; default "now"
      when the screen opens, each separate picker dialog edits only its
      half) let the injected
      message carry a chosen timestamp instead of always being "just
      now" - the user's own request, for reproducing a rule bug tied to
      a specific real notification's time. `MessageStore.add` gained an
      optional `timestampMillis` parameter (defaults to `clock()`, every
      other call site unaffected) for this; its dedup-window comparison
      switched from `now - existing.timestampMillis` to
      `Math.abs(...)` of the same, since a deliberately backdated
      timestamp can now be *older* than an existing entry, which the
      original always-monotonic "now" assumption never had to handle.
    - `AboutFragment` - app name, `versionName` (read from
      `PackageManager.getPackageInfo`, not hardcoded, so it can't drift
      from `app/build.gradle.kts`), and a GitHub link
      (`about_github_url`, opened via `ACTION_VIEW`) - static, no
      prefs/db involved.
    - `RulesListFragment` - the ordered rule list (`RulesAdapter`);
      tap opens `RuleEditFragment` to edit, the overflow (⋮) menu per
      row has move up, move down, enable, disable, duplicate, delete
      (in that order, delete last) - all straight `RulesDb` calls
      followed by a reload. Enable/disable here are new - the user's
      own explicit call, "менюшки должны соответствовать друг другу",
      matching the multi-select toolbar's own action set/order
      (below) exactly rather than leaving this per-row menu without
      them (previously only reachable via `RuleEditFragment`'s own
      `enabledSwitch`). Both always shown regardless of the rule's
      current state - tapping the one that already matches is a
      harmless no-op, the same choice the multi-select version makes
      (there, a mixed-state selection can't single out just one
      anyway). Every item's title carries one emoji prefix now too
      (⬆️/⬇️/✅/🚫/📋/🗑️) - the user's own request, and a genuinely
      practical one for this specific menu, not just decoration: a
      plain `PopupMenu` (what this is) has no reliable way to show
      `android:icon` without a private-API `setForceShowIcon` call,
      so a one-glyph text prefix is the only straightforward way to
      give these items any visual icon at all. **This row's overflow-menu
      shape is the reference pattern for every list in the app** - the
      user's own explicit call, prompted by `CurrenciesFragment` having
      grown a bespoke standalone trash-icon button instead: same
      `ic_more_vert` `ImageButton` (48dp, borderless, `onOverflowClick:
      (T, View) -> Unit` from the adapter), same `PopupMenu` shown from
      it, even where a list only ever needs one menu item
      (`CurrenciesFragment`, below) - visual/interaction consistency
      across lists matters more than how many items happen to be in the
      menu. Add/Export/Import
      (`RulesJson`, see `backup` above) are three `menu_rules_list`
      action icons (`ic_add`/`ic_export`/`ic_import`) on the hosting
      `SettingsActivity`'s toolbar, wired via a `MenuProvider` added in
      `onViewCreated` (`viewLifecycleOwner`, `Lifecycle.State.RESUMED`
      - scoped to this fragment's own lifecycle, so it stops
      contributing the moment the user navigates away, same pattern
      `MainActivity`'s selection-mode menu switch uses at the Activity
      level). This replaced an actual `FloatingActionButton` plus two
      `ImageButton`s, round-tripped through several placements this
      session: first a row of icon buttons above the list (matching the
      FAB's "icon, not text" language but not its position - flagged
      directly, "я же просил поставить кнопки рядом с +"), then the
      same icon buttons moved into the FAB's own bottom-end row - which
      the user then rejected outright too, not for position but because
      a filled, colored, elevated Material FAB sitting next to two
      plain borderless icon buttons never reads as "the same kind of
      button" ("какого фига разные кнопки делаешь?"; the FAB's own
      shadow also visibly clipped against its parent's bounds at that
      position, a separate rendering bug this change sidesteps
      entirely). Toolbar action icons give all three the exact same
      rendering for free (tinted icon, ripple, no background, no
      elevation) with no custom layout to get wrong. The icons
      themselves also went through a multi-round design review (plain
      Material up/down tray-arrow icons -> an attempt at a folder
      silhouette with a DB cylinder inside and an arrow through a gap
      in the outline, iterated live via a published Design-type
      Artifact - several rounds fixed in the abstract HTML mockup
      didn't survive contact with the real on-device render at actual
      icon size, e.g. an arrow-to-outline overlap that looked connected
      in the browser read as a floating, disconnected arrow on device -
      before the user called the whole direction a net loss and asked
      for the original tray-arrow icons back). Net result: still the
      original plain up/down tray-arrow `ic_export`/`ic_import` glyphs,
      plus a new matching filled `ic_add` (Material's own "add" glyph)
      so all three toolbar icons share one visual language. Each row's
      subtitle is the app name bold on its own line (`SpannableStringBuilder`
      + `StyleSpan`, not a separate `TextView` - it's the row's identity,
      everything else is detail about that one app), then
      `"<kind>: <source> → <destination>"` on the line below - always
      that pair shape, even when one side is blank by design (normal
      for WITHDRAWAL/DEPOSIT, which only ever fill one side; see the
      `RuleKind` note above). A blank side displays as
      `rule_account_no_name` ("(no name)") rather than leaving a blank
      gap next to the arrow, which read as if text had gone missing -
      deliberately the same placeholder Firefly III itself shows for a
      null account, so it reads as a familiar convention instead of
      app-invented text. Display-only: this is `RulesAdapter.bind`
      string-formatting the already-loaded `ParseRule`, nothing is
      written back to `RulesDb` or (once it exists) sent to Firefly -
      `TransactionDraft.sourceName`/`destinationName` stay genuinely
      `null` when unset (an earlier, different placeholder here -
      `"(from notification)"` - was removed outright after user
      feedback that its meaning wasn't obvious at a glance; this one
      replaces that spot with the Firefly-familiar wording instead of
      reintroducing the same confusion).
      **Fixed a real "not visible enough" gap, the user's own catch**:
      a disabled rule's only indicator used to be the "· disabled"
      text appended to this subtitle - easy to miss at a glance.
      `ViewHolder.bind` now also dims the whole row (`binding.root.
      alpha = 0.5f` when `!rule.enabled`, same "visible but not usable"
      convention disabled controls elsewhere in this app already use) -
      kept alongside the text, not instead of it, since alpha alone
      could just as easily read as a rendering glitch without the label
      spelling out why.
      Long-press now starts a multi-select, the user's own request
      after noticing hand-authoring a regex rule is hard enough without
      also having to reorder/toggle several at a time one by one - same
      long-press-to-select, toolbar-swap-to-"N selected" gesture
      `MessagesFragment` already uses for the identical interaction on
      the Messages list, but hosted by `SettingsActivity` instead of
      `MainActivity` since that's where this screen lives.
      `SettingsActivity` needed surprisingly little new code for this -
      just one small `updateSelectionCount(count)` method (the "N
      selected" title, reusing `MainActivity`'s own `messages_selected_
      count` string) - because its existing `setBackGuard` already
      routes both the toolbar's up arrow and system back through one
      swappable callback (originally built for `RuleEditFragment`'s
      unsaved-changes confirm), so "cancel selection" just reuses that
      instead of needing a second, bespoke mechanism the way
      `MainActivity` has its own `onSelectionCancel`/`backPressedCallback`
      pair (that activity had no such guard to reuse before Messages'
      own selection mode was built). The *menu* swap doesn't touch
      `SettingsActivity` at all either - this screen already registers
      its own add/export/import menu via a `MenuProvider` (above), so
      entering selection mode just makes that same provider's
      `onCreateMenu`/`onMenuItemSelected` branch on `inSelectionMode`
      and inflate `menu_rules_selection` instead, then calls
      `requireActivity().invalidateMenu()` to make it re-run.
      `menu_rules_selection` - move up/move down (`ic_arrow_upward`/
      `ic_arrow_downward`, new plain Material glyphs) are the only two
      `ifRoom` icons; enable/disable/duplicate/delete are all `never` -
      text-only in the overflow, **deliberately matching the per-row
      overflow menu's own all-text shape** (the user's own call,
      "менюшки должны соответствовать друг другу" - the two menus
      should read as the same kind of thing) rather than hunting for
      icons that don't already exist in this app just to fill the
      toolbar. Order is now identical to the per-row menu's own (see
      its own entry above): move up, move down, enable, disable,
      duplicate, delete - delete last in both, **the user's own
      explicit request** - keeping the one irreversible action
      furthest from the rest, including past "duplicate" which creates
      new rows rather than destroying any. Same emoji-prefixed titles
      as the per-row menu too, reusing the exact same string resources
      (`rule_move_up`/`rule_move_down`/`rule_enable_selected`/
      `rule_disable_selected`/`rule_duplicate`/`rule_action_delete`) -
      one shared set of labels, not two copies that could drift apart.
      A true visual divider line between items isn't available here -
      checked and confirmed with the user: standard `Menu`/`MenuItem`
      XML (the Toolbar/overflow this app uses, not a custom-view
      `PopupMenu` like the duplicate-confirm dialog's own 4-button
      grid) has no divider item type at all, so ordering alone is the
      separation, not a line. The per-row overflow menu (one row at a
      time) is unchanged in *mechanism* and still exists side by side -
      multi-select is additive, not a replacement - even though its
      own action set just grew to match this one's.
      `duplicateSelected()` is the same per-rule behavior the per-row
      overflow's own "Duplicate" already has (`RulesDb.duplicate`,
      clone with " (copy)" label inserted right after the original),
      just applied to every selected id - each duplicate lands next to
      its own original, not clustered together; the originals (not
      their new copies) stay selected afterward, since only their ids
      were ever in the selection set to begin with.
      Move up/down semantics for a multi-selection, the user's own
      chosen design: every selected rule moves exactly one slot in the
      chosen direction, independently, not as a block with special-
      cased adjacency handling. `moveSelected(up)` gets most of this
      for free from `RulesDb.moveUp`/`moveDown`'s own existing behavior
      (each swaps *the current* DB state with the immediate neighbor,
      re-read fresh every call, not a cached snapshot) by choosing the
      right processing order first: top-to-bottom (on-screen order)
      for "up", bottom-to-top for "down" - what keeps a *contiguous*
      selected block moving as a single unit (each row's neighbor swap
      lands it directly next to the next selected row in line) instead
      of selected rows leapfrogging each other into a jumbled order.
      **Fixed a real "boundary pair shuffles instead of staying put"
      bug, the user's own catch**: that ordering alone isn't enough
      right at the top/bottom of the whole list - if two or more
      selected rules are already the bottom-most (or top-most) rows as
      a block, each one still has a neighbor, but that neighbor is
      *another selected row in the same stuck block*, so the original
      version swapped them anyway on every tap: the block itself had
      nowhere to go, but its members kept reordering among themselves,
      which read as random shuffling rather than "these can't move".
      `moveSelected` now tracks a `frozen` set as it processes: a
      selected id freezes (no swap at all) when its neighbor in the
      chosen direction is either the true edge of the whole list (no
      neighbor at all) or is itself a selected id that already froze
      earlier in the same pass - processing boundary-first (the same
      top-to-bottom/bottom-to-top order above) guarantees the
      list-edge member of any contiguous selected run is decided
      before the rest of the run, so the freeze correctly propagates
      inward instead of each member swapping blindly with whatever's
      physically next. Verified by hand-tracing a spread-out selection
      (1/3/5), an adjacent one (1/2), and - the case that was actually
      broken - a selected run touching the list's own edge, through
      the full algorithm before writing this, not assumed.
      `moveSelected` also calls `scrollToKeepSelectionVisible(up)`
      right after `reload()` - the user's own request: repeated
      move-up/move-down taps can walk a selection clean off the
      visible screen with no scrolling to follow it. Scrolls (plain
      `scrollToPosition`, not `smoothScrollToPosition` - this runs on
      every tap including rapid repeated ones, where a queued
      animation would lag rather than help) to whichever edge of the
      selection is leading the move - the first selected row for "up",
      the last for "down" - since that's the one steering toward the
      edge the user is actually aiming for, not an arbitrary member.
      Enable/disable for a selection reuses the existing `RulesDb.
      update(rule)` (no new DB method) - `rulesDb.loadAllOrdered().
      filter { it.id in ids }.forEach { rulesDb.update(it.copy(enabled
      = ...)) }`, two explicit actions (not one ambiguous "toggle") per
      the user's own choice, so a mixed-state selection (some already
      on, some off) has one unambiguous outcome regardless of where
      each one started.
      Bulk delete asks for confirmation first (`rules_delete_selected_
      confirm_title`/`_message`, Cancel rightmost per house convention),
      same shape `MessagesFragment.confirmDeleteSelected` already uses -
      the user's own choice, folded into the same selection toolbar
      rather than left out or kept to one-row-at-a-time only.
      **Needs real-device verification** (not yet done): nothing here
      can be meaningfully exercised by a JVM unit test (the `MenuProvider`
      swap, `RecyclerView` long-press, `invalidateMenu()` actually
      re-running `onCreateMenu`) - only compiled successfully so far.
      `reload()` also appends `rules.BuiltInRules.ALL` after every real
      `RulesDb` row, always (not hidden when the shared flag is off -
      same "dim, don't hide" convention a disabled real rule already
      gets, so the toggle stays discoverable), `.copy(enabled =
      FireflyPrefs.isBuiltInRulesEnabled())` so their row dims/undims
      with the one shared flag. `rulesEmptyText` ("no rules yet") still
      checks the real-rules list alone, not the combined one - the
      built-in rows always being present would otherwise mean that hint
      can never show again. `RulesAdapter.bind` branches on `rule.id in
      BuiltInRules.IDS` only for the background tint (`0x14000000`, the
      "visually distinct" ask) and to swallow long-press (`{ true }`,
      no call into `onLongClick` - can't be multi-selected alongside
      real rules) - `onClick`/`onOverflowClick` otherwise fire exactly
      the same as a real row; it's `RulesListFragment`'s own job to
      branch on the id inside those callbacks, not the adapter's.
      **Fixed a real "tap silently flips a setting" bug, the user's own
      catch, on-device**: the first shipped version had a built-in
      row's tap call a dedicated `onToggleBuiltIn` callback straight
      into `FireflyPrefs.setBuiltInRulesEnabled` - easy to trigger by
      accident while just browsing the list (which is exactly what
      happened - the user toggled it off without meaning to, then
      separately couldn't figure out why a message that should have
      hit a built-in rule wasn't auto-creating). `onToggleBuiltIn` is
      gone entirely now: `handleClick` opens `showBuiltInRuleDetails`
      instead of `openEditor` for a built-in id (read-only - pattern/
      description/notes template fields all `enabled="false"` in
      `dialog_built_in_rule.xml`, no Save/Delete at all) *except* its
      one `builtInEnabledSwitch`, which writes straight to
      `FireflyPrefs.isBuiltInRulesEnabled()` and reloads the instant
      it's toggled - no Save button needed since that's the only
      actually-mutable thing about a built-in "rule" to begin with.
      `showOverflowMenu` similarly branches to
      `showBuiltInOverflowMenu` for a built-in id - Enable, Disable,
      Duplicate (move/delete still left out entirely rather than shown
      disabled - they're Kotlin constants, not `RulesDb` rows, nothing
      to move/delete). **Enable/Disable here were added as a follow-up
      to the original one-item ("Duplicate" only) version, the user's
      own request** - a second, quicker path to the same shared
      `FireflyPrefs.isBuiltInRulesEnabled()` flag `showBuiltInRuleDetails`'s
      own switch already controls, regardless of which of the two
      built-in rows was tapped. `duplicateBuiltInRule` clones the
      built-in's fields into a brand-new `RulesDb` row via the ordinary
      `RulesDb.add` (ignores the id it's given, appends at the end,
      same as any other "new rule") with " (copy)" appended to the
      label, then opens that new row straight in the editor - this is
      how a built-in rule becomes a real, customizable starting point,
      since it was never a database row to begin with (its own
      `reload()` call was removed once the menu's shared
      `setOnMenuItemClickListener` started calling it once, uniformly,
      after every action). **Needs real-device verification
      too** - same reasoning as the multi-select feature above,
      nothing here is exercised by a JVM test either.
    - `RuleEditFragment` - one form for both create (`ruleId == null`)
      and edit, via `RuleEditFragment.newInstance(ruleId)`. Leaving the
      screen with unsaved edits (toolbar's up arrow *or* system
      back/gesture - both, not just one) asks for confirmation first:
      `initialDraft` snapshots `buildDraft()` right after the fields
      are first populated, `hasUnsavedChanges()` just compares it
      against a fresh `buildDraft()` (`ParseRule` is a data class, one
      `!=` covers every field), and `SettingsActivity.setBackGuard`
      (see its own entry above) is how both back triggers get routed
      through that check instead of the activity's default
      pop-or-finish - registered in `onViewCreated` right after
      `initialDraft` is captured, cleared in `onDestroyView` so it
      can't linger once the fragment is gone (a real save/delete pops
      the back stack directly, bypassing this check entirely - there's
      nothing to discard). **This was the only settings screen with
      this guard until the user caught, directly, that every other
      Save-button screen silently dropped unsaved edits on a stray
      back press too** - `FireflyConnectionFragment`/
      `TransactionCreationFragment` now use the identical shape
      (their own `initialSnapshot`/`buildSnapshot`/`hasUnsavedChanges`/
      `handleBackPressed`, same `setBackGuard` wiring, re-baselined
      after a successful save so an immediate back press right after
      saving doesn't ask about changes that are already persisted).
      `FireflyConnectionFragment`'s own snapshot deliberately excludes
      `protectScreenshotsSwitch` - that switch is never persisted at
      all (see its own KDoc, "not sticky" by design), so toggling it
      alone was never an unsaved change to begin with, nothing for
      `save` to lose. The dialog strings (`discard_changes_title`/
      `_message`/`_discard`) were renamed from `rule_discard_changes_*`
      to drop the rule-specific framing, now shared by all three
      screens rather than duplicated per screen - the title/discard
      label were already screen-agnostic text, only the message needed
      a genuinely generic rewording ("to this rule" dropped). **Since
      v0.230** the dialog itself lives in one place,
      `ui.confirmDiscardChanges` (the three copies are gone), and its
      buttons are "Keep editing" (left) / "Discard changes" (right) -
      no "Cancel", which read as "cancel my changes" (a documented
      exception to the "Cancel rightmost" rule, see AGENTS.md). The
      transaction create/edit screen (`CreateTransactionFragment`) got
      the same guard in that version - it had none before: a `baseline`
      snapshot of the form fields taken once the form has filled itself
      in (the rule's draft or the loaded transaction, and again after the
      async exchange-rate result if the user has not touched anything
      meanwhile), so only the user's own edits count; back, gesture and
      the toolbar's up arrow (routed to the same dispatcher by
      `CreateTransactionActivity`) ask only when something differs.
      Saving or creating finishes the activity directly, bypassing the
      guard. App and
      kind are both Material "exposed dropdown menu" fields (an
      outlined `TextInputLayout` wrapping a
      `MaterialAutoCompleteTextView`) rather than a bare `Spinner` -
      keeps every field on the form visually consistent (a plain
      `Spinner` stood out badly next to the outlined text fields, per
      user feedback on an earlier version). The app dropdown is
      single-select, sourced from `MonitoredAppsPrefs` only (not a
      full re-enumeration of installed apps - a rule can only ever
      match what the listener service already captures) - a rule
      needs exactly one app, not "any monitored app" or several
      (simplified after user feedback; `ParseRule.packageNames` is
      still technically a `Set` for `RuleEngine`'s sake, the UI just
      always writes a singleton set). Field order: identity fields
      first (label, app, kind), then a divider, then the regex
      section: the regex field itself, then three left-aligned
      link-style buttons in one row *below* it, in this order - "pick
      existing" (every saved rule's distinct regex, *plus* two
      hardcoded illustrative examples always appended at the end
      regardless of how many real ones already exist - they're a syntax
      reminder, not just an empty-state filler, see `RulesDb` below;
      each list item shows its label on one line and the *full* regex
      on the next - not truncated - so it's shown in full before being
      applied without needing a separate confirm step on top of that;
      tapping an item applies it immediately), "pick message" (lists
      `MessageStore.shared.snapshot()` filtered to the currently
      selected app, in-memory session data only - picking one drops
      its raw `"title\nbody"` into the pattern field as a literal
      starting point to then wrap with named groups, instead of
      hand-copying the bank's wording from the Messages screen), "test"
      (runs the rule *as currently drafted* - unsaved edits included,
      not gated on `validateRule` since fields irrelevant to matching
      shouldn't block a pattern sanity check - against this session's
      captured messages for the selected app, newest first, stopping
      after 3 matches found). Each match is shown as the notification's
      raw `title` as the row's bold header, followed by one
      `groupname: value` line per named group the pattern declares as
      the body - not the derived `TransactionDraft` fields
      (amount/account/etc), which depend on rule config that may still
      be unfilled while iterating on a pattern and so aren't what "does
      my regex actually capture what I want" is asking
      (`ParseOutcome.Matched.groups` exposes the raw capture map for
      exactly this); the summary line ("N match(es) found (scanned M)")
      sits above the match rows.
      "pick existing" and "pick message" render their lists through a
      shared `PickerItemAdapter` (`item_picker_two_line.xml` +
      `bg_picker_item.xml`, a thin `?attr/colorOutlineVariant` stroke -
      a Material3 token so it survives light/dark theme switches rather
      than a hardcoded hex - around each row, with outer *padding* not
      *margin* creating the gap between rows, since `ListView` silently
      drops margins on a row's root view) instead of plain
      `AlertDialog.setItems` text: each row is a bold header line over a
      normal-weight body line, with the pattern picker's body
      additionally forced to `Typeface.MONOSPACE` (matching the regex
      field itself) - the message picker's body stays the default
      typeface since raw notification text isn't code. "test" reuses the
      *same* row layout (via a shared `bindPickerItemRow` helper, not
      `PickerItemAdapter` itself) but can't use `setAdapter` for it -
      `AlertDialog.Builder`'s `setMessage` and `setAdapter` don't
      actually combine despite looking like independent content slots:
      setting both silently drops the adapter's list, rendering only the
      message (shipped broken this way for one version - v0.26 - before
      being caught). Fixed by building a plain custom view instead
      (`dialog_test_results.xml`: a `ScrollView` > `LinearLayout`) with
      the summary `TextView` and each match's inflated
      `item_picker_two_line` row added to it programmatically, passed to
      `setView`. `MAX_TEST_MATCHES` keeps this list short enough that
      `ListView`'s view-recycling would add nothing here anyway. Before
      any of this, header/body/separate matches all rendered as one
      undifferentiated block of text with `"──────────"` separators -
      no visible boundary between a row's two lines or between separate
      rows.
      Then the live
      "available macros" hint from `parser.namedGroupNames`, then
      *source/destination account* (moved here, below the regex, from
      the identity-fields group above - once they're macro templates
      they only make sense once you're thinking about what the regex
      extracts, same reasoning `homeCurrency` already had), then
      `homeCurrency` (labeled "Account currency" in the UI - "Currency"
      alone read as ambiguous once cross-currency transactions came up
      in discussion: it's the *account's* currency, a fixed comparison
      value, not "whatever currency this transaction happens to be
      in"), then the rest of the templates.
      Source/destination each switch between *two different* end-icon
      behaviors depending on `selectedKind`
      (`RuleEditFragment.updateAccountFieldModes`, called on kind change
      and once right after `populateFields`) - which side is the
      *asset* account Firefly requires to already exist versus the
      *counterparty* side Firefly auto-creates by name (see the
      `FireflyApiClient` note above for why this split exists at all):
      WITHDRAWAL source=asset/destination=counterparty, DEPOSIT
      source=counterparty/destination=asset, TRANSFER both=asset. The
      counterparty side keeps the macro-template behavior description/
      notes/category/tags already had (`ic_insert_macro`,
      `showMacroPicker`, `Editable.replace` at the cursor, not
      `setText` - the field may have other text around where the macro
      goes; also offers the current app's name as a plain literal
      string, not a `{macro}`, see the `{appname}` removal note above).
      The asset side gets the *same* end icon `homeCurrency` already
      had (`ic_dropdown`) but over `FireflyApiClient.listAssetAccounts()`
      instead of `CurrenciesDb` - `setText`s the whole field on pick,
      same "this holds one fixed value, not a template to build up
      piece by piece" reasoning, fetched once per screen session and
      cached in `RuleEditFragment.assetAccounts` (a personal Firefly
      instance's account list doesn't change often enough to justify a
      network call on every picker tap) rather than failing the whole
      screen when Firefly isn't configured yet, this just falls back to
      a Toast and leaves the field free-typeable - the rule can still be
      drafted and fixed up once a connection exists. The picker is
      `setSingleChoiceItems`, not `setItems` - pre-selects (radio mark)
      whichever account already matches the field's current text, so
      reopening it shows what's already there at a glance instead of an
      unmarked list every time; still applies and dismisses on one tap,
      same as before, no separate confirm step.
      **Used to also capture the picked account's Firefly ID
      alongside the name** (`RuleEditFragment.PickedAccount`, held in
      `sourceAccountPick`/`destinationAccountPick`, carried into
      `ParseRule.sourceAccountId`/`destinationAccountId` at save time) -
      removed entirely, a deliberate, breaking fix for a real cross-app
      dedup bug the user caught directly on-device: two different rules
      for the same real Firefly account don't necessarily agree on
      whether an ID got cached for it (one rule's account field picked
      via this picker, another free-typed or saved before the picker
      existed), so `rules.transactionDedupHash` hashing `id ?: name`
      made the two rules' drafts hash *differently* for the identical
      real account, and a genuine duplicate between them went uncaught.
      `ParseRule` doesn't carry a Firefly account ID at all anymore (see
      its own KDoc) - this picker now only ever fills in the account's
      *name* (`target.setText(account.name)`, no `PickedAccount`/id
      tracking left to do), purely to avoid a typo against the real
      account list, not for posting precision. `ui.
      CreateTransactionFragment`'s own, separate one-shot account picker
      (see its own entry below) is the one remaining place an ID can
      still reach `FireflyJson.buildTransactionRequestBody`'s
      `source_id`/`destination_id` preference, for that single
      transaction being created right then - not something a *rule*
      carries forward across multiple future notifications.
      Every macro-template field (the counterparty account side plus
      description/notes/category/tags - *not* `homeCurrency`, which
      `renderTemplate` never touches: `RuleEngine` compares the captured
      currency *against* it literally to decide local vs. foreign
      amount, so a `{macro}` typed in there wouldn't render to
      anything - it'd just sit as that literal string and never match a
      real currency) has a custom end icon
      (`TextInputLayout.setEndIconOnClickListener`, `ic_insert_macro`)
      that opens a picker (`RuleEditFragment.showMacroPicker`) over the
      currently declared macros (the pattern's named groups plus
      `FIXED_MACROS`) *plus* the current app's name as a plain literal
      string and inserts the picked text at the current cursor
      position/selection in that field. The end icon tap (and the
      picker dialog it opens) moves focus off the field, so insertion
      explicitly calls `requestFocus()` +
      `setSelection(insertAt + text.length)` afterward - without it the
      cursor was left stranded wherever it happened to be instead of
      right after what was just inserted. Saves having to hand-type
      e.g. `{currency}` and getting the spelling wrong.
      `homeCurrency` gets the dropdown end icon too
      (`RuleEditFragment.showCurrencyPicker`) - a plain list of the
      user-maintained `CurrenciesDb` codes (e.g. "EUR (€)"). Saves a
      typo turning into a currency `normalizeCurrency` never
      recognizes. `dateFormat` (`showDateFormatPicker`, same end-icon
      dropdown shape, right below `homeCurrency` in field order - both
      are "how to interpret a captured value" fields) offers five
      preset patterns (`dd.MM.yy`/`dd.MM.yyyy`/`dd/MM/yyyy`/
      `yyyy-MM-dd`/`MM/dd/yyyy`) that *fill* the field rather than lock
      it - unlike the currency picker's codes, a bank's actual date
      wording won't always match one of these exactly, so the text
      stays freely editable afterward.
      Every `TextInputLayout` on this form (and on
      `AddTestMessageFragment`'s) sets `app:expandedHintEnabled="false"`
      so the field label always sits in the compact/floating position -
      without it, an empty unfocused field shows its hint as large
      inline placeholder text instead, and a form with some fields
      focused/filled and others not looks visually inconsistent ("zoo
      of labels", per user feedback). Save runs `validateRule` and
      shows every error in one dialog before writing anything.
    - `CurrenciesFragment`/`CurrenciesAdapter` - list + add/edit (add is a
      toolbar "+" action via a `MenuProvider`, like the Rules screen; one
      `AlertDialog` with two fields: code, symbol - the code field is
      still freely typeable but has a dropdown end icon listing Firefly's
      *enabled* currencies ("EUR - Euro (€)"); picking one also fills
      Symbol from Firefly's own record) + delete over
      `CurrenciesDb`. No separate full-screen editor like
      `RuleEditFragment` - two fields doesn't warrant one. Delete is
      behind the same overflow (⋮) `PopupMenu` `RulesListFragment`
      uses, not its own standalone trash-icon button (an earlier,
      bespoke design) - a one-item menu is still the uniform pattern,
      per the user's own call on list-interface consistency; delete
      asks for confirmation the same way it always did
      (`confirmDelete`, unchanged).
  - First-run setup checklist (`ui.MessagesFragment`'s `setupChecklist`
    block, `fragment_messages.xml`, `prefs.SetupChecklistPrefs`) - shown
    in place of the "nothing captured yet" text while the message list is
    empty, four rows (notification access granted - first, since "Not now" on
    the consent screen otherwise leaves no trace of the missing permission;
    Firefly connected, a monitored app chosen, default
    currency + default source account set) with a done/not-done icon,
    recomputed on every `onResume` so returning from a settings screen
    updates it. Below a divider, an "Optional" group of two more rows:
    *Show notifications* (done = `POST_NOTIFICATIONS` granted + system
    notifications on + the in-app switch on; a tap opens the Notifications
    screen, never the system permission dialog directly: that screen
    (`NotificationSettingsFragment`) shows an explanation card while the
    permission is missing or notifications are blocked in system settings,
    with an "Allow notifications" button - the only place the system dialog
    is launched, falling back to the system app-notification settings once it
    no longer appears - and keeps its switches disabled meanwhile. Nothing
    prompts at app start any more; the auto-create switch's own toggle-time
    request remains) and *Add a test message* (opens `AddTestMessageFragment`;
    never shows as done - the checklist is retired as soon as a message exists). Tapping a row starts `SettingsActivity` with its
    `EXTRA_SCREEN` (`SettingsActivity.SetupScreen`), which opens that one
    screen as the activity's root with no back-stack entry, so back lands
    on Messages. The checklist is *retired for good* (`SetupChecklistPrefs
    .retire`, never cleared) the first time any message exists - set at
    capture time in `BankNotificationListenerService` and
    `AddTestMessageFragment`, and again whenever the Messages listener sees
    a non-empty list (covers restore) - or when the user taps "Hide this
    guide"; an empty list alone isn't enough, since retention expiring
    every message also empties it and the guide must not come back then.
  - Onboarding autofill (`posting.DefaultsAutofill.kt`): after a successful
    "Test connection" and after "Save" on `FireflyConnectionFragment`,
    `autofillDefaults` fills the default account currency with Firefly's
    primary *enabled* currency (`FireflyCurrency.primary`, parsed from the
    `primary` key, or the older `default` one - Firefly's
    `CurrencyTransformer` emits both as the user-group native currency) and
    the default source account only when there is exactly one asset
    account (with several there is no honest default). It only fills blank
    prefs, never overwrites, makes read-only calls, and leaves fields blank
    on any failure. `chooseDefaults` is the pure part, unit-tested. On the
    Transactions screen the currency + source account fields now sit at
    the top, above the switches.
  - Save on `FireflyConnectionFragment`, `TransactionCreationFragment`,
    `RuleEditFragment` and Create/Save on `CreateTransactionFragment` is a
    filled button in the toolbar (`Fragment.addSaveMenu`, which returns a
    `SaveMenuHandle` for the label and enabled state - the create/edit
    transaction screen disables it while posting and until the form is
    loaded; it deliberately has no trash icon, deleting a transaction is
    left to the Firefly UI. The button is
    `menu_save.xml` + `action_save_button.xml` as an action layout - its view
    takes the click, it never reaches `onMenuItemSelected` - not a button at
    the bottom of the scroll view - on the long forms the bottom button was
    off-screen and easy to miss. `RuleEditFragment` also gets a trash icon
    there (existing rules only), and its Enabled switch is the first field.
  - `SettingsActivity.leaveScreen()` - the one way a settings screen closes
    itself (back guards' "discard", and after a successful Save on
    `FireflyConnectionFragment`/`TransactionCreationFragment`): pops the
    back stack if the screen was pushed, otherwise finishes the activity.
    Needed because a screen opened directly as the root (the Messages
    setup checklist) has no back-stack entry, and a bare
    `parentFragmentManager.popBackStack()` silently does nothing there.
  - `SystemBarInsets.kt` - `useClassicSystemBars(root)`, the shared
    edge-to-edge-but-padded-back-to-classic logic both activities call
    (see Known bugs/gotchas below for why it's done this way). Also
    folds `WindowInsetsCompat.Type.ime()` into the same padding calc,
    not just `systemBars()` - needed because edge-to-edge opts the app
    out of the classic `adjustResize` auto-scroll-the-keyboard-out-of-
    the-way behavior, which otherwise left a focused field on a form
    (e.g. `RuleEditFragment`) hidden behind the keyboard. Both
    activities also declare `android:windowSoftInputMode="adjustResize"`
    in the manifest, which this relies on for reliable IME inset
    dispatch. Since v0.200 the root is padded only for the status bar,
    cutouts/sides and (while the keyboard is up) the IME - *not* the
    navigation bar, so content runs under the translucent nav bar like in
    other apps. A `FragmentLifecycleCallbacks` (registered by
    `useClassicSystemBars`, recursive) walks every fragment view and gives
    each `ScrollView`/`NestedScrollView`/`RecyclerView` the nav-bar height as
    bottom padding with `clipToPadding=false`, so the last row still scrolls
    above the bar. (For a `ScrollView`/`NestedScrollView` the padding goes on
    its single child, not on the scroller, since v0.229: with the padding on
    the `ScrollView` itself, content only slightly shorter than the viewport -
    the Settings list on a 2340 px phone - did not scroll at all and "About"
    stayed under the bar.) XML tags override that: `skip_bottom_inset` (scroller
    above a pinned bar), `bottom_bar` (pinned bar -> padded, e.g. the consent
    buttons). Floating action buttons are not used (the Currencies and
    Rules "add" actions live in the toolbar). New screens with a pinned
    bottom element must use these tags. Status/nav bar icon colors are set
    explicitly from the theme background brightness.
- `FfNotifyImporterApp` (`Application` subclass, declared in the
  manifest) - the one place startup wiring happens:
  `RetentionController.applyOnStartup`.

## Data flow

`NotificationListenerService.onNotificationPosted` (only for monitored
packages) -> extract title/text -> `MessageStore.add` (dedups
app+title+text within a 15-minute window) -> `MessagesFragment`
re-renders via the store's listener callback; `MessageStore`'s
`onNewMessage` hook prunes entries older than the retention window
and, if "Save history to local storage" is on, also mirrors the entry
into `MessageHistoryDb`. On process start, `FfNotifyImporterApp`
hydrates `MessageStore` from `MessageHistoryDb` if that was left on. Tapping a message
(outside selection mode) loads rules from `RulesDb`, runs
`RuleEngine.match`, and shows the `ParseOutcome` in a dialog - nothing
is written anywhere by this, it's purely a preview. No Firefly III API
call exists yet; that's the only remaining step once a Firefly
connection exists (see TODO.md).

## Known bugs / gotchas

- **Real incident, data lost**: every `SQLiteOpenHelper.onUpgrade` in
  this app (`RulesDb`/`MessageHistoryDb`/`CurrenciesDb`) used to just
  `DROP TABLE` and recreate on *any* `DB_VERSION` bump - "fine for a
  personal, not-yet-relied-upon rules list" reasoning that was true
  early on but silently went stale as real rules accumulated over many
  sessions. The `RulesDb` v5->v6 bump (adding
  `source_account_id`/`destination_account_id`) wiped the user's actual
  hand-built rules with no backup and no warning. `RulesDb.onUpgrade`
  is now a real additive migration (`ALTER TABLE ... ADD COLUMN` per
  version, never drop+recreate again - see its KDoc);
  `MessageHistoryDb`/`CurrenciesDb` still have the old destructive
  `onUpgrade` and need the same treatment *before*, not after, their
  next schema change - both now carry a comment pointing at this
  incident so it isn't repeated there either. Lesson: "disposable test
  data" stops being an accurate justification the moment a user starts
  relying on what's stored, and that transition has no clear signal -
  bumping a `DB_VERSION` deserves friction (confirm with the user, or
  just write the migration) from here on, not habit.
- **Real incident, data lost, twice, same root cause**: `FullBackupJson`
  originally reused `RulesJson`'s rule shape wholesale and hand-picked
  `CapturedMessage` fields for messages - both choices copied
  `RulesJson`'s *deliberate* exclusion of `ParseRule.sourceAccountId`/
  `destinationAccountId` (correct there - it's a sharing format, a
  device-specific Firefly account id is meaningless once it travels
  elsewhere) onto a full backup, which is a same-device safety net,
  not a sharing format, with no independent argument for dropping
  either id. The user caught the message-side half directly: restored
  a backup made minutes earlier and every message that had shown
  "#1234" came back with no link, even though the transaction still
  existed in Firefly - then asked for the actual argument behind the
  account-id exclusion too, which didn't hold up on inspection either.
  `CreatedTransactionsDb` (the dedup log) had a related gap from a
  different angle - `BackupManager` never touched it at all, so a
  restore onto a fresh install would've silently come back with an
  empty dedup log. All three fixed now: `fullBackupToJson`/
  `fullBackupFromJson` carry `fireflyTransactionId` and both account
  ids through, and `FullBackupData.createdTransactions` backs up the
  dedup log too (see the `backup` package section above for the full
  reasoning on each). Lesson: when copying a design decision from one
  place to another ("exclude this kind of id"), check *why* the
  original exclusion was correct *there*, not just that it looks like
  the same shape of field - the same mistake was made twice, in two
  different fields, from the same unexamined copy.
- Android 15+ (targetSdk 35+, which this app uses) forces the window
  to draw edge-to-edge with no permanent opt-out -
  `android:windowOptOutEdgeToEdgeEnforcement` looked like one but is
  registered in the SDK's own resource table as a `STAGED_ID`
  (AOSP's flag-guarded-API marker), and had zero effect on a real test
  device despite matching Google's documented usage. `SystemBarInsets`
  instead lets the window draw edge-to-edge (as the platform wants)
  and manually pads the root view by `WindowInsetsCompat.Type
  .systemBars()`, which doesn't depend on that flag at all and is
  confirmed working on a real device.
- `NotificationListenerService` access, once granted, can get silently
  revoked by some OEM battery-optimization/"app hibernation" features.
  No detection for that beyond the existing "is access enabled" banner
  check on resume.
- The settings area and the rules flow have both had extensive
  real-device verification since - see the many rounds of user-reported
  UI/UX fixes throughout this file's history. Current gap: the
  Firefly III connection itself (Test connection confirmed working
  on-device; `listAssetAccounts()`/`createTransaction()` not yet
  exercised against a real instance, since nothing calls
  `createTransaction()` yet - see TODO.md).

## Launcher icon

Adaptive icon (`mipmap-anydpi-v26/ic_launcher*.xml`): solid white background
(`ic_launcher_background`), foreground = the coin-stack artwork
`drawable-nodpi/ic_launcher_art.png` (512x512, transparent background) wrapped
in a 23% `<inset>` (`ic_launcher_foreground.xml`) so the artwork stays inside
the 66dp safe zone of the 108dp canvas, and the same drawable as the
`<monochrome>` layer for Android 13+ themed icons (the system tints by alpha).

## App name

The user-visible name is the `app_name` string ("Butterfly III", the same in
every locale; the Play listing uses a descriptive subtitle instead). Backup and
rules export default file names are derived from it (`ExportFileName.kt`:
lowercase, non-alphanumerics collapsed to `-`, e.g.
`butterfly-iii-backup-2026-01-01.json`), so renaming the app renames them too.
The **application id** is `io.github.maximus5.butterflyiii` (changed from
the working-title `io.github.maximus5.ffnotifyimporter` in v0.238, before
the first Play upload - it is permanent once published and is visible in the
store URL). The Kotlin **package** (`namespace`, `io.github.maximus5.
ffnotifyimporter`) is deliberately unchanged: it is not visible to users and
does not affect Play. A build with the new id installs as a different app next
to the old one (no data is carried over; use Backup & restore). The consent screen's intro paragraph hardcodes the name per locale.

**Release signing**: `app/build.gradle.kts` signs the `release` build with the Play *upload key*
when the gitignored `keystore.properties` (template: `keystore.properties.example`) exists in the
repo root and names the keystore (`storeFile`, passwords, alias); the keystore itself lives outside
the repo. Without that file the release build is left unsigned, so a fresh checkout still builds.
Play App Signing re-signs with Google's own key. `./gradlew bundleRelease` produces the AAB.

**Publishing to Play** (v0.243+): the Gradle Play Publisher plugin (`com.github.triplet.play`) is
applied in `app/build.gradle.kts`. `./gradlew publishReleaseBundle` builds and uploads the signed
AAB to the `internal` track (the `play { track }` default); another track is chosen per run with
`--track <name>` (e.g. `--track alpha`). It authenticates with a Play service account's JSON key,
which stays outside the repo - `keystore.properties` (gitignored) points at it with
`playServiceAccountKey`, see `keystore.properties.example`. The `play {}` block only sets the
credentials when that line exists, so ordinary builds are unaffected and publishing without it
fails with the plugin's "No credentials specified" error. `versionCode` still comes from
`build.gradle.kts`; release notes are not wired up yet (the plugin reads them from
`app/src/main/play/release-notes/<locale>/<track>.txt`).

The status-bar small icon of the app's notifications (`AutoCreateNotifications`,
`ReviewNotifications`) is `drawable/ic_notification_app.xml`, a flat
coin-stack silhouette (the system masks small icons to one colour, so the
launcher art's clover is omitted); `ic_notification_sync` remains only as the
action-button icon.

## Language of background notifications

The app language is Android's per-app language (13+, `locales_config.xml`); there is no in-app
picker. Screens follow it automatically, but the workers that post notifications
(`AutoCreateNotifications`, `ReviewNotifications`) run on the plain application context, which on
a real Samsung device stayed on the system language - the "Transaction created" notification
came out in English with the app set to German. `posting/AppLocaleContext.kt`
(`Context.withAppLocale()`) reads the app locales from `LocaleManager` and wraps the context in
them; every public notify function starts with it. Any new background code that builds
user-visible strings must do the same. A notification's text is fixed when it is posted, so a
later language change doesn't relabel notifications already in the shade.

## Keeping the listener bound

Monitoring is the system-bound `BankNotificationListenerService`, not a worker: a killed process
is restarted and rebound by the system on the next notification as long as access is granted.
What really stops it is a user "Force stop", vendor power savers (Samsung "Sleeping apps"),
app hibernation after months unused, or a binding the system dropped. There is still no
foreground service (see TODO.md). Two mitigations:

- `onListenerDisconnected` calls `requestRebind(...)`, the documented way back after a
  system-initiated unbind (a no-op once the user has revoked access; failures are swallowed).
- The "Notifications" settings screen has a "Keeping the monitoring running" section. It shows a
  warning card while `PowerManager.isIgnoringBatteryOptimizations` is false, and (API 30+) while
  `PackageManager.isAutoRevokeWhitelisted` is false, each with a button to the matching system
  screen (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, `ACTION_AUTO_REVOKE_PERMISSIONS`; both
  fall back to the app-info page). The direct "allow" dialog
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) is deliberately not used - Play restricts it. Vendor
  sleep lists cannot be read, so they are only named in the one intro paragraph, which also holds the other recommendations; the warning cards sit below it.

## Rules whose account is a guess ("Account is a guess")

Real case: a bank's push does not say which account/card was charged, so its rule names a fixed
account - and picked the wrong one, while the wallet app's notification for the same payment named
the right one. The two drafts hashed differently (`transactionDedupHash` includes the account), so
the payment was created twice. Per-rule switch `ParseRule.accountApproximate` (rules editor, rules
JSON `accountApproximate`, default off; built-in rules never set it) makes `RuleEngine` add
`DraftIssue.ACCOUNT_APPROXIMATE` to the draft. Effects:

- **Never auto-created.** `AutoCreateWorker` stops at the review notification for it, like
  `ACCOUNT_SELECTION_REQUIRED`: a human confirms the account.
- **Account-agnostic duplicate checks.** `transactionDedupHashIgnoringAccounts` = amount + currency
  only (no account, no description, no counterparty: a bank and a wallet rarely spell a merchant
  alike; the date window separates different payments, as everywhere). `CreatedTransactionsDb`
  (v3, column `hash_without_accounts`, NULL for older rows and in older backups) stores it for every
  created transaction; `findDuplicate` uses it for the local log, and for the remote search it goes
  unscoped (`allowUnscoped`) and `remoteCandidateMatches` compares amount/currency/window and
  transaction type only.
- **Shade.** `ReviewNotifications.notifyMatch` posts nothing for an approximate draft whose payment
  is already in the created log (local lookup only - no network in this path, so a transaction
  deleted in Firefly within the window still hides it; the message stays in the list). Review
  notifications carry `shade_hash_without_accounts` / `shade_approximate` extras. A precise
  notification never gets suppressed by an approximate one; it supersedes it:
  `supersededApproximateIndex` picks the one closest approximate notification (one-to-one, so two
  real same-amount payments are not both swept by a single precise one). It runs when a precise
  review notification is posted, after `AutoCreateWorker` created a precise draft, and after a
  manual create from a message whose rule is not approximate (`CreateTransactionFragment.
  createdFromApproximateRule`; edit mode never sweeps). A manual create from an approximate
  message deliberately does not sweep: it would dismiss the *next* approximate notification.
- Messages are never removed by any of this - only shade notifications - so everything stays
  reachable from the Messages list ("Check if created" / tap to create).

A new `ParseRule` field has to be wired in four places, or it silently resets on save/restore (found
the hard way with `accountApproximate`, which first shipped without the first two): `RulesDb`
(column + additive migration, now v8), `backup.FullBackupJson` (its own rule (de)serialization),
`backup.RulesJson`, and `ui.RuleEditFragment`.

## The applied rule is remembered on the message

`CapturedMessage.appliedRuleLabel` records which rule created the linked transaction (the rule's
label as it was then, not an id - it must still read right after a rename or delete). It lives and
dies with `fireflyTransactionId`: `MessageStore.setTransactionId(message, id, label)` sets both and
clears both on unlink; it is ignored for identity wherever `fireflyTransactionId` already is
(`ReviewNotifications.notificationId`, `MessagesFragment`'s `cleanMessage`). Set by `AutoCreateWorker`
(from `TransactionDraft.ruleLabel`, which `RuleEngine` fills from `ParseRule.label`) and by
`CreateTransactionFragment` on a create; linking to an already-existing transaction ("Use created")
stores none. Persisted in `MessageHistoryDb` (v3, column `applied_rule_label`) and the full backup
(`appliedRuleLabel`). Shown in the transaction editor's "Rule:" line - create mode from the fresh
match, edit mode from the message's stored label (looked up in `MessageStore`, since the screen's
own message is rebuilt from intent extras; links made before this existed show nothing). Shown in
the shade ("Rule: ...", second line) on the auto-create success/skipped notifications and the
review notification, also when "Show financial information in notification" is off (the rule name is
not financial data; v0.239).

**On-device test of the shade logic**: `app/src/androidTest/.../ReviewNotificationsInstrumentedTest`
runs `ReviewNotifications` against the real `NotificationManager` (approximate vs precise review
notifications; `ShadeDedupTest` only covers the pure part). Run it without `connectedAndroidTest`,
which uninstalls the app and its data afterwards:
`./gradlew assembleDebug assembleDebugAndroidTest`, then `adb install -r` the app APK and
`adb install -r -t` the test APK, `adb shell pm grant <package> android.permission.POST_NOTIFICATIONS`,
`adb shell am instrument -w -e class <test class> <package>.test/androidx.test.runner.AndroidJUnitRunner`.

**On-device test of the whole auto-create path**: `AutoCreateFlowInstrumentedTest` captures a bank
message (rule marked "Account is a guess") and then a wallet message, through the real
`AutoCreateScheduler` -> WorkManager -> `AutoCreateWorker` -> HTTP client, and checks that the bank
review notification is dismissed and "Transaction created" is shown. It talks to a stand-in Firefly:
`node app/src/androidTest/fake-firefly/server.js` on the host plus `adb reverse tcp:18080 tcp:18080`
(the stub answers search with nothing and POST with an id; it only fits what the client parses).
The test swaps in its own connection settings and rules and restores them afterwards, but it does
use the device's real app data - run it on a test device only. Each run uses a different amount,
because the dedup log of an earlier run would (correctly) suppress the bank notification.

## Diagnostic log

`posting/DiagnosticLog.kt` keeps a small text file (`filesDir/diagnostic.log`, capped at 48 KB, older
half dropped) of what the automatic paths decided: the matched rule label and issues, whether a
review notification was posted / suppressed (already created, already in the shade) / superseded, the
shade sweep's view (flags, hash prefixes and time offsets of the review notifications it saw), and
auto-create outcomes. About -> "Copy diagnostic log" puts it on the clipboard (with the version) for a
bug report; "Clear log" deletes it. **Off by default**: About has a "Write diagnostic log" switch
(prefs file `diagnostic_log`, classified as not backed up in `BackupRulesTest`); nothing is written
until it is on, and turning it off deletes the log. It deliberately holds no amounts, descriptions,
counterparties or account names - rule labels, flags, counts, Firefly transaction numbers, second
offsets and the first 6 characters of dedup hashes (enough to compare two; a hash prefix of amount +
currency could in principle be brute-forced back to the amount, which is why it is opt-in and the log
leaves the device only when the user copies it). Logging never throws.

## Firefly lists, API errors and the diagnostic log

- `FireflyApiClient` takes an optional `log` callback (`SecureSettings.apiClient()` wires it to
  `DiagnosticLog`). It records, per call, only how the call ended: `api GET /api/v1/categories: ok,
  2 page(s), 87 items`, `HTTP 503`, `redirect 301`, `unreadable response (page 2)`, and **every failed
  attempt of a retried read** (`attempt 1 failed: SocketTimeoutException`), so a retry that finally
  succeeds does not hide the failure before it. Never a request or response body (bank data), a header,
  the token, the query string (a search carries descriptions and amounts) or an exception's own message
  (it can carry the server address); `FireflyApiClientLogTest` checks this against a local socket server.
- A 2xx answer that is not a Firefly list (`isListResponse`: JSON with a `data` array) is a failure, not
  an empty page - a half-read list used to be cached as complete for 10 minutes.
- `FireflySuggestions` replaces a cached list only after a request finished successfully; the screens
  that offer a list pass `forceRefresh = true` when they open, and each field gets its list the moment
  it arrives (independent coroutines, no waiting for the other lists). A request that fails after all
  its retries calls `onLoadFailed`; `ui.FireflyLoadFailureNotice` turns the failures of one screen
  opening into one toast naming the lists, with the status line only.

## Auto categories

A counterparty -> category list that fills in a category the rule did not give, so a shop the user
categorized once is categorized on its own afterwards (also by automatic creation, which has no
human to ask). Firefly's own category rules were the model, but they slow down when there are many;
this is a map lookup in the engine.

- **Key**: only the rule's named group `counterparty` (`Groups.COUNTERPARTY`), whitespace-trimmed /
  collapsed and lower-cased (`normalizeCounterpartyKey`). Not the description, not the destination
  account, not the notification title (it is often just "Payment made" or the app's name): the user's
  decision, so a rule has to name the group exactly that to take part. A message without the group
  never *learns*; for *applying* a category it falls back to `AutoCategories.categoryForText`: only
  `CONTAINS` entries, compared with title + text together, same first-match semantics. That is what
  lets typed-in entries ("rewe") work with the built-in rules, which capture no counterparty. The rule
  editor shows a soft hint under the category template when learning is on and the pattern has no
  such group.
- **Model** (`rules.AutoCategories`, pure, unit-tested): `AutoCategoryEntry(key, match, category,
  active, learned)`; `match` is `EQUALS` (learned) or `CONTAINS` (typed in by the user). **List order
  is the priority, exactly like the parse rules**: `categoryFor` returns the category of the *first*
  entry that matches, exact or contains alike, so a narrow entry ("amazon prime" -> Entertainment)
  above a broad one ("amazon" -> nothing) wins. An entry with `active = false` ("fill in the category"
  off) still matches and stops the search - nothing is substituted and nothing below it applies - and
  its category may be blank (the typical "this shop sells everything" entry).
- **Applied** in `RuleEngine.buildDraft`: `category = ruleCategory ?: autoCategories.categoryFor(
  counterparty)`. The rule's own category template always wins. The draft carries `counterparty` and
  `categoryFromRule` so the screens know what to learn from. The list reaches the engine as plain
  data (`posting.RuleMatching.buildEngine`), like the other injected settings.
- **Learned** (`AutoCategories.afterCategoryEdit`, stored through `AutoCategoriesPrefs.recordEdit`)
  only when the switch is on, the saved category differs from the one the editor opened with, the
  rule set no category and the counterparty is known, looking at the first entry that matches it:
  none -> a new `EQUALS` entry at the end of the list (once); a switched-off entry or one that
  already has this category -> nothing; an exact entry whose category was changed -> switched off
  (the counterparty evidently varies); a typed-in "contains" entry -> left alone, and a new exact
  entry (the correction, or "no category") is inserted right above it so the exception wins. An entry
  with `keep = true` ("Keep this category" in the dialog, off by default - "this is the main category,
  a differing transaction is a one-off") is never touched by an edit: no switching off, no exception.
- **Storage**: own prefs file `auto_categories` (switch `learn_enabled`, `entries` as a JSON array),
  in both Auto Backup rule sets.
- **Where learning happens**: `ui.CreateTransactionFragment.onSaveSuccess`, create and edit mode alike.
  Create mode takes `counterparty` / `categoryFromRule` / the opening category from the matched draft;
  edit mode (no draft) opens with the stored transaction's own category and matches the message again
  (`findCounterpartyForLearning`) for the counterparty - no match or no group means nothing is learned.
  Automatic creation never learns (no human edit); it only benefits from the list.
- **Settings screen** (`ui.AutoCategoriesFragment`, a row in the settings root): the learning switch
  (off by default), then the list in priority order - key, the category (or "No category is filled
  in" for a switched-off entry), "Equals/Contains - learned/added by you". It behaves like the rules
  list: tap opens the edit dialog, long-press starts a multi-select whose toolbar moves the selection
  up/down (`rules.moveSelected`, the same "a block at the edge stays put" semantics) or deletes it,
  and the per-row overflow menu has move up / move down / delete. The edit dialog has counterparty,
  match (a new entry defaults to Contains), the "Fill in the category" switch - off disables the
  category field and it is then not required - and the category with Firefly's suggestions. Everything
  applies at once, like the Currencies list - no Save button.
  Editing an `EQUALS` entry's key down to a shorter part of itself (`rules.keyShortenedToPart`, e.g.
  dropping a ", de" tail) switches Match to Contains on the spot, with a hint under the field, because
  the exact entry could never match again; picking a mode by hand turns the auto-switch off for that
  dialog.
- **Backups**: the `auto_categories` prefs file is in both Auto Backup rule sets, and the manual JSON
  backup carries the list (`FullBackupData.autoCategories`, null in a file from before - restore then
  keeps the current list) and the learning switch (`BackupSettings.autoCategoriesLearn`).
