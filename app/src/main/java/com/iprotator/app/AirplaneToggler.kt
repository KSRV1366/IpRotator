package com.iprotator.app

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.widget.Toast
import com.iprotator.app.assist.AssistService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Turns airplane mode on/off using, in order:
 *   1. Digital assistant method (no root)
 *   2. Root (su) fallback
 * Each method is verified by reading the actual airplane-mode setting afterwards.
 */
object AirplaneToggler {

    enum class Method { ASSISTANT, ROOT }

    @Volatile
    var rootCached: Boolean? = null
        private set

    fun isAirplaneOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    fun isAssistantActive(context: Context): Boolean =
        VoiceInteractionService.isActiveService(
            context, ComponentName(context, AssistService::class.java)
        )

    /** Probes for root. May show a Magisk prompt the first time. Call off the main thread. */
    fun hasRoot(force: Boolean = false): Boolean {
        rootCached?.let { if (!force) return it }
        val result = runSu("id") == 0
        rootCached = result
        return result
    }

    /** Sets airplane mode and waits until the system reports the new state. */
    suspend fun set(context: Context, on: Boolean): Method {
        val errors = mutableListOf<String>()

        if (isAssistantActive(context)) {
            val svc = AssistService.instance
            if (svc != null) {
                withContext(Dispatchers.Main) { svc.requestAirplane(on) }
                if (waitForState(context, on, 8_000)) return Method.ASSISTANT
                errors += "assistant: state did not change (screen locked? OEM restriction?)"
            } else {
                errors += "assistant: selected but service not bound yet"
            }
        } else {
            errors += "assistant: app is not the active digital assistant"
        }

        if (withContext(Dispatchers.IO) { hasRoot() }) {
            withContext(Dispatchers.IO) {
                val code = runSu("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
                if (code != 0) {
                    // Older Android versions
                    runSu(
                        "settings put global airplane_mode_on ${if (on) 1 else 0}; " +
                            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on"
                    )
                }
            }
            if (waitForState(context, on, 8_000)) return Method.ROOT
            errors += "root: state did not change"
        } else {
            errors += "root: not available"
        }

        throw IllegalStateException("Could not toggle airplane mode -> " + errors.joinToString("; "))
    }

    private suspend fun waitForState(context: Context, on: Boolean, timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (isAirplaneOn(context) == on) return true
            delay(250)
        }
        return false
    }

    private fun runSu(cmd: String): Int = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }
        if (p.waitFor(15, TimeUnit.SECONDS)) p.exitValue() else { p.destroy(); -1 }
    } catch (e: Exception) {
        -1
    }

    /** Attempts to ensure mobile data and Wi-Fi hotspot are enabled using root. */
    suspend fun ensureDataAndHotspot(context: Context) {
        if (withContext(Dispatchers.IO) { hasRoot() }) {
            withContext(Dispatchers.IO) {
                // Enable mobile data
                runSu("svc data enable")
                
                // Different Android versions use different service call codes or commands for tethering
                val tetherCmd = "cmd tethering start-tethering wifi"
                val result = runSu(tetherCmd)
                
                // If cmd tethering fails (e.g., older Android), try service call
                if (result != 0) {
                    runSu("service call connectivity 34 i32 0") // Try code 34
                    runSu("service call connectivity 33 i32 0") // Try code 33
                }
            }
        } else if (AutomatorService.isEnabled()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Automator turning on Hotspot...", Toast.LENGTH_SHORT).show()
            }
            // Use our MacroDroid-like Accessibility Service to open Tether Settings and tap Hotspot
            AutomatorService.enableDataAndHotspotUI()
        } else {
            // For non-rooted devices, we can only notify the user to turn it on manually
            // as Android strictly blocks background tethering toggles without root/system privileges.
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context, 
                    "Please ensure Mobile Hotspot is turned on manually, or enable the UI Automator.", 
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
