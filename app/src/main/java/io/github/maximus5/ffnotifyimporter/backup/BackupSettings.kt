package io.github.maximus5.ffnotifyimporter.backup

import android.content.Context
import io.github.maximus5.ffnotifyimporter.prefs.AutoCategoriesPrefs
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.prefs.NotificationPrefs
import org.json.JSONObject

/**
 * The plain user settings a full backup carries: everything in [FireflyPrefs] (posting behavior)
 * and [NotificationPrefs]. Defaults are the app's own defaults, so a field missing from an older
 * or partial file restores to what a fresh install would have.
 *
 * The Firefly connection (URL, token, cleartext opt-in) is deliberately *not* here - it is its own
 * class that backup code must never read (`BackupRulesTest` enforces it).
 */
data class BackupSettings(
    val allTransactionsTags: String = "",
    val approxConversionTag: String = "",
    val defaultAccountCurrency: String = "",
    val defaultSourceAccount: String = "",
    val autoCreate: Boolean = false,
    val dedupEnabled: Boolean = true,
    val dedupIncludeDescription: Boolean = false,
    val dedupWindowMinutes: Int = FireflyPrefs.DEFAULT_DEDUP_WINDOW_MINUTES,
    val builtInRulesEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val showFinancialInfo: Boolean = false,
    val notifySkippedDuplicates: Boolean = false,
    /** The Auto categories learning switch; the entries themselves travel in [FullBackupData]. */
    val autoCategoriesLearn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("allTransactionsTags", allTransactionsTags)
        .put("approxConversionTag", approxConversionTag)
        .put("defaultAccountCurrency", defaultAccountCurrency)
        .put("defaultSourceAccount", defaultSourceAccount)
        .put("autoCreate", autoCreate)
        .put("dedupEnabled", dedupEnabled)
        .put("dedupIncludeDescription", dedupIncludeDescription)
        .put("dedupWindowMinutes", dedupWindowMinutes)
        .put("builtInRulesEnabled", builtInRulesEnabled)
        .put("notificationsEnabled", notificationsEnabled)
        .put("showFinancialInfo", showFinancialInfo)
        .put("notifySkippedDuplicates", notifySkippedDuplicates)
        .put("autoCategoriesLearn", autoCategoriesLearn)

    companion object {
        private val DEFAULTS = BackupSettings()

        fun fromJson(o: JSONObject): BackupSettings = BackupSettings(
            allTransactionsTags = o.optString("allTransactionsTags", DEFAULTS.allTransactionsTags),
            approxConversionTag = o.optString("approxConversionTag", DEFAULTS.approxConversionTag),
            defaultAccountCurrency = o.optString("defaultAccountCurrency", DEFAULTS.defaultAccountCurrency),
            defaultSourceAccount = o.optString("defaultSourceAccount", DEFAULTS.defaultSourceAccount),
            autoCreate = o.optBoolean("autoCreate", DEFAULTS.autoCreate),
            dedupEnabled = o.optBoolean("dedupEnabled", DEFAULTS.dedupEnabled),
            dedupIncludeDescription = o.optBoolean("dedupIncludeDescription", DEFAULTS.dedupIncludeDescription),
            dedupWindowMinutes = o.optInt("dedupWindowMinutes", DEFAULTS.dedupWindowMinutes)
                .takeIf { it > 0 } ?: DEFAULTS.dedupWindowMinutes,
            builtInRulesEnabled = o.optBoolean("builtInRulesEnabled", DEFAULTS.builtInRulesEnabled),
            notificationsEnabled = o.optBoolean("notificationsEnabled", DEFAULTS.notificationsEnabled),
            showFinancialInfo = o.optBoolean("showFinancialInfo", DEFAULTS.showFinancialInfo),
            notifySkippedDuplicates = o.optBoolean("notifySkippedDuplicates", DEFAULTS.notifySkippedDuplicates),
            autoCategoriesLearn = o.optBoolean("autoCategoriesLearn", DEFAULTS.autoCategoriesLearn),
        )

        fun read(context: Context): BackupSettings {
            val firefly = FireflyPrefs(context)
            val notifications = NotificationPrefs(context)
            return BackupSettings(
                allTransactionsTags = firefly.getAllTransactionsTags(),
                approxConversionTag = firefly.getApproxConversionTag(),
                defaultAccountCurrency = firefly.getDefaultAccountCurrency(),
                defaultSourceAccount = firefly.getDefaultSourceAccount(),
                autoCreate = firefly.isAutoCreateEnabled(),
                dedupEnabled = firefly.isDedupEnabled(),
                dedupIncludeDescription = firefly.isDedupIncludeDescription(),
                dedupWindowMinutes = firefly.getDedupWindowMinutes(),
                builtInRulesEnabled = firefly.isBuiltInRulesEnabled(),
                notificationsEnabled = notifications.isNotificationsEnabled(),
                showFinancialInfo = notifications.isShowFinancialInfoEnabled(),
                notifySkippedDuplicates = notifications.isNotifySkippedDuplicatesEnabled(),
                autoCategoriesLearn = AutoCategoriesPrefs(context).isLearnEnabled(),
            )
        }

        fun apply(context: Context, s: BackupSettings) {
            val firefly = FireflyPrefs(context)
            val notifications = NotificationPrefs(context)
            firefly.setAllTransactionsTags(s.allTransactionsTags)
            firefly.setApproxConversionTag(s.approxConversionTag)
            firefly.setDefaultAccountCurrency(s.defaultAccountCurrency)
            firefly.setDefaultSourceAccount(s.defaultSourceAccount)
            firefly.setAutoCreateEnabled(s.autoCreate)
            firefly.setDedupEnabled(s.dedupEnabled)
            firefly.setDedupIncludeDescription(s.dedupIncludeDescription)
            firefly.setDedupWindowMinutes(s.dedupWindowMinutes)
            firefly.setBuiltInRulesEnabled(s.builtInRulesEnabled)
            notifications.setNotificationsEnabled(s.notificationsEnabled)
            notifications.setShowFinancialInfoEnabled(s.showFinancialInfo)
            notifications.setNotifySkippedDuplicatesEnabled(s.notifySkippedDuplicates)
            AutoCategoriesPrefs(context).setLearnEnabled(s.autoCategoriesLearn)
        }
    }
}
