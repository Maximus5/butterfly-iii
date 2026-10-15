package io.github.maximus5.ffnotifyimporter.rules

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCategoriesTest {

    private fun exact(key: String, category: String, active: Boolean = true) =
        AutoCategoryEntry(normalizeCounterpartyKey(key), CategoryMatch.EQUALS, category, active)

    private fun contains(key: String, category: String, active: Boolean = true) =
        AutoCategoryEntry(normalizeCounterpartyKey(key), CategoryMatch.CONTAINS, category, active, learned = false)

    @Test
    fun `key is case-insensitive and whitespace-normalized`() {
        assertEquals("shop 1234", normalizeCounterpartyKey("  Shop   1234 "))
        val categories = AutoCategories(listOf(exact("Shop 1234", "Food")))
        assertEquals("Food", categories.categoryFor("SHOP  1234"))
        assertNull(categories.categoryFor("Shop 12345"))
        assertNull(categories.categoryFor(null))
        assertNull(categories.categoryFor("  "))
    }

    @Test
    fun `an inactive exact entry substitutes nothing and also beats a contains entry`() {
        val categories = AutoCategories(listOf(contains("shop", "Food"), exact("Shop1234", "Clothes", active = false)))
        assertNull(categories.categoryFor("Shop1234"))
        assertEquals("Food", categories.categoryFor("Shop5678"))
    }

    @Test
    fun `exact beats contains, contains goes by list order and skips inactive entries`() {
        val categories = AutoCategories(
            listOf(contains("cafe", "Restaurants", active = false), contains("shop", "A"), contains("op", "B"), exact("cafe shop1234", "C")),
        )
        assertEquals("C", categories.categoryFor("Cafe Shop1234"))
        assertEquals("A", categories.categoryFor("Big Shop"))
        assertNull(categories.categoryFor("Cafe Corner"))
    }

    @Test
    fun `a category is learned once, from an edit, when learning is on`() {
        val empty = AutoCategories.EMPTY
        val learned = empty.afterCategoryEdit("Shop1234", "Food", categoryEdited = true, ruleSetCategory = false, learnEnabled = true)
        assertEquals(listOf(exact("Shop1234", "Food")), learned)

        // The same counterparty with a different category later: the entry is switched off, never relearned.
        val switchedOff = AutoCategories(learned!!)
            .afterCategoryEdit("shop1234", "Clothes", categoryEdited = true, ruleSetCategory = false, learnEnabled = true)
        assertEquals(listOf(exact("Shop1234", "Food", active = false)), switchedOff)
        assertNull(
            AutoCategories(switchedOff!!)
                .afterCategoryEdit("Shop1234", "Clothes", categoryEdited = true, ruleSetCategory = false, learnEnabled = true),
        )
    }

    @Test
    fun `nothing is learned or switched off unless every condition holds`() {
        val withEntry = AutoCategories(listOf(exact("Shop1234", "Food")))
        fun learn(c: AutoCategories, counterparty: String? = "Shop1234", category: String? = "Clothes", edited: Boolean = true, rule: Boolean = false, on: Boolean = true) =
            c.afterCategoryEdit(counterparty, category, edited, rule, on)

        assertNull(learn(withEntry, on = false))
        assertNull(learn(withEntry, edited = false))
        assertNull(learn(withEntry, rule = true))
        assertNull(learn(AutoCategories.EMPTY, counterparty = null))
        assertNull(learn(AutoCategories.EMPTY, category = " "))
        // The same category as the entry already has: nothing to change.
        assertNull(learn(withEntry, category = "food"))
        // Clearing a substituted category counts as changing it.
        assertEquals(listOf(exact("Shop1234", "Food", active = false)), learn(withEntry, category = ""))
    }

    @Test
    fun `an edit of a category that a contains entry supplied is learned as an exact exception`() {
        val manual = AutoCategories(listOf(contains("cafe", "Restaurants")))
        val updated = manual.afterCategoryEdit("Cafe Corner", "Groceries", true, false, true)!!
        assertEquals(2, updated.size)
        assertEquals("Groceries", AutoCategories(updated).categoryFor("Cafe Corner"))
        assertEquals("Restaurants", AutoCategories(updated).categoryFor("Cafe Other"))
    }

    @Test
    fun `json round trip keeps order and flags, skips unreadable entries`() {
        val entries = listOf(exact("Shop1234", "Food"), contains("cafe", "Restaurants", active = false))
        assertEquals(entries, autoCategoriesFromJson(autoCategoriesToJson(entries)))

        val array = JSONArray("""[{"key":" Shop 1 ","category":"X"},{"key":"","category":"Y"},{"key":"k","category":"Z","match":"NOPE"},"junk"]""")
        val parsed = autoCategoriesFromJson(array)
        assertEquals(listOf(AutoCategoryEntry("shop 1", CategoryMatch.EQUALS, "X")), parsed)
        assertTrue(autoCategoriesFromJson(null).isEmpty())
    }

    @Test
    fun `the engine substitutes only when the rule gives no category and a counterparty was captured`() {
        fun rule(categoryTemplate: String, pattern: String) = ParseRule(
            id = "r", label = "Pay", packageNames = emptySet(), kind = RuleKind.WITHDRAWAL,
            sourceAccountName = "Bank Account A", destinationAccountName = "{counterparty}",
            homeCurrency = "EUR", pattern = pattern, descriptionTemplate = "{title}", notesTemplate = "",
            categoryTemplate = categoryTemplate, tagsTemplate = "", enabled = true, dateFormat = "", accountApproximate = false,
        )
        val message = CapturedMessage("bank.app", "Bank", "Payment", "You paid 12,50 EUR at  Shop1234 ", 1000)
        val categories = AutoCategories(listOf(exact("Shop1234", "Food")))
        val withGroup = """(?<amount>\d+,\d+) EUR at\s+(?<counterparty>\S+)"""

        val substituted = (RuleEngine(listOf(rule("", withGroup)), autoCategories = categories).match(message) as ParseOutcome.Matched).draft
        assertEquals("Food", substituted.category)
        assertEquals("Shop1234", substituted.counterparty)
        assertFalse(substituted.categoryFromRule)

        val ruleWins = (RuleEngine(listOf(rule("Own", withGroup)), autoCategories = categories).match(message) as ParseOutcome.Matched).draft
        assertEquals("Own", ruleWins.category)
        assertTrue(ruleWins.categoryFromRule)

        val noGroup = (RuleEngine(listOf(rule("", """(?<amount>\d+,\d+) EUR""")), autoCategories = categories).match(message) as ParseOutcome.Matched).draft
        assertNull(noGroup.category)
        assertNull(noGroup.counterparty)
        assertNotNull(noGroup.amount)
    }
}
