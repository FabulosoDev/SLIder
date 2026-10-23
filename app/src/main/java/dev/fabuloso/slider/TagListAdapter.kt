package dev.fabuloso.slider

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.fabuloso.slider.databinding.ItemTagBinding

data class TagRowItem(
    val record: TagRecord,
    val shareEnabled: Boolean,
    val deleteEnabled: Boolean,
    val newBadgeStartedAtUptimeMillis: Long = 0L,
) {
    fun hasSameRenderedContent(other: TagRowItem): Boolean =
        record == other.record &&
            newBadgeStartedAtUptimeMillis == other.newBadgeStartedAtUptimeMillis
}

class TagListAdapter(
    private val onClick: (TagRecord) -> Unit,
) : ListAdapter<TagRowItem, TagListAdapter.ViewHolder>(DiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTagBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clearNewBadge()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemTagBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val hideNewBadge = Runnable {
            binding.txtNewBadge.visibility = View.GONE
        }

        fun bind(item: TagRowItem) {
            binding.txtUid.text = item.record.uid.toHexColonSeparated()
            binding.root.setOnClickListener { onClick(item.record) }

            clearNewBadge()
            val elapsed = SystemClock.uptimeMillis() - item.newBadgeStartedAtUptimeMillis
            if (item.newBadgeStartedAtUptimeMillis > 0L && elapsed in 0 until NEW_BADGE_WINDOW_MS) {
                binding.txtNewBadge.visibility = View.VISIBLE
                binding.txtNewBadge.postDelayed(hideNewBadge, NEW_BADGE_WINDOW_MS - elapsed)
            }
        }

        fun clearNewBadge() {
            binding.txtNewBadge.removeCallbacks(hideNewBadge)
            binding.txtNewBadge.visibility = View.GONE
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<TagRowItem>() {
        override fun areItemsTheSame(oldItem: TagRowItem, newItem: TagRowItem): Boolean =
            oldItem.record.uidHex == newItem.record.uidHex

        override fun areContentsTheSame(oldItem: TagRowItem, newItem: TagRowItem): Boolean =
            oldItem.hasSameRenderedContent(newItem)
    }

    companion object {
        internal const val NEW_BADGE_WINDOW_MS = 3_000L
    }
}
