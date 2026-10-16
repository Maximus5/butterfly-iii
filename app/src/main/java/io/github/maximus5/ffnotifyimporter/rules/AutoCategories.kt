package io.github.maximus5.ffnotifyimporter.rules

import org.json.JSONArray
import org.json.JSONObject

/** How an [AutoCategoryEntry]'s key is compared with a transaction's counterparty. */
enum class CategoryMatch {
    /** The whole counterparty equals the key (after [normalizeCounterpartyKey]). Learned entries. */
    EQUALS,

    /** The counterparty contains the key. Only ever typed in by the user. */
    CONTAINS,
}

/**
 * One counterparty -> category assignment.
 *
 * [key] is already normalized ([normalizeCounterpartyKey]). [active] = false means "fill in no
 * category for this counterparty": the user chose that for a shop that sells everything (a big
 * marketplace), or the app switched it off because the user changed the substituted category
 * ([AutoCategories.afterCategoryEdit]). Such an entry still *matches* - it stops the search, so
 * nothing further down the list applies to that counterparty and it is not learned again - and its
 * [category] may be blank. [learned] only tells the list screen where the entry came from.
 */
data class AutoCategoryEntry(
    val key: String,
    val match: CategoryMatch,
    val category: String,
    val active: Boolean = true,
    val learned: Boolean = true,
)

private val whitespaceRun = Regex("\\s+")

/** Case-insensitive, whitespace-trimmed and with inner whitespace runs collapsed. Nothing else
 * (punctuation, store numbers) is touched - the user shortens a key by editing the entry. */
fun normalizeCounterpartyKey(raw: String): String = raw.replace(whitespaceRun, " ").trim().lowercase()

/**
 * True when an exact ("equals") entry's key was cut down to a shorter part of itself - such an entry
 * could never match again, so the edit dialog switches it to "contains".
 */
fun keyShortenedToPart(originalKey: String, editedKey: String): Boolean {
    val edited = normalizeCounterpartyKey(editedKey)
    val original = normalizeCounterpartyKey(originalKey)
    return edited.isNotEmpty() && edited.length < original.length && original.contains(edited)
}

private fun AutoCategoryEntry.matches(normalizedCounterparty: String): Boolean = when (match) {
    CategoryMatch.EQUALS -> key == normalizedCounterparty
    CategoryMatch.CONTAINS -> key.isNotEmpty() && normalizedCounterparty.contains(key)
}

/**
 * The counterparty -> category list ("Auto categories"), applied by [RuleEngine] when a rule leaves
 * its own category blank. **List order is the priority**, exactly like the parse rules: the first
 * entry that matches decides, nothing further is tried - so a narrow entry ("Amazon Prime Video" ->
 * Entertainment) placed above a broad one ("Amazon" -> no category) wins. Pure, so it is unit-tested.
 */
class AutoCategories(val entries: List<AutoCategoryEntry>) {

    /** The category to substitute for [counterparty], or null for none (no entry matches, or the
     * first matching one is switched off). */
    fun categoryFor(counterparty: String?): String? {
        val key = normalizeCounterpartyKey(counterparty.orEmpty())
        if (key.isEmpty()) return null
        val hit = entries.firstOrNull { it.matches(key) } ?: return null
        return hit.category.takeIf { hit.active && it.isNotBlank() }
    }

    /**
     * What the entries become after the user saved a transaction, or null for "no change".
     *
     * Only an edit counts ([categoryEdited]: the saved category differs from the one the editor
     * opened with), only when learning is on, and never when the rule itself named a category
     * ([ruleSetCategory] - that is the rule's call, not an ambiguity of the counterparty). Then,
     * looking at the first entry that matches the counterparty:
     * - none: the category is learned (once) as a new entry at the end of the list;
     * - it is switched off, or already has this category: nothing changes (learning is once; the
     *   user fixes entries in the settings);
     * - an exact entry whose category the user just changed: it is switched off - the same
     *   counterparty evidently does not always get this category;
     * - a typed-in "contains" entry: it is left as it is and a new exact entry (the correction, or
     *   "no category" if the user cleared it) is inserted right above it, so the exception wins.
     */
    fun afterCategoryEdit(
        counterparty: String?,
        finalCategory: String?,
        categoryEdited: Boolean,
        ruleSetCategory: Boolean,
        learnEnabled: Boolean,
    ): List<AutoCategoryEntry>? {
        if (!learnEnabled || !categoryEdited || ruleSetCategory) return null
        val key = normalizeCounterpartyKey(counterparty.orEmpty())
        if (key.isEmpty()) return null
        val category = finalCategory?.trim().orEmpty()
        val index = entries.indexOfFirst { it.matches(key) }
        if (index < 0) {
            if (category.isEmpty()) return null
            return entries + AutoCategoryEntry(key, CategoryMatch.EQUALS, category, active = true, learned = true)
        }
        val hit = entries[index]
        if (!hit.active || hit.category.equals(category, ignoreCase = true)) return null
        if (hit.match == CategoryMatch.EQUALS) {
            return entries.toMutableList().also { it[index] = hit.copy(active = false) }
        }
        val exception = AutoCategoryEntry(key, CategoryMatch.EQUALS, category, active = category.isNotEmpty(), learned = true)
        return entries.toMutableList().also { it.add(index, exception) }
    }

    companion object {
        val EMPTY = AutoCategories(emptyList())
    }
}

/**
 * One "move up / move down" step for the selected positions of a list, the way the rules screen
 * does it: a selected item swaps with the unselected one next to it, a selected block already at the
 * edge stays put (its members do not shuffle among themselves). Returns the new list and the new
 * positions of the selected items. Pure, so it is unit-tested.
 */
fun <T> moveSelected(items: List<T>, selected: Set<Int>, up: Boolean): Pair<List<T>, Set<Int>> {
    val list = items.toMutableList()
    val marks = BooleanArray(items.size) { it in selected }
    if (up) {
        for (i in 1 until list.size) {
            if (marks[i] && !marks[i - 1]) {
                list[i - 1] = list[i].also { list[i] = list[i - 1] }
                marks[i - 1] = true
                marks[i] = false
            }
        }
    } else {
        for (i in list.size - 2 downTo 0) {
            if (marks[i] && !marks[i + 1]) {
                list[i + 1] = list[i].also { list[i] = list[i + 1] }
                marks[i + 1] = true
                marks[i] = false
            }
        }
    }
    return list to marks.indices.filter { marks[it] }.toSet()
}

/** The list as stored (SharedPreferences) and as carried by the JSON backup. */
fun autoCategoriesToJson(entries: List<AutoCategoryEntry>): JSONArray = JSONArray().apply {
    entries.forEach {
        put(
            JSONObject()
                .put("key", it.key)
                .put("match", it.match.name)
                .put("category", it.category)
                .put("active", it.active)
                .put("learned", it.learned),
        )
    }
}

/** Tolerant like the other backup sections: an unreadable entry is skipped, not fatal. Keys are
 * normalized again so a hand-edited file still matches. A blank category is only valid on a
 * switched-off entry. */
fun autoCategoriesFromJson(array: JSONArray?): List<AutoCategoryEntry> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val key = normalizeCounterpartyKey(o.optString("key"))
        val category = o.optString("category").trim()
        val active = o.optBoolean("active", true)
        if (key.isEmpty() || (category.isEmpty() && active)) return@mapNotNull null
        val match = try {
            CategoryMatch.valueOf(o.optString("match", CategoryMatch.EQUALS.name))
        } catch (e: IllegalArgumentException) {
            return@mapNotNull null
        }
        AutoCategoryEntry(key, match, category, active, o.optBoolean("learned", true))
    }
}
