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

    /** نسخ احتياطي تلقائي عند مغادرة المحرر إن تغيّر المشروع (مرة كل 5 دقائق كحد أقصى). */
    var autoBackup: Boolean
        get() = sp.getBoolean("autobackup", true)
        set(v) = sp.edit().putBoolean("autobackup", v).apply()

    /** سمة سوداء تامة لشاشات AMOLED. */
    var amoled: Boolean
        get() = sp.getBoolean("amoled", false)
        set(v) = sp.edit().putBoolean("amoled", v).apply()

    /** حجم خط المحرر (sp)، يتغير بالقرص بإصبعين. */
    var fontSp: Float
        get() = sp.getFloat("fontsp", 14f)
        set(v) = sp.edit().putFloat("fontsp", v.coerceIn(9f, 30f)).apply()

    var useLld: Boolean
        get() = sp.getBoolean("lld", false)
        set(v) = sp.edit().putBoolean("lld", v).apply()
}

object SettingsDialog {
    fun show(a: Activity, onChanged: () -> Unit) {
        val s = Settings(a)
        val items = arrayOf("Editor", "Build & performance", "Appearance", "Backup", "Keep-alive guide")
        AlertDialog.Builder(a)
            .setTitle("Settings  (%.1f GB RAM)".format(s.ramGb))
            .setItems(items) { _, i ->
                when (i) {
                    0 -> checks(a, "Editor", arrayOf("rust-analyzer (completion + diagnostics)", "Auto cargo check while typing"),
                        booleanArrayOf(s.raEnabled, s.autoCheck), onChanged) { s.raEnabled = it[0]; s.autoCheck = it[1] }
                    1 -> checks(a, "Build & performance",
                        arrayOf("Use lld linker (experimental)", "Offline crates (vendored, no internet)", "Use sccache (run rustpad-warm first)"),
                        booleanArrayOf(s.useLld, s.offlineCrates, s.sccache), onChanged) { s.useLld = it[0]; s.offlineCrates = it[1]; s.sccache = it[2] }
                    2 -> appearance(a, s, onChanged)
                    3 -> checks(a, "Backup", arrayOf("Auto backup projects (zip, last 5)"),
                        booleanArrayOf(s.autoBackup), onChanged) { s.autoBackup = it[0] }
                    4 -> VendorGuide.show(a)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun checks(a: Activity, title: String, labels: Array<String>, init: BooleanArray, onChanged: () -> Unit,
                       save: (BooleanArray) -> Unit) {
        val checked = init.copyOf()
        AlertDialog.Builder(a).setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Save") { _, _ -> save(checked); onChanged() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun appearance(a: Activity, s: Settings, onChanged: () -> Unit) {
        val checked = booleanArrayOf(s.amoled)
        var size = s.fontSp
        val lay = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(a.dp(20), a.dp(8), a.dp(20), 0) }
        val label = android.widget.TextView(a).apply { text = "Editor font size: ${size.toInt()} sp" }
        val seek = android.widget.SeekBar(a).apply {
            max = 21; progress = (size - 9).toInt()
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) { size = 9f + p; label.text = "Editor font size: ${size.toInt()} sp" }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        val cb = android.widget.CheckBox(a).apply { text = "AMOLED black theme"; isChecked = checked[0]; setOnCheckedChangeListener { _, c -> checked[0] = c } }
        lay.addView(cb); lay.addView(label); lay.addView(seek)
        lay.addView(android.widget.TextView(a).apply { text = "Tip: pinch with two fingers in the editor to zoom."; textSize = 12f; setPadding(0, a.dp(10), 0, 0) })
        AlertDialog.Builder(a).setTitle("Appearance").setView(lay)
            .setPositiveButton("Save") { _, _ -> s.amoled = checked[0]; s.fontSp = size; onChanged() }
            .setNegativeButton("Cancel", null).show()
    }
}
