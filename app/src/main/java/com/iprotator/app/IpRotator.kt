package com.iprotator.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Global lock so API-triggered and manual rotations never overlap. */
object Rotation {
    val lock = Mutex()
}

data class RotationResult(
    val ok: Boolean,
    val oldIp: String?,
    val newIp: String?,
    val changed: Boolean,
    val attempts: Int,
    val method: String?,
    val durationMs: Long,
    val error: String?,
    val hotspot: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("old_ip", oldIp ?: JSONObject.NULL)
        .put("new_ip", newIp ?: JSONObject.NULL)
        .put("changed", changed)
        .put("attempts", attempts)
        .put("method", method ?: JSONObject.NULL)
        .put("duration_ms", durationMs)
        .put("error", error ?: JSONObject.NULL)
        .put("hotspot", hotspot)
}

class IpRotator(private val context: Context, private val http: OkHttpClient) {

    private val ipEndpoints = listOf(
        "https://api.ipify.org",
        "https://icanhazip.com",
        "https://ifconfig.me/ip",
    )

    private val cm get() = context.getSystemService(ConnectivityManager::class.java)

    private fun cellularNetwork(validatedOnly: Boolean = false): Network? {
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (!validatedOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
    }

    /**
     * Public IP as seen over MOBILE DATA (bound to the cellular network even if Wi-Fi is on).
     * Falls back to the default network if there is no cellular network.
     */
    fun publicIp(): String? {
        val net = cellularNetwork()
        val client = if (net != null) {
            http.newBuilder()
                .socketFactory(net.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String) = net.getAllByName(hostname).toList()
                })
                .build()
        } else http

        for (url in ipEndpoints) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val ip = resp.body?.string()?.trim()
                    if (resp.isSuccessful && !ip.isNullOrBlank() && ip.length <= 45) return ip
                }
            } catch (_: Exception) { /* try next endpoint */ }
        }
        return null
    }

    private suspend fun waitForCellular(timeoutMs: Long = 45_000): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (cellularNetwork(validatedOnly = true) != null) return true
            delay(500)
        }
        return false
    }

    /**
     * Airplane ON -> wait [offMillis] -> airplane OFF -> wait for mobile data -> verify IP.
     * Retries when the carrier hands back the same IP.
     */
    suspend fun rotate(maxAttempts: Int = 3, offMillis: Long = 5_000): RotationResult =
        withContext(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            val oldIp = publicIp()
            var newIp: String? = null
            var method: String? = null
            var attempts = 0
            var error: String? = null

            try {
                while (attempts < maxAttempts.coerceIn(1, 10)) {
                    attempts++
                    method = AirplaneToggler.set(context, true).name
                    delay(offMillis.coerceIn(1_000, 60_000))
                    AirplaneToggler.set(context, false)
                    
                    // Enable mobile data and hotspot once airplane mode is turned off
                    HotspotManager.turnOnHotspot(context)

                    newIp = if (waitForCellular()) publicIp() else null
                    if (newIp != null && newIp != oldIp) break
                }
                if (newIp == null) error = "Mobile data did not come back / IP check failed"
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                // Safety net: never leave the phone stuck in airplane mode.
                if (AirplaneToggler.isAirplaneOn(context)) {
                    runCatching { AirplaneToggler.set(context, false) }
                }
            }

            RotationResult(
                ok = error == null && newIp != null,
                oldIp = oldIp,
                newIp = newIp,
                changed = newIp != null && newIp != oldIp,
                attempts = attempts,
                method = method,
                durationMs = SystemClock.elapsedRealtime() - start,
                error = error,
                hotspot = HotspotManager.isHotspotOn(context),
            )
        }
}
