package io.github.maximus5.ffnotifyimporter.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.ItemAutoCategoryBinding
import io.github.maximus5.ffnotifyimporter.rules.AutoCategoryEntry
import io.github.maximus5.ffnotifyimporter.rules.CategoryMatch

/**
 * Rows of the Auto categories list - same interaction as the rules list ([RulesAdapter]): tap to
 * open, long-press to start a multi-select, a per-row overflow menu. Entries have no ids, so every
 * callback and the selection are keyed by the entry's index in the list as shown (the order IS the
 * priority).
 */
class AutoCategoriesAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Boolean,
    private val onOverflowClick: (Int, View) -> Unit,
) : RecyclerView.Adapter<AutoCategoriesAdapter.ViewHolder>() {

    private var items: List<AutoCategoryEntry> = emptyList()
    private val selected = mutableSetOf<Int>()
    private var selectionMode = false

    fun submitList(newItems: List<AutoCategoryEntry>) {
        items = newItems
        selected.retainAll(newItems.indices.toSet())
        notifyDataSetChanged()
    }

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        notifyDataSetChanged()
    }

    fun selectedIndices(): Set<Int> = selected.toSet()

    fun selectedCount(): Int = selected.size

    fun setSelected(indices: Set<Int>) {
        selected.clear()
        selected.addAll(indices)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(index: Int) {
        if (!selected.add(index)) selected.remove(index)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAutoCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(position, items[position])

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemAutoCategoryBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(index: Int, entry: AutoCategoryEntry) {
            val context = binding.root.context
            binding.entryKey.text = entry.key
            if (entry.active) {
                binding.entryCategory.text = entry.category
                binding.entryCategory.setTypeface(null, Typeface.NORMAL)
            } else {
                // Instead of a category name: what this entry actually does - nothing is filled in.
                binding.entryCategory.text = context.getString(R.string.auto_categories_not_filled)
                binding.entryCategory.setTypeface(null, Typeface.ITALIC)
            }
            val match = context.getString(
                if (entry.match == CategoryMatch.EQUALS) R.string.auto_categories_match_equals else R.string.auto_categories_match_contains,
            )
            val origin = context.getString(
                if (entry.learned) R.string.auto_categories_origin_learned else R.string.auto_categories_origin_manual,
            )
            val detail = context.getString(R.string.auto_categories_row_detail, match, origin)
            binding.entryDetail.text = if (entry.keep) "$detail · ${context.getString(R.string.auto_categories_row_keep)}" else detail
            binding.root.alpha = if (entry.active) 1f else 0.6f
            binding.root.setBackgroundColor(if (index in selected) SELECTED_BACKGROUND else Color.TRANSPARENT)

            binding.entryContent.setOnClickListener { onClick(bindingAdapterPosition) }
            binding.entryContent.setOnLongClickListener { onLongClick(bindingAdapterPosition) }
            binding.entryOverflowButton.visibility = if (selectionMode) View.GONE else View.VISIBLE
            binding.entryOverflowButton.setOnClickListener { onOverflowClick(bindingAdapterPosition, it) }
        }
    }

    private companion object {
        /** Same selected-row tint as [RulesAdapter]. */
        const val SELECTED_BACKGROUND = 0x338C9EFF
    }
}
