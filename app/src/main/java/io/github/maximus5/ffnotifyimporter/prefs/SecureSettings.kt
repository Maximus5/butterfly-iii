package io.github.maximus5.ffnotifyimporter.prefs

import android.content.Context
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.isCleartextUrl
import io.github.maximus5.ffnotifyimporter.posting.DiagnosticLog

/**
 * The Firefly III connection: base URL, personal access token and the plain-`http://` opt-in. The
 * only genuinely secret values in this app (see AGENTS.md's Secrets section) - URL and token are
 * encrypted via [KeystoreCipher] before being stored in an otherwise-plain `SharedPreferences`
 * (not `EncryptedSharedPreferences` - deprecated, see [KeystoreCipher]'s KDoc).
 *
 * **MUST NEVER BE BACKED UP - not by the manual JSON backup, not by Android Auto Backup / device
 * transfer.** Do not read this class from `backup.*`, do not add its prefs file ([PREFS_NAME]) to
 * `res/xml/backup_rules.xml` / `data_extraction_rules.xml` as an `<include>`, and do not add any
 * other setting to it - this is the *only* place a value belongs that must stay on the device.
 * Everything that is a plain user setting lives in [FireflyPrefs]'s own file, which is backed up.
 * `BackupRulesTest` enforces all three: the file has an explicit `<exclude>` in both XML files,
 * and nothing under `backup/` may mention this class or its accessors. (Android has no way for a
 * prefs class to veto its own backup - the exclusion has to live in those XML files and in what
 * `BackupManager` reads, hence the tests.)
 */
class SecureSettings(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBaseUrl(): String = decryptedString(KEY_BASE_URL)
    fun setBaseUrl(url: String) = putEncrypted(KEY_BASE_URL, url)

    fun getToken(): String = decryptedString(KEY_TOKEN)
    fun setToken(token: String) = putEncrypted(KEY_TOKEN, token)
    fun clearToken() = prefs.edit().remove(KEY_TOKEN).apply()

    fun isConfigured(): Boolean = getBaseUrl().isNotBlank() && getToken().isNotBlank()

    /** Off by default: the bearer token is never sent over plain `http://` unless the user opts in
     * on the connection screen. Installs that already had an `http://` URL saved before this
     * existed are migrated to on once ([migrateAllowCleartext]), so they keep working. */
    fun isAllowCleartext(): Boolean = prefs.getBoolean(KEY_ALLOW_CLEARTEXT, false)
    fun setAllowCleartext(allow: Boolean) = prefs.edit().putBoolean(KEY_ALLOW_CLEARTEXT, allow).apply()

    /** The one way to build an API client from saved settings, so no call site can forget the
     * cleartext opt-in. */
    fun apiClient(): FireflyApiClient = FireflyApiClient(getBaseUrl(), getToken(), isAllowCleartext()) { DiagnosticLog.log(appContext, it) }

    init {
        migrateAllowCleartext()
    }

    private fun migrateAllowCleartext() {
        if (prefs.contains(KEY_ALLOW_CLEARTEXT)) return
        setAllowCleartext(isCleartextUrl(getBaseUrl()))
    }

    private fun decryptedString(key: String): String {
        val stored = prefs.getString(key, null) ?: return ""
        return KeystoreCipher.decrypt(stored).orEmpty()
    }

    private fun putEncrypted(key: String, value: String) {
        prefs.edit().putString(key, KeystoreCipher.encrypt(value)).apply()
    }

    companion object {
        /** Kept as the original file name on purpose: existing installs already hold the secrets
         * here, so they never move, and the file is already excluded from every backup. */
        internal const val PREFS_NAME = "firefly_connection"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_ALLOW_CLEARTEXT = "allow_cleartext"
    }
}
