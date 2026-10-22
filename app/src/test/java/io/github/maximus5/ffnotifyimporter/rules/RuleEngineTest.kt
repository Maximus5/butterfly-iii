package io.github.maximus5.ffnotifyimporter.rules

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class RuleEngineTest {

    private fun rule(
        id: String = "r1",
        label: String = "Test rule",
        packageNames: Set<String> = emptySet(),
        kind: RuleKind = RuleKind.WITHDRAWAL,
        sourceAccountName: String = "Bank Account A",
        destinationAccountName: String = "",
        homeCurrency: String = "EUR",
        pattern: String,
        descriptionTemplate: String = "{title}",
        notesTemplate: String = "{body}",
        categoryTemplate: String = "",
        tagsTemplate: String = "",
        enabled: Boolean = true,
        dateFormat: String = "",
        accountApproximate: Boolean = false,
        amountManual: Boolean = false,
    ) = ParseRule(
        id = id,
        label = label,
        packageNames = packageNames,
        kind = kind,
        sourceAccountName = sourceAccountName,
        destinationAccountName = destinationAccountName,
        homeCurrency = homeCurrency,
        pattern = pattern,
        descriptionTemplate = descriptionTemplate,
        notesTemplate = notesTemplate,
        categoryTemplate = categoryTemplate,
        tagsTemplate = tagsTemplate,
        enabled = enabled,
        dateFormat = dateFormat,
        accountApproximate = accountApproximate,
        amountManual = amountManual,
    )

    private fun message(
        packageName: String = "bank.app",
        title: String = "Payment",
        text: String = "You spent 12,50 EUR at Shop1234",
    ) = CapturedMessage(packageName, "Bank App", title, text, timestampMillis = 1000)

    @Test
    fun `no rules for the app`() {
        val engine = RuleEngine(listOf(rule(packageNames = setOf("other.app"), pattern = """(?<amount>\d+)""")))
        val outcome = engine.match(message(packageName = "bank.app"))
        assertEquals(ParseOutcome.NoMatch(NoMatchReason.NO_RULES_FOR_APP), outcome)
    }

    @Test
    fun `no pattern matched`() {
        val engine = RuleEngine(listOf(rule(pattern = """nope (?<amount>\d+)""")))
        val outcome = engine.match(message())
        assertEquals(ParseOutcome.NoMatch(NoMatchReason.NO_PATTERN_MATCHED), outcome)
    }

    @Test
    fun `pattern matched but amount is unparsable`() {
        val engine = RuleEngine(listOf(rule(pattern = """spent (?<amount>[A-Za-z]+) EUR""")))
        val outcome = engine.match(message(text = "You spent many EUR at Shop1234"))
        assertEquals(ParseOutcome.NoMatch(NoMatchReason.AMOUNT_UNPARSABLE), outcome)
    }

    @Test
    fun `first matching rule in list order wins, later rules are not tried`() {
        val first = rule(id = "first", pattern = """spent (?<amount>[\d,]+) EUR""")
        val second = rule(id = "second", pattern = """spent (?<amount>[\d,]+) EUR""")
        val engine = RuleEngine(listOf(first, second))

        val outcome = engine.match(message()) as ParseOutcome.Matched
        assertEquals("first", outcome.rule.id)
    }

    @Test
    fun `builds a withdrawal draft with description and notes rendered from templates`() {
        val r = rule(
            sourceAccountName = "Bank Account A",
            pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
            descriptionTemplate = "{counterparty}",
            notesTemplate = "raw: {body}",
        )
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals(BigDecimal("12.50"), outcome.draft.amount)
        assertEquals("EUR", outcome.draft.currencyCode)
        assertEquals("Shop1234", outcome.draft.description)
        assertEquals("raw: You spent 12,50 EUR at Shop1234", outcome.draft.notes)
        assertEquals("Bank Account A", outcome.draft.sourceName)
        assertEquals(null, outcome.draft.destinationName)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `destination stays null when not set on the rule - no implicit fallback to anything`() {
        val r = rule(
            sourceAccountName = "Bank Account A",
            destinationAccountName = "",
            pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
        )
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals(null, outcome.draft.destinationName)
    }

    @Test
    fun `destination can reference a captured group explicitly, same macro mechanism as templates`() {
        val r = rule(
            sourceAccountName = "Bank Account A",
            destinationAccountName = "{counterparty}",
            pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
        )
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals("Shop1234", outcome.draft.destinationName)
    }

    @Test
    fun `transfer draft uses both fixed account names, ignoring any captured counterparty`() {
        val r = rule(
            kind = RuleKind.TRANSFER,
            sourceAccountName = "Bank Account A",
            destinationAccountName = "Savings EUR",
            pattern = """moved (?<amount>[\d,]+) EUR""",
        )
        val outcome = RuleEngine(listOf(r)).match(message(text = "You moved 12,50 EUR")) as ParseOutcome.Matched

        assertEquals("Bank Account A", outcome.draft.sourceName)
        assertEquals("Savings EUR", outcome.draft.destinationName)
    }

    @Test
    fun `foreign currency without an explicit foreign amount flags LOCAL_AMOUNT_MISSING`() {
        val r = rule(
            homeCurrency = "EUR",
            pattern = """spent (?<amount>[\d,]+) (?<currency>GBP)""",
        )
        val outcome = RuleEngine(listOf(r)).match(message(text = "You spent 10,00 GBP")) as ParseOutcome.Matched

        assertEquals(null, outcome.draft.amount)
        assertEquals(BigDecimal("10.00"), outcome.draft.foreignAmount)
        assertEquals("GBP", outcome.draft.foreignCurrencyCode)
        assertEquals(setOf(DraftIssue.LOCAL_AMOUNT_MISSING), outcome.draft.issues)
    }

    @Test
    fun `an explicit foreign amount alongside the home amount is trusted as-is`() {
        val r = rule(
            homeCurrency = "EUR",
            pattern = """charged (?<amount>[\d,]+) EUR \((?<foreignAmount>[\d,]+) (?<foreignCurrency>GBP)\)""",
        )
        val outcome = RuleEngine(listOf(r))
            .match(message(text = "You were charged 11,50 EUR (10,00 GBP)")) as ParseOutcome.Matched

        assertEquals(BigDecimal("11.50"), outcome.draft.amount)
        assertEquals(BigDecimal("10.00"), outcome.draft.foreignAmount)
        assertEquals("GBP", outcome.draft.foreignCurrencyCode)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `category and tags templates render and tags split on comma`() {
        val r = rule(
            pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
            categoryTemplate = "Groceries",
            tagsTemplate = "recurring, {counterparty} ,  ",
        )
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals("Groceries", outcome.draft.category)
        assertEquals(listOf("recurring", "Shop1234"), outcome.draft.tags)
    }


    @Test
    fun `defaultSourceAccount is the source of a withdrawal when the rule names no account, and the account-selection issue stays`() {
        val r = rule(sourceAccountName = "", destinationAccountName = "", pattern = """spent (?<amount>[\d,]+) EUR""")
        val outcome = RuleEngine(listOf(r), defaultSourceAccount = "My Account").match(message()) as ParseOutcome.Matched

        assertEquals("My Account", outcome.draft.sourceName)
        assertNull(outcome.draft.destinationName)
        assertTrue(DraftIssue.ACCOUNT_SELECTION_REQUIRED in outcome.draft.issues)
    }

    @Test
    fun `defaultSourceAccount is the destination of a deposit`() {
        val r = rule(kind = RuleKind.DEPOSIT, sourceAccountName = "", destinationAccountName = "", pattern = """spent (?<amount>[\d,]+) EUR""")
        val outcome = RuleEngine(listOf(r), defaultSourceAccount = "My Account").match(message()) as ParseOutcome.Matched

        assertNull(outcome.draft.sourceName)
        assertEquals("My Account", outcome.draft.destinationName)
    }

    @Test
    fun `defaultSourceAccount is ignored when the rule names an account itself`() {
        val r = rule(sourceAccountName = "Bank Account A", pattern = """spent (?<amount>[\d,]+) EUR""")
        val outcome = RuleEngine(listOf(r), defaultSourceAccount = "My Account").match(message()) as ParseOutcome.Matched

        assertEquals("Bank Account A", outcome.draft.sourceName)
        assertTrue(DraftIssue.ACCOUNT_SELECTION_REQUIRED !in outcome.draft.issues)
    }

    @Test
    fun `a blank defaultSourceAccount leaves a rule without accounts as it was`() {
        val r = rule(sourceAccountName = "", pattern = """spent (?<amount>[\d,]+) EUR""")
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertNull(outcome.draft.sourceName)
        assertNull(outcome.draft.destinationName)
    }
    @Test
    fun `defaultAccountCurrency fills in currencyCode when the rule's own homeCurrency is blank`() {
        val r = rule(homeCurrency = "", pattern = """spent (?<amount>[\d,]+)""")
        val outcome = RuleEngine(listOf(r), defaultAccountCurrency = "USD").match(message()) as ParseOutcome.Matched

        assertEquals("USD", outcome.draft.currencyCode)
    }

    @Test
    fun `defaultAccountCurrency is used for the foreign-amount comparison too, not just currencyCode`() {
        // Captured currency matches the *fallback*, not any currency written in the rule itself
        // (there is none) - should be treated as home, not foreign.
        val r = rule(homeCurrency = "", pattern = """spent (?<amount>[\d,]+) (?<currency>[A-Z]{3})""")
        val outcome = RuleEngine(listOf(r), defaultAccountCurrency = "USD")
            .match(message(text = "You spent 12,50 USD at Shop1234")) as ParseOutcome.Matched

        assertEquals(BigDecimal("12.50"), outcome.draft.amount)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `with no home currency anywhere the captured currency is the transaction currency, not foreign`() {
        val r = rule(homeCurrency = "", pattern = """spent (?<amount>[\d,]+) (?<currency>[A-Z]{3})""")
        val outcome = RuleEngine(listOf(r))
            .match(message(text = "You spent 13,34 EUR at Shop1234")) as ParseOutcome.Matched

        assertEquals(BigDecimal("13.34"), outcome.draft.amount)
        assertEquals("EUR", outcome.draft.currencyCode)
        assertNull(outcome.draft.foreignAmount)
        assertNull(outcome.draft.foreignCurrencyCode)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `with no home currency and no captured currency the currency stays blank`() {
        val r = rule(homeCurrency = "", pattern = """spent (?<amount>[\d,]+)""")
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals("", outcome.draft.currencyCode)
        assertNull(outcome.draft.foreignAmount)
    }

    @Test
    fun `allTransactionsTags is appended to every draft's own tags`() {
        val r = rule(pattern = """spent (?<amount>[\d,]+) EUR""", tagsTemplate = "recurring")
        val outcome = RuleEngine(listOf(r), allTransactionsTags = "firefly-importer").match(message()) as ParseOutcome.Matched

        assertEquals(listOf("recurring", "firefly-importer"), outcome.draft.tags)
    }

    @Test
    fun `allTransactionsTags supports multiple comma-separated tags`() {
        val r = rule(pattern = """spent (?<amount>[\d,]+) EUR""", tagsTemplate = "recurring")
        val outcome = RuleEngine(listOf(r), allTransactionsTags = "firefly-importer, bank-import").match(message()) as ParseOutcome.Matched

        assertEquals(listOf("recurring", "firefly-importer", "bank-import"), outcome.draft.tags)
    }

    @Test
    fun `allTransactionsTags is not duplicated when the rule's own tags already include it`() {
        val r = rule(pattern = """spent (?<amount>[\d,]+) EUR""", tagsTemplate = "firefly-importer, recurring")
        val outcome = RuleEngine(listOf(r), allTransactionsTags = "firefly-importer").match(message()) as ParseOutcome.Matched

        assertEquals(listOf("firefly-importer", "recurring"), outcome.draft.tags)
    }

    @Test
    fun `blank category and tags templates produce no category and no tags`() {
        val r = rule(pattern = """spent (?<amount>[\d,]+) EUR""")
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals(null, outcome.draft.category)
        assertTrue(outcome.draft.tags.isEmpty())
    }

    @Test
    fun `currency symbol before the amount is normalized to its code via currencySymbols`() {
        val r = rule(
            homeCurrency = "EUR",
            pattern = """spent (?<currency>[€$£])(?<amount>[\d,]+)""",
        )
        val outcome = RuleEngine(listOf(r), currencySymbols = mapOf("€" to "EUR"))
            .match(message(text = "You spent €12,50")) as ParseOutcome.Matched

        assertEquals(BigDecimal("12.50"), outcome.draft.amount)
        assertEquals("EUR", outcome.draft.currencyCode)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `an unmapped currency symbol is treated as foreign as-is`() {
        val r = rule(
            homeCurrency = "EUR",
            pattern = """spent (?<currency>[€$£])(?<amount>[\d,]+)""",
        )
        val outcome = RuleEngine(listOf(r), currencySymbols = mapOf("€" to "EUR"))
            .match(message(text = "You spent £12,50")) as ParseOutcome.Matched

        assertEquals("£", outcome.draft.foreignCurrencyCode)
        assertEquals(setOf(DraftIssue.LOCAL_AMOUNT_MISSING), outcome.draft.issues)
    }

    @Test
    fun `disabled rules are never candidates`() {
        val engine = RuleEngine(listOf(rule(pattern = """(?<amount>\d+)""", enabled = false)))
        val outcome = engine.match(message(text = "123"))
        assertEquals(ParseOutcome.NoMatch(NoMatchReason.NO_RULES_FOR_APP), outcome)
    }

    @Test
    fun `date defaults to the notification's own arrival time when dateFormat is blank`() {
        val r = rule(pattern = """spent (?<amount>[\d,]+) EUR""")
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertEquals(Instant.ofEpochMilli(1000), outcome.draft.date)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `a captured date group is ignored unless dateFormat is also set`() {
        val r = rule(pattern = """spent (?<amount>[\d,]+) EUR.*am (?<date>[\d.]+) statt""")
        val outcome = RuleEngine(listOf(r))
            .match(message(text = "You spent 12,50 EUR, charged am 01.02.30 statt")) as ParseOutcome.Matched

        assertEquals(Instant.ofEpochMilli(1000), outcome.draft.date)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `a captured date overrides the notification's arrival time when dateFormat parses it`() {
        val r = rule(
            pattern = """spent (?<amount>[\d,]+) EUR.*am (?<date>[\d.]+) statt""",
            dateFormat = "dd.MM.yy",
        )
        val outcome = RuleEngine(listOf(r))
            .match(message(text = "You spent 12,50 EUR, charged am 01.02.30 statt")) as ParseOutcome.Matched

        assertEquals(Instant.parse("2030-02-01T12:00:00Z"), outcome.draft.date)
        assertTrue(outcome.draft.issues.isEmpty())
    }

    @Test
    fun `a captured date that does not match dateFormat flags DATE_UNPARSABLE and falls back`() {
        val r = rule(
            pattern = """spent (?<amount>[\d,]+) EUR.*am (?<date>[\d.]+) statt""",
            dateFormat = "yyyy-MM-dd",
        )
        val outcome = RuleEngine(listOf(r))
            .match(message(text = "You spent 12,50 EUR, charged am 01.02.30 statt")) as ParseOutcome.Matched

        assertEquals(Instant.ofEpochMilli(1000), outcome.draft.date)
        assertEquals(setOf(DraftIssue.DATE_UNPARSABLE), outcome.draft.issues)
    }

    @Test
    fun `WITHDRAWAL with a blank source account flags ACCOUNT_SELECTION_REQUIRED`() {
        val r = rule(kind = RuleKind.WITHDRAWAL, sourceAccountName = "", pattern = """spent (?<amount>[\d,]+)""")
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertTrue(DraftIssue.ACCOUNT_SELECTION_REQUIRED in outcome.draft.issues)
    }

    @Test
    fun `WITHDRAWAL with a real source account does not flag ACCOUNT_SELECTION_REQUIRED, regardless of destination`() {
        val r = rule(kind = RuleKind.WITHDRAWAL, sourceAccountName = "Bank Account A", destinationAccountName = "", pattern = """spent (?<amount>[\d,]+)""")
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertTrue(DraftIssue.ACCOUNT_SELECTION_REQUIRED !in outcome.draft.issues)
    }

    @Test
    fun `DEPOSIT with a blank destination account flags ACCOUNT_SELECTION_REQUIRED`() {
        val r = rule(kind = RuleKind.DEPOSIT, sourceAccountName = "", destinationAccountName = "", pattern = """spent (?<amount>[\d,]+)""")
        val outcome = RuleEngine(listOf(r)).match(message()) as ParseOutcome.Matched

        assertTrue(DraftIssue.ACCOUNT_SELECTION_REQUIRED in outcome.draft.issues)
    }

    @Test
    fun `TRANSFER requires both sides, flags ACCOUNT_SELECTION_REQUIRED if either is blank`() {
        val bothBlank = rule(kind = RuleKind.TRANSFER, sourceAccountName = "", destinationAccountName = "", pattern = """spent (?<amount>[\d,]+)""")
        val sourceOnly = rule(kind = RuleKind.TRANSFER, sourceAccountName = "Bank Account A", destinationAccountName = "", pattern = """spent (?<amount>[\d,]+)""")
        val bothFilled = rule(
            kind = RuleKind.TRANSFER,
            sourceAccountName = "Bank Account A",
            destinationAccountName = "Bank Account B",
            pattern = """spent (?<amount>[\d,]+)""",
        )

        assertTrue(DraftIssue.ACCOUNT_SELECTION_REQUIRED in (RuleEngine(listOf(bothBlank)).match(message()) as ParseOutcome.Matched).draft.issues)
        assertTrue(DraftIssue.ACCOUNT_SELECTION_REQUIRED in (RuleEngine(listOf(sourceOnly)).match(message()) as ParseOutcome.Matched).draft.issues)
        assertTrue(DraftIssue.ACCOUNT_SELECTION_REQUIRED !in (RuleEngine(listOf(bothFilled)).match(message()) as ParseOutcome.Matched).draft.issues)
    }

    @Test
    fun `a built-in-shaped rule (blank accounts both sides, WITHDRAWAL) flags ACCOUNT_SELECTION_REQUIRED`() {
        val outcome = RuleEngine(listOf(BuiltInRules.CURRENCY_AFTER_AMOUNT), defaultAccountCurrency = "EUR")
            .match(message(text = "100.50 EUR at Shop1234")) as ParseOutcome.Matched

        assertTrue(DraftIssue.ACCOUNT_SELECTION_REQUIRED in outcome.draft.issues)
    }

    @Test
    fun `a rule marked account approximate yields the approximate issue, others do not`() {
        val text = "You spent 12,50 EUR at Shop1234"
        val plain = RuleEngine(listOf(rule(pattern = """(?<amount>\d+,\d+) (?<currency>[A-Z]{3})""")))
            .match(message(text = text)) as ParseOutcome.Matched
        val approximate = RuleEngine(listOf(rule(pattern = """(?<amount>\d+,\d+) (?<currency>[A-Z]{3})""", accountApproximate = true)))
            .match(message(text = text)) as ParseOutcome.Matched

        assertFalse(DraftIssue.ACCOUNT_APPROXIMATE in plain.draft.issues)
        assertTrue(DraftIssue.ACCOUNT_APPROXIMATE in approximate.draft.issues)
        assertEquals("Bank Account A", approximate.draft.sourceName)
    }

    @Test
    fun `a rule marked amount manual matches a notification without an amount and leaves the amount empty`() {
        val salary = message(title = "SpenditCard: Payment received", text = "Your employer has sent you a payment to your SpenditCard.")
        val rule = rule(pattern = """Payment received.*employer""", amountManual = true)

        val outcome = RuleEngine(listOf(rule)).match(salary) as ParseOutcome.Matched

        assertNull(outcome.draft.amount)
        assertTrue(DraftIssue.AMOUNT_ENTRY_REQUIRED in outcome.draft.issues)
        assertEquals("Bank Account A", outcome.draft.sourceName)
        assertEquals("EUR", outcome.draft.currencyCode)
    }

    @Test
    fun `without the amount manual flag a notification with no amount still does not match`() {
        val salary = message(title = "SpenditCard: Payment received", text = "Your employer has sent you a payment to your SpenditCard.")

        val outcome = RuleEngine(listOf(rule(pattern = """Payment received.*employer"""))).match(salary)

        assertEquals(ParseOutcome.NoMatch(NoMatchReason.AMOUNT_UNPARSABLE), outcome)
    }

    @Test
    fun `a rule marked amount manual still uses an amount the pattern does capture`() {
        val rule = rule(pattern = """(?<amount>\d+,\d+) EUR""", amountManual = true)

        val outcome = RuleEngine(listOf(rule)).match(message()) as ParseOutcome.Matched

        assertEquals(BigDecimal("12.50"), outcome.draft.amount)
        assertFalse(DraftIssue.AMOUNT_ENTRY_REQUIRED in outcome.draft.issues)
    }

    @Test
    fun `the draft carries the matching rule's label for display, null when the label is blank`() {
        val pattern = """(?<amount>\d+,\d+) EUR"""
        val labelled = RuleEngine(listOf(rule(label = "Bank rule", pattern = pattern))).match(message()) as ParseOutcome.Matched
        val blank = RuleEngine(listOf(rule(label = "", pattern = pattern))).match(message()) as ParseOutcome.Matched

        assertEquals("Bank rule", labelled.draft.ruleLabel)
        assertEquals(null, blank.draft.ruleLabel)
    }
}
