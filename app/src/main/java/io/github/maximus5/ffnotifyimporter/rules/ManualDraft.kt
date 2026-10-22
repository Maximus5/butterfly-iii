package io.github.maximus5.ffnotifyimporter.rules

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import java.time.Instant

/**
 * The starting point for a transaction made by hand from a message no rule matched: no amount (it
 * is typed on the review screen, hence [DraftIssue.AMOUNT_ENTRY_REQUIRED]), a withdrawal (the kind
 * follows the accounts picked there anyway), the notification's title as the description and its
 * text as the notes. The currency, the account and the all-transactions tags are the same
 * defaults [RuleEngine] falls back to, passed in as plain data for the same reason.
 */
fun manualDraft(
    message: CapturedMessage,
    defaultAccountCurrency: String,
    defaultSourceAccount: String,
    allTransactionsTags: String,
): TransactionDraft = TransactionDraft(
    kind = RuleKind.WITHDRAWAL,
    date = Instant.ofEpochMilli(message.timestampMillis),
    amount = null,
    currencyCode = defaultAccountCurrency,
    foreignAmount = null,
    foreignCurrencyCode = null,
    sourceName = defaultSourceAccount.ifBlank { null },
    sourceAccountId = null,
    destinationName = null,
    destinationAccountId = null,
    description = message.title.ifBlank { message.text }.trim(),
    notes = message.text.trim(),
    category = null,
    tags = allTransactionsTags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
    issues = setOf(DraftIssue.AMOUNT_ENTRY_REQUIRED),
)
