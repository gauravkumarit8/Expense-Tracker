package com.autoexpensetracker

/**
 * Collects every failed check in a test and reports them all at once, so one
 * run lists everything that broke instead of stopping at the first. Each
 * test method builds one, calls [check] for each expectation, then calls
 * [assertAll].
 */
class Checks {
    private val failures = ArrayList<String>()
    var total = 0
        private set

    fun check(name: String, ok: Boolean) {
        total++
        if (!ok) failures.add(name)
    }

    fun assertAll() {
        if (failures.isNotEmpty()) {
            throw AssertionError("${failures.size} of $total checks failed: " + failures.joinToString("; "))
        }
    }
}
