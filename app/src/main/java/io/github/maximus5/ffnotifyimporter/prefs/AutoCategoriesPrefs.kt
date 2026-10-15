package io.github.maximus5.ffnotifyimporter.prefs

import android.content.Context
import io.github.maximus5.ffnotifyimporter.rules.AutoCategories
import io.github.maximus5.ffnotifyimporter.rules.AutoCategoryEntry
import io.github.maximus5.ffnotifyimporter.rules.autoCategoriesFromJson
import io.github.maximus5.ffnotifyimporter.rules.autoCategoriesToJson
import org.json.JSONArray
import org.json.JSONException

/**
 * The "Auto categories" setting: the learning switch (off by default) and the counterparty ->
 * category list. A few dozen short entries at most (the user keeps a handful of regular shops), so
 * one JSON string in a plain prefs file is enough; the file is part of both backups (own file,
 * [PREFS_NAME]) and carried by the manual JSON backup too.
 *
 * [recordEdit] is the single read-modify-write used by the screens that save a transaction.
 */
class AutoCategoriesPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isLearnEnabled(): Boolean = prefs.getBoolean(KEY_LEARN, false)
    fun setLearnEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_LEARN, enabled).apply()

    fun loadEntries(): List<AutoCategoryEntry> = synchronized(LOCK) {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        try {
            autoCategoriesFromJson(JSONArray(json))
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun saveEntries(entries: List<AutoCategoryEntry>) = synchronized(LOCK) {
        prefs.edit().putString(KEY_ENTRIES, autoCategoriesToJson(entries).toString()).apply()
    }

    fun load(): AutoCategories = AutoCategories(loadEntries())

    /** Applies [AutoCategories.afterCategoryEdit] to the stored list. Returns true if it changed. */
    fun recordEdit(
        counterparty: String?,
        finalCategory: String?,
        categoryEdited: Boolean,
        ruleSetCategory: Boolean,
    ): Boolean = synchronized(LOCK) {
        val updated = load().afterCategoryEdit(
            counterparty, finalCategory, categoryEdited, ruleSetCategory, isLearnEnabled(),
        ) ?: return false
        saveEntries(updated)
        true
    }

    companion object {
        private const val PREFS_NAME = "auto_categories"
        private const val KEY_LEARN = "learn_enabled"
        private const val KEY_ENTRIES = "entries"
        private val LOCK = Any()
    }
}
