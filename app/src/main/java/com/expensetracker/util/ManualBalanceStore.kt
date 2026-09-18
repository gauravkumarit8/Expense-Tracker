package com.autoexpensetracker.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Manual overrides/additions/deletions for the "Account balances" section
 * on ChartsScreen, which otherwise only ever shows balances auto-derived
 * from the most recent parsed transaction per bank source (see
 * `Transaction.balanceAfter`). Three things live here that pure
 * auto-detection can't cover on its own:
 *
 * - An **override** for a source that DOES have auto-detected
 *   transactions, when the user corrects the figure by hand (e.g. the
 *   last captured message was stale, or parsing under/over-shot). Wins
 *   over the auto-detected value whenever present, regardless of which
 *   one is more recent — an explicit correction should stick until the
 *   user changes it again, not get silently overwritten by the next
 *   captured message. (If that's ever undesirable for a given source,
 *   removing the override lets auto-detection take back over.)
 * - A purely **manual entry** for a source with NO transaction history at
 *   all (a cash account, or a bank whose notifications aren't captured),
 *   added entirely through the "+ Add account" UI. Stored identically to
 *   an override — the only thing that distinguishes the two cases to a
 *   caller is whether the source also happens to appear among
 *   transaction-derived sources.
 * - A **hidden** set — "deleting" a balance card doesn't erase transaction
 *   history (there's nothing to delete for an auto-detected source), it
 *   just suppresses that source from the list, including any future
 *   auto-detected transactions for it. Re-adding the same source via
 *   "+ Add account" (or editing it back in) un-hides it.
 *
 * Deliberately plain SharedPreferences + kotlinx.serialization JSON,
 * matching the existing lightweight-store pattern in this package
 * (`BalanceVisibilityStore`, `DismissedSuggestionsStore`) rather than a
 * Room table — this is a handful of user-entered rows, not app-generated
 * data needing queries or migrations.
 */
object ManualBalanceStore {
    private const val PREFS_NAME = "manual_balances"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_HIDDEN = "hidden_sources"

    private val json = Json { ignoreUnknownKeys = true }
    private val entryListSerializer = ListSerializer(Entry.serializer())

    @Serializable
    data class Entry(val source: String, val amount: Double, val asOfMillis: Long)

    /** All manual entries/overrides currently stored, regardless of hidden state. */
    fun getAll(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            json.decodeFromString(entryListSerializer, raw)
        } catch (e: Exception) {
            // Corrupt/unreadable prefs value (shouldn't happen, but this is
            // user-facing display data, not something worth crashing over).
            emptyList()
        }
    }

    /** Adds a new manual balance, or overwrites the existing one for [source]. Un-hides [source] if it was previously deleted. */
    fun upsert(context: Context, source: String, amount: Double, asOfMillis: Long = System.currentTimeMillis()) {
        val updated = getAll(context).filterNot { it.source == source } + Entry(source, amount, asOfMillis)
        prefs(context).edit().putString(KEY_ENTRIES, json.encodeToString(entryListSerializer, updated)).apply()
        unhide(context, source)
    }

    /** Clears any manual override for [source] (auto-detection, if any, takes back over — unless also hidden). */
    fun removeOverride(context: Context, source: String) {
        val updated = getAll(context).filterNot { it.source == source }
        prefs(context).edit().putString(KEY_ENTRIES, json.encodeToString(entryListSerializer, updated)).apply()
    }

    fun getHidden(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    /** "Deletes" a balance card: clears any override and hides the source going forward. */
    fun delete(context: Context, source: String) {
        removeOverride(context, source)
        val current = getHidden(context).toMutableSet()
        current.add(source)
        prefs(context).edit().putStringSet(KEY_HIDDEN, current).apply()
    }

    private fun unhide(context: Context, source: String) {
        val current = getHidden(context).toMutableSet()
        if (current.remove(source)) {
            prefs(context).edit().putStringSet(KEY_HIDDEN, current).apply()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}