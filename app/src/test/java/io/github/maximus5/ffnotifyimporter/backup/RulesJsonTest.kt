package io.github.maximus5.ffnotifyimporter.backup

import io.github.maximus5.ffnotifyimporter.rules.ParseRule
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesJsonTest {

    private fun rule(
        id: String = "r1",
        label: String = "Test rule",
        packageNames: Set<String> = setOf("bank.app"),
        kind: RuleKind = RuleKind.WITHDRAWAL,
        sourceAccountName: String = "Bank Account A",
        destinationAccountName: String = "",
        dateFormat: String = "dd.MM.yy",
    ) = ParseRule(
        id = id,
        label = label,
        packageNames = packageNames,
        kind = kind,
        sourceAccountName = sourceAccountName,
        destinationAccountName = destinationAccountName,
        homeCurrency = "EUR",
        pattern = """spent (?<amount>[\d,]+) EUR at (?<counterparty>.+)""",
        descriptionTemplate = "{counterparty}",
        notesTemplate = "{body}",
        categoryTemplate = "Groceries",
        tagsTemplate = "recurring",
        enabled = true,
        dateFormat = dateFormat,
    )

    @Test
    fun `round-trips a rule through json`() {
        val original = rule()
        val imported = rulesFromJson(rulesToJson(listOf(original)))

        assertEquals(1, imported?.size)
        val round = imported!!.first()
        assertEquals(original.label, round.label)
        assertEquals(original.packageNames, round.packageNames)
        assertEquals(original.kind, round.kind)
        assertEquals(original.sourceAccountName, round.sourceAccountName)
        assertEquals(original.destinationAccountName, round.destinationAccountName)
        assertEquals(original.homeCurrency, round.homeCurrency)
        assertEquals(original.pattern, round.pattern)
        assertEquals(original.descriptionTemplate, round.descriptionTemplate)
        assertEquals(original.notesTemplate, round.notesTemplate)
        assertEquals(original.categoryTemplate, round.categoryTemplate)
        assertEquals(original.tagsTemplate, round.tagsTemplate)
        assertEquals(original.enabled, round.enabled)
        assertEquals(original.dateFormat, round.dateFormat)
    }

    @Test
    fun `import never carries over the id - this device's RulesDb internal identity`() {
        val imported = rulesFromJson(rulesToJson(listOf(rule(id = "r1"))))

        assertEquals("", imported?.first()?.id)
    }

    @Test
    fun `rulesFromJson returns null for a file that isn't shaped like a rules export at all`() {
        assertNull(rulesFromJson("not json"))
        assertNull(rulesFromJson("""{"notRules": []}"""))
    }

    @Test
    fun `rulesFromJson skips one malformed entry rather than failing the whole import`() {
        val json = """
            {"rules": [
                {"kind": "WITHDRAWAL", "pattern": "a", "homeCurrency": "EUR", "label": "Good"},
                {"kind": "NOT_A_KIND", "pattern": "b", "homeCurrency": "EUR"},
                {"kind": "DEPOSIT", "homeCurrency": "EUR"},
                {"kind": "DEPOSIT", "homeCurrency": "EUR", "pattern": "  "}
            ]}
        """.trimIndent()

        val imported = rulesFromJson(json)

        assertEquals(1, imported?.size)
        assertEquals("Good", imported?.first()?.label)
    }


    @Test
    fun `a rule with a blank or missing homeCurrency imports - blank means use the default account currency`() {
        val json = """
            {"rules": [
                {"label": "Blank", "kind": "WITHDRAWAL", "pattern": "a", "homeCurrency": ""},
                {"label": "Missing", "kind": "WITHDRAWAL", "pattern": "b"}
            ]}
        """.trimIndent()

        val imported = rulesFromJson(json)

        assertEquals(listOf("Blank", "Missing"), imported?.map { it.label })
        assertEquals("", imported?.first()?.homeCurrency)
    }
    @Test
    fun `an empty rule list round-trips to an empty list, not null`() {
        assertTrue(rulesFromJson(rulesToJson(emptyList()))?.isEmpty() == true)
    }

    @Test
    fun `amountManual round-trips and defaults to false when absent`() {
        val rule = ParseRule(id = "", label = "A", kind = RuleKind.DEPOSIT, homeCurrency = "EUR", pattern = "a", amountManual = true)
        val roundTripped = rulesFromJson(rulesToJson(listOf(rule, rule.copy(amountManual = false))))!!

        assertTrue(roundTripped[0].amountManual)
        assertFalse(roundTripped[1].amountManual)
        assertFalse(rulesFromJson("""{"rules": [{"kind": "WITHDRAWAL", "pattern": "a"}]}""")!!.single().amountManual)
    }

    @Test
    fun `accountApproximate round-trips and defaults to false when absent`() {
        val rule = ParseRule(id = "", label = "A", kind = RuleKind.WITHDRAWAL, homeCurrency = "EUR", pattern = "a", accountApproximate = true)
        val roundTripped = rulesFromJson(rulesToJson(listOf(rule, rule.copy(accountApproximate = false))))!!

        assertTrue(roundTripped[0].accountApproximate)
        assertFalse(roundTripped[1].accountApproximate)
        assertFalse(rulesFromJson("""{"rules": [{"kind": "WITHDRAWAL", "pattern": "a"}]}""")!!.single().accountApproximate)
    }
}
