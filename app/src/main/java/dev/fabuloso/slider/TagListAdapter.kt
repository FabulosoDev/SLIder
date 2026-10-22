package dev.fabuloso.slider

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.fabuloso.slider.databinding.ItemTagBinding

data class TagRowItem(
    val record: TagRecord,
    val shareEnabled: Boolean,
    val deleteEnabled: Boolean,
    val highlightStartedAtUptimeMillis: Long = 0L,
)

class TagListAdapter(
    private val onClick: (TagRecord) -> Unit,
) : ListAdapter<TagRowItem, TagListAdapter.ViewHolder>(DiffCallback) {
    private var lastPlayedHighlight: Pair<String, Long>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTagBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clearHighlight()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemTagBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var highlightAnimator: ValueAnimator? = null
        private var boundHighlight: Pair<String, Long>? = null

        fun bind(item: TagRowItem) {
            val highlight = item.highlightStartedAtUptimeMillis
                .takeIf { it > 0L }
                ?.let { item.record.uidHex to it }
            if (boundHighlight != highlight &&
                (highlight != null ||
                    boundHighlight?.first != item.record.uidHex ||
                    highlightAnimator == null)
            ) {
                clearHighlight()
                boundHighlight = highlight
            }

            binding.txtUid.text = item.record.uid.toHexColonSeparated()
            binding.root.setOnClickListener { onClick(item.record) }

            if (highlight != null && highlight != lastPlayedHighlight) {
                lastPlayedHighlight = highlight
                val elapsed = SystemClock.uptimeMillis() - highlight.second
                if (elapsed in 0 until HIGHLIGHT_WINDOW_MS) {
                    val highlightColor = ContextCompat.getColor(
                        binding.root.context,
                        R.color.new_tag_highlight,
                    )
                    highlightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = HIGHLIGHT_DURATION_MS
                        addUpdateListener { animator ->
                            val elapsedHighlight = animator.currentPlayTime
                            val opacity = when {
                                elapsedHighlight < FADE_IN_MS ->
                                    elapsedHighlight.toFloat() / FADE_IN_MS
                                elapsedHighlight > HIGHLIGHT_DURATION_MS - FADE_OUT_MS ->
                                    (HIGHLIGHT_DURATION_MS - elapsedHighlight).toFloat() / FADE_OUT_MS
                                else -> 1f
                            }.coerceIn(0f, 1f)
                            binding.root.setBackgroundColor(
                                ColorUtils.setAlphaComponent(
                                    highlightColor,
                                    (Color.alpha(highlightColor) * opacity).toInt(),
                                ),
                            )
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                binding.root.setBackgroundColor(Color.TRANSPARENT)
                                highlightAnimator = null
                            }
                        })
                        start()
                    }
                }
            }
        }

        fun clearHighlight() {
            highlightAnimator?.cancel()
            highlightAnimator = null
            boundHighlight = null
            binding.root.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<TagRowItem>() {
        override fun areItemsTheSame(oldItem: TagRowItem, newItem: TagRowItem): Boolean =
            oldItem.record.uidHex == newItem.record.uidHex

        override fun areContentsTheSame(oldItem: TagRowItem, newItem: TagRowItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val FADE_IN_MS = 250L
        private const val FADE_OUT_MS = 350L
        private const val HIGHLIGHT_DURATION_MS = 3_000L
        internal const val HIGHLIGHT_WINDOW_MS = 3_000L
    }
}
