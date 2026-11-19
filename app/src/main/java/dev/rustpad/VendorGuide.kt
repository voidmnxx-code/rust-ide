package dev.rustpad

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/** إرشاد خاص بكل شركة لمنع قتل التطبيق أثناء البناء. */
object VendorGuide {
    fun show(a: Activity) {
        val m = Build.MANUFACTURER.lowercase()
        val xiaomi = listOf("xiaomi", "redmi", "poco").any { it in m }
        val samsung = "samsung" in m
        val text = when {
            xiaomi ->
                "Xiaomi / HyperOS:\n" +
                "1) Open settings below → turn Autostart ON for RustPad.\n" +
                "2) Settings → Apps → RustPad → Battery saver → No restrictions.\n" +
                "3) In Recents, long-press the RustPad card and tap the lock icon."
            samsung ->
                "Samsung / One UI:\n" +
                "1) Settings → Battery → Background usage limits.\n" +
                "2) Remove RustPad from \"Sleeping apps\" / \"Deep sleeping apps\".\n" +
                "3) Add RustPad to \"Never sleeping apps\"."
            else ->
                "Allow RustPad to run unrestricted in the background:\n" +
                "Settings → Apps → RustPad → Battery → Unrestricted."
        }
        AlertDialog.Builder(a).setTitle("Keep builds alive").setMessage(text)
            .setPositiveButton("Open settings") { _, _ -> openVendor(a, xiaomi) }
            .setNeutralButton("Battery exemption") { _, _ -> PermissionManager(a).requestBatteryExemption() }
            .setNegativeButton("Close", null).show()
    }

    private fun openVendor(a: Activity, xiaomi: Boolean) {
        val intents = buildList {
            if (xiaomi) add(Intent().setComponent(ComponentName(
                "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
        }
        for (i in intents) {
            try { a.startActivity(i); return } catch (_: Exception) {}
        }
    }
}
