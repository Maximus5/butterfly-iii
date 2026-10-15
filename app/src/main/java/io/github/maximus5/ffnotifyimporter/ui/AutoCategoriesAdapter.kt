package io.github.maximus5.ffnotifyimporter.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.ItemAutoCategoryBinding
import io.github.maximus5.ffnotifyimporter.rules.AutoCategoryEntry
import io.github.maximus5.ffnotifyimporter.rules.CategoryMatch

/** Rows of the Auto categories list; every callback gets the entry's index in the list as shown. */
class AutoCategoriesAdapter(
    private val onClick: (Int) -> Unit,
    private val onActiveChanged: (Int, Boolean) -> Unit,
    private val onOverflowClick: (Int, View) -> Unit,
) : RecyclerView.Adapter<AutoCategoriesAdapter.ViewHolder>() {

    private var items: List<AutoCategoryEntry> = emptyList()

    fun submitList(newItems: List<AutoCategoryEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAutoCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemAutoCategoryBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: AutoCategoryEntry) {
            val context = binding.root.context
            binding.entryKey.text = entry.key
            binding.entryCategory.text = entry.category
            val match = context.getString(
                if (entry.match == CategoryMatch.EQUALS) R.string.auto_categories_match_equals else R.string.auto_categories_match_contains,
            )
            val origin = context.getString(
                if (entry.learned) R.string.auto_categories_origin_learned else R.string.auto_categories_origin_manual,
            )
            binding.entryDetail.text = context.getString(R.string.auto_categories_row_detail, match, origin)
            binding.root.alpha = if (entry.active) 1f else 0.55f

            binding.entryActiveSwitch.setOnCheckedChangeListener(null)
            binding.entryActiveSwitch.isChecked = entry.active
            binding.entryActiveSwitch.setOnCheckedChangeListener { _, checked ->
                onActiveChanged(bindingAdapterPosition, checked)
            }
            binding.entryContent.setOnClickListener { onClick(bindingAdapterPosition) }
            binding.entryOverflowButton.setOnClickListener { onOverflowClick(bindingAdapterPosition, it) }
        }
    }
}
