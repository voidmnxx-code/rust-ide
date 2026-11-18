package dev.rustpad

import java.io.PrintWriter
import java.io.StringWriter

/** يحفظ أي انهيار غير معالَج في ملف، ويعرضه عند التشغيل التالي. */
object CrashLogger {
    fun install(env: Env) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                env.crashFile.parentFile?.mkdirs()
                env.crashFile.writeText("thread=${t.name}\n$sw")
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** يعيد نص الانهيار السابق (إن وُجد) ثم يحذف الملف. */
    fun consume(env: Env): String? {
        if (!env.crashFile.exists()) return null
        val s = env.crashFile.readText()
        env.crashFile.delete()
        return s
    }
}
