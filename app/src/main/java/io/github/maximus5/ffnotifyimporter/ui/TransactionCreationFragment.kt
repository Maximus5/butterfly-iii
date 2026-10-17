package io.github.maximus5.ffnotifyimporter.ui

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentTransactionCreationBinding
import io.github.maximus5.ffnotifyimporter.network.FireflyAccount
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.posting.FireflySuggestions
import io.github.maximus5.ffnotifyimporter.network.FireflyCurrency
import io.github.maximus5.ffnotifyimporter.posting.isNotificationPermissionGranted
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import kotlinx.coroutines.launch

/**
 * Everything [FireflyPrefs] holds *except* the base URL/token (see [FireflyConnectionFragment]'s
 * own KDoc) - split out together, the user's own call, prompted by an AI-Review finding about the
 * token field's reveal icon: none of these fields are secret, so there's no reason to carry them
 * behind the same `FLAG_SECURE`/write-once protection that screen now has.
 *
 * [FragmentTransactionCreationBinding.allTransactionsTagInput] ("Add tags for all transactions
 * (comma separated)") - the user's own request: one or more tags merged into *every* created
 * transaction's tags, regardless of which rule matched, so tags wanted everywhere don't have to be
 * repeated in every single rule's own tags template (unlike
 * [FragmentTransactionCreationBinding.approxTagInput], which only ever applies to an amount this
 * app itself computed from a fetched exchange rate). The actual merge happens in `rules.RuleEngine`
 * (a plain `String` constructor parameter - see its own KDoc for why that keeps `rules` free of any
 * `FireflyPrefs`/`Context` dependency), read fresh from `FireflyPrefs.getAllTransactionsTags()` by
 * every caller that builds a `RuleEngine` - this screen's own field is placed after both dividers,
 * grouped with [approxTagInput] (moved here from the top of the screen) rather than with
 * auto-create/dedup above it - the user's own layout call. Both tag fields' explanatory text is a
 * plain caption *below* the field now, not the `TextInputEditText`'s own inline `android:hint` the
 * way other fields on this screen still use - the user's own catch: a long inline hint made the
 * field itself taller, pushing content down for no benefit over a caption that only takes space
 * when it's actually shown.
 *
 * [FragmentTransactionCreationBinding.defaultAccountCurrencyInput] ("Default account currency") -
 * the user's own request: `rules.RuleEngine`'s fallback for a rule that leaves its own
 * `homeCurrency` blank (see `FireflyPrefs.getDefaultAccountCurrency`'s own KDoc for why - most
 * notably `rules.BuiltInRules`'s generic fallback rules, which have no one account to inherit a
 * currency from). [showCurrencyPicker] sources its list from the currencies actually *enabled* in
 * the user's own Firefly instance (`FireflyApiClient.listCurrencies`), not this app's own locally-
 * maintained `rules.CurrenciesDb` table `RuleEditFragment`'s own currency picker reads - a
 * deliberately different data source for a deliberately different question.
 *
 * [FragmentTransactionCreationBinding.defaultSourceAccountInput] ("Default Source account") - see
 * `FireflyPrefs.getDefaultSourceAccount`'s own KDoc for the full reasoning (onboarding, and why
 * it's deliberately display-only, never read by `rules`/`posting`). [showAccountPicker] sources
 * from `FireflyApiClient.listAssetAccounts()`, same shape `CreateTransactionFragment`'s own
 * account picker already uses. */
class TransactionCreationFragment : Fragment() {

    private var binding: FragmentTransactionCreationBinding? = null
    private lateinit var prefs: FireflyPrefs

    /** A snapshot of [buildSnapshot] taken right after the fields are first populated - the
     * baseline [hasUnsavedChanges] compares against, same "leaving with unsaved edits asks first"
     * shape `RuleEditFragment.initialDraft`/`hasUnsavedChanges` already uses. Re-baselined at the
     * end of a successful [save] too, so a back press right after saving doesn't ask about
     * changes that are already persisted. */
    private var initialSnapshot: SettingsSnapshot? = null

    /** Every field on this screen, for change-detection only - not persisted itself, [save]
     * writes straight to [FireflyPrefs] as before. */
    private data class SettingsSnapshot(
        val autoCreate: Boolean,
        val dedupEnabled: Boolean,
        val dedupIncludeDescription: Boolean,
        val dedupWindow: String,
        val defaultAccountCurrency: String,
        val defaultSourceAccount: String,
        val allTransactionsTags: String,
        val approxTag: String,
    )

    /** Fetched once per screen session on first use, same reasoning as `RuleEditFragment.
     * assetAccounts`/`CreateTransactionFragment.assetAccounts` - a personal Firefly instance's
     * enabled-currency set changes rarely enough that a stale cache for the rest of this one
     * screen is an acceptable trade for not hitting the network on every picker tap. */
    private var enabledCurrencies: List<FireflyCurrency>? = null
    private var assetAccounts: List<FireflyAccount>? = null

    /** Requested the moment the user turns auto-create on - the explanation for it lives on the
     * Notifications screen ([NotificationSettingsFragment]), which is also where the permission is
     * otherwise requested. Declining doesn't block the switch itself - auto-create still works
     * without it, the user just won't see `posting.AutoCreateNotifications`' outcome notifications. */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentTransactionCreationBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    /** Best-effort and silent: the two tag fields offer Firefly's existing tags, the default
     * account its asset accounts and the default currency its enabled currencies while typing (the
     * shared [FireflySuggestions] cache); without a server they stay plain text fields. */
    private fun loadSuggestions() {
        if (!prefs.isConfigured()) return
        // Independent requests: each field gets its list as soon as it arrives.
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = (FireflySuggestions.tags(prefs, forceRefresh = true) as? FireflyApiClient.Result.Success)?.value ?: return@launch
            val binding = binding ?: return@launch
            binding.allTransactionsTagInput.setSuggestions(tags)
            binding.approxTagInput.setSuggestions(tags)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val accounts = (FireflySuggestions.assetAccounts(prefs, forceRefresh = true) as? FireflyApiClient.Result.Success)?.value ?: return@launch
            binding?.defaultSourceAccountInput?.setAdapter(SuggestionAdapter(requireContext(), accounts.map { it.name }))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val currencies = (FireflySuggestions.enabledCurrencies(prefs) as? FireflyApiClient.Result.Success)?.value ?: return@launch
            binding?.defaultAccountCurrencyInput?.setAdapter(SuggestionAdapter(requireContext(), currencies.map { it.code }))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        prefs = FireflyPrefs(requireContext())

        binding.autoCreateSwitch.isChecked = prefs.isAutoCreateEnabled()
        binding.dedupEnabledSwitch.isChecked = prefs.isDedupEnabled()
        binding.dedupIncludeDescriptionSwitch.isChecked = prefs.isDedupIncludeDescription()
        binding.dedupWindowInput.setText(prefs.getDedupWindowMinutes().toString())
        binding.defaultAccountCurrencyInput.setText(prefs.getDefaultAccountCurrency())
        binding.defaultSourceAccountInput.setText(prefs.getDefaultSourceAccount())
        binding.allTransactionsTagInput.setText(prefs.getAllTransactionsTags())
        binding.approxTagInput.setText(prefs.getApproxConversionTag())
        loadSuggestions()

        binding.defaultAccountCurrencyLayout.setEndIconOnClickListener { showCurrencyPicker(binding.defaultAccountCurrencyInput) }
        binding.defaultSourceAccountLayout.setEndIconOnClickListener { showAccountPicker(binding.defaultSourceAccountInput) }

        binding.dedupWindowInput.doAfterTextChanged { binding.dedupWindowLayout.error = null }

        binding.autoCreateSwitch.setOnCheckedChangeListener { _, isChecked -> if (isChecked) requestNotificationPermissionIfNeeded() }

        initialSnapshot = buildSnapshot()
        (activity as? SettingsActivity)?.setBackGuard { handleBackPressed() }

        addSaveMenu(onSave = ::save)
    }

    private fun buildSnapshot(): SettingsSnapshot? {
        val binding = binding ?: return null
        return SettingsSnapshot(
            autoCreate = binding.autoCreateSwitch.isChecked,
            dedupEnabled = binding.dedupEnabledSwitch.isChecked,
            dedupIncludeDescription = binding.dedupIncludeDescriptionSwitch.isChecked,
            dedupWindow = binding.dedupWindowInput.text?.toString()?.trim().orEmpty(),
            defaultAccountCurrency = binding.defaultAccountCurrencyInput.text?.toString()?.trim().orEmpty(),
            defaultSourceAccount = binding.defaultSourceAccountInput.text?.toString()?.trim().orEmpty(),
            allTransactionsTags = normalizeTagList(binding.allTransactionsTagInput.text?.toString().orEmpty()),
            approxTag = normalizeTagList(binding.approxTagInput.text?.toString().orEmpty()),
        )
    }

    private fun hasUnsavedChanges(): Boolean = buildSnapshot() != initialSnapshot

    private fun handleBackPressed() {
        if (hasUnsavedChanges()) {
            confirmDiscardChanges(requireContext()) { (activity as? SettingsActivity)?.leaveScreen() }
        } else {
            (activity as? SettingsActivity)?.leaveScreen()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (isNotificationPermissionGranted(requireContext())) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Picks from the currencies actually *enabled* in the user's own Firefly instance (`GET
     * /v1/currencies`, client-side filtered - Firefly's own endpoint has no server-side "enabled
     * only" filter, see `FireflyApiClient.listCurrencies`'s own KDoc) - the user's own request, so
     * this doesn't need typing a code by hand and risking a typo. Deliberately a *different* data
     * source from `RuleEditFragment`'s own currency picker, which reads this app's locally-
     * maintained `rules.CurrenciesDb` table instead - that one's a user-maintained symbol<->code
     * lookup for typing a rule's `homeCurrency`, this one's "what does my Firefly actually have
     * switched on right now". The field stays freely typeable either way (not forced into
     * picker-only), same as every other Firefly-sourced picker in this app. */
    private fun showCurrencyPicker(target: EditText) {
        val context = requireContext()
        val prefs = FireflyPrefs(context)
        if (!prefs.isConfigured()) {
            Toast.makeText(context, R.string.rule_firefly_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        val cached = enabledCurrencies
        if (cached != null) {
            showCurrencyPickerDialog(target, cached)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = prefs.apiClient().listCurrencies()) {
                is FireflyApiClient.Result.Success -> {
                    val enabled = result.value.filter { it.enabled }
                    enabledCurrencies = enabled
                    showCurrencyPickerDialog(target, enabled)
                }
                is FireflyApiClient.Result.Failure ->
                    Toast.makeText(context, getString(R.string.firefly_pick_currency_failed, result.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCurrencyPickerDialog(target: EditText, currencies: List<FireflyCurrency>) {
        if (currencies.isEmpty()) {
            Toast.makeText(requireContext(), R.string.firefly_no_currencies, Toast.LENGTH_LONG).show()
            return
        }
        val currentCode = target.text?.toString()?.trim()
        val selectedIndex = currencies.indexOfFirst { it.code == currentCode }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rule_pick_currency)
            .setSingleChoiceItems(currencies.map { it.code }.toTypedArray(), selectedIndex) { dialog, which ->
                target.setText(currencies[which].code)
                dialog.dismiss()
            }
            .show()
    }

    /** Same `FireflyApiClient.listAssetAccounts()`-backed picker `CreateTransactionFragment`'s own
     * account fields already use - the field stays freely typeable either way, same as every other
     * Firefly-sourced picker in this app. */
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
                    showAccountPickerDialog(target, result.value)
                }
                is FireflyApiClient.Result.Failure ->
                    Toast.makeText(context, getString(R.string.rule_firefly_accounts_failed, result.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAccountPickerDialog(target: EditText, accounts: List<FireflyAccount>) {
        if (accounts.isEmpty()) {
            Toast.makeText(requireContext(), R.string.rule_firefly_no_accounts, Toast.LENGTH_LONG).show()
            return
        }
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

    private fun save() {
        val binding = binding ?: return
        val dedupWindow = binding.dedupWindowInput.text?.toString()?.trim()?.toIntOrNull()

        if (dedupWindow == null || dedupWindow <= 0) {
            binding.dedupWindowLayout.error = getString(R.string.firefly_dedup_window_invalid)
            return
        }

        prefs.setAutoCreateEnabled(binding.autoCreateSwitch.isChecked)
        prefs.setDedupEnabled(binding.dedupEnabledSwitch.isChecked)
        prefs.setDedupIncludeDescription(binding.dedupIncludeDescriptionSwitch.isChecked)
        prefs.setDedupWindowMinutes(dedupWindow)
        prefs.setDefaultAccountCurrency(binding.defaultAccountCurrencyInput.text?.toString().orEmpty().trim())
        prefs.setDefaultSourceAccount(binding.defaultSourceAccountInput.text?.toString().orEmpty().trim())
        prefs.setAllTransactionsTags(normalizeTagList(binding.allTransactionsTagInput.text?.toString().orEmpty()))
        prefs.setApproxConversionTag(normalizeTagList(binding.approxTagInput.text?.toString().orEmpty()))

        initialSnapshot = buildSnapshot()
        Toast.makeText(requireContext(), R.string.firefly_settings_saved, Toast.LENGTH_SHORT).show()
        (activity as? SettingsActivity)?.leaveScreen()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? SettingsActivity)?.setBackGuard(null)
        binding = null
    }
}
