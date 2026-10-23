package dev.fabuloso.slider

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class TagSwipeCallback(
    private val context: Context,
    private val adapter: TagListAdapter,
    private val onShare: (TagRecord) -> Unit,
    private val onDelete: (TagRecord) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
    private val backgroundPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            14f,
            context.resources.displayMetrics,
        )
        typeface = Typeface.DEFAULT_BOLD
    }
    private val shareIcon = swipeIcon(R.drawable.ic_share)
    private val deleteIcon = swipeIcon(R.drawable.ic_delete)

    override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        val item = adapter.currentList.getOrNull(viewHolder.bindingAdapterPosition) ?: return 0
        var directions = 0
        if (item.shareEnabled) directions = directions or ItemTouchHelper.RIGHT
        if (item.deleteEnabled) directions = directions or ItemTouchHelper.LEFT
        return directions
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        val item = adapter.currentList.getOrNull(position) ?: return
        when (direction) {
            ItemTouchHelper.RIGHT -> {
                onShare(item.record)
                resetSwipedRow(viewHolder.itemView, item.record.uidHex)
            }
            ItemTouchHelper.LEFT -> {
                resetSwipedRow(viewHolder.itemView, item.record.uidHex)
                onDelete(item.record)
            }
        }
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = SWIPE_THRESHOLD

    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
            if (dX > 0f) {
                drawSwipeAction(
                    canvas,
                    viewHolder.itemView,
                    dX,
                    R.color.action_share,
                    shareIcon,
                    context.getString(R.string.swipe_share),
                )
            } else {
                drawSwipeAction(
                    canvas,
                    viewHolder.itemView,
                    dX,
                    R.color.action_danger,
                    deleteIcon,
                    context.getString(R.string.delete),
                )
            }
        }
        super.onChildDraw(
            canvas,
            recyclerView,
            viewHolder,
            dX,
            dY,
            actionState,
            isCurrentlyActive,
        )
    }

    private fun drawSwipeAction(
        canvas: Canvas,
        itemView: View,
        dX: Float,
        colorResource: Int,
        icon: Drawable,
        label: String,
    ) {
        val fromStart = dX > 0f
        val revealLeft = if (fromStart) itemView.left.toFloat() else itemView.right + dX
        val revealRight = if (fromStart) itemView.left + dX else itemView.right.toFloat()
        backgroundPaint.color = ContextCompat.getColor(context, colorResource)
        canvas.drawRect(
            revealLeft,
            itemView.top.toFloat(),
            revealRight,
            itemView.bottom.toFloat(),
            backgroundPaint,
        )

        val iconSize = dp(SWIPE_ICON_SIZE_DP)
        val margin = dp(SWIPE_CONTENT_MARGIN_DP)
        val spacing = dp(SWIPE_ICON_TEXT_SPACING_DP)
        val centerY = (itemView.top + itemView.bottom) / 2
        val iconTop = centerY - iconSize / 2
        val textBaseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f

        canvas.save()
        canvas.clipRect(revealLeft, itemView.top.toFloat(), revealRight, itemView.bottom.toFloat())
        if (fromStart) {
            val iconLeft = itemView.left + margin
            icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            icon.draw(canvas)
            canvas.drawText(label, (iconLeft + iconSize + spacing).toFloat(), textBaseline, textPaint)
        } else {
            val iconRight = itemView.right - margin
            val iconLeft = iconRight - iconSize
            icon.setBounds(iconLeft, iconTop, iconRight, iconTop + iconSize)
            icon.draw(canvas)
            val textX = iconLeft - spacing - textPaint.measureText(label)
            canvas.drawText(label, textX, textBaseline, textPaint)
        }
        canvas.restore()
    }

    private fun resetSwipedRow(itemView: View, uidHex: String) {
        itemView.post {
            val position = adapter.currentList.indexOfFirst { it.record.uidHex == uidHex }
            if (position >= 0) adapter.notifyItemChanged(position)
        }
    }

    private fun swipeIcon(resource: Int): Drawable =
        requireNotNull(ContextCompat.getDrawable(context, resource)).mutate().apply {
            setTint(Color.WHITE)
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val SWIPE_THRESHOLD = 0.35f
        const val SWIPE_ICON_SIZE_DP = 24
        const val SWIPE_CONTENT_MARGIN_DP = 16
        const val SWIPE_ICON_TEXT_SPACING_DP = 8
    }
}
