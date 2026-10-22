package io.github.maximus5.ffnotifyimporter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.databinding.FragmentMessagesBinding
import io.github.maximus5.ffnotifyimporter.network.ExchangeRateClient
import io.github.maximus5.ffnotifyimporter.network.dedupSearchQuery
import io.github.maximus5.ffnotifyimporter.network.searchUrl
import io.github.maximus5.ffnotifyimporter.network.transactionUrl
import io.github.maximus5.ffnotifyimporter.posting.DuplicateCheckResult
import io.github.maximus5.ffnotifyimporter.posting.LinkedTransactionCheck
import io.github.maximus5.ffnotifyimporter.posting.ReviewNotifications
import io.github.maximus5.ffnotifyimporter.posting.checkForDuplicate
import io.github.maximus5.ffnotifyimporter.posting.findDuplicate
import io.github.maximus5.ffnotifyimporter.posting.isNotificationPermissionGranted
import io.github.maximus5.ffnotifyimporter.posting.matchMessage
import io.github.maximus5.ffnotifyimporter.posting.verifyLinkedTransaction
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.prefs.MonitoredAppsPrefs
import io.github.maximus5.ffnotifyimporter.prefs.NotificationPrefs
import io.github.maximus5.ffnotifyimporter.prefs.SetupChecklistPrefs
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.NoMatchReason
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.RemoteTransactionCandidate
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.applyApproxConversion
import java.math.RoundingMode
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MessagesFragment : Fragment() {

    private var binding: FragmentMessagesBinding? = null
    private lateinit var adapter: MessagesAdapter
    private var inSelectionMode = false

    private val listener: (List<CapturedMessage>) -> Unit = { messages ->
        adapter.submitList(messages)
        if (messages.isNotEmpty()) SetupChecklistPrefs(requireContext()).retire()
        updateEmptyState(messages.isEmpty())
        if (inSelectionMode) {
            if (adapter.selectedCount() == 0) clearSelectionAndExit() else updateSelectionCountDisplay()
        }
    }

    private var listIsEmpty = true

    /** While the list is empty: the first-run setup checklist (until [SetupChecklistPrefs] retires
     * it - see its KDoc for why an empty list alone isn't enough), otherwise the plain "nothing
     * captured yet" text. */
    private fun updateEmptyState(isEmpty: Boolean = listIsEmpty) {
        listIsEmpty = isEmpty
        val binding = binding ?: return
        val showChecklist = isEmpty && !SetupChecklistPrefs(requireContext()).isRetired()
        binding.setupChecklist.visibility = if (showChecklist) View.VISIBLE else View.GONE
        binding.messagesEmptyText.visibility = if (isEmpty && !showChecklist) View.VISIBLE else View.GONE
        if (showChecklist) refreshChecklist(binding)
    }

    private fun refreshChecklist(binding: FragmentMessagesBinding) {
        val context = requireContext()
        val firefly = FireflyPrefs(context)
        bindStep(
            binding.stepAccess.root,
            done = isNotificationListenerAccessGranted(context),
            titleRes = R.string.setup_step_access,
            subtitleRes = R.string.setup_step_access_sub,
            screen = SettingsActivity.SetupScreen.NOTIFICATION_ACCESS,
        )
        bindStep(
            binding.stepFirefly.root,
            done = firefly.isConfigured(),
            titleRes = R.string.setup_step_firefly,
            subtitleRes = R.string.setup_step_firefly_sub,
            screen = SettingsActivity.SetupScreen.FIREFLY,
        )
        bindStep(
            binding.stepApps.root,
            done = MonitoredAppsPrefs(context).getMonitoredPackages().isNotEmpty(),
            titleRes = R.string.setup_step_apps,
            subtitleRes = R.string.setup_step_apps_sub,
            screen = SettingsActivity.SetupScreen.APPS,
        )
        bindStep(
            binding.stepDefaults.root,
            done = firefly.getDefaultAccountCurrency().isNotBlank() && firefly.getDefaultSourceAccount().isNotBlank(),
            titleRes = R.string.setup_step_defaults,
            subtitleRes = R.string.setup_step_defaults_sub,
            screen = SettingsActivity.SetupScreen.TRANSACTIONS,
        )
        val notificationsReady = isNotificationPermissionGranted(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            NotificationPrefs(context).isNotificationsEnabled()
        bindStep(
            binding.stepNotifications.root,
            done = notificationsReady,
            titleRes = R.string.setup_step_notifications,
            subtitleRes = R.string.setup_step_notifications_sub,
            screen = SettingsActivity.SetupScreen.NOTIFICATIONS,
        )
        bindStep(
            binding.stepTestMessage.root,
            done = false,
            titleRes = R.string.setup_step_test_message,
            subtitleRes = R.string.setup_step_test_message_sub,
            screen = SettingsActivity.SetupScreen.ADD_TEST_MESSAGE,
        )
        binding.setupChecklistHide.setOnClickListener {
            SetupChecklistPrefs(context).retire()
            updateEmptyState()
        }
    }

    private fun bindStep(row: View, done: Boolean, titleRes: Int, subtitleRes: Int, screen: SettingsActivity.SetupScreen) =
        bindStep(row, done, titleRes, subtitleRes) { startActivity(SettingsActivity.intentFor(requireContext(), screen)) }

    private fun bindStep(row: View, done: Boolean, titleRes: Int, subtitleRes: Int, onClick: () -> Unit) {
        row.findViewById<android.widget.ImageView>(R.id.stepIcon)
            .setImageResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
        row.findViewById<android.widget.TextView>(R.id.stepTitle).setText(titleRes)
        row.findViewById<android.widget.TextView>(R.id.stepSubtitle).setText(subtitleRes)
        row.setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        updateEmptyState()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MessagesAdapter(onClick = ::handleClick, onLongClick = ::handleLongClick, onOverflowClick = ::showMessageOverflowMenu)
        binding?.messagesRecyclerView?.layoutManager = LinearLayoutManager(requireContext())
        binding?.messagesRecyclerView?.adapter = adapter
        consumePendingMessageCheck()
    }

    /** `ReviewNotifications`'s "Create transaction" action - see `MainActivity.checkMessageIntent`'s
     * own KDoc for why it lands here instead of opening [CreateTransactionActivity] directly. Looks
     * up the live entry in [MessageStore] by the same identity fields `MessageStore.setTransactionId`
     * already matches on, rather than trusting the reconstructed message's own (always-null)
     * `fireflyTransactionId` - the message may have picked up a link since the notification was
     * posted (created by hand in the meantime, say); falls back to the reconstructed message
     * itself if it's no longer in the store at all (deleted, or the process was killed and restarted
     * with message retention off) rather than failing silently - [showTestMatch] still works fine
     * on it, same as any other fresh match. */
    fun consumePendingMessageCheck() {
        val reconstructed = MainActivity.consumeCheckMessageIntent(requireActivity().intent) ?: return
        val live = MessageStore.shared.snapshot().firstOrNull {
            it.packageName == reconstructed.packageName &&
                it.title == reconstructed.title &&
                it.text == reconstructed.text &&
                it.timestampMillis == reconstructed.timestampMillis
        } ?: reconstructed
        showTestMatch(live, fromNotification = true)
    }

    /** Set when a check opened [CreateTransactionActivity]; see [showTestMatch]'s `fromNotification`. */
    private var screenStarted = false

    private fun openCreateScreen(intent: Intent) {
        screenStarted = true
        startActivity(intent)
    }

    private fun handleClick(message: CapturedMessage) {
        if (inSelectionMode) {
            adapter.toggleSelection(message)
            if (adapter.selectedCount() == 0) clearSelectionAndExit() else updateSelectionCountDisplay()
        } else {
            showTestMatch(message)
        }
    }

    /** The title *is* the outcome - the matched rule's own label, or "No match" - instead of a
     * generic "Test match" that made every dialog look the same regardless of which rule (if any)
     * actually fired.
     *
     * A fresh match (nothing linked yet, Firefly configured) runs the same preemptive duplicate
     * check every other create path does, *before* deciding what to show - a real tap-tested
     * case the user caught: a transaction genuinely already existed for it (confirmed by "Check
     * if created" separately finding it), but tapping the message just opened a blank review
     * screen instead of surfacing that first. Found -> the usual duplicate-confirm dialog; not
     * found (or the remote check failed/was skipped) -> [CreateTransactionActivity] opens as
     * before. The already-linked case now runs [verifyLinkedThenShow] first - see its own KDoc -
     * which opens the *editor* directly once the id is confirmed live, the user's own request:
     * no more read-only info dialog with "Open"/"Create Again" buttons for that case at all. The
     * no-match/not-configured cases still show the plain dialog directly - there's nothing to
     * check or review/edit in either. */
    private fun showTestMatch(message: CapturedMessage, fromNotification: Boolean = false) {
        // The checks below can take a while on a slow network; without this a second tap started
        // a second identical chain and every one of them opened its own screen when it finished.
        if (openingMessage) {
            if (fromNotification) (activity as? MainActivity)?.endReviewCheck()
            return
        }
        openingMessage = true
        screenStarted = false
        val context = requireContext()
        val fireflyPrefs = FireflyPrefs(context)
        // Launched (Main.immediate - runs inline unless matchMessage actually has to wait on a
        // Firefly currency-symbol lookup, see posting.learnCurrencySymbols).
        viewLifecycleOwner.lifecycleScope.launch {
            // Only for a check that's actually slow - a toast on every tap would just flicker. From a
            // review notification the activity shows a blank panel meanwhile (MainActivity.
            // beginReviewCheck), so the hint goes there, sooner: an empty screen looks like a hang.
            val slowHint = launch {
                if (fromNotification) {
                    delay(SLOW_REVIEW_CHECK_HINT_MILLIS)
                    (activity as? MainActivity)?.showCheckingSlow()
                } else {
                    delay(SLOW_CHECK_HINT_MILLIS)
                    Toast.makeText(context, R.string.messages_checking, Toast.LENGTH_SHORT).show()
                }
            }
            try {
                when (val outcome = matchMessage(context, message)) {
                    is ParseOutcome.NoMatch -> {
                        val dialog = AlertDialog.Builder(context)
                            .setTitle(R.string.test_match_no_match_title)
                            .setMessage(getString(reasonStringRes(outcome.reason)))
                            .setPositiveButton(android.R.string.ok, null)
                        // The real action goes left and OK last (the repo's dialog convention). Only
                        // with a connection, like every other way to the review screen.
                        if (fireflyPrefs.isConfigured()) {
                            dialog.setNegativeButton(R.string.no_match_create_manually) { _, _ ->
                                openCreateScreen(CreateTransactionActivity.manualIntent(context, message))
                            }
                        }
                        dialog.show()
                    }
                    is ParseOutcome.Matched -> {
                        val configured = fireflyPrefs.isConfigured()
                        val existingId = message.fireflyTransactionId
                        when {
                            existingId == null && configured -> checkThenOpenCreateOrShowFound(context, outcome.draft, message)
                            existingId != null && configured -> verifyLinkedThenShow(context, outcome, message, existingId)
                            else -> showMatchedDialog(context, outcome)
                        }
                    }
                }
            } finally {
                slowHint.cancel()
                openingMessage = false
                if (fromNotification) {
                    // The editor is up: this screen was only the host of the check, so leave it - Back
                    // from the editor then goes back to wherever the user was, not to the list. A dialog
                    // (duplicate found, error) keeps the screen and reveals the list behind it.
                    val host = activity as? MainActivity
                    // If the app was already on screen when the notification was tapped, keep this
                    // screen under the editor so Back/Save return to it; the list is revealed again.
                    if (screenStarted && host?.openedWhileVisible != true) activity?.finish() else host?.endReviewCheck()
                }
            }
        }
    }

    private var openingMessage = false

    /** Verifies [existingId] still exists before trusting it, via the one shared
     * `posting.verifyLinkedTransaction` - **fixed a real "pretends everything is fine" bug, the
     * user's own catch/report (screenshotted)**: tapping an already-linked message used to go
     * straight to a read-only info dialog with zero verification, so a transaction deleted by hand
     * in Firefly left "Open transaction" pointing at a dead link with nothing on screen explaining
     * why. Still there -> opens [CreateTransactionActivity] in *edit* mode directly (the user's own
     * follow-up request - no more read-only dialog with "Open"/"Create Again" buttons for this case
     * at all; "Create Again" is gone entirely, the user's own call: if the linked transaction is
     * wrong, fix it here instead of creating a second one and leaving the original behind).
     * Confirmed gone -> falls through to the exact same search-then-show/create flow
     * [checkThenOpenCreateOrShowFound] already runs for a fresh match - a replacement match, if
     * search finds one, is shown with [showDuplicateConfirmDialog]'s `staleLinkedId` warning rather
     * than presenting it as if nothing had ever been linked; nothing found falls through to
     * [CreateTransactionActivity] same as any other fresh match. The stale link itself is left
     * alone here, deliberately - see the inline comment at the call site for why (the user's own
     * catch: don't change state before the user's actually chosen something). A verification
     * failure (network/server error, *not* confirmed gone) shows the error and stops - same
     * "don't guess" rule every dedup check in this file now follows (see `DuplicateCheckResult.
     * SearchFailed`'s KDoc). */
    private suspend fun verifyLinkedThenShow(context: Context, outcome: ParseOutcome.Matched, message: CapturedMessage, existingId: String) {
        when (val result = verifyLinkedTransaction(FireflyPrefs(context), existingId)) {
            is LinkedTransactionCheck.Found ->
                openCreateScreen(CreateTransactionActivity.editIntent(context, message, existingId))
            is LinkedTransactionCheck.Gone ->
                // Deliberately not cleared here - the user's own catch: wiping the stale
                // link the instant it's confirmed gone, before any dialog is even shown,
                // changes state with no user action behind it at all (a plain Cancel on
                // whatever shows next would've silently unlinked the message). The link
                // only actually changes as a side effect of the user's own choice: "Use
                // Created" overwrites it with the newly found id, a successful create
                // (fresh or "Create anyway") overwrites it with the new transaction's id.
                // A Cancel leaves the stale id exactly as it was - re-verified the same
                // way next time, not silently forgotten.
                checkThenOpenCreateOrShowFound(context, outcome.draft, message, staleLinkedId = existingId)
            is LinkedTransactionCheck.CheckFailed ->
                showResultDialog(context, R.string.check_search_failed_title, result.message)
        }
    }

    /** Resolves the amount (if `DraftIssue.LOCAL_AMOUNT_MISSING`, same lookup `checkIfCreated`
     * already does quietly), then runs [checkForDuplicate] before deciding what to show - see
     * [showTestMatch]'s own KDoc for why. A still-unresolved amount after the lookup skips the
     * check entirely and opens the review screen directly, same "flag it, don't guess, don't
     * block" fallback used everywhere else `LOCAL_AMOUNT_MISSING` shows up - `transactionDedupHash`
     * requires a resolved amount, there's nothing to hash/search with yet. */
    private suspend fun checkThenOpenCreateOrShowFound(
        context: Context,
        initialDraft: TransactionDraft,
        message: CapturedMessage,
        staleLinkedId: String? = null,
    ) {
        // Every reachable call site here already knows there's no *currently valid* link - either
        // message.fireflyTransactionId was never set (a genuinely fresh match), or staleLinkedId
        // proves whatever it did hold is now confirmed gone. CreateTransactionActivity reads
        // fireflyTransactionId purely to decide whether to show its "already linked" warning (see
        // its own KDoc) - **fixed a real "we just proved this ID is gone and then claimed it's
        // still linked" bug, the user's own catch**: passing the raw, still-stale message through
        // unchanged let that warning fire with a transaction this exact flow had just confirmed
        // doesn't exist. [cleanMessage] carries that proof forward instead of re-deriving it.
        val cleanMessage = message.copy(fireflyTransactionId = null, appliedRuleLabel = null)
        // A foreign-currency draft has no local amount yet: the review screen opens at once and
        // fetches the exchange rate itself in the background (it can be slow - an external
        // service), and the duplicate check then happens when the user presses Create, with the
        // amount actually shown there. Nothing to hash/search with until then.
        if (initialDraft.amount == null) {
            openCreateScreen(CreateTransactionActivity.intent(context, cleanMessage, staleLinkedId))
            return
        }
        when (val result = checkForDuplicate(context, initialDraft)) {
            is DuplicateCheckResult.None ->
                openCreateScreen(CreateTransactionActivity.intent(context, cleanMessage, staleLinkedId))
            is DuplicateCheckResult.SearchFailed ->
                showResultDialog(context, R.string.check_search_failed_title, result.message)
            is DuplicateCheckResult.Found ->
                showDuplicateConfirmDialog(context, result.id, cleanMessage, knownCandidate = result.candidate, staleLinkedId = staleLinkedId)
            is DuplicateCheckResult.FoundMultiple -> {
                Toast.makeText(context, getString(R.string.create_remote_duplicates_found, result.count), Toast.LENGTH_LONG).show()
                showDuplicateConfirmDialog(context, result.mostRecentId, cleanMessage, knownCandidate = result.candidate, staleLinkedId = staleLinkedId)
            }
        }
    }

    /** The no-match/not-configured fallback only now - **superseded, the user's own request**:
     * this used to also handle the already-linked case (Open/Create Again buttons, a found
     * candidate's real fields), but tapping an already-linked *live* message now opens the editor
     * directly instead ([verifyLinkedThenShow]) - there's nothing left here to edit or act on,
     * just the rule-derived read-only summary a no-match/unconfigured tap always showed. */
    private fun showMatchedDialog(context: Context, outcome: ParseOutcome.Matched) {
        AlertDialog.Builder(context)
            .setTitle(outcome.rule.label)
            .setMessage(buildMatchedBody(outcome.draft))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Four actions ("Use Created"/"Open"/"Create anyway"/"Cancel", Cancel always last) - more
     * than `AlertDialog.Builder`'s positive/negative/neutral slots can hold, so this is a plain
     * custom view (same reasoning as the rule editor's "Test results" dialog) instead of the
     * native button bar: `dialog_duplicate_confirm_4.xml`, a 2x2 grid (not four stacked full-width
     * rows - that left a lot of dead vertical space for four short labels). "Open" deliberately
     * doesn't dismiss - it's "let me go check first", not a decision, so the dialog is still there
     * to act on once the user comes back from Firefly. "Use Created" links the *found*
     * transaction's id to [message] instead of posting a new one - the dedup check already did the
     * work of finding it, no reason to make the user go dig it up by hand in Firefly.
     * "Create anyway" opens [CreateTransactionActivity] for review rather than posting [draft]
     * straight to Firefly - automatic creation bypassing the review screen is only ever allowed
     * for `posting.AutoCreateWorker`'s background auto-create-by-notification path (the user's own
     * explicit rule), never for a manual tap like this one, even when a likely duplicate has
     * already been confirmed by hand.
     *
     * [showCreateAnyway] drops to three actions - `dialog_duplicate_confirm_3.xml`, one row
     * instead of a grid - for [checkIfCreated]: that flow is an explicit "just tell me" search,
     * not a step on the way to posting a transaction, so "post one anyway" isn't a meaningful
     * option there; title/message also switch to the check-specific wording in that case.
     *
     * Always shows the found transaction's real fields (date/amount/description/accounts)
     * alongside the id, not just the bare "#<id>" - the user's own call, same reasoning as the
     * not-found dialog's candidate dump: seeing what was actually found is what lets the user
     * judge whether "Use Created" is really correct, not just trust the id. Bold field labels,
     * same [boldField] helper every field-summary dialog in this fragment uses - the user's own
     * catch, this one used to be plain text. [knownCandidate] is passed straight through when this
     * came from the remote search (already fetched in full, no reason to ask again); for a
     * local-log hit (`CreatedTransactionsDb` only ever has the id) this fetches it itself
     * (`fetchCandidateInfo`) - suspends briefly for that, a worthwhile trade for not showing a bare
     * id the user has no way to independently verify.
     *
     * "Use Created" is hidden when [foundId] already *is* [message]'s own linked id - the user's
     * own catch: relinking a message to the exact id it's already linked to is a pure no-op, and
     * offering it as if it were a real choice (next to "Open"/"Cancel") was just confusing. This
     * is the common case once `checkIfCreated`'s own linked-id-first check (see its KDoc) finds
     * the known id still valid and shows it here directly.
     *
     * [descriptionMismatch] (only ever true from [checkIfCreated]'s retry-without-description -
     * see its own KDoc) prepends an explicit warning that this match ignored description.
     * [staleLinkedId] (the user's own catch/report, screenshotted) prepends a second, independent
     * warning when [message] already had a *different* linked id that's been confirmed gone - see
     * `posting.verifyLinkedTransaction` - so "here's what matches now" doesn't read as if nothing
     * had ever been linked, when actually something was, and it's since vanished from Firefly. */
    private suspend fun showDuplicateConfirmDialog(
        context: Context,
        foundId: String,
        message: CapturedMessage,
        showCreateAnyway: Boolean = true,
        knownCandidate: RemoteTransactionCandidate? = null,
        descriptionMismatch: Boolean = false,
        staleLinkedId: String? = null,
    ) {
        val candidate = knownCandidate ?: fetchCandidateInfo(context, foundId)
        val layoutRes = if (showCreateAnyway) R.layout.dialog_duplicate_confirm_4 else R.layout.dialog_duplicate_confirm_3
        val view = LayoutInflater.from(context).inflate(layoutRes, null, false)
        val baseMessage = if (showCreateAnyway) {
            getString(R.string.create_duplicate_confirm_message, foundId)
        } else {
            getString(R.string.check_found_message, foundId)
        }
        val staleWarning = staleLinkedId?.let { getString(R.string.check_linked_transaction_gone_warning, it) + "\n\n" }.orEmpty()
        val warning = staleWarning + if (descriptionMismatch) getString(R.string.check_found_description_mismatch_warning) + "\n\n" else ""
        val dialogMessage = SpannableStringBuilder(warning + baseMessage)
        candidate?.let {
            dialogMessage.append("\n\n")
            dialogMessage.append(buildFoundTransactionInfo(it))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (showCreateAnyway) R.string.create_duplicate_confirm_title else R.string.check_found_title)
            .setMessage(dialogMessage)
            .setView(view)
            .create()

        val useCreatedButton = view.findViewById<View>(R.id.useCreatedButton)
        if (foundId == message.fireflyTransactionId) {
            useCreatedButton.visibility = View.GONE
        } else {
            useCreatedButton.setOnClickListener {
                MessageStore.shared.setTransactionId(message, foundId)
                ReviewNotifications.cancel(context, message)
                Toast.makeText(context, getString(R.string.create_already_created_linked, foundId), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        view.findViewById<View>(R.id.openDuplicateButton).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { verifyThenOpenTransaction(context, foundId) }
        }
        if (showCreateAnyway) {
            view.findViewById<View>(R.id.createAnywayButton).setOnClickListener {
                startActivity(CreateTransactionActivity.intent(context, message, staleLinkedId))
                dialog.dismiss()
            }
        }
        view.findViewById<View>(R.id.cancelDuplicateButton).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /** Fetches a known transaction's full fields for [showDuplicateConfirmDialog] - only needed
     * for a local-log hit, which only ever has the id. Null on any failure (network, malformed
     * response, or a genuinely missing transaction) - the dialog just shows the bare id in that
     * case, same as before this existed, rather than failing to show anything at all. */
    private suspend fun fetchCandidateInfo(context: Context, id: String): RemoteTransactionCandidate? =
        (verifyLinkedTransaction(FireflyPrefs(context), id) as? LinkedTransactionCheck.Found)?.candidate

    /** Labeled field summary appended to the duplicate-confirm dialog's message - reuses
     * `showTestMatch`'s own field labels (`test_match_label_*`) so this reads the same way as the
     * already-familiar match-preview dialog, rather than inventing a second, denser text format.
     * Seeing the found transaction's actual date/amount/description/accounts is what lets the
     * user judge a match independently, not just trust a bare id. Shows the foreign amount/
     * currency alongside the home one when present, for the same reason the home amount alone is
     * unreliable for matching (see `network.dedupSearchQuery`'s KDoc) - it's informative here too,
     * not just for searching. Bold field labels, same [boldField] helper [buildMatchedBody] uses -
     * the user's own catch: this one used to be plain text, reading inconsistently next to the
     * other field-summary dialog in this fragment. */
    private fun buildFoundTransactionInfo(candidate: RemoteTransactionCandidate): CharSequence {
        val amountText = if (candidate.foreignAmount != null && candidate.foreignCurrencyCode != null) {
            "${formatAmountForDisplay(candidate.amount)} ${candidate.currencyCode} " +
                "(${formatAmountForDisplay(candidate.foreignAmount)} ${candidate.foreignCurrencyCode})"
        } else {
            "${formatAmountForDisplay(candidate.amount)} ${candidate.currencyCode}"
        }
        val text = SpannableStringBuilder()
        val field = boldField(text)
        field(getString(R.string.test_match_label_date), formatDraftDate(candidate.date))
        field(getString(R.string.test_match_label_amount), amountText)
        field(getString(R.string.test_match_label_description), candidate.description)
        candidate.sourceName?.let { field(getString(R.string.test_match_label_source), it) }
        candidate.destinationName?.let { field(getString(R.string.test_match_label_destination), it) }
        candidate.notes?.takeIf { it.isNotBlank() }?.let { field(getString(R.string.test_match_label_notes), it) }
        candidate.category?.let { field(getString(R.string.test_match_label_category), it) }
        if (candidate.tags.isNotEmpty()) field(getString(R.string.test_match_label_tags), candidate.tags.joinToString(", "))
        return text
    }

    /** Shared bold-label field appender behind every field-summary dialog in this fragment
     * ([buildMatchedBody]/[buildFoundTransactionInfo]) - appends "\n<label>
     * <value>" to [text] (no leading newline the very first time), bolding just the label, so a
     * multi-field dialog reads as a list of fields rather than a wall of same-weight text. */
    private fun boldField(text: SpannableStringBuilder): (String, String) -> Unit = { label, value ->
        if (text.isNotEmpty()) text.append("\n")
        val labelStart = text.length
        text.append(label)
        text.setSpan(StyleSpan(Typeface.BOLD), labelStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append(" ").append(value)
    }

    private fun openTransaction(context: Context, transactionId: String) {
        val baseUrl = FireflyPrefs(context).getBaseUrl()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(transactionUrl(baseUrl, transactionId))))
    }

    /** The one place "Open"/"Open transaction"/"Open in Firefly III" actually opens Firefly -
     * **fixed a real "silent dead link" bug, the user's own catch**: Firefly's own web UI doesn't
     * show a 404 for a deleted transaction, it silently redirects to the home page, leaving no
     * sign anything was wrong - an `ACTION_VIEW` launch straight to [openTransaction] has no way to
     * tell the difference from here either, since the browser's own navigation isn't something
     * this app observes. Verifies first via the shared `posting.verifyLinkedTransaction`: still
     * there -> opens as before; confirmed gone -> shows an explicit `open_transaction_gone_*`
     * dialog instead of the ambiguous silent redirect; a check failure shows the real reason, same
     * "don't guess" rule every dedup check in this file already follows. */
    private suspend fun verifyThenOpenTransaction(context: Context, transactionId: String) {
        when (val result = verifyLinkedTransaction(FireflyPrefs(context), transactionId)) {
            is LinkedTransactionCheck.Found -> openTransaction(context, transactionId)
            is LinkedTransactionCheck.Gone ->
                showResultDialog(context, R.string.open_transaction_gone_title, getString(R.string.open_transaction_gone_message, transactionId))
            is LinkedTransactionCheck.CheckFailed ->
                showResultDialog(context, R.string.check_search_failed_title, result.message)
        }
    }

    private fun showResultDialog(context: Context, titleRes: Int, message: String) {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Bolds each field's label ("Source:", "Amount:", ...) so a multi-field dialog reads as a
     * list of fields rather than a wall of same-weight text - via the shared [boldField] helper. */
    private fun buildMatchedBody(draft: TransactionDraft): CharSequence {
        val text = SpannableStringBuilder()
        val field = boldField(text)

        draft.sourceName?.let { field(getString(R.string.test_match_label_source), it) }
        draft.destinationName?.let { field(getString(R.string.test_match_label_destination), it) }
        field(
            getString(R.string.test_match_label_amount),
            if (draft.amount != null) {
                "${draft.amount.toPlainString()} ${draft.currencyCode}"
            } else {
                getString(R.string.test_match_amount_missing_value)
            },
        )
        draft.foreignAmount?.let {
            field(
                getString(R.string.test_match_label_foreign),
                "${it.toPlainString()} ${draft.foreignCurrencyCode.orEmpty()}",
            )
        }
        field(getString(R.string.test_match_label_date), formatDraftDate(draft.date))
        field(getString(R.string.test_match_label_description), draft.description)
        field(getString(R.string.test_match_label_notes), draft.notes)
        draft.category?.let { field(getString(R.string.test_match_label_category), it) }
        if (draft.tags.isNotEmpty()) field(getString(R.string.test_match_label_tags), draft.tags.joinToString(", "))
        draft.issues.forEach { issue ->
            text.append("\n").append(
                when (issue) {
                    DraftIssue.LOCAL_AMOUNT_MISSING -> getString(R.string.test_match_issue_local_amount_missing)
                    DraftIssue.DATE_UNPARSABLE -> getString(R.string.test_match_issue_date_unparsable)
                    DraftIssue.ACCOUNT_SELECTION_REQUIRED -> getString(R.string.test_match_issue_account_selection_required)
                    DraftIssue.ACCOUNT_APPROXIMATE -> getString(R.string.test_match_issue_account_approximate)
                    DraftIssue.AMOUNT_ENTRY_REQUIRED -> getString(R.string.test_match_issue_amount_entry_required)
                },
            )
        }
        return text
    }

    /** Same system date+time formatting `MessagesAdapter` uses for the notification list, for the
     * same reason - respects the device's actual date/time settings rather than guessing from
     * locale alone. Shown for every match, not just when [ParseRule.dateFormat] overrides it, so
     * the override is actually visible: without this field the dialog never showed a date at all,
     * and a wrong override would be invisible until a transaction posted with it. */
    private fun formatDraftDate(date: Instant): String {
        val context = requireContext()
        val javaDate = Date.from(date)
        val dateFormat = DateFormat.getDateFormat(context)
        val timeFormat = DateFormat.getTimeFormat(context)
        return "${dateFormat.format(javaDate)} ${timeFormat.format(javaDate)}"
    }

    private fun reasonStringRes(reason: NoMatchReason) = when (reason) {
        NoMatchReason.NO_RULES_FOR_APP -> R.string.no_match_reason_no_rules_for_app
        NoMatchReason.NO_PATTERN_MATCHED -> R.string.no_match_reason_no_pattern_matched
        NoMatchReason.AMOUNT_UNPARSABLE -> R.string.no_match_reason_amount_unparsable
    }

    private fun handleLongClick(message: CapturedMessage): Boolean {
        if (!inSelectionMode) {
            inSelectionMode = true
            adapter.setSelectionMode(true)
            adapter.toggleSelection(message)
            (requireActivity() as MainActivity).enterSelectionMode(
                count = adapter.selectedCount(),
                onCopy = ::copySelectedToClipboard,
                onDelete = ::confirmDeleteSelected,
                onCancel = ::clearSelectionAndExit,
            )
        } else {
            adapter.toggleSelection(message)
            if (adapter.selectedCount() == 0) clearSelectionAndExit() else updateSelectionCountDisplay()
        }
        return true
    }

    private fun updateSelectionCountDisplay() {
        (requireActivity() as MainActivity).updateSelectionCount(adapter.selectedCount())
    }

    private fun clearSelectionAndExit() {
        adapter.clearSelection()
        adapter.setSelectionMode(false)
        inSelectionMode = false
        (activity as? MainActivity)?.exitSelectionMode()
    }

    /** "Create transaction" (`ID_CREATE_TRANSACTION`) - the user's own request: a direct shortcut
     * to the same flow a plain tap on the row already runs ([showTestMatch]), for someone who'd
     * rather reach it from this menu than tap the row itself. Disabled whenever
     * [CapturedMessage.fireflyTransactionId] is already set - the user's own explicit call: a tap
     * on an already-linked row opens the *editor* instead ([verifyLinkedThenShow]'s `Found`
     * branch, inside [showTestMatch]), so "Create transaction" here would be misleading - there's
     * already one linked, nothing new to create from this menu item's own label. */
    private fun showMessageOverflowMenu(message: CapturedMessage, anchor: View) {
        val context = requireContext()
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, ID_CREATE_TRANSACTION, 0, R.string.test_match_create)
            .isEnabled = message.fireflyTransactionId == null
        popup.menu.add(0, ID_OPEN_IN_FIREFLY, 1, R.string.messages_action_open_in_firefly)
            .isEnabled = message.fireflyTransactionId != null
        popup.menu.add(0, ID_CHECK_IF_CREATED, 2, R.string.messages_check_if_created)
        popup.menu.add(0, ID_FORGET_TRANSACTION, 3, R.string.messages_action_forget_transaction)
            .isEnabled = message.fireflyTransactionId != null
        popup.menu.add(0, ID_COPY_SINGLE, 4, R.string.messages_action_copy)
        popup.menu.add(0, ID_DELETE_SINGLE, 5, R.string.messages_action_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_CREATE_TRANSACTION -> showTestMatch(message)
                ID_OPEN_IN_FIREFLY -> message.fireflyTransactionId?.let { id ->
                    viewLifecycleOwner.lifecycleScope.launch { verifyThenOpenTransaction(context, id) }
                }
                ID_CHECK_IF_CREATED -> checkIfCreated(message)
                ID_FORGET_TRANSACTION -> forgetLinkedTransactionIfGone(context, message)
                ID_COPY_SINGLE -> copyMessagesToClipboard(listOf(message))
                ID_DELETE_SINGLE -> confirmDeleteSingle(message)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    /** Toolbar copy action for the current multi-select, in on-screen order
     * ([MessagesAdapter.selectedMessagesInOrder]). Ends the selection afterward
     * ([clearSelectionAndExit]) - the user's own explicit follow-up: copy is a one-shot action
     * here ("got what I needed"), not a toggle-able state worth leaving active the way
     * `RulesListFragment`'s own enable/disable does. */
    private fun copySelectedToClipboard() {
        copyMessagesToClipboard(adapter.selectedMessagesInOrder())
        clearSelectionAndExit()
    }

    /** `{title}\n{body}` per message, each pair separated by a blank line when there's more than
     * one - the user's own exact format. [android.content.ClipboardManager] rather than
     * `Intent.ACTION_SEND` or similar - the user asked for a plain clipboard copy, not a share
     * sheet. */
    private fun copyMessagesToClipboard(messages: List<CapturedMessage>) {
        if (messages.isEmpty()) return
        val context = requireContext()
        val text = messages.joinToString("\n\n") { "${it.title}\n${it.text}" }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.messages_action_copy), text))
        Toast.makeText(context, R.string.messages_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    /** "Forget linked transaction" - the user's own request, after noticing "Check if created"
     * could confirm a cached id was gone with no way to actually clear it (the not-found dialog
     * has nothing to clear *to* - a fresh search already came up empty too). Verifies first via
     * the shared `posting.verifyLinkedTransaction`: confirmed gone -> clears the link
     * (`MessageStore.setTransactionId` to `null`) immediately and says so, no confirmation needed
     * - there's nothing left to protect, the link was already pointing at nothing.
     *
     * **Fixed a real "stuck forever" gap, the user's own catch**: still-valid used to just leave
     * the link alone unconditionally, with no way out at all - but a *live* link can still be the
     * *wrong* one (a false-positive dedup match, auto-create or a notification-tap matching this
     * message to someone else's real, still-existing transaction) - and once linked, a tap only
     * ever opens the editor for that (wrong) transaction, never the create screen, so there was no
     * way back to create the actually-correct one short of fixing it by hand in Firefly first. A
     * still-valid link now shows an explicit confirmation (`messages_forget_transaction_confirm_*`,
     * Cancel rightmost per house convention) instead of silently refusing - unlinking here never
     * touches Firefly itself, only this app's own record of which transaction this message points
     * to, so it's safe to allow outright once the user's explicitly said so. A check failure shows
     * the real reason and touches nothing either way, same "don't guess" rule every dedup check in
     * this file already follows. */
    private fun forgetLinkedTransactionIfGone(context: Context, message: CapturedMessage) {
        val existingId = message.fireflyTransactionId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = verifyLinkedTransaction(FireflyPrefs(context), existingId)) {
                is LinkedTransactionCheck.Gone -> {
                    MessageStore.shared.setTransactionId(message, null)
                    Toast.makeText(context, getString(R.string.messages_forget_transaction_cleared, existingId), Toast.LENGTH_SHORT).show()
                }
                is LinkedTransactionCheck.Found -> {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.messages_forget_transaction_confirm_title)
                        .setMessage(getString(R.string.messages_forget_transaction_confirm_message, existingId))
                        .setNegativeButton(R.string.messages_forget_transaction_confirm_button) { _, _ ->
                            MessageStore.shared.setTransactionId(message, null)
                            Toast.makeText(context, getString(R.string.messages_forget_transaction_cleared, existingId), Toast.LENGTH_SHORT).show()
                        }
                        .setPositiveButton(android.R.string.cancel, null)
                        .show()
                }
                is LinkedTransactionCheck.CheckFailed ->
                    showResultDialog(context, R.string.check_search_failed_title, result.message)
            }
        }
    }

    /** Shows which notification is about to go, right under the title - the user's own request:
     * a bare "Delete notification?" gave no way to confirm *which one* without backing out and
     * checking the row first. Same fields the row itself shows (app label bold, then title/text),
     * so this reads as "yes, that one" rather than a new, denser format to learn. */
    private fun confirmDeleteSingle(message: CapturedMessage) {
        val fullMessage = SpannableStringBuilder(message.appLabel)
        fullMessage.setSpan(StyleSpan(Typeface.BOLD), 0, fullMessage.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (message.title.isNotBlank()) fullMessage.append("\n").append(message.title)
        if (message.text.isNotBlank()) fullMessage.append("\n").append(message.text)
        fullMessage.append("\n\n").append(getString(R.string.delete_notification_confirm_message))
        // Cancel always rightmost/last (house convention, AGENTS.md).
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_notification_confirm_title)
            .setMessage(fullMessage)
            .setNegativeButton(R.string.messages_action_delete) { _, _ -> deleteMessage(message) }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    /** "Check if created" (the per-row overflow menu) - an explicit, deliberate search, unlike
     * the preemptive check `showTestMatch`/`startCreate` run before posting: it always looks
     * (local log, then Firefly), ignoring `FireflyPrefs.isDedupEnabled()` - that toggle is about
     * whether *creating* gates on a dedup check, not whether this separate, user-initiated "just
     * tell me" action should run at all. Re-parses the message fresh (same `RuleEngine.match` as
     * `showTestMatch`) rather than requiring the message to already have a cached draft.
     *
     * **Fixed a real "ignores what it already knows" bug, the user's own catch/report**: if
     * [message] already has a linked id, that's a direct answer to "was this created?" on its
     * own - no content search needed, and a content search can genuinely disagree (the user's own
     * example: editing a transaction's description/notes in Firefly by hand can make it stop
     * matching this app's own hash/query, so the old code searched anyway, found a *different*
     * transaction, and offered to link to that instead - confusing and simply wrong when the
     * known id was never in question). Now checks the linked id first, via the one shared
     * `posting.verifyLinkedTransaction`: still there -> shown directly, search skipped entirely;
     * confirmed gone -> falls through to the search below same as before, now carrying the gone
     * id as `staleLinkedId` so a replacement match (if any) says so explicitly; a check failure
     * stops and shows the error, same "don't guess" rule every dedup check in this file follows.
     *
     * Manual-only retry, the user's own call: if `isDedupIncludeDescription()` is on and the
     * first attempt finds nothing, tries again with description excluded entirely (a second
     * `findDuplicate` call - local log included, since a without-description hash might be
     * populated there too, see `CreatedTransactionsDb`'s KDoc) before giving up. The create-flow
     * callers (`checkForDuplicate`) deliberately do *not* do this - silently treating a
     * description mismatch as "good enough" there would risk posting on top of a genuinely
     * different transaction that just happens to share amount/account/date; a manual lookup has
     * a human looking at the result either way, so showing a weaker match *with a clear warning*
     * is the right trade-off here specifically. */
    private fun checkIfCreated(message: CapturedMessage) {
        val context = requireContext()
        if (!FireflyPrefs(context).isConfigured()) {
            Toast.makeText(context, R.string.rule_firefly_not_configured, Toast.LENGTH_LONG).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = FireflyPrefs(context)
            val existingId = message.fireflyTransactionId
            var staleLinkedId: String? = null
            if (existingId != null) {
                when (val linked = verifyLinkedTransaction(prefs, existingId)) {
                    is LinkedTransactionCheck.Found -> {
                        showDuplicateConfirmDialog(context, existingId, message, showCreateAnyway = false, knownCandidate = linked.candidate)
                        return@launch
                    }
                    is LinkedTransactionCheck.CheckFailed -> {
                        showResultDialog(context, R.string.check_search_failed_title, linked.message)
                        return@launch
                    }
                    is LinkedTransactionCheck.Gone -> staleLinkedId = existingId
                }
            }

            val draft = (matchMessage(context, message) as? ParseOutcome.Matched)?.draft
            if (draft == null) {
                Toast.makeText(context, R.string.messages_check_no_rule_matched, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val resolved = resolveAmountQuietly(draft)
            if (resolved.amount == null) {
                Toast.makeText(context, R.string.create_amount_not_ready, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val includeDescription = prefs.isDedupIncludeDescription()
            var result = findDuplicate(context, resolved, prefs, includeDescription, allowUnscoped = true)
            var effectiveIncludeDescription = includeDescription
            var matchedWithoutDescription = false
            if (result is DuplicateCheckResult.None && includeDescription) {
                when (val retry = findDuplicate(context, resolved, prefs, includeDescription = false, allowUnscoped = true)) {
                    is DuplicateCheckResult.Found, is DuplicateCheckResult.FoundMultiple -> {
                        result = retry
                        effectiveIncludeDescription = false
                        matchedWithoutDescription = true
                    }
                    is DuplicateCheckResult.None -> {
                        // Still nothing, but the broader (no-description) search may have turned
                        // up raw candidates the first attempt's query filtered out server-side -
                        // show those instead, the more informative "last/broadest attempt" result.
                        result = retry
                        effectiveIncludeDescription = false
                    }
                    is DuplicateCheckResult.SearchFailed -> Unit // keep the original None as-is
                }
            }
            when (val r = result) {
                is DuplicateCheckResult.None -> {
                    val query = dedupSearchQuery(
                        amount = resolved.amount,
                        currencyCode = resolved.currencyCode,
                        foreignAmount = resolved.foreignAmount,
                        foreignCurrencyCode = resolved.foreignCurrencyCode,
                        centerDate = resolved.date,
                        windowMinutes = prefs.getDedupWindowMinutes(),
                        sourceName = resolved.sourceName,
                        destinationName = resolved.destinationName,
                        description = resolved.description,
                        includeDescription = effectiveIncludeDescription,
                        allowUnscoped = true,
                    ).orEmpty()
                    showSearchNotFoundDialog(context, query, r.rawCandidates, message)
                }
                is DuplicateCheckResult.Found ->
                    showDuplicateConfirmDialog(
                        context, r.id, message,
                        showCreateAnyway = false, knownCandidate = r.candidate, descriptionMismatch = matchedWithoutDescription,
                        staleLinkedId = staleLinkedId,
                    )
                is DuplicateCheckResult.FoundMultiple -> {
                    Toast.makeText(
                        context,
                        getString(R.string.create_remote_duplicates_found, r.count),
                        Toast.LENGTH_LONG,
                    ).show()
                    showDuplicateConfirmDialog(
                        context, r.mostRecentId, message,
                        showCreateAnyway = false, knownCandidate = r.candidate, descriptionMismatch = matchedWithoutDescription,
                        staleLinkedId = staleLinkedId,
                    )
                }
                // Unlike the create-flow callers, this is an explicit "just tell me" lookup - a
                // failed search gets shown for what it actually was, not quietly presented as
                // "not found" (which is exactly what the user caught: the same query string found
                // the transaction fine when pasted into Firefly's own web search, so an API-side
                // failure being swallowed as "not found" would be actively misleading here).
                is DuplicateCheckResult.SearchFailed ->
                    showResultDialog(context, R.string.check_search_failed_title, r.message)
            }
        }
    }

    /** Same exchange-rate resolution as [resolveApproxConversion], without an [AlertDialog] to
     * update along the way - for [checkIfCreated], which doesn't show the matched-fields dialog
     * at all. A failed lookup silently keeps the original (still-unresolved) draft, same as
     * [resolveApproxConversion]'s own fallback. */
    private suspend fun resolveAmountQuietly(draft: TransactionDraft): TransactionDraft {
        if (DraftIssue.LOCAL_AMOUNT_MISSING !in draft.issues) return draft
        val foreignCurrency = draft.foreignCurrencyCode ?: return draft
        val context = requireContext()
        return when (val result = ExchangeRateClient().fetchRate(foreignCurrency, draft.currencyCode, draft.date)) {
            is ExchangeRateClient.Result.Success -> {
                val approxTag = FireflyPrefs(context).getApproxConversionTag()
                applyApproxConversion(draft, result.rate, approxTag)
            }
            is ExchangeRateClient.Result.Failure -> draft
        }
    }

    /** No match in Firefly - shows the exact query string `checkIfCreated` searched with (so the
     * user can see/debug what was actually looked for) and offers to open the same search,
     * pre-filled, in Firefly's own web UI (`network.searchUrl`) rather than leaving them to
     * retype it by hand. [rawCandidates] (Firefly's own raw results, before this app's
     * client-side `remoteCandidateMatches` filtering rejected every one of them) are listed with
     * their actual field values when non-empty - "found N but none matched exactly" is a
     * different, more useful signal than "found literally nothing", and seeing the candidate's
     * real date/amount/account/description next to what was searched for is what actually shows
     * *why* the match failed (case, whitespace, a stale date, ...) instead of needing another
     * debug round-trip to find out. When exactly one candidate came back, a "Use anyway" button
     * links [message] to it directly - the user's own call: with only one candidate, the
     * client-side rejection is more likely a field-comparison quirk than a genuine different
     * transaction, so skip the round trip through Firefly's own search UI.
     *
     * `dialog_search_not_found.xml`, not native `setNegativeButton`/`setNeutralButton`/
     * `setPositiveButton` slots: the user caught a real native-AlertDialog quirk directly - once
     * "Use anyway" made this a 3-button dialog, AlertDialog's own button bar stacked them
     * vertically (each right-aligned) instead of one row, since the combined label text didn't
     * fit. Same fix already applied to `dialog_duplicate_confirm_3.xml` for the same reason. */
    private fun showSearchNotFoundDialog(
        context: Context,
        query: String,
        rawCandidates: List<RemoteTransactionCandidate>,
        message: CapturedMessage,
    ) {
        val dialogMessage = if (query.isBlank()) {
            getString(R.string.check_not_found_no_query)
        } else if (rawCandidates.isNotEmpty()) {
            val candidateLines = rawCandidates.joinToString("\n") {
                "id=${it.groupId} date=${it.date} amount=${it.amount.setScale(2, RoundingMode.HALF_UP)} ${it.currencyCode} " +
                    "desc=\"${it.description}\" src=${it.sourceId}/${it.sourceName} dst=${it.destinationId}/${it.destinationName}"
            }
            getString(R.string.check_not_found_message_with_candidates, query, rawCandidates.size, candidateLines)
        } else {
            getString(R.string.check_not_found_message, query)
        }
        val onlyCandidate = rawCandidates.singleOrNull()

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_search_not_found, null, false)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.check_not_found_title)
            .setMessage(dialogMessage)
            .setView(view)
            .create()

        val openSearchButton = view.findViewById<View>(R.id.openSearchButton)
        if (query.isBlank()) openSearchButton.visibility = View.GONE
        openSearchButton.setOnClickListener {
            val baseUrl = FireflyPrefs(context).getBaseUrl()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl(baseUrl, query))))
            dialog.dismiss()
        }
        val useAnywayButton = view.findViewById<View>(R.id.useAnywayButton)
        if (onlyCandidate != null) {
            useAnywayButton.setOnClickListener {
                MessageStore.shared.setTransactionId(message, onlyCandidate.groupId)
                ReviewNotifications.cancel(context, message)
                Toast.makeText(context, getString(R.string.create_already_created_linked, onlyCandidate.groupId), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        } else {
            useAnywayButton.visibility = View.GONE
        }
        view.findViewById<View>(R.id.cancelSearchButton).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /** Removes [message] from the list *and* drops its notification from our own shade - a
     * review/auto-create notification for a message that no longer exists in the app would point
     * at nothing (the user's own report: deleted a wrong message, its notification stayed). */
    private fun deleteMessage(message: CapturedMessage) {
        MessageStore.shared.remove(message)
        ReviewNotifications.cancel(requireContext().applicationContext, message)
    }

    /** Title carries the count ("Delete 3 notification(s)?") - the user's own request, same
     * reasoning as [confirmDeleteSingle]'s own preview: knowing *how many* before confirming,
     * not just that "some" are about to go. */
    private fun confirmDeleteSelected() {
        val toDelete = adapter.selectedMessages()
        // Cancel always rightmost/last (house convention, AGENTS.md).
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_notifications_confirm_title, toDelete.size))
            .setMessage(R.string.delete_notification_confirm_message)
            .setNegativeButton(R.string.messages_action_delete) { _, _ ->
                toDelete.forEach { deleteMessage(it) }
                clearSelectionAndExit()
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        MessageStore.shared.addListener(listener)
    }

    override fun onStop() {
        super.onStop()
        MessageStore.shared.removeListener(listener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (inSelectionMode) clearSelectionAndExit()
        binding = null
    }

    private companion object {
        const val ID_CREATE_TRANSACTION = 1
        const val ID_OPEN_IN_FIREFLY = 2
        const val ID_CHECK_IF_CREATED = 3
        const val ID_FORGET_TRANSACTION = 4
        const val ID_COPY_SINGLE = 5
        const val ID_DELETE_SINGLE = 6
        const val SLOW_CHECK_HINT_MILLIS = 1500L
        const val SLOW_REVIEW_CHECK_HINT_MILLIS = 500L
    }
}
