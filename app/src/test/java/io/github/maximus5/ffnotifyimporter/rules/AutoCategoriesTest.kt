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
    fun `list order decides - the first matching entry wins, exact or contains alike`() {
        val categories = AutoCategories(listOf(exact("Cafe Shop1234", "C"), contains("shop", "A"), contains("op", "B")))
        assertEquals("C", categories.categoryFor("Cafe Shop1234"))
        assertEquals("A", categories.categoryFor("Big Shop"))
        assertEquals("B", categories.categoryFor("Laptop"))

        val broadFirst = AutoCategories(listOf(contains("shop", "A"), exact("Cafe Shop1234", "C")))
        assertEquals("A", broadFirst.categoryFor("Cafe Shop1234"))
    }

    @Test
    fun `a switched-off entry still matches and stops the search, with or without a category`() {
        val categories = AutoCategories(
            listOf(contains("amazon prime", "Entertainment"), contains("amazon", "", active = false), contains("a", "Misc")),
        )
        assertEquals("Entertainment", categories.categoryFor("Amazon Prime Video"))
        assertNull(categories.categoryFor("Amazon Marketplace"))
        assertEquals("Misc", categories.categoryFor("Bakery"))

        val remembered = AutoCategories(listOf(exact("Shop1234", "Clothes", active = false), contains("shop", "Food")))
        assertNull(remembered.categoryFor("Shop1234"))
        assertEquals("Food", remembered.categoryFor("Shop5678"))
    }

    @Test
    fun `a category is learned once, from an edit, when learning is on`() {
        val learned = AutoCategories.EMPTY
            .afterCategoryEdit("Shop1234", "Food", categoryEdited = true, ruleSetCategory = false, learnEnabled = true)
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
    fun `a new learned entry goes to the end of the list`() {
        val existing = listOf(contains("cafe", "Restaurants"), exact("Shop1", "Food"))
        val updated = AutoCategories(existing).afterCategoryEdit("Shop2", "Clothes", true, false, true)!!
        assertEquals(existing + exact("Shop2", "Clothes"), updated)
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
        // A matching entry that is switched off is never touched, and nothing is learned past it.
        assertNull(learn(AutoCategories(listOf(contains("shop", "", active = false)))))
    }

    @Test
    fun `an edit under a contains entry inserts an exact exception right above it`() {
        val manual = AutoCategories(listOf(exact("Other", "X"), contains("cafe", "Restaurants")))
        val updated = manual.afterCategoryEdit("Cafe Corner", "Groceries", true, false, true)!!
        assertEquals(listOf(exact("Other", "X"), exact("Cafe Corner", "Groceries"), contains("cafe", "Restaurants")), updated)
        assertEquals("Groceries", AutoCategories(updated).categoryFor("Cafe Corner"))
        assertEquals("Restaurants", AutoCategories(updated).categoryFor("Cafe Other"))

        // Clearing the supplied category: the exception is a switched-off entry with no category.
        val cleared = manual.afterCategoryEdit("Cafe Corner", null, true, false, true)!!
        assertEquals(AutoCategoryEntry("cafe corner", CategoryMatch.EQUALS, "", active = false, learned = true), cleared[1])
        assertNull(AutoCategories(cleared).categoryFor("Cafe Corner"))
    }

    @Test
    fun `a key cut down to a part of itself is detected`() {
        assertTrue(keyShortenedToPart("amzn mktp, de", "amzn mktp"))
        assertTrue(keyShortenedToPart("amzn mktp, de", " MKTP "))
        assertFalse(keyShortenedToPart("amzn mktp, de", "amzn mktp, de"))
        assertFalse(keyShortenedToPart("amzn mktp, de", ""))
        assertFalse(keyShortenedToPart("amzn mktp, de", "amazon"))
        assertFalse(keyShortenedToPart("amzn", "amzn mktp"))
    }

    @Test
    fun `a kept entry ignores edits - no switching off, no exception`() {
        val keptExact = AutoCategories(listOf(exact("Shop1234", "Food").copy(keep = true)))
        assertNull(keptExact.afterCategoryEdit("Shop1234", "Clothes", true, false, true))
        val keptContains = AutoCategories(listOf(contains("cafe", "Restaurants").copy(keep = true)))
        assertNull(keptContains.afterCategoryEdit("Cafe Corner", "Groceries", true, false, true))
        // Still applied as usual, and the flag survives the JSON round trip (absent = off).
        assertEquals("Food", keptExact.categoryFor("Shop1234"))
        assertEquals(keptExact.entries, autoCategoriesFromJson(autoCategoriesToJson(keptExact.entries)))
        assertFalse(autoCategoriesFromJson(JSONArray("""[{"key":"k","category":"X"}]""")).single().keep)
    }

    @Test
    fun `without a counterparty only contains entries apply, to the title and text`() {
        val categories = AutoCategories(
            listOf(exact("rewe", "Wrong"), contains("amazon prime", "Entertainment"), contains("amazon", "", active = false), contains("rewe", "Food")),
        )
        assertEquals("Food", categories.categoryForText("Payment made", "You made a payment of 52.85 at  REWE Markt GmbH-Zw."))
        assertEquals("Entertainment", categories.categoryForText("Amazon Prime", "Payment"))
        assertNull(categories.categoryForText("Amazon", "Payment"))
        assertNull(categories.categoryForText("Payment made", "at Shop1234"))
    }

    @Test
    fun `the engine falls back to the notification text for a rule without a counterparty group`() {
        val rule = ParseRule(
            id = "r", label = "Default", packageNames = emptySet(), kind = RuleKind.WITHDRAWAL,
            sourceAccountName = "Bank Account A", destinationAccountName = "",
            homeCurrency = "EUR", pattern = """(?<amount>\d+,\d+) EUR""", descriptionTemplate = "{title}", notesTemplate = "",
            categoryTemplate = "", tagsTemplate = "", enabled = true, dateFormat = "", accountApproximate = false,
        )
        val message = CapturedMessage("bank.app", "Bank", "Payment", "You paid 12,50 EUR at REWE Markt", 1000)
        val draft = (RuleEngine(listOf(rule), autoCategories = AutoCategories(listOf(contains("rewe", "Food")))).match(message) as ParseOutcome.Matched).draft
        assertEquals("Food", draft.category)
        assertNull(draft.counterparty)
    }

    @Test
    fun `moving the selection up or down keeps blocks at the edge in place`() {
        val items = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("b", "a", "c", "d", "e") to setOf(0), moveSelected(items, setOf(1), up = true))
        assertEquals(listOf("a", "b", "d", "c", "e") to setOf(3), moveSelected(items, setOf(2), up = false))
        // A block already at the top does not shuffle internally; a separate member still moves.
        assertEquals(listOf("a", "b", "d", "c", "e") to setOf(0, 1, 2), moveSelected(items, setOf(0, 1, 3), up = true))
        assertEquals(items to setOf(3, 4), moveSelected(items, setOf(3, 4), up = false))
    }

    @Test
    fun `json round trip keeps order and flags, skips unreadable entries`() {
        val entries = listOf(exact("Shop1234", "Food"), contains("cafe", "Restaurants", active = false), contains("amazon", "", active = false))
        assertEquals(entries, autoCategoriesFromJson(autoCategoriesToJson(entries)))

        val array = JSONArray("""[{"key":" Shop 1 ","category":"X"},{"key":"","category":"Y"},{"key":"k","category":"Z","match":"NOPE"},{"key":"blank","category":""},"junk"]""")
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
