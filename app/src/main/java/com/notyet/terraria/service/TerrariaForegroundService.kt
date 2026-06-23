package com.notyet.terraria.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.notyet.terraria.core.proot.ProotRunner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TerrariaForegroundService : Service() {

    @Inject
    lateinit var prootRunner: ProotRunner

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    // Tracks last notification update time for throttling
    private var lastNotificationMs = 0L

    // ── Companion: shared state read by MainViewModel ─────────────────────────

    companion object {
        const val CHANNEL_ID      = "TerrariaServerChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START    = "ACTION_START"
        const val ACTION_STOP     = "ACTION_STOP"

        private const val NOTIFICATION_THROTTLE_MS = 10_000L
        private const val MAX_LOG_LINES            = 500

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs.asStateFlow()

        /**
         * Thread-safe log append with a rolling 500-line cap.
         * Uses MutableStateFlow.update (CAS) so concurrent calls are safe.
         */
        internal fun appendLog(line: String) {
            _logs.update { current ->
                val trimmed = if (current.size >= MAX_LOG_LINES) current.drop(1) else current
                trimmed + line
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!_isRunning.value) startServer()
            ACTION_STOP  -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        startForeground(NOTIFICATION_ID, buildNotification("Starting TShock…"))
        acquireWakeLock()

        _isRunning.value = true
        _logs.value = emptyList()   // clear previous session logs

        serviceScope.launch {
            prootRunner.startServer().collect { line ->
                appendLog(line)
                throttledNotification(line)
            }
            // Reach here when proot exits naturally (server crash / world save / etc.)
            _isRunning.value = false
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_DETACH)  // keep notification visible (shows it ended)
            stopSelf()
        }
    }

    private fun stopServer() {
        prootRunner.stopServer()
        _isRunning.value = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)  // remove notification immediately on user stop
        stopSelf()
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    @Suppress("WakelockTimeout")    // intentional — released in stopServer / onDestroy
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NotyetTerraria::ServerWakeLock"
        ).also { it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ── Notification ──────────────────────────────────────────────────────────

    /**
     * Updates the notification at most once every 10 seconds.
     * Called on every log line — without throttling, NotificationManager
     * would rate-limit / drop updates, wasting IPC on every TShock line.
     */
    private fun throttledNotification(lastLine: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationMs >= NOTIFICATION_THROTTLE_MS) {
            lastNotificationMs = now
            updateNotification("Running — ${lastLine.take(40)}")
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(contentText: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Terraria Server")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)   // TODO: replace with app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Terraria Server Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the running state of the Terraria dedicated server"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        prootRunner.stopServer()
        _isRunning.value = false
        releaseWakeLock()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
