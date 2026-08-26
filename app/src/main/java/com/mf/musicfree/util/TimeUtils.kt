package com.mf.musicfree.util

fun Long.formatDuration(): String {
    if (this <= 0) return "00:00"
    val totalSeconds = this / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}