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
 * [key] is already normalized ([normalizeCounterpartyKey]). [active] = false means "do not
 * substitute a category for this counterparty": either the user switched it off (a shop that sells
 * everything, e.g. a big marketplace) or the app switched it off because the user changed the
 * substituted category ([AutoCategories.afterCategoryEdit]). An inactive [CategoryMatch.EQUALS]
 * entry is also what stops the same counterparty from being learned again - learning is once.
 * [learned] only tells the list screen where the entry came from.
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
 * The counterparty -> category list ("Auto categories"), applied by [RuleEngine] when a rule leaves
 * its own category blank. Order is the user's list order; pure, so it is unit-tested.
 */
class AutoCategories(val entries: List<AutoCategoryEntry>) {

    /** The category to substitute for [counterparty], or null for none. An exact entry decides on
     * its own - active or not - so an inactive exact entry ("don't categorize this shop") also
     * beats a broader [CategoryMatch.CONTAINS] entry; only then the active contains entries are
     * tried, first one in list order wins. */
    fun categoryFor(counterparty: String?): String? {
        val key = normalizeCounterpartyKey(counterparty.orEmpty())
        if (key.isEmpty()) return null
        val exact = entries.firstOrNull { it.match == CategoryMatch.EQUALS && it.key == key }
        if (exact != null) return exact.category.takeIf { exact.active && it.isNotBlank() }
        return entries.firstOrNull {
            it.match == CategoryMatch.CONTAINS && it.active && it.key.isNotEmpty() && key.contains(it.key)
        }?.category?.takeIf { it.isNotBlank() }
    }

    /**
     * What the entries become after the user saved a transaction, or null for "no change".
     *
     * Only an edit counts ([categoryEdited]: the saved category differs from the one the editor
     * opened with), only when learning is on, and never when the rule itself named a category
     * ([ruleSetCategory] - that is the rule's call, not an ambiguity of the counterparty).
     * - no entry for this counterparty yet and a category was chosen: it is learned (once);
     * - an active entry whose category the user just changed: switched off, because the same
     *   counterparty evidently does not always get this category;
     * - an inactive entry: left alone (learning is once; the user fixes it in the settings).
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
        val index = entries.indexOfFirst { it.match == CategoryMatch.EQUALS && it.key == key }
        if (index >= 0) {
            val existing = entries[index]
            if (!existing.active || existing.category.equals(category, ignoreCase = true)) return null
            return entries.toMutableList().also { it[index] = existing.copy(active = false) }
        }
        if (category.isEmpty()) return null
        return entries + AutoCategoryEntry(key, CategoryMatch.EQUALS, category, active = true, learned = true)
    }

    companion object {
        val EMPTY = AutoCategories(emptyList())
    }
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
 * normalized again so a hand-edited file still matches. */
fun autoCategoriesFromJson(array: JSONArray?): List<AutoCategoryEntry> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val key = normalizeCounterpartyKey(o.optString("key"))
        val category = o.optString("category").trim()
        if (key.isEmpty() || category.isEmpty()) return@mapNotNull null
        val match = try {
            CategoryMatch.valueOf(o.optString("match", CategoryMatch.EQUALS.name))
        } catch (e: IllegalArgumentException) {
            return@mapNotNull null
        }
        AutoCategoryEntry(key, match, category, o.optBoolean("active", true), o.optBoolean("learned", true))
    }
}
