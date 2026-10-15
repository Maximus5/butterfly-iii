package io.github.maximus5.ffnotifyimporter.backup

import io.github.maximus5.ffnotifyimporter.prefs.RetentionSettings
import io.github.maximus5.ffnotifyimporter.rules.AutoCategoryEntry
import io.github.maximus5.ffnotifyimporter.rules.CategoryMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BackupSettingsJsonTest {

    private val nonDefault = BackupSettings(
        allTransactionsTags = "butterfly, imported",
        approxConversionTag = "approx",
        defaultAccountCurrency = "EUR",
        defaultSourceAccount = "Account1234",
        autoCreate = true,
        dedupEnabled = false,
        dedupIncludeDescription = true,
        dedupWindowMinutes = 11,
        builtInRulesEnabled = false,
        notificationsEnabled = false,
        showFinancialInfo = true,
        notifySkippedDuplicates = true,
        autoCategoriesLearn = true,
    )

    private fun backup(settings: BackupSettings?) = FullBackupData(
        monitoredPackages = setOf("bank.app"),
        retention = RetentionSettings(persistToDisk = false, days = 7),
        currencies = emptyList(),
        rules = emptyList(),
        messages = emptyList(),
        settings = settings,
    )

    @Test
    fun `every setting round-trips with non-default values`() {
        val json = fullBackupToJson(backup(nonDefault))
        assertEquals(nonDefault, fullBackupFromJson(json)!!.settings)
    }

    @Test
    fun `a backup from before settings were saved restores no settings at all`() {
        val json = fullBackupToJson(backup(null))
        assertFalse(json.contains("\"settings\""))
        assertNull(fullBackupFromJson(json)!!.settings)
    }

    @Test
    fun `keys missing from a settings section fall back to the app defaults`() {
        val json = """{"monitoredPackages":["bank.app"],"settings":{"autoCreate":true,"dedupWindowMinutes":0}}"""
        val settings = fullBackupFromJson(json)!!.settings!!
        assertEquals(BackupSettings().copy(autoCreate = true), settings)
    }

    @Test
    fun `the auto categories list round-trips and an older file leaves it untouched`() {
        val entries = listOf(
            AutoCategoryEntry("shop1234", CategoryMatch.EQUALS, "Food"),
            AutoCategoryEntry("cafe", CategoryMatch.CONTAINS, "Restaurants", active = false, learned = false),
        )
        val withList = backup(nonDefault).copy(autoCategories = entries)
        assertEquals(entries, fullBackupFromJson(fullBackupToJson(withList))!!.autoCategories)

        val old = fullBackupToJson(backup(nonDefault))
        assertFalse(old.contains("autoCategories\""))
        assertNull(fullBackupFromJson(old)!!.autoCategories)
    }

    @Test
    fun `the connection is never written to the file`() {
        val json = fullBackupToJson(backup(nonDefault))
        for (secret in listOf("token", "baseUrl", "base_url", "cleartext", "Cleartext")) {
            assertFalse("backup file must not mention $secret", json.contains(secret))
        }
    }
}
