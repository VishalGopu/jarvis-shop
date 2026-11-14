package com.jarvis.shop.ui

import kotlin.math.round

private fun r2(x: Double) = round(x * 100) / 100
private fun num(x: Double): String = r2(x).let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }

/** Same rules as the server's units.js: kg/litre shrink to g/ml under 1. */
fun fmtQty(q: Double, unit: String): String {
    val v = r2(q)
    return when (unit) {
        "kg" -> if (v < 1) "${round(v * 1000).toLong()} g" else "${num(v)} kg"
        "litre" -> if (v < 1) "${round(v * 1000).toLong()} ml" else "${num(v)} L"
        "packet" -> "${num(v)} ${if (v == 1.0) "packet" else "packets"}"
        else -> num(v)
    }
}

fun unitLabel(unit: String) = when (unit) { "plate", "piece", "cup", "glass" -> ""; else -> "/$unit" }
fun minutesSince(ms: Long): Int = if (ms <= 0) 0 else ((System.currentTimeMillis() - ms) / 60000).toInt().coerceAtLeast(0)

/** 2:30 PM style label for a pickup/start time. */
fun clockTime(ms: Long): String = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ms))
