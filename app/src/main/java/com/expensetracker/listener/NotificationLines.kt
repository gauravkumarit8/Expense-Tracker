package com.autoexpensetracker.listener

/** One message line pulled from a notification: its text and when it arrived (0 if the app didn't say). */
data class NotifLine(val text: String, val timeMillis: Long)

/**
 * Chooses which message lines of a conversation-style notification are worth
 * ingesting. Kept free of Android types so the rules can be tested off-device.
 *
 * SMS apps such as Google Messages post ONE notification per conversation and
 * keep updating it, carrying the recent messages in its MessagingStyle list.
 * Reading only the plain "text" extra therefore loses messages when several
 * arrive close together, and can capture a useless summary like "3 new
 * messages". Reading the whole list fixes that, but needs two safeguards:
 *  - each line keeps its OWN timestamp, so a message re-read from an
 *    updated notification is recognised as the duplicate it is, instead of
 *    being stamped "now" and inserted a second time;
 *  - lines at or before the newest one already seen for that notification are
 *    skipped, so an update doesn't re-process the whole history.
 */
object NotificationLines {
    private val SUMMARY = Regex(
        "^\\d+\\s+(?:new\\s+)?messages?(?:\\s+from\\s+\\d+\\s+(?:chats?|conversations?))?\\.?$",
        RegexOption.IGNORE_CASE
    )

    /** "3 new messages", "2 messages from 2 chats" — a header, not message content. */
    fun looksLikeSummary(text: String): Boolean = SUMMARY.matches(text.trim())

    /** Lines to ingest, oldest first. A missing time (<= 0) falls back to [fallbackMillis]. */
    fun selectNew(lines: List<NotifLine>, lastSeenMillis: Long?, fallbackMillis: Long): List<NotifLine> {
        val chosen = ArrayList<NotifLine>()
        for (line in lines) {
            val text = line.text.trim()
            if (text.isEmpty() || looksLikeSummary(text)) continue
            if (lastSeenMillis != null && line.timeMillis > 0L && line.timeMillis <= lastSeenMillis) continue
            chosen.add(NotifLine(text, if (line.timeMillis > 0L) line.timeMillis else fallbackMillis))
        }
        return chosen.sortedBy { it.timeMillis }
    }

    /** The newest real timestamp among [lines], or null if none carry one. */
    fun newestTime(lines: List<NotifLine>): Long? {
        var newest = 0L
        for (line in lines) if (line.timeMillis > newest) newest = line.timeMillis
        return if (newest > 0L) newest else null
    }
}
