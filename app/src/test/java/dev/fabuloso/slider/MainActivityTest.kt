package dev.fabuloso.slider

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNfcAdapter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    private lateinit var nfcAdapter: NfcAdapter

    @Before
    fun setUp() {
        ShadowNfcAdapter.setNfcHardwareExists(true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        nfcAdapter = requireNotNull(NfcAdapter.getDefaultAdapter(context))
    }

    @After
    fun tearDown() {
        ShadowNfcAdapter.reset()
    }

    @Test
    fun `disabled NFC opens settings automatically and reader starts after enabling`() {
        val shadowAdapter = shadowOf(nfcAdapter)
        shadowAdapter.setEnabled(false)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertEquals(
            Settings.ACTION_NFC_SETTINGS,
            shadowOf(activity).nextStartedActivity.action,
        )
        assertFalse(shadowAdapter.isInReaderMode)

        shadowAdapter.setEnabled(true)
        controller.pause().resume()

        assertTrue(shadowAdapter.isInReaderMode)
        controller.pause().stop().destroy()
    }

    @Test
    fun `enabled NFC starts reader without opening settings`() {
        val shadowAdapter = shadowOf(nfcAdapter)
        shadowAdapter.setEnabled(true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertTrue(shadowAdapter.isInReaderMode)
        assertEquals(null, shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }
}
