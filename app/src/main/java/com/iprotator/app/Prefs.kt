package com.iprotator.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("rotator", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v).apply()

    var deviceId: String
        get() = sp.getString("device_id", "") ?: ""
        set(v) = sp.edit().putString("device_id", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    /** Whether the service should run (also used to auto-start after reboot). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var lastResult: String
        get() = sp.getString("last_result", "") ?: ""
        set(v) = sp.edit().putString("last_result", v).apply()
}
