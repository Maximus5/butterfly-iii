package io.github.maximus5.ffnotifyimporter

import android.app.Application
import androidx.work.WorkManager
import io.github.maximus5.ffnotifyimporter.data.MessageHistoryDb
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.data.RetentionController
import io.github.maximus5.ffnotifyimporter.posting.AutoCreateScheduler
import io.github.maximus5.ffnotifyimporter.posting.FireflySuggestions
import io.github.maximus5.ffnotifyimporter.prefs.RetentionPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb
import io.github.maximus5.ffnotifyimporter.ui.FireflyLoadFailureNotice

class FfNotifyImporterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppVisibility.register(this)
        FireflySuggestions.onLoadFailed = FireflyLoadFailureNotice(this)::report
        RetentionController.applyOnStartup(
            settings = RetentionPrefs(this).get(),
            store = MessageStore.shared,
            db = MessageHistoryDb.getInstance(this),
            createdTransactions = CreatedTransactionsDb.getInstance(this),
        )
        purgePoisonedAutoCreateChainOnce()
    }

    /** One-time migration, safe to delete once enough time has passed that every real install
     * has already run it once. Fixed a real "every future auto-create silently never runs" bug,
     * root-caused on the user's own device by reading `androidx.work.workdb` directly:
     * `AutoCreateScheduler` used to enqueue every message with plain `ExistingWorkPolicy.APPEND`
     * (now `APPEND_OR_REPLACE`, see its own KDoc for the full story) - if the chain's tail ever
     * reached a terminal `FAILED`/`CANCELLED` state (which `AutoCreateWorker.post`'s own
     * since-removed retry-until-`MAX_ATTEMPTS` loop could produce), WorkManager silently cascaded
     * that failure to every later message enqueued as its dependent, forever, with no error and
     * no notification - `APPEND_OR_REPLACE` prevents this from happening again, but doesn't retroactively
     * un-poison a chain that's *already* stuck this way on an install that hit it before this fix
     * shipped. Cancelling the unique work name once is harmless even when nothing was actually
     * stuck - at worst it drops one real in-flight auto-create attempt, which just means that one
     * message gets handled manually instead, same as any other dropped auto-create outcome. */
    private fun purgePoisonedAutoCreateChainOnce() {
        val migrations = getSharedPreferences("startup_migrations", MODE_PRIVATE)
        val key = "auto_create_chain_purged_v1"
        if (migrations.getBoolean(key, false)) return
        WorkManager.getInstance(this).cancelUniqueWork(AutoCreateScheduler.UNIQUE_WORK_NAME)
        migrations.edit().putBoolean(key, true).apply()
    }
}
