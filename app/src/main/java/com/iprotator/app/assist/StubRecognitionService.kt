package com.iprotator.app.assist

import android.content.Intent
import android.speech.RecognitionService

/** Android requires an assistant to declare a recognition service. We don't need speech. */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
