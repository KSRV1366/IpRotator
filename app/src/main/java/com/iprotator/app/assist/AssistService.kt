package com.iprotator.app.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService

/**
 * Active only when the user picks this app as the phone's "Digital assistant app".
 * Being the active assistant is what lets us ask the system Settings app to toggle
 * airplane mode (the same path "Hey Google, turn on airplane mode" uses), without root.
 */
class AssistService : VoiceInteractionService() {

    companion object {
        @Volatile
        var instance: AssistService? = null
            private set
    }

    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        instance = null
        super.onShutdown()
    }

    /** Must be called on the main thread. */
    fun requestAirplane(enable: Boolean) {
        showSession(Bundle().apply { putBoolean(AirplaneSession.EXTRA_ENABLE, enable) }, 0)
    }
}
