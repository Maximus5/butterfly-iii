package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.posting.FireflySuggestions

/**
 * The short notice for a Firefly list (categories, tags, accounts, currencies) that could not be
 * loaded even after every retry. A screen usually asks for several lists at once, so failures that
 * arrive within [WINDOW_MILLIS] of each other become one toast naming them all, instead of a queue of
 * toasts. Only the status line of the error is shown (never a response body); the diagnostic log has
 * the per-attempt details.
 */
class FireflyLoadFailureNotice(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val kinds = linkedSetOf<FireflySuggestions.Kind>()
    private var reason = ""

    private val show = Runnable {
        val names = kinds.joinToString(", ") { appContext.getString(label(it)) }
        val text = appContext.getString(R.string.firefly_load_failed, names, reason)
        kinds.clear()
        Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
    }

    /** May be called from any thread. */
    fun report(kind: FireflySuggestions.Kind, message: String) {
        handler.post {
            if (kinds.isEmpty()) reason = message.substringBefore(": ").take(MAX_REASON)
            kinds += kind
            handler.removeCallbacks(show)
            handler.postDelayed(show, WINDOW_MILLIS)
        }
    }

    private fun label(kind: FireflySuggestions.Kind) = when (kind) {
        FireflySuggestions.Kind.CATEGORIES -> R.string.firefly_kind_categories
        FireflySuggestions.Kind.TAGS -> R.string.firefly_kind_tags
        FireflySuggestions.Kind.ASSET_ACCOUNTS -> R.string.firefly_kind_accounts
        FireflySuggestions.Kind.CURRENCIES -> R.string.firefly_kind_currencies
    }

    private companion object {
        const val WINDOW_MILLIS = 1000L
        const val MAX_REASON = 80
    }
}
