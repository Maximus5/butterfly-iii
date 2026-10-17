package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.data.RetentionController
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.FireflyTransactionRequest
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.prefs.NotificationPrefs
import io.github.maximus5.ffnotifyimporter.prefs.RetentionPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.accountApproximate
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHash
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHashIgnoringAccounts
import java.math.BigDecimal

/**
 * Background counterpart to `ui.MessagesFragment`'s manual "Создать" button - auto-posts a
 * transaction for a captured notification without the user opening the app at all, when
 * [FireflyPrefs.isAutoCreateEnabled]. Only ever enqueued through [AutoCreateScheduler], which is
 * also what guarantees these never run two at a time (see its own KDoc, the user's own explicit
 * requirement) - this class assumes it has the dedup log/Firefly to itself for the duration of
 * [doWork].
 *
 * Decided design (asked the user directly, not assumed, see TODO.md): a likely duplicate is
 * *skipped*, not posted and not auto-resolved - unlike the manual path's "create anyway"
 * confirmation, there is no human here to make that call, so the safer default is "don't",
 * surfaced as a system notification ([AutoCreateNotifications.notifySkippedDuplicate]) so the user
 * can still decide by hand (`checkIfCreated`/"Create Again"). A genuine success also gets a system
 * notification - manual creation's own feedback is the in-app OK dialog, which makes no sense for
 * something that happened with the app not even open.
 *
 * [Result.retry], up to [MAX_ATTEMPTS], applies **only** to a failed dedup-check *verification*
 * ([DuplicateCheckResult.SearchFailed] - a local-cache hit that couldn't be confirmed against
 * Firefly, see that type's own KDoc for why this is no longer treated as "assume no duplicate") -
 * this is the actual "WorkManager for retrying failed API calls" use AGENTS.md's own stack note
 * was written for, and it's safe specifically because nothing has been written yet at that point,
 * a repeated *read*.
 * Capped rather than left to WorkManager's default indefinite retry: [AutoCreateScheduler]
 * enqueues every message into one single `ExistingWorkPolicy.APPEND` chain, so a stuck-retrying
 * item would otherwise block every later notification's auto-create attempt forever (a dead/
 * revoked token would silently stall the entire feature, not just one message) - exceeding the
 * cap gives up ([Result.failure]) instead, unblocking the chain, and tells the user once via
 * notification rather than retrying it forever in silence.
 *
 * **[post] itself never retries, on purpose, the user's own catch of a real inconsistency**: an
 * earlier version of this class *did* retry a failed `createTransaction` call too, the same
 * `Result.retry()`/[MAX_ATTEMPTS] shape - directly contradicting the non-idempotency reasoning
 * already settled and documented on `network.FireflyApiClient.createTransaction` itself (never
 * wrapped in `network.withNetworkRetry`, precisely because a lost response *after* Firefly already
 * wrote the transaction would retry into a genuine second one - "the caller decides whether to try
 * again", that KDoc says, meaning a *human*, the same way `FireflyApiClient.updateTransaction`'s
 * own failure just surfaces and stops). Retrying automatically at this layer carries the exact
 * same risk as retrying inside [FireflyApiClient] would have - it's the same non-idempotent write
 * either way - so [post] now matches `updateTransaction`'s own posture: one failure, one
 * notification, stop.
 * The user resolves it by hand afterward (`checkIfCreated`/manual create), the same
 * human-supervised path every other ambiguous case in this app already goes through.
 */
class AutoCreateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = FireflyPrefs(applicationContext)
        // Re-checked here, not just at enqueue time (AutoCreateScheduler) - the setting, or the
        // connection itself, may have changed while this sat in the queue behind an earlier item.
        if (!prefs.isAutoCreateEnabled() || !prefs.isConfigured()) return Result.success()

        val message = capturedMessageFrom(inputData)
        val draft = matchRule(applicationContext, message)
        if (draft == null) {
            DiagnosticLog.log(applicationContext, "auto-create: no rule matched")
            return Result.success()
        }

        val resolved = resolveAmountQuietly(draft, prefs)
        // Can't auto-post without a resolved amount - same "flag it, don't guess" precedent as
        // everywhere else LOCAL_AMOUNT_MISSING shows up, not a failure worth retrying or notifying
        // about (the message is still sitting in the Messages list for the user to handle by hand).
        val amount = resolved.amount
        if (amount == null) {
            DiagnosticLog.log(applicationContext, "auto-create '${resolved.ruleLabel.orEmpty()}': skipped, no resolved amount")
            return Result.success()
        }
        DiagnosticLog.log(applicationContext, "auto-create '${resolved.ruleLabel.orEmpty()}': matched, issues=${resolved.issues}")

        // Fixed a real bug, the user's own catch: a rule can leave the account side(s) its own
        // kind requires blank (most notably rules.BuiltInRules's generic fallback rules, which
        // can't know which real Firefly account any given app's money should land in) - Firefly's
        // API rejects a post like that every time, so attempting it just burned through every
        // retry (with exponential backoff) before finally showing a failure notification, which
        // looked like silence for a long stretch. No point trying at all - same review
        // notification posting.ReviewNotificationWorker shows when auto-create is off entirely,
        // since a human has to pick the account by hand regardless.
        if (DraftIssue.ACCOUNT_SELECTION_REQUIRED in resolved.issues || DraftIssue.ACCOUNT_APPROXIMATE in resolved.issues) {
            DiagnosticLog.log(applicationContext, "auto-create: stops at a review notification")
            ReviewNotifications.notifyMatch(applicationContext, message, resolved)
            return Result.success()
        }

        return when (val result = checkForDuplicate(applicationContext, resolved)) {
            is DuplicateCheckResult.None -> post(resolved, amount, message, prefs)
            is DuplicateCheckResult.SearchFailed -> {
                DiagnosticLog.log(applicationContext, "auto-create: duplicate check failed (attempt ${runAttemptCount + 1})")
                // Safe to retry - nothing has been written yet, this is only a read (unlike
                // post()'s own createTransaction call, which never retries, see this class's own
                // KDoc). A dedup check that couldn't be verified is no longer "assume no
                // duplicate" (the user's own explicit call, see DuplicateCheckResult.SearchFailed's
                // KDoc), so this gets a chance to recover from a transient network blip first.
                if (runAttemptCount >= MAX_ATTEMPTS) {
                    AutoCreateNotifications.notifyFailed(applicationContext, message, result.message)
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
            is DuplicateCheckResult.Found -> {
                DiagnosticLog.log(applicationContext, "auto-create: skipped as a duplicate of #${result.id}")
                if (NotificationPrefs(applicationContext).isNotifySkippedDuplicatesEnabled()) {
                    AutoCreateNotifications.notifySkippedDuplicate(
                        applicationContext, prefs.getBaseUrl(), message,
                        amount, resolved.currencyCode, resolved.description, result.id,
                        resolved.ruleLabel,
                    )
                }
                Result.success()
            }
            is DuplicateCheckResult.FoundMultiple -> {
                DiagnosticLog.log(applicationContext, "auto-create: skipped as a duplicate of #${result.mostRecentId} (${result.count} matches)")
                if (NotificationPrefs(applicationContext).isNotifySkippedDuplicatesEnabled()) {
                    AutoCreateNotifications.notifySkippedDuplicate(
                        applicationContext, prefs.getBaseUrl(), message,
                        amount, resolved.currencyCode, resolved.description, result.mostRecentId,
                        resolved.ruleLabel,
                    )
                }
                Result.success()
            }
        }
    }

    private suspend fun post(draft: TransactionDraft, amount: BigDecimal, message: CapturedMessage, prefs: FireflyPrefs): Result {
        val request = FireflyTransactionRequest(
            type = draft.kind.name.lowercase(),
            dateIso = draft.date.toString(),
            amount = amount.toPlainString(),
            description = draft.description,
            currencyCode = draft.currencyCode,
            sourceId = draft.sourceAccountId,
            sourceName = draft.sourceName,
            destinationId = draft.destinationAccountId,
            destinationName = draft.destinationName,
            categoryName = draft.category,
            tags = draft.tags,
            notes = draft.notes.takeIf { it.isNotBlank() },
            foreignAmount = draft.foreignAmount?.toPlainString(),
            foreignCurrencyCode = draft.foreignCurrencyCode,
        )
        val client = prefs.apiClient()
        return when (val result = client.createTransaction(request)) {
            is FireflyApiClient.Result.Success -> {
                val createdDb = CreatedTransactionsDb.getInstance(applicationContext)
                createdDb.recordCreated(
                    hashWithDescription = transactionDedupHash(draft, true),
                    hashWithoutDescription = transactionDedupHash(draft, false),
                    hashWithoutAccounts = transactionDedupHashIgnoringAccounts(draft),
                    transactionDateMillis = draft.date.toEpochMilli(),
                    fireflyTransactionId = result.value,
                )
                RetentionController.purgeCreatedTransactions(createdDb, RetentionPrefs(applicationContext).getRetentionDays())
                MessageStore.shared.setTransactionId(message, result.value, draft.ruleLabel)
                DiagnosticLog.log(applicationContext, "auto-create '${draft.ruleLabel.orEmpty()}': created #${result.value}, approx=${draft.accountApproximate}")
                // A precise source just created this payment: the bank message that only guessed the
                // account (still waiting for review in the shade) is redundant now.
                if (!draft.accountApproximate) ReviewNotifications.supersedeApproximate(applicationContext, draft)
                AutoCreateNotifications.notifySuccess(
                    applicationContext, prefs.getBaseUrl(), message,
                    amount, draft.currencyCode, draft.description, result.value,
                    draft.ruleLabel,
                )
                Result.success()
            }
            is FireflyApiClient.Result.Failure -> {
                DiagnosticLog.log(applicationContext, "auto-create: create failed")
                // Never retried - see this class's own KDoc. A lost response after Firefly
                // already applied the write would retry into a genuine second transaction, the
                // exact thing this app's dedup machinery exists to prevent in the first place.
                AutoCreateNotifications.notifyFailed(applicationContext, message, result.message)
                Result.failure()
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 5

        fun inputData(message: CapturedMessage): Data = capturedMessageInputData(message)
    }
}
