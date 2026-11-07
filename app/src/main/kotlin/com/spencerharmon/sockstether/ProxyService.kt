package com.spencerharmon.sockstether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service hosting the SOCKS5 server.
 *
 * Start (e.g. from the USB-attached host):
 *   adb shell am start-foreground-service -n com.spencerharmon.sockstether/.ProxyService [--ei port 1080]
 * Stop:
 *   adb shell am start-foreground-service -n com.spencerharmon.sockstether/.ProxyService -a com.spencerharmon.sockstether.STOP
 *
 * A partial wake lock is held only while the server runs, so the CPU keeps
 * forwarding while the screen is off. Nothing is held (and nothing runs) otherwise.
 */
class ProxyService : Service() {
    private var server: Socks5Server? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        // Must call startForeground promptly after startForegroundService().
        startForeground(NOTIFICATION_ID, notification("Starting..."))
        if (server == null) {
            val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
            val s = Socks5Server(port)
            try {
                s.start()
            } catch (e: Exception) {
                running = false
                lastError = e.message ?: e.javaClass.simpleName
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            server = s
            lastError = null
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "socks-tether:proxy").apply {
                setReferenceCounted(false)
                acquire()
            }
            running = true
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("SOCKS5 proxy on port $port (USB tether only)"))
        }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        running = false
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Proxy status", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Socks Tether")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.spencerharmon.sockstether.STOP"
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 1080
        private const val CHANNEL = "proxy"
        private const val NOTIFICATION_ID = 1

        @Volatile var running = false
        @Volatile var lastError: String? = null
    }
}
