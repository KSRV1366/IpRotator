package com.iprotator.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    @Volatile private var localTest = ""

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Allow app to turn on screen and show on lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        window.statusBarColor = Color.parseColor("#F4F7F9")
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        prefs = Prefs(this)

        val serverUrl = findViewById<EditText>(R.id.serverUrl)
        val deviceId = findViewById<EditText>(R.id.deviceId)
        val token = findViewById<EditText>(R.id.token)
        statusView = findViewById(R.id.status)

        serverUrl.setText(prefs.serverUrl)
        deviceId.setText(prefs.deviceId.ifBlank { "phone1" })
        token.setText(prefs.token)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            prefs.serverUrl = serverUrl.text.toString().trim().trimEnd('/')
            prefs.deviceId = deviceId.text.toString().trim()
            prefs.token = token.text.toString().trim()
            if (prefs.serverUrl.isBlank() || prefs.deviceId.isBlank()) {
                toast("Server URL and Device ID are required")
                return@setOnClickListener
            }
            if (!AirplaneToggler.hasRoot() && !AutomatorService.isEnabled()) {
                toast("Please tap 'Enable UI Automator' first to allow Hotspot toggling!")
                openFirst(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            prefs.enabled = true
            RotatorService.start(this)
            toast("Service started")
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            prefs.enabled = false
            RotatorService.stop(this)
        }

        findViewById<Button>(R.id.btnHotspotShortcut).setOnClickListener {
            openFirst(
                Intent("android.settings.TETHER_SETTINGS"),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
        }

        findViewById<Button>(R.id.btnAssistant).setOnClickListener {
            toast("Choose 'IP Rotator' as the Digital assistant app")
            openFirst(
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
        }

        findViewById<Button>(R.id.btnRoot).setOnClickListener {
            scope.launch {
                val root = withContext(Dispatchers.IO) { AirplaneToggler.hasRoot(force = true) }
                toast(if (root) "Root available" else "No root (that's fine if the assistant is active)")
            }
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            openFirst(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            scope.launch {
                if (!Rotation.lock.tryLock()) {
                    toast("A rotation is already running")
                    return@launch
                }
                try {
                    localTest = "Rotating… watch the airplane icon"
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()
                    localTest = IpRotator(applicationContext, client).rotate().toJson().toString(2)
                } finally {
                    Rotation.lock.unlock()
                }
            }
        }

        findViewById<Button>(R.id.btnAutomator).setOnClickListener {
            toast("Enable 'IpRotator' in the Accessibility settings")
            openFirst(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        val assistant = AirplaneToggler.isAssistantActive(this)
        val root = when (AirplaneToggler.rootCached) {
            true -> "yes"
            false -> "no"
            null -> "not checked"
        }
        val automatorActive = AutomatorService.isEnabled()
        val hotspotOn = HotspotManager.isHotspotOn(this)
        
        statusView.text = buildString {
            appendLine("Service:            ${RotatorService.status}")
            appendLine("Digital assistant:  ${if (assistant) "ACTIVE ✓" else "NOT SET ✗ (tap button 1)"}")
            appendLine("UI Automator:       ${if (automatorActive) "ACTIVE ✓" else "NOT SET ✗ (tap new button)"}")
            appendLine("Root:               $root")
            appendLine("Airplane mode:      ${if (AirplaneToggler.isAirplaneOn(this@MainActivity)) "ON" else "OFF"}")
            appendLine("Hotspot state:      ${if (hotspotOn) "ON ✓" else "OFF ✗"}")
            if (localTest.isNotBlank()) {
                appendLine()
                appendLine("Local test result:")
                appendLine(localTest)
            }
            if (prefs.lastResult.isNotBlank()) {
                appendLine()
                appendLine("Last API job:")
                appendLine(prefs.lastResult)
            }
        }
    }

    private fun openFirst(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) { }
        }
        toast("Could not open settings on this device")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
