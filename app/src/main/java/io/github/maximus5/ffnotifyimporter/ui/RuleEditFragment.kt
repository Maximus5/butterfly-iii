package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.databinding.FragmentRuleEditBinding
import io.github.maximus5.ffnotifyimporter.network.FireflyAccount
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.posting.FireflySuggestions
import io.github.maximus5.ffnotifyimporter.notifications.AppLabelResolver
import io.github.maximus5.ffnotifyimporter.parser.namedGroupNames
import io.github.maximus5.ffnotifyimporter.prefs.AutoCategoriesPrefs
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.prefs.MonitoredAppsPrefs
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.FIXED_MACROS
import io.github.maximus5.ffnotifyimporter.rules.Groups
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.ParseRule
import io.github.maximus5.ffnotifyimporter.rules.RuleEngine
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import io.github.maximus5.ffnotifyimporter.rules.validateRule
import kotlinx.coroutines.launch

/** Create (ruleId == null) or edit (ruleId != null) one [ParseRule]. */
class RuleEditFragment : Fragment() {

    private var binding: FragmentRuleEditBinding? = null
    private lateinit var rulesDb: RulesDb
    private var existingRule: ParseRule? = null

    /** A snapshot of [buildDraft] taken right after the fields are first populated - the baseline
     * [hasUnsavedChanges] compares against. Captured, not recomputed from [existingRule] directly,
     * because a brand-new rule's "nothing changed yet" state is whatever [populateFields] defaults
     * it to (today's date label, first monitored app, WITHDRAWAL), not an all-blank `ParseRule`. */
    private var initialDraft: ParseRule? = null

    /** package name to display label, sorted - a rule can only ever match a monitored app. */
    private var monitoredApps: List<Pair<String, String>> = emptyList()
    private var selectedPackageName: String? = null
    private var selectedKind: RuleKind = RuleKind.WITHDRAWAL

    /** Fetched once per screen session on first use, not re-fetched on every picker tap - a
     * personal Firefly instance's asset account list changes rarely enough that a stale cache
     * for the rest of this one editing session is an acceptable trade for not hitting the
     * network every time. */
    private var assetAccounts: List<FireflyAccount>? = null

    private val kindOrder = listOf(RuleKind.WITHDRAWAL, RuleKind.DEPOSIT, RuleKind.TRANSFER)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentRuleEditBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    /** The account fields that are on the asset side right now (see [updateAccountFieldModes]) -
     * only those suggest Firefly's own accounts; the counterparty side is a free-text/macro
     * template. Same rule as the transaction editor's account fields. */
    private val assetSideInputs = mutableSetOf<EditText>()

    private fun applyAccountSuggestions() {
        val binding = binding ?: return
        val names = assetAccounts?.map { it.name }.orEmpty()
        listOf(binding.sourceAccountInput, binding.destinationAccountInput).forEach { input ->
            input.setAdapter(if (input in assetSideInputs && names.isNotEmpty()) SuggestionAdapter(requireContext(), names) else null)
        }
    }

    /** A soft note (helper text, not an error - saving is never blocked) under an asset-side account
     * field whose text is not one of Firefly's asset accounts, e.g. the `{counterparty}` template a
     * copied rule still carries after its kind was switched. Only once the account list is known;
     * without a server there is nothing to compare with. */
    private fun updateAssetAccountHints() {
        val binding = binding ?: return
        val names = assetAccounts?.map { it.name }?.toSet() ?: return
        listOf(binding.sourceAccountLayout to binding.sourceAccountInput, binding.destinationAccountLayout to binding.destinationAccountInput)
            .forEach { (layout, input) ->
                val text = input.text?.toString().orEmpty().trim()
                val unknown = input in assetSideInputs && text.isNotEmpty() && text !in names
                layout.helperText = if (unknown) getString(R.string.rule_asset_account_unknown) else null
            }
    }

    /** A soft note under the category template: learning is on, the rule sets no category itself, but
     * its pattern has no `counterparty` group, so nothing is learned from it (and no learned category
     * is applied; typed-in "contains" entries still match the notification text). */
    private fun updateCounterpartyHint() {
        val binding = binding ?: return
        val missing = AutoCategoriesPrefs(requireContext()).isLearnEnabled() &&
            binding.categoryTemplateInput.text.isNullOrBlank() &&
            !binding.patternInput.text.toString().contains("(?<${Groups.COUNTERPARTY}>")
        binding.categoryTemplateLayout.helperText = if (missing) getString(R.string.rule_no_counterparty_group_hint) else null
    }

    /** Best-effort and silent: the category and tags template fields offer Firefly's existing names
     * while typing (the shared [FireflySuggestions] cache), and the asset-side account fields offer
     * Firefly's asset accounts. A template may also hold `{macros}`, so the lists are only a
     * convenience; without a server the fields stay plain text. */
    private fun loadSuggestions() {
        val prefs = FireflyPrefs(requireContext())
        if (!prefs.isConfigured()) return
        // Independent requests: each field gets its list as soon as it arrives.
        viewLifecycleOwner.lifecycleScope.launch {
            (FireflySuggestions.assetAccounts(prefs, forceRefresh = true) as? FireflyApiClient.Result.Success)?.value?.let {
                assetAccounts = it
                applyAccountSuggestions()
                updateAssetAccountHints()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val categories = (FireflySuggestions.categories(prefs, forceRefresh = true) as? FireflyApiClient.Result.Success)?.value ?: return@launch
            binding?.categoryTemplateInput?.setAdapter(SuggestionAdapter(requireContext(), categories.map { it.name }))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = (FireflySuggestions.tags(prefs, forceRefresh = true) as? FireflyApiClient.Result.Success)?.value ?: return@launch
            binding?.tagsTemplateInput?.setSuggestions(tags)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        rulesDb = RulesDb.getInstance(requireContext())

        setupKindDropdown()
        setupAppsDropdown()
        loadSuggestions()

        val ruleId = arguments?.getString(ARG_RULE_ID)
        existingRule = ruleId?.let { id -> rulesDb.loadAllOrdered().firstOrNull { it.id == id } }
        populateFields(existingRule)
        initialDraft = buildDraft()
        (activity as? SettingsActivity)?.setBackGuard { handleBackPressed() }

        binding.patternInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateAvailableMacros()
                updateCounterpartyHint()
            }
        })
        binding.categoryTemplateInput.doAfterTextChanged { updateCounterpartyHint() }
        updateCounterpartyHint()

        binding.pickMessageButton.setOnClickListener { showMessagePicker() }
        binding.patternLayout.setEndIconOnClickListener { showPatternPicker() }
        binding.testAgainstHistoryButton.setOnClickListener { testAgainstHistory() }
        addSaveMenu(onDelete = if (existingRule != null) ::confirmDelete else null, onSave = ::save)
        binding.homeCurrencyLayout.setEndIconOnClickListener { showCurrencyPicker() }
        // The same list the picker above offers: the user's own code<->symbol table.
        val homeCodes = CurrenciesDb.getInstance(requireContext()).loadAll().map { it.code }
        if (homeCodes.isNotEmpty()) binding.homeCurrencyInput.setAdapter(SuggestionAdapter(requireContext(), homeCodes))
        binding.dateFormatLayout.setEndIconOnClickListener { showDateFormatPicker() }

        setupMacroInsertButtons()
        updateAvailableMacros()
        updateAccountFieldModes()
        binding.sourceAccountInput.doAfterTextChanged { updateAssetAccountHints() }
        binding.destinationAccountInput.doAfterTextChanged { updateAssetAccountHints() }
    }

    /** Picks from the user-maintained code<->symbol table (Settings -> Currencies) instead of
     * hand-typing a code and risking a typo `normalizeCurrency` would never recognize. */
    private fun showCurrencyPicker() {
        val context = requireContext()
        val entries = CurrenciesDb.getInstance(context).loadAll()
        if (entries.isEmpty()) return
        val labels = entries.map { "${it.code} (${it.symbol})" }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_pick_currency)
            .setItems(labels) { _, which -> binding?.homeCurrencyInput?.setText(entries[which].code) }
            .show()
    }

    /** Presets for [ParseRule.dateFormat] - fills the field, same as [showCurrencyPicker], but the
     * text stays freely editable afterward (a bank's actual wording won't always match one of
     * these exactly, e.g. a format with a literal weekday name mixed in). Covers the common
     * day-first/ISO/US shapes; see [ParseRule.dateFormat]'s KDoc for why there's no attempt to
     * detect the right one automatically. */
    private fun showDateFormatPicker() {
        val context = requireContext()
        val presets = listOf("dd.MM.yy", "dd.MM.yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "MM/dd/yyyy")
        AlertDialog.Builder(context)
            .setTitle(R.string.rule_pick_date_format)
            .setItems(presets.toTypedArray()) { _, which -> binding?.dateFormatInput?.setText(presets[which]) }
            .show()
    }

    /**
     * The always-macro template fields (description/notes/category/tags - never the asset side
     * of an account pair, see [updateAccountFieldModes]) get a custom end icon that inserts a
     * `{name}` macro at the cursor, instead of requiring it to be typed out by hand. Deliberately
     * an insert at the current selection, not a `setText` - the field may already have other text
     * around where the macro goes. Not home currency - that's a plain comparison value, not a
     * template, and has its own end icon ([showCurrencyPicker]) that `setText`s the whole field
     * instead.
     */
    private fun setupMacroInsertButtons() {
        val binding = binding ?: return
        val targets = listOf(
            binding.descriptionTemplateLayout to binding.descriptionTemplateInput,
            binding.notesTemplateLayout to binding.notesTemplateInput,
            binding.categoryTemplateLayout to binding.categoryTemplateInput,
            binding.tagsTemplateLayout to binding.tagsTemplateInput,
        )
        targets.forEach { (layout, input) ->
            layout.setEndIconOnClickListener { showMacroPicker(input) }
        }
    }

    /**
     * Source/destination each switch between two different end-icon behaviors depending on
     * [selectedKind] - which side is the *asset* account Firefly requires to already exist
     * (picked from [showAccountPicker], `setText`s the whole field) versus the *counterparty*
     * side Firefly auto-creates by name (stays a `{macro}` template, [showMacroPicker], same as
     * description/notes/etc): WITHDRAWAL source=asset/destination=counterparty, DEPOSIT
     * source=counterparty/destination=asset, TRANSFER both=asset (Firefly requires an existing
     * asset account on both ends of a transfer - see TODO.md/ARCHITECTURE.md for the API
     * behavior this is built on).
     */
    private fun updateAccountFieldModes() {
        val binding = binding ?: return
        val (sourceIsAsset, destinationIsAsset) = when (selectedKind) {
            RuleKind.WITHDRAWAL -> true to false
            RuleKind.DEPOSIT -> false to true
            RuleKind.TRANSFER -> true to true
        }
        setAccountFieldMode(binding.sourceAccountLayout, binding.sourceAccountInput, sourceIsAsset)
        setAccountFieldMode(binding.destinationAccountLayout, binding.destinationAccountInput, destinationIsAsset)
    }

    private fun setAccountFieldMode(layout: TextInputLayout, input: EditText, isAsset: Boolean) {
        val context = requireContext()
        if (isAsset) assetSideInputs += input else assetSideInputs -= input
        // The label says which role the side plays *now*, so a kind switch is visible right at the
        // field (a copied Withdrawal rule turned into a Transfer used to give no sign that the
        // destination had become an asset account).
        layout.hint = getString(
            when {
                layout === binding?.sourceAccountLayout && isAsset -> R.string.rule_field_source_asset_account
                layout === binding?.sourceAccountLayout -> R.string.rule_field_source_account
                isAsset -> R.string.rule_field_destination_asset_account
                else -> R.string.rule_field_destination_account
            },
        )
        applyAccountSuggestions()
        updateAssetAccountHints()
        if (isAsset) {
            layout.endIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_dropdown)
            layout.endIconContentDescription = getString(R.string.rule_pick_account)
            layout.setEndIconOnClickListener { showAccountPicker(input) }
        } else {
            layout.endIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_insert_macro)
            layout.endIconContentDescription = getString(R.string.rule_insert_macro)
            layout.setEndIconOnClickListener { showMacroPicker(input) }
        }
    }

    /** Picks from Firefly's real asset accounts ([FireflyApiClient.listAssetAccounts]) instead of
     * free-typing a name that has to match one exactly - Firefly only auto-creates the
     * *counterparty* side of a transaction by name, this side must already exist (see
     * [updateAccountFieldModes]). Falls back to a Toast, not a blocked field, when Firefly isn't
     * configured yet - the rule can still be drafted and fixed up once it is. Only ever fills in
     * the account's *name* ([ParseRule] doesn't carry a Firefly account id at all, see its own
     * KDoc for the real cross-rule dedup bug caching one caused) - this is purely about avoiding a
     * typo against the real account list, not about posting precision. */
    private fun showAccountPicker(target: EditText) {
        val context = requireContext()
        val prefs = FireflyPrefs(context)
        if (!prefs.isConfigured()) {
            Toast.makeText(context, R.string.rule_firefly_not_configured, Toast.LENGTH_LONG).show()
            return
        }

        val cached = assetAccounts
        if (cached != null) {
            showAccountPickerDialog(target, cached)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = prefs.apiClient().listAssetAccounts()) {
                is FireflyApiClient.Result.Success -> {
                    assetAccounts = result.value
                    updateAssetAccountHints()
                    showAccountPickerDialog(target, result.value)
                }
                is FireflyApiClient.Result.Failure ->
                    Toast.makeText(
                        context,
                        getString(R.string.rule_firefly_accounts_failed, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    private fun showAccountPickerDialog(target: EditText, accounts: List<FireflyAccount>) {
        if (accounts.isEmpty()) {
            Toast.makeText(requireContext(), R.string.rule_firefly_no_accounts, Toast.LENGTH_LONG).show()
            return
        }
        // setSingleChoiceItems (radio-marked list), not setItems - pre-selects whichever account
        // already matches the field's current text, so reopening the picker shows at a glance
        // what's already there instead of a plain, unmarked list every time. Still applies and
        // dismisses on a single tap, same as setItems did - not a separate "confirm" step.
        val currentName = target.text?.toString()?.trim()
        val selectedIndex = accounts.indexOfFirst { it.name == currentName }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rule_pick_account)
            .setSingleChoiceItems(accounts.map { it.name }.toTypedArray(), selectedIndex) { dialog, which ->
                target.setText(accounts[which].name)
                dialog.dismiss()
            }
            .show()
    }

    /**
     * One list mixing two kinds of insertable text: real `{macro}` tokens (declared groups plus
     * [FIXED_MACROS]), and the current app's name as a plain literal string - not a macro, since
     * a rule is always scoped to exactly one app (no `{appname}` macro exists; see
     * `MacroTemplate.kt`'s KDoc for why that was removed).
     */
    private fun showMacroPicker(target: EditText) {
        val context = requireContext()
        val pattern = binding?.patternInput?.text?.toString().orEmpty()
        val groups = runCatching { namedGroupNames(pattern) }.getOrDefault(emptyList())
        val macroEntries = (groups + FIXED_MACROS).distinct().sortedBy { it.lowercase() }.map { "{$it}" to "{$it}" }
        val appLabel = monitoredApps.firstOrNull { it.first == selectedPackageName }?.second
        val appEntry = appLabel?.let { listOf(it to it) }.orEmpty()
        val entries = macroEntries + appEntry
        if (entries.isEmpty()) return

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_insert_macro)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> insertTextAtCursor(target, entries[which].second) }
            .show()
    }

    private fun insertTextAtCursor(target: EditText, text: String) {
        val start = target.selectionStart.coerceAtLeast(0)
        val end = target.selectionEnd.coerceAtLeast(0)
        val insertAt = minOf(start, end)
        target.text?.replace(insertAt, maxOf(start, end), text)
        // The end icon click (and the picker dialog it opened) moved focus away from the field,
        // so the cursor needs to be put back explicitly - otherwise it's left wherever it was
        // (or nowhere), not right after what was just inserted.
        target.requestFocus()
        target.setSelection(insertAt + text.length)
    }

    private fun setupKindDropdown() {
        val binding = binding ?: return
        binding.kindInput.setSimpleItems(kindOrder.map { getString(it.labelRes()) }.toTypedArray())
        binding.kindInput.setOnItemClickListener { _, _, position, _ ->
            selectedKind = kindOrder[position]
            updateAccountFieldModes()
        }
    }

    private fun setupAppsDropdown() {
        val binding = binding ?: return
        val context = requireContext()
        monitoredApps = MonitoredAppsPrefs(context).getMonitoredPackages()
            .map { it to AppLabelResolver.resolve(context, it) }
            .sortedBy { it.second.lowercase() }

        if (monitoredApps.isEmpty()) {
            binding.appInput.isEnabled = false
            Toast.makeText(context, R.string.rule_no_monitored_apps, Toast.LENGTH_LONG).show()
            return
        }

        binding.appInput.setSimpleItems(monitoredApps.map { it.second }.toTypedArray())
        binding.appInput.setOnItemClickListener { _, _, position, _ ->
            selectedPackageName = monitoredApps[position].first
        }
    }

    private fun populateFields(rule: ParseRule?) {
        val binding = binding ?: return

        binding.labelInput.setText(
            rule?.label ?: getString(R.string.rule_default_label, rulesDb.loadAllOrdered().size + 1),
        )

        selectedPackageName = rule?.packageNames?.firstOrNull() ?: monitoredApps.firstOrNull()?.first
        monitoredApps.firstOrNull { it.first == selectedPackageName }?.let { binding.appInput.setText(it.second, false) }

        selectedKind = rule?.kind ?: RuleKind.WITHDRAWAL
        binding.kindInput.setText(getString(selectedKind.labelRes()), false)

        binding.sourceAccountInput.setText(rule?.sourceAccountName.orEmpty())
        binding.destinationAccountInput.setText(rule?.destinationAccountName.orEmpty())
        binding.homeCurrencyInput.setText(rule?.homeCurrency.orEmpty())
        binding.dateFormatInput.setText(rule?.dateFormat.orEmpty())
        binding.patternInput.setText(rule?.pattern.orEmpty())
        binding.descriptionTemplateInput.setText(rule?.descriptionTemplate.orEmpty())
        binding.notesTemplateInput.setText(rule?.notesTemplate.orEmpty())
        binding.categoryTemplateInput.setText(rule?.categoryTemplate.orEmpty())
        binding.tagsTemplateInput.setText(rule?.tagsTemplate.orEmpty())
        binding.enabledSwitch.isChecked = rule?.enabled ?: true
        binding.accountApproximateSwitch.isChecked = rule?.accountApproximate ?: false
        binding.amountManualSwitch.isChecked = rule?.amountManual ?: false
    }

    private fun updateAvailableMacros() {
        val binding = binding ?: return
        val pattern = binding.patternInput.text?.toString().orEmpty()
        val groups = runCatching { namedGroupNames(pattern) }.getOrDefault(emptyList())
        val macros = (groups + FIXED_MACROS).distinct().sortedBy { it.lowercase() }.joinToString(" ") { "{$it}" }
        binding.availableMacrosText.text =
            getString(R.string.rule_available_macros, macros) + "\n" + getString(R.string.rule_escape_hint)
    }

    /**
     * Lets the user pick a real captured message (filtered to the app currently selected above -
     * a rule can only ever match that app's notifications anyway) and drops its combined
     * "title\nbody" into the pattern field as a literal starting point, instead of having to
     * retype or hand-copy the bank's wording from the Messages screen. In-memory only
     * (`MessageStore.shared`), so this only offers whatever's been captured so far this session.
     */
    private fun showMessagePicker() {
        val context = requireContext()
        val packageName = selectedPackageName
        if (packageName == null) {
            Toast.makeText(context, R.string.rule_no_monitored_apps, Toast.LENGTH_SHORT).show()
            return
        }
        val messages = MessageStore.shared.snapshot().filter { it.packageName == packageName }
        if (messages.isEmpty()) {
            Toast.makeText(context, R.string.rule_no_captured_messages, Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = PickerItemAdapter(context, messages.map { it.title to it.text }, monospaceBody = false)

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_pick_message)
            .setAdapter(adapter) { _, which -> setPatternFrom(messages[which]) }
            .show()
    }

    private fun setPatternFrom(message: CapturedMessage) {
        binding?.patternInput?.setText("${message.title}\n${message.text}")
    }

    /**
     * Runs the rule as currently drafted (unsaved edits included - deliberately not gated on
     * `validateRule`, since fields irrelevant to matching like a blank label shouldn't block a
     * pattern sanity check) against this session's captured messages for the selected app,
     * newest first, stopping once [MAX_TEST_MATCHES] matches are found - just enough for a
     * representative sample without scanning a long history for nothing.
     */
    private fun testAgainstHistory() {
        val context = requireContext()
        val packageName = selectedPackageName
        if (packageName == null) {
            Toast.makeText(context, R.string.rule_no_monitored_apps, Toast.LENGTH_SHORT).show()
            return
        }
        val messages = MessageStore.shared.snapshot().filter { it.packageName == packageName }
        if (messages.isEmpty()) {
            Toast.makeText(context, R.string.rule_no_captured_messages, Toast.LENGTH_SHORT).show()
            return
        }

        val draftRule = buildDraft()
        val declaredGroups = namedGroupNames(draftRule.pattern)
        val engine = RuleEngine(listOf(draftRule), CurrenciesDb.getInstance(context).symbolToCodeMap())
        val matches = mutableListOf<Pair<CapturedMessage, Map<String, String>>>()
        var scanned = 0
        for (message in messages) {
            scanned++
            val outcome = engine.match(message)
            if (outcome is ParseOutcome.Matched) matches.add(message to outcome.groups)
            if (matches.size >= MAX_TEST_MATCHES) break
        }

        // Shows what the pattern actually captured for each match - amount/account/etc are
        // derived from rule config that may not be filled in yet, which isn't what "does my
        // regex work" is asking. Same bordered-card rows as the message/pattern pickers (title
        // bold, captured groups normal weight below it) instead of one long message string with
        // "──────────" text separators between matches.
        if (matches.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(R.string.rule_test_title)
                .setMessage(getString(R.string.rule_test_no_matches, scanned))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        // AlertDialog.Builder's setMessage + setAdapter don't actually combine - the adapter's
        // list silently doesn't render once a message is also set (found the hard way: v0.26
        // shipped with the summary line showing and zero match rows below it). Built as a plain
        // custom view instead - a summary TextView, then one inflated item_picker_two_line row
        // per match - since MAX_TEST_MATCHES keeps the list short enough that ListView's
        // recycling has no value here anyway.
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_test_results, null, false)
        val container = view.findViewById<ViewGroup>(R.id.testResultsContainer)
        container.addView(
            TextView(context).apply { text = getString(R.string.rule_test_matches_found, matches.size, scanned) },
        )
        matches.forEach { (message, groups) ->
            val capturedGroups = declaredGroups.mapNotNull { name -> groups[name]?.let { "$name: $it" } }
            val row = LayoutInflater.from(context).inflate(R.layout.item_picker_two_line, container, false)
            bindPickerItemRow(row, message.title, capturedGroups.joinToString("\n"), monospaceBody = false)
            container.addView(row)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_test_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showPatternPicker() {
        val context = requireContext()
        val saved = rulesDb.distinctPatterns()
        // The illustrative examples are always offered too, not just when nothing's saved yet -
        // they're a handy syntax reminder regardless of how many real rules already exist.
        // distinctBy guards against listing one twice if a saved regex happens to match it
        // verbatim.
        val examples = listOf(
            EXAMPLE_PATTERN to getString(R.string.rule_pattern_example_label),
            EXAMPLE_PATTERN_TITLE_ANCHORED to getString(R.string.rule_pattern_example_label_title_anchored),
            EXAMPLE_PATTERN_DATE_OVERRIDE to getString(R.string.rule_pattern_example_label_date_override),
        )
        val regexes = (saved + examples).distinctBy { it.first }
        // Full regex shown right here in the list, label above it - not truncated to one line,
        // since a cut-off regex is too easy to misread (and previously looked like a rendering
        // glitch). Seeing it in full here, before tapping, is the "shown before applying" step -
        // no separate confirm dialog needed on top of that. Monospace matches the pattern field
        // itself, same reasoning as that field's own font choice.
        val adapter = PickerItemAdapter(
            context,
            regexes.map { (regex, label) -> label to regex },
            monospaceBody = true,
        )

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_pick_existing_pattern)
            .setAdapter(adapter) { _, which -> binding?.patternInput?.setText(regexes[which].first) }
            .show()
    }

    private fun buildDraft(): ParseRule {
        val binding = requireBinding()
        val sourceAccountName = binding.sourceAccountInput.text?.toString().orEmpty().trim()
        val destinationAccountName = binding.destinationAccountInput.text?.toString().orEmpty().trim()
        return ParseRule(
            id = existingRule?.id ?: "",
            label = binding.labelInput.text?.toString().orEmpty().trim(),
            packageNames = selectedPackageName?.let { setOf(it) } ?: emptySet(),
            kind = selectedKind,
            sourceAccountName = sourceAccountName,
            destinationAccountName = destinationAccountName,
            homeCurrency = binding.homeCurrencyInput.text?.toString().orEmpty().trim(),
            // Not .trim()'d: the Android keyboard's autocorrect routinely leaves a trailing
            // space behind, and leading/trailing whitespace in a *regex* is syntax, not
            // formatting - trimming it could silently change what the pattern matches (e.g. a
            // pattern deliberately ending " $" to require a trailing space before end-of-input).
            pattern = binding.patternInput.text?.toString().orEmpty(),
            descriptionTemplate = binding.descriptionTemplateInput.text?.toString().orEmpty().trim(),
            notesTemplate = binding.notesTemplateInput.text?.toString().orEmpty().trim(),
            categoryTemplate = binding.categoryTemplateInput.text?.toString().orEmpty().trim(),
            tagsTemplate = normalizeTagList(binding.tagsTemplateInput.text?.toString().orEmpty()),
            enabled = binding.enabledSwitch.isChecked,
            dateFormat = binding.dateFormatInput.text?.toString().orEmpty().trim(),
            accountApproximate = binding.accountApproximateSwitch.isChecked,
            amountManual = binding.amountManualSwitch.isChecked,
        )
    }

    private fun save() {
        val draft = buildDraft()
        val errors = validateRule(draft)
        if (errors.isNotEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.rule_validation_failed)
                .setMessage(errors.joinToString("\n") { "• $it" })
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        if (existingRule != null) {
            rulesDb.update(draft)
        } else {
            rulesDb.add(draft)
        }
        parentFragmentManager.popBackStack()
    }

    private fun confirmDelete() {
        val id = existingRule?.id ?: return
        // Cancel always rightmost/last (house convention, AGENTS.md).
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rule_delete_confirm_title)
            .setNegativeButton(R.string.action_delete) { _, _ ->
                rulesDb.delete(id)
                parentFragmentManager.popBackStack()
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    /** True once [buildDraft] differs from [initialDraft] in any field - covers every editable
     * field in one comparison (`ParseRule` is a data class) instead of tracking each input's
     * dirty state separately. */
    private fun hasUnsavedChanges(): Boolean = initialDraft != buildDraft()

    private fun handleBackPressed() {
        if (hasUnsavedChanges()) {
            confirmDiscardChanges(requireContext()) { parentFragmentManager.popBackStack() }
        } else {
            parentFragmentManager.popBackStack()
        }
    }

    private fun requireBinding() = binding ?: error("binding accessed after onDestroyView")

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? SettingsActivity)?.setBackGuard(null)
        binding = null
    }

    companion object {
        private const val ARG_RULE_ID = "ruleId"
        private const val MAX_TEST_MATCHES = 3

        // Always offered in the pattern picker, alongside any saved patterns - just enough to
        // demonstrate the named-group syntax, not meant to match anything real.
        private const val EXAMPLE_PATTERN =
            """(?<amount>\d[\d.,]*)\s*(?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥]).*?(?:at|bei)\s+(?<counterparty>.+?)\.?$"""

        // Second example, anchored to a literal title instead of starting at the amount - real
        // case that caught a classic regex pitfall: a *greedy* `.*` before the amount group lets
        // the engine settle for matching just the last digit of a multi-digit number (whatever
        // satisfies the rest of the pattern first), instead of the full number. `[^\d]*` here
        // only skips non-digits, so it can't overshoot past the number it's looking for. The
        // trailing `(?:gezahlt)?` is why counterparty doesn't end up swallowing the sentence's
        // closing verb along with the merchant name.
        private const val EXAMPLE_PATTERN_TITLE_ANCHORED =
            """Zahlung erfolgreich[^\d]*(?<amount>\d[\d.,]*)\s*(?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥]).*?(?:an)\s+(?<counterparty>.+?)\s*(?:gezahlt)?\.?$"""

        // Third example - captures `date` too, for the real case that prompted ParseRule.dateFormat:
        // a bank (Bank DE) announcing a *future* charge date in the body, separately from when the
        // notification itself arrived ("Die Abbuchung über €99,99 für 00000 findet am 01.02.30
        // statt."). Paired with a "dd.MM.yy" dateFormat, this is the pattern that actually uses it.
        private const val EXAMPLE_PATTERN_DATE_OVERRIDE =
            """Die Abbuchung über\s*(?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥])(?<amount>\d[\d.,]*).*?(?:findet am)\s+(?<date>\d{2}\.\d{2}\.\d{2})\s*(?:statt)"""

        fun newInstance(ruleId: String?): RuleEditFragment = RuleEditFragment().apply {
            arguments = Bundle().apply { putString(ARG_RULE_ID, ruleId) }
        }
    }
}

/**
 * Shared two-line, bordered row for both picker dialogs (existing regex / captured message) -
 * `item_picker_two_line.xml`'s bold header + normal-or-monospace body, instead of plain
 * `setItems` text that gave no visual separation between a row's two lines or between rows.
 */
private class PickerItemAdapter(
    context: Context,
    private val items: List<Pair<String, String>>,
    private val monospaceBody: Boolean,
) : ArrayAdapter<Pair<String, String>>(context, 0, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_picker_two_line, parent, false)
        val (header, body) = items[position]
        bindPickerItemRow(view, header, body, monospaceBody)
        return view
    }
}

/** Shared by [PickerItemAdapter] and the "Test results" dialog's static rows. */
private fun bindPickerItemRow(view: View, header: String, body: String, monospaceBody: Boolean) {
    view.findViewById<TextView>(R.id.pickerItemHeader).text = header
    view.findViewById<TextView>(R.id.pickerItemBody).apply {
        text = body
        typeface = if (monospaceBody) Typeface.MONOSPACE else Typeface.DEFAULT
    }
}
