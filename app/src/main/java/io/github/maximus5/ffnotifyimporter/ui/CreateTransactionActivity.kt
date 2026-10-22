package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.databinding.ActivityCreateTransactionBinding

/**
 * Hosts [CreateTransactionFragment] - a single screen, not a multi-level area like
 * `SettingsActivity`, so this skips that class's `openScreen`/back-stack machinery and just shows
 * the one fragment directly. Two distinct modes, same fields/layout, decided purely by which
 * `intent(...)`/`editIntent(...)` factory built the launching `Intent`:
 *
 * - **Create** (`intent`) - the pre-create review/edit screen (TODO.md's dedup history), for a
 *   message with no *currently valid* linked transaction. Replaces what used to be
 *   `MessagesFragment.showTestMatch`'s read-only test-match dialog for a fresh match; also where
 *   a found-but-rejected possible duplicate lands via `showDuplicateConfirmDialog`'s "Create
 *   anyway". `staleLinkedId`, when present, means a *previous* link was just confirmed gone (see
 *   [CreateTransactionFragment] for the resulting warning banner) - informational, not a gate.
 * - **Edit** (`editIntent`) - the user's own request: when a tap finds a *currently valid* linked
 *   transaction (`MessagesFragment.verifyLinkedThenShow`'s `Found` branch), this opens straight
 *   into editing that real transaction instead of the old read-only "Open Transaction"/"Create
 *   Again" info dialog (`MessagesFragment.showMatchedDialog`, now only reachable for the
 *   no-match/not-configured cases, which have nothing to edit). "Create Again" itself is gone -
 *   the user's own call: if the linked transaction is wrong, fix it here instead of creating a
 *   second one and leaving the original behind.
 *
 * The no-match case shows a dialog with a "Create manually" button (`manualIntent`): the same
 * Create mode, but starting from an empty draft instead of a rule match.
 */
class CreateTransactionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityCreateTransactionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        useClassicSystemBars(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (editTransactionIdFrom(intent) != null) {
            supportActionBar?.title = getString(R.string.create_tx_edit_title)
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, CreateTransactionFragment())
                .commit()
        }
    }

    /** The toolbar's up arrow goes through the same path as the system back, so the fragment's
     * "discard unsaved changes?" guard covers both. */
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val EXTRA_APP_LABEL = "app_label"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TIMESTAMP_MILLIS = "timestamp_millis"
        private const val EXTRA_STALE_LINKED_ID = "stale_linked_id"
        private const val EXTRA_EDIT_TRANSACTION_ID = "edit_transaction_id"
        private const val EXTRA_MANUAL = "manual"

        /** [message] is passed as its own plain fields, not a `Parcelable`/serialized
         * `CapturedMessage` - [CreateTransactionFragment] re-parses it fresh via `RuleEngine.match`
         * the same way `posting.AutoCreateWorker` reconstructs one from `WorkManager.Data`, rather
         * than trying to carry a `TransactionDraft` (`BigDecimal`/`Instant`/`List<String>`) across
         * process boundaries.
         *
         * [staleLinkedId] - the user's own request: when a tap falls through here because a
         * previously-linked id was just confirmed gone (`MessagesFragment.verifyLinkedThenShow`'s
         * `Gone` branch, `checkThenOpenCreateOrShowFound`'s `cleanMessage`), the screen still says
         * so - a warning banner with different wording than "already linked" (which would be
         * false - that's exactly what was just disproven). Null whenever there's nothing stale to
         * report (a genuinely fresh match). */
        fun intent(context: Context, message: CapturedMessage, staleLinkedId: String? = null): Intent =
            Intent(context, CreateTransactionActivity::class.java)
                .putExtra(EXTRA_PACKAGE_NAME, message.packageName)
                .putExtra(EXTRA_APP_LABEL, message.appLabel)
                .putExtra(EXTRA_TITLE, message.title)
                .putExtra(EXTRA_TEXT, message.text)
                .putExtra(EXTRA_TIMESTAMP_MILLIS, message.timestampMillis)
                .putExtra(EXTRA_STALE_LINKED_ID, staleLinkedId)

        /** Edit mode - [transactionId] is always a *live-verified* id by the time this is called
         * (`MessagesFragment.verifyLinkedThenShow`'s `Found` branch), never trusted blindly here
         * either: [CreateTransactionFragment] re-verifies it itself on load (see its own KDoc) -
         * confirming existence twice in a row across two taps is cheap, and closes the race where
         * the transaction gets deleted in the gap between the list-row tap and this screen
         * finishing its own network round-trip. */
        fun editIntent(context: Context, message: CapturedMessage, transactionId: String): Intent =
            Intent(context, CreateTransactionActivity::class.java)
                .putExtra(EXTRA_PACKAGE_NAME, message.packageName)
                .putExtra(EXTRA_APP_LABEL, message.appLabel)
                .putExtra(EXTRA_TITLE, message.title)
                .putExtra(EXTRA_TEXT, message.text)
                .putExtra(EXTRA_TIMESTAMP_MILLIS, message.timestampMillis)
                .putExtra(EXTRA_EDIT_TRANSACTION_ID, transactionId)

        /** Create mode for a message no rule matched: the screen starts from `rules.manualDraft`
         * instead of matching the message again. */
        fun manualIntent(context: Context, message: CapturedMessage): Intent =
            intent(context, message).putExtra(EXTRA_MANUAL, true)

        fun isManual(intent: Intent): Boolean = intent.getBooleanExtra(EXTRA_MANUAL, false)

        fun messageFrom(intent: Intent): CapturedMessage =
            CapturedMessage(
                packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty(),
                appLabel = intent.getStringExtra(EXTRA_APP_LABEL).orEmpty(),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                timestampMillis = intent.getLongExtra(EXTRA_TIMESTAMP_MILLIS, 0L),
            )

        fun staleLinkedIdFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_STALE_LINKED_ID)

        fun editTransactionIdFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_EDIT_TRANSACTION_ID)
    }
}
