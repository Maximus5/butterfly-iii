package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-device log of what the automatic paths decided (which rule matched, whether a review
 * notification was posted, suppressed or superseded, what the shade sweep saw), so a report like "the
 * bank notification stayed" can be diagnosed from the real phone instead of guessed at. The user
 * copies it from About and pastes it into a bug report.
 *
 * **Off by default** (About -> "Write diagnostic log"): nothing is written until the user turns it
 * on, and turning it off deletes what was written. While on it holds no amounts, descriptions,
 * counterparties or account names - only the user's own rule labels, flags, counts, time offsets,
 * Firefly transaction numbers, how each Firefly API call ended (endpoint without its query, HTTP status
 * or exception class - never a request or response body, header, token or exception message), the times of the events themselves and the first characters of the
 * dedup hashes (enough to tell "same" from "different"; the hash of amount + currency could in
 * principle be brute-forced back to the amount, which is why the log is opt-in and stays on the device
 * until the user copies it). Capped at
 * [MAX_BYTES]; the older half is dropped when it fills. Never throws: logging must not be able to
 * break the code it observes.
 */
object DiagnosticLog {

    private const val FILE_NAME = "diagnostic.log"
    private const val PREFS_NAME = "diagnostic_log"
    private const val KEY_ENABLED = "enabled"
    private const val MAX_BYTES = 48 * 1024

    private val lock = Any()

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** Turning it off also deletes the log written so far. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) clear(context)
    }

    fun log(context: Context, line: String) {
        try {
            if (!isEnabled(context)) return
            synchronized(lock) {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val text = file.readText()
                    file.writeText(text.substring(text.length / 2).substringAfter('\n'))
                }
                val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText("$stamp $line\n")
            }
        } catch (e: Exception) {
            // Nothing sensible to do - see the class KDoc.
        }
    }

    fun read(context: Context): String = try {
        synchronized(lock) {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (file.exists()) file.readText() else ""
        }
    } catch (e: Exception) {
        ""
    }

    /** First characters of a hash - enough to compare two, not the inputs. */
    fun short(hash: String): String = hash.take(6).ifEmpty { "-" }

    fun clear(context: Context) {
        try {
            synchronized(lock) { File(context.applicationContext.filesDir, FILE_NAME).delete() }
        } catch (e: Exception) {
            // see above
        }
    }
}
