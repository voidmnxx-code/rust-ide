package dev.rustpad

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/** يحدد عدد خيوط البناء ويمنع العمل الخلفي عند الحرارة العالية أو البطارية الضعيفة. */
object BuildTuner {
    private fun thermal(ctx: Context): Int =
        if (Build.VERSION.SDK_INT >= 29)
            ctx.getSystemService(PowerManager::class.java).currentThermalStatus else 0

    private fun battery(ctx: Context): Pair<Int, Boolean> {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
    }

    fun jobs(ctx: Context): Int {
        var j = maxOf(2, Runtime.getRuntime().availableProcessors() / 2)
        val t = thermal(ctx)
        if (t >= PowerManager.THERMAL_STATUS_SEVERE) return 1
        if (t >= PowerManager.THERMAL_STATUS_MODERATE) j = maxOf(1, j / 2)
        val (level, charging) = battery(ctx)
        if (!charging && level in 0..15) return 1
        if (!charging && level in 0..29) j = maxOf(1, j / 2)
        return j
    }

    /** للمهام التلقائية (cargo check التلقائي، rust-analyzer). */
    fun canBackgroundWork(ctx: Context): Boolean {
        if (thermal(ctx) >= PowerManager.THERMAL_STATUS_MODERATE) return false
        val (level, charging) = battery(ctx)
        return charging || level < 0 || level >= 25
    }
}
