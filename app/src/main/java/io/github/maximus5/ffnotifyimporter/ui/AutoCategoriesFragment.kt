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
import androidx.core.widget.doAfterTextChanged
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
import io.github.maximus5.ffnotifyimporter.rules.keyShortenedToPart
import io.github.maximus5.ffnotifyimporter.rules.moveSelected
import io.github.maximus5.ffnotifyimporter.rules.normalizeCounterpartyKey
import kotlinx.coroutines.launch

/**
 * Settings -> Auto categories: the learning switch and the counterparty -> category list (see
 * `rules.AutoCategories`). The list behaves like the rules list: its order is the priority (first
 * match wins), long-press selects rows and the toolbar then moves them up/down or deletes them, each
 * row has the same overflow menu. Changes apply at once (switch, add/edit/move/delete) - there is no
 * Save button on this screen, like the Currencies list.
 */
class AutoCategoriesFragment : Fragment() {

    private var binding: FragmentAutoCategoriesBinding? = null
    private lateinit var prefs: AutoCategoriesPrefs
    private lateinit var adapter: AutoCategoriesAdapter
    private var entries: List<AutoCategoryEntry> = emptyList()
    private var inSelectionMode = false

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
            onClick = ::handleClick,
            onLongClick = ::handleLongClick,
            onOverflowClick = ::showOverflowMenu,
        )
        binding.entriesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.entriesRecyclerView.adapter = adapter

        // One provider for both menus (normal / selection), re-run by invalidateMenu() - same
        // shape as RulesListFragment.
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(
                        if (inSelectionMode) R.menu.menu_auto_categories_selection else R.menu.menu_auto_categories_list,
                        menu,
                    )
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
                    R.id.action_add_auto_category -> { showEditDialog(null); true }
                    R.id.action_move_up_selected -> { moveSelectedEntries(up = true); true }
                    R.id.action_move_down_selected -> { moveSelectedEntries(up = false); true }
                    R.id.action_delete_selected_entries -> { confirmDelete(adapter.selectedIndices()); true }
                    else -> false
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED,
        )
        loadCategoryNames()
    }

    override fun onResume() {
        super.onResume()
        if (!inSelectionMode) (activity as? SettingsActivity)?.setScreenTitle(R.string.settings_auto_categories_title)
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

    private fun handleClick(index: Int) {
        if (inSelectionMode) {
            adapter.toggleSelection(index)
            if (adapter.selectedCount() == 0) clearSelectionAndExit() else updateSelectionCountDisplay()
        } else {
            showEditDialog(index)
        }
    }

    private fun handleLongClick(index: Int): Boolean {
        if (!inSelectionMode) {
            inSelectionMode = true
            adapter.setSelectionMode(true)
            adapter.toggleSelection(index)
            requireActivity().invalidateMenu()
            (activity as? SettingsActivity)?.apply {
                updateSelectionCount(adapter.selectedCount())
                setBackGuard(::clearSelectionAndExit)
            }
        } else {
            handleClick(index)
        }
        return true
    }

    private fun updateSelectionCountDisplay() {
        (activity as? SettingsActivity)?.updateSelectionCount(adapter.selectedCount())
    }

    private fun clearSelectionAndExit() {
        adapter.clearSelection()
        adapter.setSelectionMode(false)
        inSelectionMode = false
        requireActivity().invalidateMenu()
        (activity as? SettingsActivity)?.apply {
            setBackGuard(null)
            setScreenTitle(R.string.settings_auto_categories_title)
        }
    }

    /** The selection (on-screen positions) after the move is what stays selected, and the list
     * scrolls to the leading edge so a repeated tap cannot walk it off the screen. */
    private fun moveSelectedEntries(up: Boolean) {
        val (moved, selection) = moveSelected(entries, adapter.selectedIndices(), up)
        prefs.saveEntries(moved)
        entries = moved
        adapter.submitList(moved)
        adapter.setSelected(selection)
        val lead = if (up) selection.minOrNull() else selection.maxOrNull()
        if (lead != null) binding?.entriesRecyclerView?.scrollToPosition(lead)
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

        // "Fill in the category" off: the category is not needed (it stays in the field, greyed out,
        // so switching back does not lose it).
        fun applyActive(active: Boolean) {
            dialogBinding.categoryLayout.isEnabled = active
            dialogBinding.categoryInput.isEnabled = active
        }
        dialogBinding.activeSwitch.isChecked = existing?.active ?: true
        applyActive(dialogBinding.activeSwitch.isChecked)
        dialogBinding.activeSwitch.setOnCheckedChangeListener { _, checked -> applyActive(checked) }
        dialogBinding.keepSwitch.isChecked = existing?.keep ?: false

        val matches = listOf(CategoryMatch.EQUALS, CategoryMatch.CONTAINS)
        val labels = matches.map {
            getString(if (it == CategoryMatch.EQUALS) R.string.auto_categories_match_equals else R.string.auto_categories_match_contains)
        }
        var match = existing?.match ?: CategoryMatch.CONTAINS
        dialogBinding.matchInput.setSimpleItems(labels.toTypedArray())
        dialogBinding.matchInput.setText(labels[matches.indexOf(match)], false)
        // Cutting an exact key down to a part of itself (e.g. dropping a ", de" tail) would make the
        // entry unmatchable: switch to "contains" with a hint, unless the user already chose a mode.
        var matchChosenByUser = false
        dialogBinding.matchInput.setOnItemClickListener { _, _, position, _ ->
            match = matches[position]
            matchChosenByUser = true
            dialogBinding.matchLayout.helperText = null
        }
        if (existing != null && existing.match == CategoryMatch.EQUALS) {
            dialogBinding.keyInput.doAfterTextChanged { text ->
                if (matchChosenByUser || match != CategoryMatch.EQUALS) return@doAfterTextChanged
                if (keyShortenedToPart(existing.key, text?.toString().orEmpty())) {
                    match = CategoryMatch.CONTAINS
                    dialogBinding.matchInput.setText(labels[matches.indexOf(match)], false)
                    dialogBinding.matchLayout.helperText = getString(R.string.auto_categories_match_switched_hint)
                }
            }
        }

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
            val active = dialogBinding.activeSwitch.isChecked
            val problem = when {
                key.isEmpty() -> R.string.auto_categories_key_required
                active && category.isEmpty() -> R.string.auto_categories_category_required
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
                active = active,
                learned = existing?.learned ?: false,
                keep = dialogBinding.keepSwitch.isChecked,
            )
            prefs.saveEntries(
                if (index != null && existing != null) entries.toMutableList().also { it[index] = updated } else entries + updated,
            )
            reload()
            dialog.dismiss()
        }
    }

    /** Same action set/order as the selection menu (move up, move down, delete last), like the
     * rules list's own pair of menus. */
    private fun showOverflowMenu(index: Int, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ID_MOVE_UP, 0, R.string.rule_move_up)
        popup.menu.add(0, ID_MOVE_DOWN, 1, R.string.rule_move_down)
        popup.menu.add(0, ID_DELETE, 2, R.string.rule_action_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_MOVE_UP, ID_MOVE_DOWN -> {
                    val (moved, _) = moveSelected(entries, setOf(index), item.itemId == ID_MOVE_UP)
                    prefs.saveEntries(moved)
                    reload()
                }
                ID_DELETE -> confirmDelete(setOf(index))
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun confirmDelete(indices: Set<Int>) {
        if (indices.isEmpty()) return
        val title = if (indices.size == 1) {
            getString(R.string.auto_categories_delete_confirm_title, entries.getOrNull(indices.first())?.key.orEmpty())
        } else {
            getString(R.string.auto_categories_delete_selected_confirm_title, indices.size)
        }
        // Cancel always rightmost/last (house convention, AGENTS.md).
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setNegativeButton(R.string.action_delete) { _, _ ->
                prefs.saveEntries(entries.filterIndexed { i, _ -> i !in indices })
                reload()
                if (inSelectionMode) clearSelectionAndExit()
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Leaving with a selection active must not leave the activity's back guard behind.
        if (inSelectionMode) (activity as? SettingsActivity)?.setBackGuard(null)
        binding = null
    }

    private companion object {
        const val ID_MOVE_UP = 1
        const val ID_MOVE_DOWN = 2
        const val ID_DELETE = 3
    }
}
