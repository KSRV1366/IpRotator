package com.iprotator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Keeps an OUTBOUND WebSocket to your server (works behind carrier CGNAT) and executes
 * commands it receives. Results produced while offline are queued and delivered as soon
 * as the connection comes back (i.e. right after airplane mode is turned off).
 */
class RotatorService : Service() {

    companion object {
        private const val TAG = "RotatorService"
        private const val CHANNEL_ID = "rotator"
        private const val NOTIF_ID = 1
        const val ACTION_RECONNECT = "com.iprotator.app.RECONNECT"

        @Volatile
        var status: String = "stopped"
            private set

        /** Starts the service, or makes a running one reconnect with the latest settings. */
        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RotatorService::class.java).setAction(ACTION_RECONNECT)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RotatorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs

    private val wsHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val ipHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val rotator by lazy { IpRotator(applicationContext, ipHttp) }

    private val lock = Any()
    private var current: WebSocket? = null   // connecting or open socket
    private var isOpen = false
    private val outbox = ConcurrentLinkedQueue<String>()
    private var loopJob: Job? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "IP Rotator", NotificationManager.IMPORTANCE_LOW)
        )
        scope.launch { AirplaneToggler.hasRoot() } // probe once (Magisk may prompt)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        if (intent?.action == ACTION_RECONNECT) dropConnection()
        if (loopJob == null) loopJob = scope.launch { connectLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        synchronized(lock) {
            current?.close(1000, "service stopped")
            current = null
            isOpen = false
        }
        status = "stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- connection

    private suspend fun connectLoop() {
        while (scope.isActive) {
            val needsConnect = synchronized(lock) { current == null }
            if (needsConnect) connect()
            delay(3_000)
        }
    }

    private fun connect() {
        val base = prefs.serverUrl.trimEnd('/')
        val id = prefs.deviceId
        if (base.isBlank() || id.isBlank()) {
            updateStatus("Not configured")
            return
        }

        val request = try {
            Request.Builder()
                .url("$base/ws/$id")   // OkHttp accepts http(s):// and ws(s):// here
                .header("Authorization", "Bearer ${prefs.token}")
                .build()
        } catch (e: IllegalArgumentException) {
            updateStatus("Invalid server URL")
            return
        }

        synchronized(lock) {
            if (current != null) return
            updateStatus("Connecting…")
            current = wsHttp.newWebSocket(request, listener)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (webSocket !== current) { webSocket.cancel(); return }
                isOpen = true
            }
            updateStatus("Connected")
            webSocket.send(helloJson().toString())
            // Deliver results that were produced while we were offline
            while (true) {
                val msg = outbox.peek() ?: break
                if (webSocket.send(msg)) outbox.poll() else break
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDisconnected(webSocket, "closed ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onDisconnected(webSocket, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun onDisconnected(webSocket: WebSocket, why: String) {
        val wasCurrent = synchronized(lock) {
            if (webSocket === current) { current = null; isOpen = false; true } else false
        }
        if (wasCurrent) updateStatus("Disconnected: $why (retrying)")
    }

    /** Throws away the current socket; the loop reconnects within a few seconds. */
    private fun dropConnection() {
        val old = synchronized(lock) {
            val ws = current
            current = null
            isOpen = false
            ws
        }
        old?.cancel()
    }

    private fun send(text: String) {
        val ws = synchronized(lock) { if (isOpen) current else null }
        if (ws == null || !ws.send(text)) outbox.add(text)
    }

    // ---------------------------------------------------------------- commands

    private fun handleMessage(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        val jobId = msg.optString("job_id")

        when (msg.optString("cmd")) {
            "rotate" -> scope.launch {
                val attempts = msg.optInt("max_attempts", 3)
                val offMs = msg.optLong("off_seconds", 5) * 1000
                runExclusive(jobId, networkWillChange = true) {
                    rotator.rotate(attempts, offMs).toJson()
                }
            }

            "status" -> scope.launch {
                send(statusJson().put("job_id", jobId).put("type", "result").toString())
            }

            "ping" -> send(JSONObject().put("type", "result").put("job_id", jobId).put("ok", true).toString())

            else -> send(
                JSONObject().put("type", "result").put("job_id", jobId)
                    .put("ok", false).put("error", "unknown command").toString()
            )
        }
    }

    private suspend fun runExclusive(
        jobId: String,
        networkWillChange: Boolean,
        block: suspend () -> JSONObject,
    ) {
        if (!Rotation.lock.tryLock()) {
            send(JSONObject().put("type", "result").put("job_id", jobId)
                .put("ok", false).put("error", "busy: rotation already in progress").toString())
            return
        }
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iprotator:rotate")
        wakeLock.acquire(5 * 60 * 1000L)
        try {
            updateStatus("Rotating IP…")
            val result = try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Job failed", e)
                JSONObject().put("ok", false).put("error", e.message ?: e.toString())
            }
            result.put("type", "result").put("job_id", jobId)
            prefs.lastResult = result.toString()

            if (networkWillChange) {
                // The old socket died with the network. Queue the result and reconnect now;
                // it is flushed in onOpen of the fresh connection.
                outbox.add(result.toString())
                dropConnection()
                connect()
            } else {
                send(result.toString())
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            Rotation.lock.unlock()
        }
    }

    private fun helloJson(): JSONObject = JSONObject()
        .put("type", "hello")
        .put("device_id", prefs.deviceId)
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("android", Build.VERSION.RELEASE)
        .put("assistant", AirplaneToggler.isAssistantActive(this))
        .put("root", AirplaneToggler.rootCached ?: JSONObject.NULL)

    private fun statusJson(): JSONObject = helloJson()
        .put("ok", true)
        .put("airplane_mode", AirplaneToggler.isAirplaneOn(this))
        .put("ip", rotator.publicIp() ?: JSONObject.NULL)
        .put("busy", Rotation.lock.isLocked)

    // ---------------------------------------------------------------- notification

    private fun updateStatus(text: String) {
        status = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("IP Rotator")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
