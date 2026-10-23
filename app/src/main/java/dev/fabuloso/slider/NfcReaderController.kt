package dev.fabuloso.slider

import android.content.ActivityNotFoundException
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity

class NfcReaderController(
    private val activity: ComponentActivity,
    private val onTagDiscovered: (Tag) -> Unit,
    private val onUnsupportedTag: () -> Unit,
    private val onNfcUnavailable: () -> Unit,
    private val onNfcDisabled: (() -> Unit) -> Unit,
    private val onNfcEnabled: () -> Unit,
) {
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private var settingsLaunched = false

    init {
        if (adapter == null) {
            Log.w(TAG, "This device does not support NFC")
            onNfcUnavailable()
        }
    }

    fun handleIntent(intent: Intent?) {
        val tag: Tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        } ?: return

        handleTag(tag)
    }

    fun resume() {
        val nfcAdapter = adapter ?: return
        if (nfcAdapter.isEnabled) {
            settingsLaunched = false
            onNfcEnabled()
            nfcAdapter.enableReaderMode(
                activity,
                ::handleTag,
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null,
            )
            Log.i(TAG, "Automatic NFC reading is active")
            return
        }

        Log.w(TAG, "NFC is disabled")
        onNfcDisabled(::openSettings)
        if (!settingsLaunched) openSettings()
    }

    fun pause() {
        adapter?.disableReaderMode(activity)
    }

    private fun handleTag(tag: Tag) {
        if (!tag.techList.orEmpty().contains(NfcV::class.java.name)) {
            Log.w(TAG, "Detected tag is not ISO15693 / NfcV")
            onUnsupportedTag()
            return
        }
        onTagDiscovered(tag)
    }

    private fun openSettings() {
        settingsLaunched = true
        try {
            activity.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "Dedicated NFC settings are unavailable; opening wireless settings", error)
            activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    private companion object {
        const val TAG = "NfcReaderController"
    }
}
