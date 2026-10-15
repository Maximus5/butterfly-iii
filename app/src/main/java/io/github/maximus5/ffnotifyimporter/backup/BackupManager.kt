package io.github.maximus5.ffnotifyimporter.backup

import android.content.Context
import io.github.maximus5.ffnotifyimporter.data.MessageHistoryDb
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.data.RetentionController
import io.github.maximus5.ffnotifyimporter.prefs.AutoCategoriesPrefs
import io.github.maximus5.ffnotifyimporter.prefs.MonitoredAppsPrefs
import io.github.maximus5.ffnotifyimporter.prefs.RetentionPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.RulesDb

/**
 * Collects/restores a [FullBackupData] against the real on-device stores - the Android-dependent
 * counterpart to [fullBackupToJson]/[fullBackupFromJson]'s pure JSON shape. Not unit tested, same
 * reason `RulesDb`/`MessageHistoryDb` aren't - thin glue over real `SQLiteOpenHelper`/
 * `SharedPreferences` that don't run under plain JVM unit tests.
 */
object BackupManager {

    fun collect(context: Context): FullBackupData = FullBackupData(
        monitoredPackages = MonitoredAppsPrefs(context).getMonitoredPackages(),
        retention = RetentionPrefs(context).get(),
        currencies = CurrenciesDb.getInstance(context).loadAll(),
        rules = RulesDb.getInstance(context).loadAllOrdered(),
        messages = MessageStore.shared.snapshot(),
        createdTransactions = CreatedTransactionsDb.getInstance(context).loadAll(),
        settings = BackupSettings.read(context),
        autoCategories = AutoCategoriesPrefs(context).loadEntries(),
    )

    /**
     * Replaces, not merges - every store this touches is cleared first. A restore is meant to
     * put the device back exactly how the backup found it, not pile the backup's contents on top
     * of whatever's already there (which would duplicate rules/currencies/messages). The caller
     * is responsible for confirming this with the user before calling it - this function doesn't
     * ask.
     */
    fun restore(context: Context, data: FullBackupData) {
        MonitoredAppsPrefs(context).setMonitoredPackages(data.monitoredPackages)
        RetentionPrefs(context).set(data.retention)
        // Null = a file from before settings were backed up: keep whatever is set now.
        data.settings?.let { BackupSettings.apply(context, it) }
        // Null = a file from before Auto categories existed: keep the current list.
        data.autoCategories?.let { AutoCategoriesPrefs(context).saveEntries(it) }

        val currenciesDb = CurrenciesDb.getInstance(context)
        currenciesDb.loadAll().forEach { currenciesDb.delete(it.code) }
        data.currencies.forEach { currenciesDb.add(it) }

        val rulesDb = RulesDb.getInstance(context)
        rulesDb.loadAllOrdered().forEach { rulesDb.delete(it.id) }
        // Sequential add() calls assign increasing sort order automatically - the backup's array
        // order (which is RulesDb.loadAllOrdered's order at export time) is preserved without
        // needing to carry an explicit sort-order field through the JSON.
        data.rules.forEach { rulesDb.add(it) }

        MessageStore.shared.clear()
        MessageStore.shared.restore(data.messages.sortedBy { it.timestampMillis })

        val createdTransactionsDb = CreatedTransactionsDb.getInstance(context)
        createdTransactionsDb.clearAll()
        data.createdTransactions.forEach {
            createdTransactionsDb.recordCreated(it.hashWithDescription, it.hashWithoutDescription, it.dateMillis, it.fireflyTransactionId, it.hashWithoutAccounts)
        }

        // Last, once both stores hold the restored data: trims everything to the restored window,
        // rewrites the on-disk message mirror from memory (or clears it when persisting is off) and
        // re-wires the live capture hooks to the restored setting.
        RetentionController.applyOnSettingChanged(
            settings = data.retention,
            store = MessageStore.shared,
            db = MessageHistoryDb.getInstance(context),
            createdTransactions = createdTransactionsDb,
        )
    }
}
