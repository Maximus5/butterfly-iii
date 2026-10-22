package io.github.maximus5.ffnotifyimporter.rules

import org.junit.Assert.assertTrue
import org.junit.Test

class RuleValidationTest {

    private fun validRule() = ParseRule(
        id = "r1",
        label = "Test",
        packageNames = setOf("bank.app"),
        kind = RuleKind.WITHDRAWAL,
        sourceAccountName = "Bank Account A",
        homeCurrency = "EUR",
        pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
        descriptionTemplate = "{counterparty}",
        notesTemplate = "{body}",
    )

    @Test
    fun `a well-formed rule has no errors`() {
        assertTrue(validateRule(validRule()).isEmpty())
    }

    @Test
    fun `pattern must define the amount group`() {
        val errors = validateRule(validRule().copy(pattern = """spent (?<notAmount>[\d,]+)"""))
        assertTrue(errors.any { it.contains("amount") })
    }

    @Test
    fun `a rule marked amount manual does not need the amount group`() {
        val rule = validRule().copy(pattern = """Payment received""", descriptionTemplate = "{title}", amountManual = true)
        assertTrue(validateRule(rule).isEmpty())
    }

    @Test
    fun `invalid regex is reported`() {
        val errors = validateRule(validRule().copy(pattern = "("))
        assertTrue(errors.any { it.contains("regular expression", ignoreCase = true) })
    }

    @Test
    fun `withdrawal rules need a source account`() {
        val errors = validateRule(validRule().copy(sourceAccountName = ""))
        assertTrue(errors.any { it.contains("source", ignoreCase = true) })
    }

    @Test
    fun `deposit rules need a destination account`() {
        val errors = validateRule(
            validRule().copy(kind = RuleKind.DEPOSIT, sourceAccountName = "", destinationAccountName = ""),
        )
        assertTrue(errors.any { it.contains("destination", ignoreCase = true) })
    }

    @Test
    fun `transfer rules need both a source and a destination account`() {
        val errors = validateRule(
            validRule().copy(kind = RuleKind.TRANSFER, sourceAccountName = "", destinationAccountName = ""),
        )
        assertTrue(errors.any { it.contains("source", ignoreCase = true) })
        assertTrue(errors.any { it.contains("destination", ignoreCase = true) })
    }

    @Test
    fun `a macro template counts as set for the source-destination requirement`() {
        val errors = validateRule(validRule().copy(sourceAccountName = "{counterparty}"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `an unknown macro in source or destination account is not reported - renderTemplate handles it gracefully`() {
        val errors = validateRule(validRule().copy(sourceAccountName = "{doesNotExist}"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `no apps selected is reported`() {
        val errors = validateRule(validRule().copy(packageNames = emptySet()))
        assertTrue(errors.any { it.contains("app", ignoreCase = true) })
    }

    @Test
    fun `an unknown macro in a template is not reported - renderTemplate handles it gracefully`() {
        // Real case: editing a pattern down to just {amount} while a template field still
        // mentions a macro from an earlier, richer draft of the pattern used to block saving
        // outright ("Unknown macro(s): ..."), even though it's harmless - renderTemplate just
        // leaves it as literal text.
        val errors = validateRule(validRule().copy(descriptionTemplate = "{doesNotExist}"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `title and body macros are always valid even if not declared groups`() {
        val errors = validateRule(validRule().copy(descriptionTemplate = "{title}: {body}"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a blank date format is valid - it just means use the notification's own time`() {
        val errors = validateRule(validRule().copy(dateFormat = ""))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a well-formed date format is valid`() {
        val errors = validateRule(validRule().copy(dateFormat = "dd.MM.yy"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a malformed date format is reported`() {
        val errors = validateRule(validRule().copy(dateFormat = "dd.MM.'yy"))
        assertTrue(errors.any { it.contains("date format", ignoreCase = true) })
    }
}
