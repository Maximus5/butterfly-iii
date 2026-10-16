package io.github.maximus5.ffnotifyimporter.ui

import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentAboutBinding
import io.github.maximus5.ffnotifyimporter.posting.DiagnosticLog

/** Name, version, Play Store and GitHub links - nothing user-configurable, so no prefs/db involved. */
class AboutFragment : Fragment() {

    private var binding: FragmentAboutBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return

        val versionName = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        binding.aboutVersion.text = getString(R.string.about_version, versionName ?: "?")

        val githubUrl = getString(R.string.about_github_url)
        binding.aboutGithubLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
        }
        // The Play Store app first (market:// is what an Android link should open); the web page when
        // there is no Play Store app on the device.
        binding.aboutPlayStoreLink.setOnClickListener {
            val packageName = requireContext().packageName
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_play_store_url))))
            }
        }

        val context = requireContext()
        binding.aboutDiagnosticSwitch.isChecked = DiagnosticLog.isEnabled(context)
        binding.aboutDiagnosticSwitch.setOnCheckedChangeListener { _, isChecked -> DiagnosticLog.setEnabled(context, isChecked) }
        binding.aboutCopyLog.setOnClickListener {
            val log = DiagnosticLog.read(context)
            if (log.isBlank()) {
                Toast.makeText(context, R.string.about_diagnostic_empty, Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Butterfly III diagnostic log", "Version ${versionName ?: "?"}\n$log"))
                Toast.makeText(context, R.string.about_diagnostic_copied, Toast.LENGTH_SHORT).show()
            }
        }
        binding.aboutClearLog.setOnClickListener { DiagnosticLog.clear(context) }
        binding.aboutDiagnosticDetails.setOnClickListener { showDiagnosticDetails() }
    }

    /** What the log holds and what it never does - kept out of the screen itself because it is long. */
    private fun showDiagnosticDetails() {
        val text = SpannableStringBuilder()
        fun section(titleRes: Int, itemsRes: Int) {
            if (text.isNotEmpty()) text.append("\n\n")
            val start = text.length
            text.append(getString(titleRes))
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append("\n").append(getString(itemsRes))
        }
        section(R.string.about_diagnostic_written_title, R.string.about_diagnostic_written)
        section(R.string.about_diagnostic_never_title, R.string.about_diagnostic_never)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.about_diagnostic_details_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
