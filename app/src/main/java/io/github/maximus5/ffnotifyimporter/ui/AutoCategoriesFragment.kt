package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.DialogAutoCategoryEditBinding
import io.github.maximus5.ffnotifyimporter.databinding.FragmentAutoCategoriesBinding
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.posting.FireflySuggestions
import io.github.maximus5.ffnotifyimporter.prefs.AutoCategoriesPrefs
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.AutoCategoryEntry
import io.github.maximus5.ffnotifyimporter.rules.CategoryMatch
import io.github.maximus5.ffnotifyimporter.rules.normalizeCounterpartyKey
import kotlinx.coroutines.launch

/**
 * Settings -> Auto categories: the learning switch and the counterparty -> category list (see
 * `rules.AutoCategories`). Changes apply at once (switch, per-row switch, add/edit/delete), like
 * the Currencies list - there is no Save button on this screen.
 */
class AutoCategoriesFragment : Fragment() {

    private var binding: FragmentAutoCategoriesBinding? = null
    private lateinit var prefs: AutoCategoriesPrefs
    private lateinit var adapter: AutoCategoriesAdapter
    private var entries: List<AutoCategoryEntry> = emptyList()

    /** Firefly's category names for the edit dialog's suggestions; silent if unavailable. */
    private var categoryNames: List<String>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentAutoCategoriesBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        prefs = AutoCategoriesPrefs(requireContext())

        binding.learnSwitch.isChecked = prefs.isLearnEnabled()
        binding.learnSwitch.setOnCheckedChangeListener { _, checked -> prefs.setLearnEnabled(checked) }

        adapter = AutoCategoriesAdapter(
            onClick = ::showEditDialog,
            onActiveChanged = ::setActive,
            onOverflowClick = ::showOverflowMenu,
        )
        binding.entriesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.entriesRecyclerView.adapter = adapter

        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_auto_categories_list, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    if (menuItem.itemId != R.id.action_add_auto_category) return false
                    showEditDialog(null)
                    return true
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED,
        )
        loadCategoryNames()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        entries = prefs.loadEntries()
        adapter.submitList(entries)
        binding?.emptyText?.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadCategoryNames() {
        val firefly = FireflyPrefs(requireContext())
        if (!firefly.isConfigured()) return
        viewLifecycleOwner.lifecycleScope.launch {
            categoryNames = (FireflySuggestions.categories(firefly) as? FireflyApiClient.Result.Success)?.value?.map { it.name }
        }
    }

    private fun setActive(index: Int, active: Boolean) {
        if (index !in entries.indices || entries[index].active == active) return
        prefs.saveEntries(entries.toMutableList().also { it[index] = it[index].copy(active = active) })
        reload()
    }

    /** [index] null = a new, typed-in entry (contains by default - the usual "cafe" case). */
    private fun showEditDialog(index: Int?) {
        val existing = index?.let { entries.getOrNull(it) }
        val dialogBinding = DialogAutoCategoryEditBinding.inflate(LayoutInflater.from(requireContext()))
        dialogBinding.keyInput.setText(existing?.key.orEmpty())
        dialogBinding.categoryInput.setText(existing?.category.orEmpty())
        categoryNames?.takeIf { it.isNotEmpty() }?.let {
            dialogBinding.categoryInput.setAdapter(SuggestionAdapter(requireContext(), it))
        }

        val matches = listOf(CategoryMatch.EQUALS, CategoryMatch.CONTAINS)
        val labels = matches.map {
            getString(if (it == CategoryMatch.EQUALS) R.string.auto_categories_match_equals else R.string.auto_categories_match_contains)
        }
        var match = existing?.match ?: CategoryMatch.CONTAINS
        dialogBinding.matchInput.setSimpleItems(labels.toTypedArray())
        dialogBinding.matchInput.setText(labels[matches.indexOf(match)], false)
        dialogBinding.matchInput.setOnItemClickListener { _, _, position, _ -> match = matches[position] }

        // Cancel always rightmost/last (house convention, AGENTS.md) - Save sits in the negative slot.
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing != null) R.string.auto_categories_edit_title else R.string.auto_categories_add)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_save, null)
            .setPositiveButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            val key = normalizeCounterpartyKey(dialogBinding.keyInput.text?.toString().orEmpty())
            val category = dialogBinding.categoryInput.text?.toString()?.trim().orEmpty()
            val problem = when {
                key.isEmpty() -> R.string.auto_categories_key_required
                category.isEmpty() -> R.string.auto_categories_category_required
                entries.withIndex().any { (i, e) -> i != index && e.match == match && e.key == key } -> R.string.auto_categories_duplicate
                else -> null
            }
            if (problem != null) {
                Toast.makeText(requireContext(), problem, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val updated = AutoCategoryEntry(
                key = key,
                match = match,
                category = category,
                active = existing?.active ?: true,
                learned = existing?.learned ?: false,
            )
            prefs.saveEntries(
                if (index != null && existing != null) entries.toMutableList().also { it[index] = updated } else entries + updated,
            )
            reload()
            dialog.dismiss()
        }
    }

    private fun showOverflowMenu(index: Int, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ID_DELETE, 0, R.string.action_delete)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == ID_DELETE) confirmDelete(index)
            true
        }
        popup.show()
    }

    private fun confirmDelete(index: Int) {
        val entry = entries.getOrNull(index) ?: return
        // Cancel always rightmost/last (house convention, AGENTS.md).
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.auto_categories_delete_confirm_title, entry.key))
            .setNegativeButton(R.string.action_delete) { _, _ ->
                prefs.saveEntries(entries.toMutableList().also { it.removeAt(index) })
                reload()
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private companion object {
        const val ID_DELETE = 1
    }
}
