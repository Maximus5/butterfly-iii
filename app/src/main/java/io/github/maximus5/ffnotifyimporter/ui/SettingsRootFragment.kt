package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentSettingsRootBinding
import io.github.maximus5.ffnotifyimporter.network.hostOf
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.prefs.RetentionPrefs

class SettingsRootFragment : Fragment() {

    private var binding: FragmentSettingsRootBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentSettingsRootBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return

        binding.rowFirefly.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                FireflyConnectionFragment(),
                R.string.settings_firefly_title,
            )
        }
        binding.rowTransactionCreation.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                TransactionCreationFragment(),
                R.string.settings_transaction_creation_title,
            )
        }
        binding.rowAutoCategories.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                AutoCategoriesFragment(),
                R.string.settings_auto_categories_title,
            )
        }
        binding.rowNotifications.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                NotificationSettingsFragment(),
                R.string.settings_notifications_title,
            )
        }
        binding.rowMonitoredApps.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                AppsFragment(),
                R.string.settings_apps_screen_title,
            )
        }
        binding.rowRules.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                RulesListFragment(),
                R.string.settings_rules_title,
            )
        }
        binding.rowCurrencies.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                CurrenciesFragment(),
                R.string.settings_currencies_title,
            )
        }
        binding.rowMessageHistory.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                MessageHistoryFragment(),
                R.string.settings_message_history_title,
            )
        }
        binding.rowBackup.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                BackupFragment(),
                R.string.settings_backup_title,
            )
        }
        binding.rowAbout.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                AboutFragment(),
                R.string.settings_about_title,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setScreenTitle(R.string.action_settings)
        updateMessageHistorySubtitle()
        updateFireflySubtitle()
        updateAboutSubtitle()
    }

    /** Same `PackageInfo.versionName` lookup `AboutFragment` itself does, reusing its own
     * `about_version` ("Version %1$s") string - the user's own request: "Version x.yy" visible
     * right on the Settings list, not only after opening the About screen. */
    private fun updateAboutSubtitle() {
        val binding = binding ?: return
        val context = requireContext()
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        binding.rowAboutSubtitle.text = getString(R.string.about_version, versionName ?: "?")
    }

    private fun updateFireflySubtitle() {
        val binding = binding ?: return
        val host = hostOf(FireflyPrefs(requireContext()).getBaseUrl())
        binding.rowFireflySubtitle.text = if (host != null) {
            getString(R.string.settings_firefly_subtitle_configured, host)
        } else {
            getString(R.string.settings_firefly_subtitle_not_configured)
        }
    }

    private fun updateMessageHistorySubtitle() {
        val binding = binding ?: return
        val retention = RetentionPrefs(requireContext()).get()
        val kept = resources.getQuantityString(R.plurals.history_keep_days, retention.days, retention.days)
        binding.rowMessageHistorySubtitle.text = getString(
            if (retention.persistToDisk) R.string.settings_message_history_subtitle_persisted
            else R.string.settings_message_history_subtitle_memory,
            kept,
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
