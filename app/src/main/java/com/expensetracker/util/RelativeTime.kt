package com.autoexpensetracker.util

/** "3 min ago" style text for the capture-status screen. Pure, so it can be tested. */
object RelativeTime {
    fun ago(thenMillis: Long, nowMillis: Long): String {
        if (thenMillis <= 0L) return "never"
        val diffMillis = nowMillis - thenMillis
        if (diffMillis < 0L) return "just now" // clock moved backwards; don't print a negative age
        val minutes = diffMillis / 60000L
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "$minutes min ago"
            minutes < 60L * 24L -> "${minutes / 60L} h ago"
            else -> {
                val days = minutes / (60L * 24L)
                if (days == 1L) "yesterday" else "$days days ago"
            }
        }
    }
}
