package io.github.maximus5.ffnotifyimporter.rules

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ManualDraftTest {

    private val message = CapturedMessage(
        "bank.app", "Bank App", "SpenditCard: Payment received",
        "Your employer has sent you a payment to your SpenditCard.", timestampMillis = 5000,
    )

    @Test
    fun `an unmatched message becomes a withdrawal with no amount, its title as the description and its text as the notes`() {
        val draft = manualDraft(message, "EUR", "Bank Account A", "imported, manual")

        assertNull(draft.amount)
        assertEquals(RuleKind.WITHDRAWAL, draft.kind)
        assertEquals("SpenditCard: Payment received", draft.description)
        assertEquals("Your employer has sent you a payment to your SpenditCard.", draft.notes)
        assertEquals(Instant.ofEpochMilli(5000), draft.date)
        assertEquals("EUR", draft.currencyCode)
        assertEquals("Bank Account A", draft.sourceName)
        assertEquals(listOf("imported", "manual"), draft.tags)
        assertEquals(setOf(DraftIssue.AMOUNT_ENTRY_REQUIRED), draft.issues)
    }

    @Test
    fun `without defaults there is no account and no tags, and a blank title falls back to the text`() {
        val draft = manualDraft(message.copy(title = ""), "", "", "")

        assertNull(draft.sourceName)
        assertEquals(emptyList<String>(), draft.tags)
        assertEquals("Your employer has sent you a payment to your SpenditCard.", draft.description)
    }
}
