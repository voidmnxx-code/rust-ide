package dev.rustpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger

/** خدمة مقدّمة + WakeLock تُبقي العملية حيّة أثناء العمليات الثقيلة (تحميل/بناء/git). */
class KeepAliveService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("work", "RustPad", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "work")
        } else Notification.Builder(this)
        return b.setContentTitle("RustPad").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
    }

    override fun onCreate() {
        super.onCreate()
        // يجب إعلان الخدمة فوراً: لو دُمّرت قبل ذلك ينهار التطبيق (ForegroundServiceDidNotStartInTime).
        startForeground(1, notification("Working..."))
        lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rustpad:work").apply { acquire(2 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification(intent?.getStringExtra("text") ?: "Working..."))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private val users = AtomicInteger(0)
        private val main = Handler(Looper.getMainLooper())

        fun start(c: Context, text: String) {
            users.incrementAndGet()
            val app = c.applicationContext
            val i = Intent(app, KeepAliveService::class.java).putExtra("text", text)
            try {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            } catch (_: Throwable) {
                users.decrementAndGet() // لا نريد أن تنهار الميزة الأساسية بسبب الإشعار
            }
        }

        /** إيقاف مؤجَّل بعدّاد: عمليات قصيرة متتالية لا تُسقط الخدمة قبل أن تستقر. */
        fun stop(c: Context) {
            val app = c.applicationContext
            main.postDelayed({
                if (users.decrementAndGet() <= 0) {
                    users.set(0)
                    app.stopService(Intent(app, KeepAliveService::class.java))
                }
            }, 2000)
        }
    }
}
