package dev.rustpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** خدمة مقدّمة + WakeLock تُبقي العملية حيّة أثناء العمليات الثقيلة (تحميل/فك Bootstrap). */
class KeepAliveService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("work", "RustPad", NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, "work")
        } else Notification.Builder(this)
        val n = b.setContentTitle("RustPad")
            .setContentText(intent?.getStringExtra("text") ?: "Working...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        startForeground(1, n)
        if (lock == null) {
            lock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rustpad:work").apply { acquire(2 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        fun start(c: Context, text: String) {
            val i = Intent(c, KeepAliveService::class.java).putExtra("text", text)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.stopService(Intent(c, KeepAliveService::class.java)) }
    }
}
