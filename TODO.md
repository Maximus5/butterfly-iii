# TODO.md

Deferred/known-outstanding work. Add an entry when something is
identified but deliberately not done now (out of scope, needs a
decision, etc.); remove the entry in the same commit that finishes the
work. Finished work is documented in ARCHITECTURE.md, not here.

- Turn on R8 (code shrinking + obfuscation) for the release build. Play Console
  flags "DEX code optimization is below our threshold" (obfuscation 2%, under
  the 25% bar), fix by Feb 2027. Plan: do it on a separate `obfuscation` branch
  and ship that branch only to Internal testing until it is proven. Steps:
  `isMinifyEnabled = true` + `isShrinkResources = true` in `release`, a
  `proguard-rules.pro`, keep rules for whatever uses reflection or is named
  only in XML/manifest (rules JSON via `org.json`, WorkManager workers,
  notification listener/receivers, Room if present), then exercise a real
  device: notifications, create/review flow, auto-create worker, backup &
  restore, dialogs. A missing keep rule only breaks the release build, never
  the debug build or the JVM tests. Play takes the mapping file from the AAB
  automatically (also clears the old "no deobfuscation file" warning).

- Release/Play listing prep for the "Butterfly III" rename and new icon (not done yet):
  - Store description + subtitle that say what the app does (the name alone
    doesn't), with a "unofficial, not affiliated with the Firefly III project"
    note; same note in README.
  - Check the name "Butterfly III" isn't already taken on Google Play/GitHub.
  - Play listing icon: separate 512x512 PNG on an opaque background (the
    in-app launcher art is transparent, which Play can render black); no
    pre-rounded corners or shadow.
  - Confirm the rights/licence of the coin-stack icon artwork and record it
    in README if it comes from a third-party set.
  - Optional: a butterfly-themed icon, if the coin stack no longer fits the name.

- An intermittent real-device bug, under observation: a bank "Transaction to
  review" notification stayed in the shade after the Wallet notification for
  the same payment had created its transaction, and the Wallet message got
  its transaction id late. Not reproduced since; enable About -> "Write
  diagnostic log" and copy the log right after the next occurrence.
  Proposals not approved yet: run the shade sweep
  (`ReviewNotifications.supersedeApproximate`) also when a message is linked
  by any other path (tap check "Use created", manual create of a precise
  rule), and hand a running auto-create over cleanly when a setting changes.

- Rule engine leftovers:
  - A way to reorder rules other than one-step-at-a-time up/down buttons (fine
    for a handful of rules; revisit - e.g. drag handles - if the list grows
    long enough to make that tedious).
  - Let *user-defined* rules match "any app" (the built-in ones already do).
    `RuleEngine` supports it (`packageNames.isEmpty()`); missing is UI and
    validation - `RuleEditFragment` offers a single-app spinner and
    `RuleValidation` requires picking one. Needs a multi-select or an explicit
    "Any app" option and a relaxed validation.
  - `POST`ing the fetched exchange rate back into Firefly's own exchange-rate
    table as a side effect (nice-to-have).
  - Open question: telling "the same payment reported twice by two apps" apart
    from "the same payment genuinely happening twice in a row" (e.g. two NFC
    turnstile taps). Manual creation has the "create anyway" confirmation as a
    safety net; auto-create has no human in the loop (the "Account is a guess"
    rule flag covers the bank + wallet case only).
  - Open question: when Firefly's own server-side duplicate check
    (`error_if_duplicate_hash`) rejects a create that this app's own check
    missed, parse its error (does it name the existing transaction's id?) and
    offer the same link-to-existing recovery instead of a bare failure.
  - Template fields have only flat `{macro}` substitution, no conditional
    logic. Raised by the user: sometimes only one field should vary with what
    a group captured (e.g. a tag that says "foreign" only when `currency`
    isn't the home currency), and duplicating the whole rule for that is
    wasteful. A conditional block was proposed and is not implemented - a real
    jump in complexity (an actual small block parser instead of one regex
    pass in `MacroTemplate.kt`). Decided so far: Jinja2-style syntax
    `{% if <expr> %}`...`{% else %}`...`{% endif %}`, and real `==`/`!=`
    comparisons between a bare macro name and a quoted string literal (not
    just a truthiness check; no numeric ordering in v1). Still open: quoting
    and escaping inside the literal, whitespace around the operator, whether
    the left side may be a literal or two macros may be compared, whether
    nesting is allowed and whether `else` is required (each needs tests);
    `validateRule`/`templateMacroNames` must validate the macro name on the
    condition's left side but not `if`/`else`/`endif` or the literal; the
    insert-macro picker would need its own entry point for a whole block.

- Currencies: `CurrenciesDb` holds one symbol per code (code is the primary
  key), so a bank printing both "zł" and "PLN"-style variants, or a shared
  symbol ("$" for several enabled dollars, deliberately not auto-learned),
  can't be mapped beyond one row. Allowing several symbols per code needs a
  real `CurrenciesDb` migration (never the drop-and-recreate `onUpgrade`) plus
  list/dialog changes. Also nothing tells the user when a symbol stays
  unresolved and Firefly rejects the post for it.

- Onboarding: a "now add a test message" prompt after the first successful
  setup.

- No foreground service - chosen deliberately (no persistent notification
  icon) at the cost of the monitoring being easier for the system to stop
  (see ARCHITECTURE.md "Keeping the listener bound" for what is done about it)
  and, with "Save history to local storage" off (the default), of the captured
  list being lost if Android kills the backgrounded app.

- `ic_launcher` is a placeholder vector icon, not a real app icon.

- Optional fullscreen/edge-to-edge mode as a user setting (in the future
  "Appearance" settings row). The default is the classic non-overlapping look
  via `ui/SystemBarInsets.kt`'s `useClassicSystemBars` (see ARCHITECTURE.md);
  a fullscreen setting just needs to make that padding conditional.

- Localization: English (base), Russian and German exist. Keep `strings.xml`
  in sync across locales when adding strings; add more `values-<lang>` as needed.

- Real-device verification still outstanding (some of it may already be
  covered by day-to-day use - confirm and delete what is):
  - "Save history to local storage": toggle on, capture a notification,
    restart the app, confirm it is still there; toggle off, confirm the file
    is cleared; an upgrade from the old retention setting (1+ days must come
    up with the switch on and the same window).
  - The rules flow end to end (add/edit/duplicate/delete/reorder, "test match"
    tap, the foreign-currency / `LOCAL_AMOUNT_MISSING` case) and the built-in
    fallback rules (list rows, the `kind` inference on the create screen, the
    match -> create flow for a built-in-matched message).
  - Manual creation against a real Firefly: clean create, the duplicate-confirm
    path ("Use created" / "Create anyway"), the `#<id>` badge, edit of a linked
    transaction.
  - Auto-create from a real notification with the app closed: skip-duplicate
    and success notifications and their taps; a forced dedup-check failure
    retrying up to `MAX_ATTEMPTS` and giving up; a forced `createTransaction`
    failure surfacing at once with no retry; two back-to-back notifications
    handled strictly one at a time; the two-rules / same-real-payment case
    being caught as a duplicate.
  - Schema upgrade paths with no SQLite-level test coverage:
    `MessageHistoryDb` v1->v2, `RulesDb` v6->v7 against real on-disk databases
    with data; full backup/restore preserving a message's linked `#<id>` and
    the dedup log, including restore onto a fresh install.
  - The date-override rule field against the real Bank DE announcement
    notification that prompted it (the transaction must land on the charge
    date, not the arrival day).
  - Rules list multi-select: the toolbar menu swap via `invalidateMenu()`,
    long-press entering selection mode on a real `RecyclerView`, and the
    move-up/move-down ordering with spread-out and adjacent selections.
  - Deleting a just-created transaction by hand in Firefly, then "Check if
    created" and tapping the linked message: the stale-id paths (with and
    without a replacement match).
  - Auto Backup: a real `bmgr backupnow` (v0.176, Android 16) included only
    `rules.db` and the four allowlisted `shared_prefs`; the restore side - a
    real restore / device-to-device transfer bringing rules and monitored
    apps back on a fresh install - is not verified.

- Auto categories leftovers (see ARCHITECTURE.md, "Auto categories"):
  - Optionally seeding the list from Firefly's history (the most frequent category
    per counterparty) - deliberately left out for now: Firefly has no
    counterparty field, only expense accounts, which can be numerous.
