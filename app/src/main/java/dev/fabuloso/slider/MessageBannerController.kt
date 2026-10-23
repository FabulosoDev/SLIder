package dev.fabuloso.slider

import android.content.res.ColorStateList
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import dev.fabuloso.slider.databinding.ActivityMainBinding

class MessageBannerController(
    private val activity: ComponentActivity,
    private val binding: ActivityMainBinding,
) {
    private var currentMessage: Int? = null
    private val hideBanner = Runnable { hide() }

    init {
        binding.btnDismissMessage.setOnClickListener { hide() }
    }

    fun show(message: Int) {
        show(message, styleFor(message))
    }

    fun showAction(
        message: Int,
        actionLabel: Int,
        durationMillis: Long?,
        onAction: () -> Unit,
    ) {
        show(
            message = message,
            style = styleFor(message).copy(durationMillis = durationMillis),
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }

    fun hideIfShowing(message: Int) {
        if (currentMessage == message) hide()
    }

    fun hide() {
        val banner = binding.messageBanner
        banner.removeCallbacks(hideBanner)
        if (banner.visibility != View.VISIBLE) return
        currentMessage = null

        banner.animate().cancel()
        banner.animate()
            .alpha(0f)
            .translationY(-dp(MESSAGE_BANNER_OFFSET_DP).toFloat())
            .setDuration(MESSAGE_BANNER_ANIMATION_MS)
            .withEndAction {
                banner.visibility = View.GONE
                banner.alpha = 1f
                banner.translationY = 0f
            }
            .start()
    }

    fun dispose() {
        binding.messageBanner.removeCallbacks(hideBanner)
    }

    private fun show(
        message: Int,
        style: MessageBannerStyle,
        actionLabel: Int? = null,
        onAction: (() -> Unit)? = null,
    ) {
        currentMessage = message
        val banner = binding.messageBanner
        banner.removeCallbacks(hideBanner)
        banner.animate().cancel()

        binding.txtMessageBanner.apply {
            setText(message)
            setTextColor(ContextCompat.getColor(activity, style.contentColor))
        }
        binding.btnDismissMessage.apply {
            visibility = if (style.durationMillis == null && onAction == null) {
                View.VISIBLE
            } else {
                View.GONE
            }
            imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(activity, style.contentColor),
            )
        }
        binding.btnMessageAction.apply {
            visibility = if (actionLabel != null && onAction != null) View.VISIBLE else View.GONE
            actionLabel?.let(::setText)
            setTextColor(ContextCompat.getColor(activity, style.contentColor))
            setOnClickListener(
                if (onAction == null) {
                    null
                } else {
                    View.OnClickListener {
                        hide()
                        onAction()
                    }
                },
            )
        }
        banner.setBackgroundResource(style.background)

        if (banner.visibility != View.VISIBLE) {
            banner.alpha = 0f
            banner.translationY = -dp(MESSAGE_BANNER_OFFSET_DP).toFloat()
            banner.visibility = View.VISIBLE
        }
        banner.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MESSAGE_BANNER_ANIMATION_MS)
            .start()

        style.durationMillis?.let { duration ->
            banner.postDelayed(hideBanner, duration)
        }
    }

    private fun styleFor(message: Int): MessageBannerStyle = when (message) {
        R.string.toast_tag_added,
        R.string.toast_save_success,
        R.string.toast_tag_deleted,
        R.string.toast_tag_restored,
        R.string.toast_all_deleted,
        -> MessageBannerStyle(
            background = R.drawable.bg_message_success,
            contentColor = R.color.message_success_content,
            durationMillis = MESSAGE_BANNER_DURATION_MS,
        )

        R.string.toast_tag_unsupported,
        R.string.toast_tag_duplicate,
        R.string.toast_scan_queue_full,
        -> MessageBannerStyle(
            background = R.drawable.bg_message_warning,
            contentColor = R.color.message_warning_content,
            durationMillis = MESSAGE_BANNER_DURATION_MS,
        )

        R.string.toast_nfc_unavailable,
        R.string.toast_nfc_disabled,
        R.string.toast_read_failed,
        R.string.toast_storage_failed,
        R.string.toast_save_failed,
        R.string.toast_save_missing,
        R.string.toast_share_failed,
        -> MessageBannerStyle(
            background = R.drawable.bg_message_error,
            contentColor = R.color.message_error_content,
            durationMillis = null,
        )

        else -> MessageBannerStyle(
            background = R.drawable.bg_message_info,
            contentColor = R.color.message_info_content,
            durationMillis = MESSAGE_BANNER_DURATION_MS,
        )
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private data class MessageBannerStyle(
        val background: Int,
        val contentColor: Int,
        val durationMillis: Long?,
    )

    private companion object {
        const val MESSAGE_BANNER_DURATION_MS = 3_500L
        const val MESSAGE_BANNER_ANIMATION_MS = 180L
        const val MESSAGE_BANNER_OFFSET_DP = 8
    }
}
