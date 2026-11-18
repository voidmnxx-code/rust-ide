package dev.rustpad

import android.content.Context
import android.graphics.Color
import android.widget.Button

object Colors {
    val BG = Color.parseColor("#121212")
    val PANEL = Color.parseColor("#1C1C1E")
    val TEXT = Color.parseColor("#E0E0E0")
    val DIM = Color.parseColor("#8A8F98")
    val ERROR = Color.parseColor("#FF6B68")
    val WARN = Color.parseColor("#E5C07B")
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
