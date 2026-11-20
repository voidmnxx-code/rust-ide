package dev.rustpad

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

object Colors {
    val BG = Color.parseColor("#121212")
    val PANEL = Color.parseColor("#1C1C1E")
    val TEXT = Color.parseColor("#E0E0E0")
    val DIM = Color.parseColor("#8A8F98")
    val ERROR = Color.parseColor("#FF6B68")
    val WARN = Color.parseColor("#E5C07B")
    val ACCENT = Color.parseColor("#F26B21")
    val CARD = Color.parseColor("#1C1C1E")
    val CARD_PRESSED = Color.parseColor("#2A2A2E")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.flatButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    textSize = 12f
    minWidth = 0
    minimumWidth = 0
    setPadding(dp(10), 0, dp(10), 0)
    setOnClickListener { onClick() }
}

/** مستطيل مستدير بلون ثابت. */
fun Context.roundBg(color: Int, radiusDp: Int = 12, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

/** زر أيقونة بحجم لمس مريح (48dp): الأيقونة حرف رمزي. */
fun Context.iconButton(glyph: String, desc: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = glyph; textSize = 20f; setTextColor(Colors.TEXT); gravity = Gravity.CENTER
    minWidth = dp(48); minHeight = dp(48)
    contentDescription = desc
    isClickable = true
    setOnClickListener { onClick() }
    setBackgroundResource(android.R.drawable.list_selector_background)
}

/** رقاقة (chip) صغيرة قابلة للّمس لإجراءات سياقية. */
fun Context.chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label; textSize = 13f; setTextColor(Colors.TEXT); gravity = Gravity.CENTER
    setPadding(dp(14), dp(8), dp(14), dp(8)); minHeight = dp(36)
    background = roundBg(Colors.CARD, 18, Colors.DIM, 1)
    setOnClickListener { onClick() }
}

/** زر صف الرموز: أكبر من flatButton لسهولة الضغط. */
fun Context.keyButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label; textSize = 15f; setTextColor(Colors.TEXT); gravity = Gravity.CENTER
    typeface = android.graphics.Typeface.MONOSPACE
    minWidth = dp(40); minHeight = dp(44); setPadding(dp(10), 0, dp(10), 0)
    isClickable = true
    setOnClickListener { onClick() }
    setBackgroundResource(android.R.drawable.list_selector_background)
}
