package dev.fabuloso.slider

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.fabuloso.slider.databinding.ItemTagBinding

data class TagRowItem(
    val record: TagRecord,
    val saveEnabled: Boolean,
    val deleteEnabled: Boolean,
)

class TagListAdapter(
    private val onSave: (TagRecord) -> Unit,
    private val onDelete: (TagRecord) -> Unit,
) : ListAdapter<TagRowItem, TagListAdapter.ViewHolder>(DiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTagBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemTagBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TagRowItem) {
            binding.txtUid.text = item.record.uid.toHexColonSeparated()
            binding.btnSave.isEnabled = item.saveEnabled
            binding.btnSave.alpha = if (item.saveEnabled) 1f else DISABLED_ALPHA
            binding.btnDelete.isEnabled = item.deleteEnabled
            binding.btnDelete.alpha = if (item.deleteEnabled) 1f else DISABLED_ALPHA
            binding.btnSave.setOnClickListener { onSave(item.record) }
            binding.btnDelete.setOnClickListener { onDelete(item.record) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<TagRowItem>() {
        override fun areItemsTheSame(oldItem: TagRowItem, newItem: TagRowItem): Boolean =
            oldItem.record.uidHex == newItem.record.uidHex

        override fun areContentsTheSame(oldItem: TagRowItem, newItem: TagRowItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val DISABLED_ALPHA = 0.45f
    }
}
