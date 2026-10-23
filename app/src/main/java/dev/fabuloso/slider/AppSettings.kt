package dev.fabuloso.slider

import android.content.Context

class AppSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var lowDataRateEnabled: Boolean
        get() = preferences.getBoolean(KEY_LOW_DATA_RATE_ENABLED, false)
        set(enabled) {
            preferences.edit().putBoolean(KEY_LOW_DATA_RATE_ENABLED, enabled).apply()
        }

    companion object {
        internal const val PREFERENCES_NAME = "app_settings"
        private const val KEY_LOW_DATA_RATE_ENABLED = "low_data_rate_enabled"
    }
}
