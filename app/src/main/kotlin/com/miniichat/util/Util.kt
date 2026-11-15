package com.miniichat.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

fun formatTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    val oneDay = 24 * 60 * 60 * 1000L
    val sameDay = SimpleDateFormat("HH:mm", Locale.getDefault())
    val withDate = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
    return if (diff < oneDay) sameDay.format(Date(ts)) else withDate.format(Date(ts))
}

/** 75_000 → "1:15", 3_725_000 → "1:02:05" */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
