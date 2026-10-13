package com.iprotator.app.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.util.Log

class AirplaneSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        const val EXTRA_ENABLE = "enable"
        private const val TAG = "AirplaneSession"
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        // Opened by the user (e.g. long-press home) rather than by us: do nothing.
        if (args == null || !args.containsKey(EXTRA_ENABLE)) {
            hide()
            return
        }

        val enable = args.getBoolean(EXTRA_ENABLE)
        try {
            startVoiceActivity(
                Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE)
                    .putExtra(Settings.EXTRA_AIRPLANE_MODE_ENABLED, enable)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Voice airplane-mode action failed", e)
        }
        handler.postDelayed({ hide() }, 2000)
    }
}
