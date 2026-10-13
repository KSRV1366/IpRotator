package com.iprotator.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * An AccessibilityService that acts like MacroDroid/Tasker to automate UI clicks.
 * Automatically dismisses lock screen keyguard, opens Tether Settings, and turns on Mobile Hotspot.
 */
class AutomatorService : AccessibilityService() {

    companion object {
        private const val TAG = "AutomatorService"

        @Volatile
        var instance: AutomatorService? = null

        fun isEnabled(): Boolean = instance != null

        /**
         * Dismisses keyguard, opens Tether Settings, and flips the Mobile Hotspot switch ON.
         */
        suspend fun enableDataAndHotspotUI() = withContext(Dispatchers.Main) {
            val svc = instance ?: return@withContext
            
            val pm = svc.getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "IpRotator::AutomatorWakeLock"
            )
            wakeLock.acquire(25 * 1000L) // Hold wake lock for 25 seconds max

            try {
                // 1. Native Keyguard Dismissal for Android 12+ (API 31+)
                if (Build.VERSION.SDK_INT >= 31) {
                    svc.performGlobalAction(15) // 15 = GLOBAL_ACTION_DISMISS_KEYGUARD
                } else {
                    svc.performGlobalAction(GLOBAL_ACTION_BACK)
                }
                delay(1000)

                // 2. Physical Swipe-Up gesture to guarantee keyguard wallpaper is dismissed
                val displayMetrics = svc.resources.displayMetrics
                val width = displayMetrics.widthPixels.toFloat()
                val height = displayMetrics.heightPixels.toFloat()

                val swipeUpPath = Path().apply {
                    moveTo(width / 2f, height * 0.9f)
                    lineTo(width / 2f, height * 0.1f)
                }
                val unlockGesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(swipeUpPath, 0, 200))
                    .build()
                svc.dispatchGesture(unlockGesture, null, null)
                delay(1200) // Wait for unlock animation

                // 3. Launch Tether Settings page directly
                val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                
                try {
                    svc.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not open tether settings directly", e)
                    svc.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }

                delay(1500) // Wait for Settings app to open

                // 4. Scan window for Hotspot switch/toggle (retries up to 8 times)
                var toggled = false
                for (attempt in 1..8) {
                    val rootNode = svc.rootInActiveWindow
                    if (rootNode != null) {
                        if (findAndToggleHotspotSwitch(svc, rootNode)) {
                            toggled = true
                            break
                        }
                    }
                    delay(800)
                }

                if (toggled) {
                    Log.d(TAG, "Hotspot successfully toggled ON via Settings!")
                    delay(2000)
                    // Return home
                    svc.performGlobalAction(GLOBAL_ACTION_HOME)
                } else {
                    Log.w(TAG, "Could not toggle hotspot switch automatically.")
                }

            } finally {
                wakeLock.release()
            }
        }

        private fun findAndToggleHotspotSwitch(svc: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""

            // Look for "Hotspot" or "Mobile Hotspot"
            if (text.contains("hotspot") || desc.contains("hotspot") || text.contains("tethering") || desc.contains("tethering")) {
                // Find a Switch inside this node or its parent
                val switchNode = findSwitchInHierarchy(node) ?: findAdjacentSwitch(node)
                
                if (switchNode != null) {
                    if (!switchNode.isChecked) {
                        performClickOrTap(svc, switchNode)
                        Log.d(TAG, "Toggled Hotspot Switch ON!")
                        return true
                    } else {
                        Log.d(TAG, "Hotspot is already ON!")
                        return true
                    }
                } else {
                    // If no switch was directly attached to this text view, tap the list item itself
                    performClickOrTap(svc, node)
                    Log.d(TAG, "Tapped Mobile Hotspot item to open details/toggle")
                    return true
                }
            }

            // Also check for any standalone Switch if we are on a dedicated Hotspot Settings screen
            val className = node.className?.toString() ?: ""
            if (className.contains("Switch", ignoreCase = true) || node.isCheckable) {
                if (!node.isChecked) {
                    performClickOrTap(svc, node)
                    Log.d(TAG, "Toggled standalone Switch ON!")
                    return true
                }
            }

            // Recursively search children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findAndToggleHotspotSwitch(svc, child)) return true
            }

            return false
        }

        private fun findSwitchInHierarchy(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val className = node.className?.toString() ?: ""
            if (className.contains("Switch", ignoreCase = true) || node.isCheckable) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findSwitchInHierarchy(child)
                if (found != null) return found
            }
            return null
        }

        private fun findAdjacentSwitch(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var parent = node.parent
            while (parent != null) {
                val found = findSwitchInHierarchy(parent)
                if (found != null) return found
                parent = parent.parent
            }
            return null
        }

        private fun performClickOrTap(svc: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
            // 1. Try Accessibility Click action
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                current = current.parent
            }

            // 2. Fallback: Physical Touch Gesture on node center coordinates
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY().toFloat()
                
                val path = Path().apply {
                    moveTo(x, y)
                    lineTo(x + 1, y + 1)
                }
                
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                    .build()
                
                svc.dispatchGesture(gesture, null, null)
                return true
            }

            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AutomatorService Connected!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: ""
        if (pkg.contains("settings", ignoreCase = true)) {
            val rootNode = rootInActiveWindow ?: return
            findAndToggleHotspotSwitch(this, rootNode)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AutomatorService Interrupted!")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.d(TAG, "AutomatorService Unbound!")
        return super.onUnbind(intent)
    }
}
