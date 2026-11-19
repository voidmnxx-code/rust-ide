package dev.rustpad

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import androidx.appcompat.app.AlertDialog

/** إعدادات المستخدم. القيم الافتراضية تُضبط حسب ذاكرة الجهاز (اكتشاف تلقائي عند أول تشغيل). */
class Settings(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("rustpad", Context.MODE_PRIVATE)

    val ramGb: Double by lazy {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        mi.totalMem / (1024.0 * 1024 * 1024)
    }

    /** جهاز قوي (8GB مثل Pad 7): totalMem ≈ 7.5GiB. جهاز 6GB (M31) ≈ 5.5GiB. */
    val isHighEnd: Boolean get() = ramGb >= 6.5

    var raEnabled: Boolean
        get() = sp.getBoolean("ra", isHighEnd)
        set(v) = sp.edit().putBoolean("ra", v).apply()

    var autoCheck: Boolean
        get() = sp.getBoolean("autocheck", isHighEnd)
        set(v) = sp.edit().putBoolean("autocheck", v).apply()

    /** استخدام مجلد المكتبات المحمّلة مسبقاً بدل crates.io (يتطلب مرحلة offline-crates). */
    var offlineCrates: Boolean
        get() = sp.getBoolean("offline", true)
        set(v) = sp.edit().putBoolean("offline", v).apply()

    /** يخزّن مخرجات ترجمة المكتبات بين المشاريع. معطّل افتراضياً حتى يُختبر بـ rustpad-warm. */
    var sccache: Boolean
        get() = sp.getBoolean("sccache", false)
        set(v) = sp.edit().putBoolean("sccache", v).apply()

    var useLld: Boolean
        get() = sp.getBoolean("lld", false)
        set(v) = sp.edit().putBoolean("lld", v).apply()
}

object SettingsDialog {
    fun show(a: Activity, onChanged: () -> Unit) {
        val s = Settings(a)
        val labels = arrayOf(
            "rust-analyzer (completion + diagnostics)",
            "Auto cargo check while typing",
            "Use lld linker (experimental)",
            "Offline crates (vendored, no internet)",
            "Use sccache (run rustpad-warm first)",
        )
        val checked = booleanArrayOf(s.raEnabled, s.autoCheck, s.useLld, s.offlineCrates, s.sccache)
        AlertDialog.Builder(a)
            .setTitle("Settings  (%.1f GB RAM)".format(s.ramGb))
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Save") { _, _ ->
                s.raEnabled = checked[0]; s.autoCheck = checked[1]; s.useLld = checked[2]
                s.offlineCrates = checked[3]; s.sccache = checked[4]
                onChanged()
            }
            .setNeutralButton("Keep-alive guide") { _, _ -> VendorGuide.show(a) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
