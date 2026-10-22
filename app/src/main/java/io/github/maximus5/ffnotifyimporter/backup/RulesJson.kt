package io.github.maximus5.ffnotifyimporter.backup

import io.github.maximus5.ffnotifyimporter.rules.ParseRule
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Rules-only export/import (Settings -> Rules -> Export/Import) - sharing one or more rules
 * between devices/people, not a full app backup (see `FullBackupJson` for that - which reuses
 * these two functions for its own `rules` section).
 *
 * Deliberately excludes `id`: a row id is this device's `RulesDb` internal identity, meaningless
 * elsewhere - import always appends as new rows (`RulesDb.add` assigns a fresh id). `ParseRule`
 * itself no longer carries a Firefly account id at all (see its own KDoc) - the asset-side account
 * travels as plain text, same as it always has, no separate exclusion needed here for that
 * anymore.
 */

fun rulesToJson(rules: List<ParseRule>): String {
    val array = JSONArray()
    rules.forEach { rule -> array.put(rule.toJson()) }
    return JSONObject().put("rules", array).toString(2)
}

/**
 * Null (not an empty list) if [json] isn't shaped like what [rulesToJson] produces at all - a
 * completely wrong file shouldn't silently import as "zero rules". An individual malformed rule
 * *within* an otherwise-valid file is skipped instead, not fatal to the rest of the list - same
 * "one bad entry doesn't sink the whole import" reasoning `FireflyJson`'s parsing already uses.
 */
fun rulesFromJson(json: String): List<ParseRule>? {
    val root = try {
        JSONObject(json)
    } catch (e: JSONException) {
        return null
    }
    val array = root.optJSONArray("rules") ?: return null
    return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.toRuleOrNull() }
}

private fun ParseRule.toJson(): JSONObject = JSONObject().apply {
    put("label", label)
    put("packageNames", JSONArray(packageNames.toList()))
    put("kind", kind.name)
    put("sourceAccountName", sourceAccountName)
    put("destinationAccountName", destinationAccountName)
    put("homeCurrency", homeCurrency)
    put("pattern", pattern)
    put("descriptionTemplate", descriptionTemplate)
    put("notesTemplate", notesTemplate)
    put("categoryTemplate", categoryTemplate)
    put("tagsTemplate", tagsTemplate)
    put("enabled", enabled)
    put("dateFormat", dateFormat)
    if (accountApproximate) put("accountApproximate", true)
    if (amountManual) put("amountManual", true)
}

private fun JSONObject.toRuleOrNull(): ParseRule? {
    val kind = try {
        RuleKind.valueOf(optString("kind"))
    } catch (e: IllegalArgumentException) {
        return null
    }
    val pattern = optString("pattern").takeIf { it.isNotBlank() } ?: return null
    val homeCurrency = optString("homeCurrency", "")
    val packageNames = optJSONArray("packageNames")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
    }.orEmpty()

    return ParseRule(
        id = "",
        label = optString("label", ""),
        packageNames = packageNames,
        kind = kind,
        sourceAccountName = optString("sourceAccountName", ""),
        destinationAccountName = optString("destinationAccountName", ""),
        homeCurrency = homeCurrency,
        pattern = pattern,
        descriptionTemplate = optString("descriptionTemplate", ""),
        notesTemplate = optString("notesTemplate", ""),
        categoryTemplate = optString("categoryTemplate", ""),
        tagsTemplate = optString("tagsTemplate", ""),
        enabled = optBoolean("enabled", true),
        dateFormat = optString("dateFormat", ""),
        accountApproximate = optBoolean("accountApproximate", false),
        amountManual = optBoolean("amountManual", false),
    )
}
