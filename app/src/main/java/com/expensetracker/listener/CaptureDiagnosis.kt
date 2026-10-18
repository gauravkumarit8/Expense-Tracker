package com.autoexpensetracker.listener

enum class CaptureVerdict { ACCESS_OFF, NEVER_CONNECTED, NO_MESSAGES_YET, MESSAGES_NOT_RECOGNISED, WORKING }

/**
 * Turns the capture timestamps into a plain-language verdict, so "why isn't it
 * picking up my bank messages?" has an answer that points at the right layer:
 *
 *  ACCESS_OFF              — notification access isn't granted.
 *  NEVER_CONNECTED         — access is on but Android never connected the app
 *                            to the notification service (the classic
 *                            battery-saver / autostart kill on Xiaomi etc.).
 *  NO_MESSAGES_YET         — connected, but no bank/SMS notification has
 *                            reached the app.
 *  MESSAGES_NOT_RECOGNISED — messages reach the app but none ever became a
 *                            transaction (unsupported wording, or only OTPs).
 *  WORKING                 — at least one transaction has been captured.
 */
object CaptureDiagnosis {
    fun verdict(accessGranted: Boolean, connectedAt: Long, lastCandidateAt: Long, lastCapturedAt: Long): CaptureVerdict = when {
        !accessGranted -> CaptureVerdict.ACCESS_OFF
        connectedAt <= 0L -> CaptureVerdict.NEVER_CONNECTED
        lastCandidateAt <= 0L -> CaptureVerdict.NO_MESSAGES_YET
        lastCapturedAt <= 0L -> CaptureVerdict.MESSAGES_NOT_RECOGNISED
        else -> CaptureVerdict.WORKING
    }
}
