package com.iprotator.app

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

object HotspotManager {
    private const val TAG = "HotspotManager"

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * Checks whether the Wi-Fi Hotspot is currently enabled/active on the device.
     */
    fun isHotspotOn(context: Context): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            (method.invoke(wm) as? Boolean) == true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read hotspot state via reflection", e)
            false
        }
    }

    /**
     * Enables Mobile Hotspot programmatically or opens Settings.
     */
    suspend fun turnOnHotspot(context: Context) {
        val appContext = context.applicationContext

        // If already ON, no action needed
        if (isHotspotOn(appContext)) return

        // 1. Try Root Method if root is available
        if (AirplaneToggler.hasRoot()) {
            AirplaneToggler.ensureDataAndHotspot(appContext)
            if (isHotspotOn(appContext)) return
        }

        // 2. Try Native Android LocalOnlyHotspot API
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm != null) {
            try {
                @Suppress("MissingPermission")
                wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation?) {
                        super.onStarted(res)
                        reservation = res
                        Log.d(TAG, "LocalOnlyHotspot started successfully!")
                    }

                    override fun onStopped() {
                        super.onStopped()
                        reservation = null
                        Log.d(TAG, "LocalOnlyHotspot stopped!")
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        Log.e(TAG, "LocalOnlyHotspot failed with reason: $reason")
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: SecurityException) {
                Log.e(TAG, "startLocalOnlyHotspot permission denied", e)
            } catch (e: Exception) {
                Log.e(TAG, "startLocalOnlyHotspot exception", e)
            }
        }

        if (isHotspotOn(appContext)) return

        // 3. UI Automator or Open Settings Directly
        if (AutomatorService.isEnabled()) {
            AutomatorService.enableDataAndHotspotUI()
        } else {
            // Open Tether Settings directly for quick 1-tap access
            val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try {
                appContext.startActivity(intent)
            } catch (_: Exception) {
                appContext.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
