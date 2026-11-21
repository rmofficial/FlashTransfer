package com.rahulmandal.flashtransfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps the HTTP server alive while the screen is off,
 * during phone calls, or when the app is minimized.
 */
class ServerService : Service() {

    private var server: FileServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        val port = intent?.getIntExtra(EXTRA_PORT, 8181) ?: 8181
        val root = intent?.getStringExtra(EXTRA_ROOT)
        val pass = intent?.getStringExtra(EXTRA_PASS) ?: ""

        stopServerOnly()
        if (root.isNullOrEmpty()) {
            ServerState.error = "No shared folder selected"
            shutdown()
            return START_NOT_STICKY
        }
        try {
            val s = FileServer(this, port, Uri.parse(root), pass)
            s.start()
            server = s
            ServerState.port = port
            ServerState.error = null
            ServerState.bytesSent.set(0)
            ServerState.active.set(0)
            ServerState.running = true
            acquireLocks()
            val ip = NetUtils.list().firstOrNull()?.ip
            val text = if (ip != null) "http://$ip:$port" else "Port $port · waiting for network"
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            ServerState.error = "Could not start server on port $port (${e.message ?: "port busy"})"
            shutdown()
        }
        return START_NOT_STICKY
    }

    private fun stopServerOnly() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
    }

    private fun shutdown() {
        stopServerOnly()
        releaseLocks()
        ServerState.running = false
        ServerState.active.set(0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServerOnly()
        releaseLocks()
        ServerState.running = false
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlashTransfer:cpu").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "FlashTransfer:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Flash Transfer server", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shows while the file server is running"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle("Flash Transfer is running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.rahulmandal.flashtransfer.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_ROOT = "root"
        const val EXTRA_PASS = "pass"
        private const val CHANNEL_ID = "flash_server"
        private const val NOTIF_ID = 8181
    }
}
