# Expense Tracker — Requirements & Implementation Log

Living document. Every architectural decision, tradeoff, and open question
goes here as it's made — update this file in the same commit as the code
change it describes.

Last updated: 2026-09-04

---

## 1. Product Summary

An Android app that automatically detects transaction-related SMS/notifications
(bank debit/credit alerts, UPI payment confirmations) and extracts:
- Amount
- Direction (sent / received)
- Timestamp
- Counterparty (merchant/contact, best-effort)

All processing happens **on-device**. No backend server. No raw message
content is transmitted or persisted long-term.

**Platform: Android only.** iOS has no API for reading SMS or notification
content from third-party apps — this is a hard platform constraint, not a
current limitation. An iOS version would require manual entry or bank-API
(Plaid-style) integration instead, and is out of scope for now.

---

## 2. Architecture

### 2.1 Capture layer (dual path)
| Path | Mechanism | Permission | Role |
|---|---|---|---|
| Primary | `NotificationListenerService` | Special "Notification access" | Reads notification text from banking/UPI/SMS apps |
| Fallback (removed 2026-08-18) | ~~`BroadcastReceiver` on `SMS_RECEIVED`~~ | ~~`RECEIVE_SMS`, `READ_SMS`~~ | Permanently removed per Google Play policy — see ยง2.12/ยง3.5. `NotificationListenerService` is the sole capture path. |

Both paths hand off to `ParseAndStoreWorker` (WorkManager) rather than doing
work inline in the callback — keeps the OS-facing callbacks fast and lets
WorkManager handle retry/battery-Doze constraints.

Deduplication: both paths may fire for the same message. `Transaction.rawTextHash`
(SHA-256 of the original text) is checked before insert to avoid double-counting.

### 2.2 Parsing layer
- `TransactionParser` filters first (exclude OTP/promo, require transaction
  keywords), then applies a per-sender regex template loaded from
  `assets/bank_patterns.json`.
- Regex evaluation is wrapped with a 200ms hard timeout on a dedicated
  executor to prevent ReDoS (catastrophic backtracking) from a malformed or
  malicious pattern/input hanging the parser.
- Patterns are bundled as a JSON asset now; **planned** to move to a
  remotely-updatable config (Firebase Remote Config) so new bank formats can
  be added without a full app release — see Open Items.

### 2.3 Storage layer
- Room over SQLCipher (`net.zetetic:android-database-sqlcipher`), encryption
  key generated once via `SecureRandom` and stored in
  `EncryptedSharedPreferences`, whose master key lives in Android Keystore
  (hardware-backed where available).
- Only structured fields are stored (see `data/Transaction.kt`). Raw message
  text is never persisted — it exists only as a local variable during parsing
  in `ParseAndStoreWorker.doWork()` and is discarded after.
- `rawTextHash` is a one-way hash used only for dedup, not reversible to
  original content.

### 2.4 UI layer
- Jetpack Compose + Material3.
- Charts: Vico (Compose-native, lightweight) — planned, not yet wired up.

### 2.5 Background execution
- WorkManager for all deferred work — respects Doze/App Standby automatically.
- No persistent foreground service. `NotificationListenerService` is already
  a bound system service and doesn't need an additional foreground notification.

## 2.6 UI layer (updated 2026-08-16)
- Search bar (merchant/bank), direction filter chips (All/Sent/Received/Needs
  review), date-range filter chips (Today/This week/This month/All time).
- Summary cards react to the active filter set, not the full history.
- Grouped-by-day list with color-coded direction icons (see ยง Decision Log).

## 2.7 Feature Roadmap vs. Competitors

Researched against real India-market apps (Walnut/axio, Money View, ET
Money) — sourced 2026-08-16, see citations in that session.

| Feature | Status |
|---|---|
| SMS/notification auto-capture | ✅ Have it |
| Multi-bank support | ✅ Infrastructure supports it |
| Search & filter transactions | ✅ Have it (2026-08-16) |
| Sent/received summary | ✅ Have it |
| Custom categories + auto-categorization | ✅ Have it |
| Manual cash-expense entry | ✅ Have it (2026-08-18) — including edit/delete |
| Charts/spending trends | ✅ Have it (2026-08-18) — category breakdown + account balances, this-month only, custom Canvas chart |
| Budget limits per category | ✅ Have it (2026-08-18) |
| Bill/subscription reminders | ✅ Have it — now includes auto-detected recurring-merchant suggestions (2026-08-18) |
| Unusual-spend alerts | ✅ Have it (2026-08-18) — mean-based threshold per category |
| Balance tracking | ✅ Have it (2026-08-18) — parsed from message text where present, shown per account |
| Notes/tags on transactions | ✅ Have it (2026-08-16) |
| Backup & Restore (export/import) | ✅ Have it (2026-08-18) — file is unencrypted, see ยง2.10 |
| Export to CSV/PDF | ✅ CSV done (2026-08-18) — PDF still not built |
| App lock (biometric/PIN) | ✅ Have it (2026-08-20), see ยง2.16 |
| Receipt photo attachment | ❌ Not yet — lower priority |
| Bank-linked Pay Later/loans | Out of scope — lending product, not expense tracking |
| Bill splitting with friends | Out of scope for now — needs multi-user infra |

**Suggested build order** (highest user value first): categories →
manual cash entry → charts → app lock → budgets/reminders → export.

## 2.8 Categories, Notes/Tags, and Reminders (added 2026-08-16)

**Categories**: `Category` enum (`data/Category.kt`) with 9 predefined values
(Food, Groceries, Shopping, Bills, Transfer, Entertainment, Travel, Health,
Other). `Categorizer` (`parser/Categorizer.kt`) auto-assigns a category at
insert time via keyword matching against the merchant name first, then the
raw message text — no ML, no network call, consistent with the project's
lightweight/on-device/explainable design goals. Users can override via the
transaction detail dialog; manual overrides are never re-auto-categorized.

**Notes/Tags**: `Transaction` gained `note: String?` and `tags: String?`
(comma-separated) columns, user-entered only via the detail dialog (tap any
transaction row). Search now also matches against notes and tags, not just
merchant/bank.

**Reminders**: new `Reminder` entity/table (title, optional amount,
dueDayOfMonth, notes). A daily `ReminderCheckWorker` (WorkManager periodic
work, `ExistingPeriodicWorkPolicy.KEEP`) compares today's date against each
reminder's due day and fires a local notification via
`ReminderNotificationHelper`, guarded against duplicate same-month firing via
`lastNotifiedYearMonth`. Requires `POST_NOTIFICATIONS` permission on Android
13+, requested at runtime the first time the Reminders screen is opened.
Reminders are purely local/manual right now — no auto-detection of
recurring merchants yet (see Open Items).

**Schema note**: this added a new table and new columns to `transactions`,
bumping `AppDatabase` from version 1 to 2. Since `fallbackToDestructiveMigration()`
is still in place, this wipes existing local data on upgrade — acceptable at
this dev stage, but **must be replaced with a real `Migration` before any
release build**, or every user's transaction history would be deleted on
app update. Tracked in Open Items.

## 2.9 Filters, Sorting, Charts, Budgets, Manual Entry (added 2026-08-18)

**Navigation**: switched from a single bell-icon toggle to a proper
`NavigationBar` with 4 tabs (Transactions, Charts, Budgets, Reminders).
`allTransactions` is now collected once at the top level in `MainActivity`
and passed down to each screen, rather than each screen collecting
independently.

**Sorting**: `SortOption` (Newest/Oldest/Amount high/Amount low) applied
after filtering. Amount-sorted views render as a flat list instead of
day-grouped, since grouping by day doesn't make sense once chronological
order is broken.

**Tappable summary cards**: the Sent/Received stat cards in the summary
header now double as direction filters (tap to toggle), in addition to the
existing filter chip row — both control the same `directionFilter` state.

**Custom date range**: `DateFilter.CUSTOM` + a `DateRangeDialog` using two
Material3 `DatePicker` components (From/To, switched via a small toggle
inside the dialog). Reachable both via a dedicated button next to the sort
menu and via a chip in the date filter row.

**Charts**: deliberately NOT built on the Vico dependency that's been sitting
unused in `build.gradle` since the project started — wiring up an unfamiliar
chart library's exact API blind risked another multi-round dependency
debugging cycle (see the `kotlinx-serialization` plugin saga in the Decision
Log). Instead, `ChartsScreen` is a simple category-spend breakdown rendered
with Compose's own `Canvas`, fully within our control. Currently fixed to
"this month" — no month picker yet (see Open Items). The Vico dependency
is still in `build.gradle`, unused — candidate for removal if we don't end
up needing real chart types (line/pie) later.

**Budgets**: new `Budget` entity/table (one row per category, `monthlyLimit`).
`BudgetsScreen` shows all 9 categories with current-month spend vs. limit,
a color-coded progress bar (green <70%, orange 70–100%, red >100%), and an
over-budget warning. Tap any category to set/edit/remove its limit.

**Manual cash entry**: the `+` FAB on the Transactions tab (previously used
for nothing) now opens `ManualEntryDialog` — amount, direction, merchant,
category, optional note. Inserted with `bankOrSource = "Cash"` and a random
`rawTextHash` (since there's no real message to hash/dedupe against) so it's
visually distinguishable from auto-captured transactions and never
collides with real dedup hashes.

**Schema**: this bumped `AppDatabase` to version 3 with a real
`Migration(2, 3)` (adds the `budgets` table) — existing data is preserved,
consistent with the fix from the previous session.

## 2.10 Backup & Restore (added 2026-08-18)

`BackupPayload` (`backup/BackupPayload.kt`) is a `@Serializable` snapshot of
all `transactions`, `reminders`, and `budgets`, exported/imported as JSON
via Android's Storage Access Framework (`ActivityResultContracts.CreateDocument`
/ `OpenDocument`) — no storage permission needed, the user picks the exact
file location through the system picker.

**Export**: reads all three tables once (`getAllOnce()`, added to each DAO
alongside the existing reactive `getAll(): Flow<...>`), serializes to
pretty-printed JSON, writes to the user-chosen `content://` URI.

**Restore**: reads the chosen file, deserializes, then **fully wipes**
all three tables (`deleteAll()`, newly added to `TransactionDao` and
`BudgetDao`; already existed on `ReminderDao`) before re-inserting
everything from the backup. This is a deliberate full-replace design, not a
merge — simpler and far less error-prone than trying to reconcile
auto-incrementing IDs between two datasets. A confirmation dialog
("Replace everything") gates this since it's destructive and irreversible.

**Known tradeoff — the export file is NOT encrypted.** Unlike the on-device
SQLCipher database, the exported JSON is plain text once it leaves the app.
The UI shows an explicit warning ("save it somewhere private, not a
publicly shared folder") rather than silently exporting unprotected
financial data. Properly encrypting the export (e.g. passphrase-derived
AES-GCM) is tracked as an Open Item — deliberately deferred rather than
blocking a working backup/restore feature on crypto work, but should be
addressed before recommending this as the primary backup method to anyone
other than the developer during active testing.

This directly addresses the data-loss incidents from earlier sessions
(uninstall wiping app storage, and the pre-fix destructive schema
migrations) — export before anything risky, restore anytime after,
independent of `adb install -r` or migration correctness.

## 2.11 Manual Entry Delete/Edit, Recurring Detection, Balance Tracking, Unusual-Spend Alerts (added 2026-08-18)

**Manual entry delete/edit**: `TransactionDao.delete(id)` added. Tapping any
transaction opens `TransactionDetailDialog`, which now detects
`bankOrSource == "Cash"` (the marker used for manually-added transactions)
and shows full editable fields — amount, direction, merchant — in addition
to category/note/tags. Auto-captured (real bank/UPI) transactions only
expose category/note/tags editing; amount/direction/merchant stay tied to
what the actual message said, preserving audit integrity for real bank
data. A red "Delete transaction" button with a confirmation step is
available on both types.

**Balance tracking**: `Transaction.balanceAfter: Double?` (new column,
`MIGRATION_3_4`) is populated by a bank-agnostic regex in
`TransactionParser` (`avl\.?\s*bal\.?...`) verified against real ECS and
Slice message samples. Applied to every message regardless of which bank
pattern matched, since the "Avl Bal"/"Avl. Bal." convention is common
across banks rather than being bank-specific. The Charts screen shows an
"Account balances" section — latest known balance per `bankOrSource`,
computed client-side from whichever transaction most recently included a
parsed balance (no separate accounts table).

**Recurring transaction detection**: `RecurringDetector` groups SENT,
non-`needsReview` transactions by merchant (trimmed, lowercased), and
flags any merchant appearing in 2+ distinct calendar months with amounts
within 15% of their average as a candidate reminder. Suggests the most
common day-of-month as the due day. Excludes merchants that already have a
matching `Reminder` title, or that the user has dismissed
(`DismissedSuggestionsStore`, plain SharedPreferences rather than a new
Room table/migration — merchant names here are already visible elsewhere
in the unencrypted UI, so this isn't a new privacy exposure). Surfaced as
a "Suggested" section at the top of the Reminders screen with Add/Dismiss
actions per suggestion.

**Unusual-spend alerts**: `UnusualSpendDetector.checkAndNotify()` runs
after every successful insert (both `ParseAndStoreWorker` for auto-captured
transactions and the manual-entry save path), comparing the new
transaction's amount against the historical mean for its category. Fires a
local notification (`UnusualSpendNotificationHelper`, its own notification
channel) if the amount is ≥2.5× the category average AND at least 3 prior
transactions exist in that category — the minimum-history gate avoids
false positives on a category's very first transaction, where any amount
would otherwise look "unusual" relative to a zero/undefined baseline.
Mean-based threshold, no ML — consistent with the project's
lightweight/explainable design philosophy throughout.

## 2.15 Cross-Source Duplicate Detection (added 2026-08-20)

**Problem**: real screenshots showed the same real-world payment appearing
twice — once via a payment app's own notification (e.g. GPay's
`"X paid you ₹Y"`, captured because `com.google.android.apps.nbu.paisa.user`
is in `NotificationCaptureService`'s listened-package whitelist) and once
via the bank's own SMS alert relayed through the default messaging app.
These have entirely different raw text, so `TransactionDao.existsByHash`
(which only catches the *same* notification being redelivered verbatim)
never flagged them as related.

**Alternative considered and rejected**: stop listening to
GPay/PhonePe/Paytm's own notifications entirely, relying solely on the
SMS-relay path (which has been the fully-validated capture mechanism
throughout this project). Rejected because some UPI activity — wallet-only
or wallet-funded payments — may not always generate a bank SMS if no bank
account was directly touched, so this would have traded a duplicate-data
problem for a missing-data problem.

**Fix implemented**: `DuplicateDetector.isLikelyDuplicate()`, called from
`ParseAndStoreWorker` right before insert (after the existing exact-hash
check passes). Flags a new transaction as a likely duplicate only if an
existing one matches on: same direction, same amount (exact — UPI amounts
don't round), a **different** `bankOrSource`, and within a **90-second**
window. All three conditions together, deliberately narrow to avoid
false-positiving on genuinely separate transactions that happen to share
an amount — e.g. two separate ₹100 purchases would both come from the same
bank SMS source, so they correctly stay as two rows. Manual ("Cash")
entries are excluded from this check entirely, in both directions (a
manual entry is never suppressed as a "duplicate," and never used to
suppress an unrelated auto-capture).

**Not addressed by this fix**: if the *first*-arriving side of a genuine
cross-source duplicate has already been auto-categorized, tagged, or
edited by the user before the second (now-suppressed) copy would have
arrived, no reconciliation happens — the first one simply stays as the
sole record, which is the intended behavior.

**AMENDED 2026-09-02 — race condition found and fixed**: despite the
matching logic above, real screenshots this session showed cross-source
duplicates *still* landing in the DB. Root cause was not the matching
criteria (which were already order-independent — the check looks for
*any* existing row with a different `bankOrSource`, not specifically
"the SMS one" or "the UPI one"). It was a **check-then-insert race**:
`ParseAndStoreWorker` called `dao.existsByHash(...)`, then
`dao.getAllOnce().filter{...}` + `DuplicateDetector.isLikelyDuplicate()`,
then `dao.insert(...)` as separate suspend calls. When the bank-SMS
notification and the UPI-app notification for the same real payment
arrive within milliseconds of each other, WorkManager can run both
workers concurrently — both see "no duplicate yet" (because neither has
inserted), and both insert.

**Fix**: collapsed the check and the insert into one `@Transaction` DAO
method, `TransactionDao.insertIfNotDuplicate()`, returning a new sealed
`InsertOutcome` (`Inserted` / `ExactDuplicateSkipped` /
`CrossSourceDuplicateSkipped`). Room routes `@Transaction` suspend
functions through its internal transaction executor, which serializes
concurrent callers against the same database — so two workers racing on
this call can no longer both pass the check before either commits, no
matter which side (bank SMS vs. UPI app notification) happens to arrive
first. `ParseAndStoreWorker.doWork()` now calls this single method
instead of the old three-call sequence. Also added
`TransactionDao.getNearTimestamp()`, a narrow ±window query, replacing
the previous `getAllOnce().filter{...}` full-table scan for this check.

## 2.16 App Lock (added 2026-08-20)

Biometric/PIN app lock via `androidx.biometric.BiometricPrompt`, requiring
`MainActivity` to extend `FragmentActivity` instead of `ComponentActivity`
(added `androidx.fragment:fragment-ktx` dependency — `androidx.biometric`
itself was already present in `build.gradle` from the original scaffold,
unused until now).

**`AppLockManager`**: SharedPreferences-backed enabled/disabled flag, plus
`canUseAppLock()` checking `BiometricManager.canAuthenticate(BIOMETRIC_WEAK
or DEVICE_CREDENTIAL)` — the Settings toggle is disabled entirely if the
device has neither biometric enrollment nor any screen lock configured,
since the app cannot enforce a lock without *some* underlying device
security to delegate to.

**`BiometricAuthHelper`**: wraps `BiometricPrompt` with
`BIOMETRIC_WEAK | DEVICE_CREDENTIAL` as allowed authenticators — biometric
first, with the device PIN/pattern/password as the system's own automatic
fallback. `setNegativeButtonText()` is deliberately never called, since
it's incompatible with `DEVICE_CREDENTIAL` (throws if both are set — the
system supplies its own "use PIN" affordance instead).

**Lock/unlock flow in `MainActivity`**: `isUnlocked` state resets to
`false` every time the Composable leaves `STARTED` (via
`LifecycleStartEffect`'s `onStopOrDispose`) whenever app lock is enabled —
covers both backgrounding and process death/recreation, so a lost/stolen
unlocked phone doesn't stay exposed after switching apps and back. Top bar
actions, bottom nav, and FAB are all conditionally hidden while locked
(`isLocked = appLockEnabled && !isUnlocked`), alongside the existing
`showSettings` gating, so no navigation hints or screen titles leak before
unlocking. Enabling the toggle in Settings requires a successful
authentication first (`onAppLockToggle` triggers `BiometricAuthHelper`
before persisting `enabled = true`) to prevent accidentally locking
yourself out with a fat-fingered toggle tap; disabling requires no
re-auth (a deliberate simplicity tradeoff — see Open Items for the
alternative of requiring auth to disable too).

## 2.13 Settings Screen (added 2026-08-18)

Replaced the top-bar cloud-sync icon with a gear icon opening a dedicated
full-screen `SettingsScreen` (overlay via a `showSettings` boolean state,
not a bottom-nav tab — it's not a frequently-visited main section like the
other four). Contains: **Backup & Restore** (relocated from its previous
standalone top-bar entry point, same dialog/logic, no functional change);
**Notification access status** (green "Granted" / orange "Not granted" with
tap-to-fix, reusing `NotificationAccessHelper`); a **Danger zone** with
**Delete all data** (red, requires confirmation, explicitly suggests
exporting a backup first); and an **About** section showing the real app
version (`PackageManager.getPackageInfo`) and a one-line on-device-only
reminder. System/gesture back is wired via `BackHandler` to close Settings
rather than exit the app.

## 2.14 Needs-Review Workflow & CSV Export (added 2026-08-18)

**Needs-Review workflow**: `TransactionDetailDialog` now treats any
`needsReview == true` transaction as fully editable (amount, direction,
merchant), not just manual/Cash entries — that's precisely the case where
the parser's guess needs a human correction, unlike a confidently-parsed
real bank message where those fields should stay tied to what the bank
actually reported. Saving always clears `needsReview` to `false`, whether
or not the user changed anything — opening and confirming the dialog is
itself the "I've looked at this" signal. A `NeedsReviewBanner` surfaces on
the Transactions screen whenever `needsReview` count > 0 and the Needs
Review filter isn't already active, tapping it applies that filter
directly (reusing the existing filter infrastructure rather than building
a separate queue screen).

**CSV export**: `CsvExporter` (`backup/CsvExporter.kt`) is a distinct
export path from the JSON `BackupPayload` — CSV is for opening in
Excel/Sheets for the user's own analysis, explicitly not meant to be
re-imported (drops reminders/budgets, flattens category to its enum name).
Proper CSV field escaping (quotes fields containing commas/quotes/newlines,
doubles internal quotes). Added as its own row in Settings, separate from
Backup & Restore, with a subtitle clarifying the distinction so users don't
confuse the two export formats' purposes.

## 2.12 SMS Permissions Permanently Removed (2026-08-18)

Per Google's SMS and Call Log Permissions policy (confirmed via web search
during this session — see Decision Log): apps that are not the user's
default SMS/Phone/Assistant handler may not declare `RECEIVE_SMS`/`READ_SMS`
at all, and **even inert/commented manifest declarations are flagged**
during review. The previous "Dev Mode: SMS Fallback Disabled" state
(commented-out permissions + commented-out `<receiver>` block, kept for
potential future re-enabling) has been **permanently removed**, not just
commented: both manifest lines and `SmsReceiver.kt` are deleted outright.

`NotificationListenerService` (`NotificationCaptureService`) is now
documented as the sole, sufficient capture path — validated across 7+ real
bank/UPI message formats (Kotak, HDFC, Slice ×2, Central Bank of India)
over the course of this project, so this removal costs no real-world
functionality. Do not re-add SMS permissions or a SmsReceiver for a
Play-Store-distributed build; if OEM-specific notification-delivery gaps
are ever found in the field, the correct fix is improving notification
capture robustness (e.g. battery-optimization exemption prompts,
autostart guidance), not falling back to SMS permissions the app cannot
legitimately declare.

---

## 2.19 Monthly Scoping & History (added 2026-09-02)

**Problem**: the Transactions (home) tab showed every transaction ever
captured, all-time, with search/filter/sort chrome permanently docked at
the top. As real usage accumulates this is a poor default (users care
about "this month," not the full history at a glance) and it also crowded
out room for the banner ad.

**Fix**: `TransactionsScreen` now scopes to the **current calendar month**
by default, filtering the existing `allTransactions` list via a new
`MonthRange` helper (`util/MonthRange.kt`). Deliberately built on
`java.util.Calendar`, not `java.time.YearMonth` — minSdk is 24 with no
core library desugaring configured in `app/build.gradle`, so `java.time`
isn't safely available on every supported device; this also matches the
`Calendar`-based logic already in `MainActivity.filterTransactions()`.

A "See all months" row opens the new **`MonthlyHistoryScreen`** (a
full-screen overlay reached from the home tab, mirroring the existing
`showSettings` overlay pattern rather than adding a 6th bottom-nav tab).
It has a prev/next month stepper plus a tap-to-open month/year picker
dialog, and a **Month vs. Year** toggle controlling whether the summary
totals shown are scoped to just the selected month or its whole calendar
year — the transaction list underneath always shows the selected month's
transactions either way. The Month/Year preference is persisted via a new
`SummaryPeriodStore` (plain `SharedPreferences`), matching the existing
pattern for single, low-stakes UI preferences (see the 2026-08-18
`DismissedSuggestionsStore` decision).

**No schema change** — this is filtering over data already loaded via the
existing `transactionDao.getAll()` Flow collected once in `MainActivity`;
no new Room query, column, or migration was needed.

**Deferred, not done**: no `CREATE INDEX` on `transactions.timestampMillis`
has been added. Not needed yet since filtering happens client-side over
data already in memory, but if a future change moves this to a DB-level
range query (e.g. for scale), that query would want the index. Also
deferred: `ChartsScreen` (§2.9) still has its own separate "this month
only, no picker" limitation — it does not yet share `MonthlyHistoryScreen`'s
month-selection state, so the app currently has two independent places a
user might expect a month picker. Worth unifying later.

## 2.20 Navigation Restructure — Search/Review Tab, Net Summary (added 2026-09-02)

**Problem**: the search bar, filter chips, sort menu, and
`NeedsReviewBanner` (§2.14) all lived permanently at the top of the
Transactions screen, leaving no guaranteed space for the banner ad
without it competing for room depending on scroll position and which
sub-state (empty / no-results / populated) was showing.

**Fix**: the bottom `NavigationBar` gained a 5th tab, **Search**. All of
the search bar, filter chip row, sort menu, custom date-range dialog, and
the needs-review banner moved off the Transactions tab entirely into a
new `SearchReviewScreen` — reached via this tab, rather than always-docked
at the top of the home screen. Unlike the now month-scoped home screen,
`SearchReviewScreen` searches/filters across the **full** transaction
history, since "search" implies more than just the current month.

**Badge**: the Search tab's icon shows a Material3 `Badge` (error color,
count text) sized to the number of `needsReview == true` transactions —
the same count the old always-visible banner showed — via `BadgedBox`.
Omitted entirely when the count is 0.

**Net effect on the Transactions tab**: it now opens with the banner ad
at the top (guaranteed space, no longer competing with search chrome),
then a new `NetSummaryCard` (see below), then the day-grouped,
current-month-only list.

**Net summary card**: `NetSummaryCard` shows Sent, Received, and Net
(received − sent) for whichever period is currently in scope — this
month, on the home tab; the selected month or year, on
`MonthlyHistoryScreen`. The net figure renders in green when ≥ 0 and red
when negative, using flat semantic hex values (`#2E7D32` / `#D32F2F`)
consistent with the green/orange/red already used for budget progress
bars (§2.9). Deliberately non-interactive here — the old always-visible
StatCards doubled as direction-filter toggles, but that behavior now
belongs on `SearchReviewScreen`, where filtering is actually meaningful;
duplicating it on the home tab's read-only summary would be confusing.

**AMENDED 2026-09-03**: the previous paragraph's reasoning was wrong on
testing. Real usage showed two regressions from this session's changes:
(1) the tap-to-filter behavior was a workflow people actually relied on,
not redundant with the Search tab — restored as a **local** Sent/Received
toggle on both `TransactionsScreen` and `MonthlyHistoryScreen` (separate
state from `SearchReviewScreen`'s own filter; this one never exposes
NEEDS_REVIEW, only ALL/SENT/RECEIVED, and doesn't persist as a saved
search). (2) `NetSummaryCard` also moved to a **single row** (Net, Sent,
Received side by side) instead of Net on its own row above a second
Sent/Received row, per direct feedback. Additionally, restored a second
`BannerAdView()` at the **bottom** of `TransactionsScreen` alongside the
top one — moving the ad to the top for space was correct, but removing
the bottom placement entirely wasn't asked for, and the shorter
month-scoped list comfortably fits both now.

**AMENDED AGAIN 2026-09-03 — Search reworked from a nav tab into an
overlay**: the 5th-tab design above (this section's original "Fix")
turned out to be the wrong shape for search. A bottom-nav tab is a
*destination* — selecting it replaces whatever was on screen, including
the current-month Transactions list, which people expect to still be
there when they're done searching. Search is inherently a transient
action, not a place to live.

**New design**: `Screen` reverted to 4 entries (`TRANSACTIONS`, `CHARTS`,
`BUDGETS`, `REMINDERS`) — no bottom-nav Search tab. A **Search icon moved
to the top app bar**, next to the existing Settings gear icon, visible on
every main screen. Tapping it sets a new `showSearch` boolean and renders
`SearchReviewScreen` as a **full-screen overlay** — the same pattern
already used for `showSettings` and `showMonthlyHistory` — with a back
arrow (and system/gesture back, via `BackHandler`) closing it back to
whichever screen (Transactions, Charts, Budgets, or Reminders) was
showing underneath, unchanged. The needs-review `Badge` moved from the
nav-bar icon to this top-bar icon, same count, same visual treatment.

This also incidentally un-crowds the bottom `NavigationBar` back to 4
items instead of 5, and matches a more common mobile pattern (search as a
top-bar action that expands over content, e.g. Gmail/YouTube) rather than
search as a permanent tab.

## 2.21 Pro-Gating Extended to Backup & Restore + CSV Export (added 2026-09-02)

**Note on doc state**: this section references `isPro` / `requirePro{}` /
`BannerAdView` / subscription concepts that are already present in
`MainActivity.kt` (Play Billing integration), but this file does not yet
have the §2.17/§2.18 sections documenting that work — REQUIREMENTS.md is
currently behind the actual code here. That documentation gap predates
this session's changes and should be backfilled separately from whichever
session first added Play Billing/AdMob, rather than reconstructed
secondhand here.

**Change made this session**: `Backup & Restore` and `Export to CSV` rows
in `SettingsScreen` are now wrapped in `if (isPro) { ... }` and fully
hidden for free-tier users, rather than always visible with CSV export as
the sole previously-gated example. `requirePro { }` remains wired to both
click handlers as a defensive fallback in case either is ever reached
another way (e.g. a future deep link), even though the primary gate is
now visibility itself.

**Tradeoff, not yet resolved**: hiding entirely (rather than showing a
grayed-out row with an upgrade prompt) was implemented per explicit
product direction this session. It trades away a conversion-nudge
surface for simplicity. Worth reconsidering before real launch — see
Open Items.

## 2.22 First-Launch Onboarding / Disclosure Flow (added 2026-09-04)

**Why now**: this was the first Open Item on the list ("Onboarding flow:
explain permissions before requesting them...") and became a hard
prerequisite once Play Store submission entered scope — Google Play's
User Data policy requires **prominent in-app disclosure and affirmative
consent** before an app accesses sensitive data sources like notification
content, separate from (and in addition to) the Data Safety form and
Privacy Policy, which describe the same thing but don't substitute for an
actual in-app explanation shown to the user.

**Implementation**: `OnboardingScreen` (`MainActivity.kt`), a fixed
3-page flow — no pager library dependency, just a `page: OnboardingPage`
enum and manual Next buttons, consistent with the project's general
"avoid unfamiliar library APIs when plain Compose does the job" approach
(see the Vico/Canvas-chart decision).

1. **Welcome** — what the app does, in plain language (reads bank/UPI
   alerts, logs them automatically).
2. **Transparency** — the actual disclosure content: what's read (only
   banking/UPI/messaging notifications), what's extracted (amount,
   direction, date, merchant — nothing else), what's discarded (the raw
   notification text, immediately), what's never done (no upload, no
   server, no SMS permission).
3. **Grant Notification Access** — the actual permission ask, via
   `NotificationAccessHelper.settingsIntent()` (unchanged, pre-existing).
   Also offers a secondary "Also check battery settings" button (see
   below) and a **"Skip for now" path that is never a dead end** — the
   app must remain usable via manual entry without this permission, both
   as a matter of policy and because forcing the permission would be a
   worse experience than the `OnboardingBanner` reminder it falls back to.

**Gating**: tracked by a new `OnboardingStore` (plain `SharedPreferences`,
same pattern as `SummaryPeriodStore`/`DismissedSuggestionsStore`) — one
boolean, "has completed onboarding," checked in `MainActivity.onCreate`
before the normal `Scaffold` renders at all. Deliberately tracks *having
seen the explanation*, not *having granted the permission* — a user who
taps "Skip for now" has still completed onboarding and won't be forced
through the full 3-page flow again on next launch; the existing
`OnboardingBanner` on the Transactions screen (§2.6) already handles
follow-up reminders for anyone who skipped.

**Battery settings button — deliberately NOT a direct exemption request**:
the "Also check battery settings" button on page 3 opens the app's system
"App info" screen (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`) via a
new `BatteryOptimizationHelper`, rather than firing
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the direct "exempt this app"
system dialog). That intent requires declaring the
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission in the manifest, which
is itself a Play Console-restricted permission needing a core-functionality
justification (similar to exact alarms) — and battery exemption is a
reliability nice-to-have for background capture, not something the app is
broken without, so it doesn't clear that bar. Opening the plain App Info
screen needs no manifest permission and no Play declaration at all, and
still gets a motivated user to the same underlying OS setting in one more
tap.

**Not done**: OEM-specific ("allow autostart" on MIUI/ColorOS/etc.)
guidance is still a separate, unaddressed Open Item — this onboarding flow
covers the stock-Android permission asks only.

## 2.23 UMP Consent Gathering for Ads (added 2026-09-04)

**Why**: Google's EU User Consent Policy has required a certified Consent
Management Platform (Google's own UMP SDK, in this case) since January 16,
2024 for any app serving Google ads to EEA/UK/regulated-US-state users.
This isn't tied to AdMob account tier or app category — it applies the
moment the app might show a real ad to a user in those regions, and is
separate from (in addition to) the Data Safety form, which only *discloses*
data collection rather than gathering consent for it. Flagged as a real gap
in ยง10.2 of this doc's research; implemented this session before real ads
go live.

**New `ads/ConsentManager.kt`** wraps the UMP SDK
(`com.google.android.ump:user-messaging-platform:4.0.0`, a standalone
Gradle dependency — not bundled in `play-services-ads` despite both being
Google ad libraries):

- `gatherConsent(activity, onCanRequestAdsChanged)` — calls
  `requestConsentInfoUpdate()`, then `loadAndShowConsentFormIfRequired()`
  if a form is actually needed for this user's region, then initializes
  the Mobile Ads SDK exactly once, only after `canRequestAds()` is true.
  A UMP network failure falls back to whatever consent status is cached
  from a prior session rather than blocking the app indefinitely.
- `isPrivacyOptionsRequired()` / `showPrivacyOptionsForm()` — GDPR requires
  consent be *revocable*, not just gatherable once at first launch, so an
  always-available entry point is required for users in regions where this
  applies.

**Moved: `MobileAds.initialize()` out of `ExpenseTrackerApp.onCreate`**,
where it previously ran unconditionally, into `ConsentManager`, called
from `MainActivity.onCreate`. This wasn't optional refactoring —
`requestConsentInfoUpdate()` requires an `Activity`, not just an
Application `Context`, so the consent-gathering step could never have
lived in the `Application` class to begin with.

**New `MainActivity` state**: `canRequestAds` and `privacyOptionsRequired`,
both plain `by mutableStateOf(false)` properties on the Activity (not
declared inside `setContent`) — `ConsentManager`'s callback fires
asynchronously, independent of any single composition, so it needs a
stable place to write to that Compose will still observe correctly.

**All three `BannerAdView()` call sites** (top and bottom of
`TransactionsScreen`, and the one in `SettingsScreen`) gated on
`!isPro && canRequestAds` instead of just `!isPro` — no ad request, real
or test, fires before consent is resolved where required.

**New Settings row**: "Privacy & Ad Consent," shown only when
`privacyOptionsRequired` is true, re-opens the consent form via
`showPrivacyOptionsForm()` and re-resolves `canRequestAds` afterward in
case the user changed their choice.

**Debug testing support, gated behind `BuildConfig.DEBUG`**: a commented
template for `ConsentDebugSettings` (test device hash + forced EEA
geography) is included inline in `ConsentManager.gatherConsent()` with
instructions for finding your own device's test hash via logcat — never
active in a release build.

**Not verified by compiling** — same sandbox limitation as everything
else this project has been built under this way. This integration is
based on current Google documentation (fetched live this session, not
recalled from training data) rather than guessed API shapes, but a real
build/device test is still necessary before trusting it — the UMP SDK's
consent form only actually renders for a user whose device/region
triggers it, so a passing debug build without a forced EEA geography
won't visibly prove much on its own.

---

## 3. Security & Privacy (see prior design discussion — this is the source of truth)

1. **Encryption at rest**: SQLCipher DB, Keystore-backed passphrase, never hardcoded/logged.
2. **Data minimization**: raw SMS/notification text discarded immediately after parsing; only structured fields persisted.
3. **No network transmission**: app currently has no `INTERNET` permission at all. `network_security_config.xml` blocks cleartext by default in case this changes.
4. **Permission discipline**: only notification-listener access, `POST_NOTIFICATIONS`, and battery-optimization-exemption request. No SMS, contacts, location, or storage permissions — see ยง2.12 for why SMS permissions were permanently removed.
5. **Backup exclusion**: `allowBackup="false"` + `data_extraction_rules.xml` explicitly excludes the DB file even if backup is ever re-enabled.
6. **ReDoS safety**: all regex evaluation wrapped in a timeout (see ยง2.2).
7. **App-level lock**: biometric/PIN lock via `BiometricPrompt` — **planned, not yet implemented** (see Open Items).
8. **Transparency**: in-app "what we read / what we store" disclosure screen and a delete-all-data action — **planned, not yet implemented**.

---

## 3.5 SMS Permissions: Permanently Removed (superseded 2026-08-18)

**Status: `RECEIVE_SMS`/`READ_SMS` and `SmsReceiver` are permanently
removed from this project — not disabled, not commented out, deleted.**
See ยง2.12 for the full reasoning (Google Play policy) and history. This
section is kept for context on the dev-mode workaround this permanently
replaces.

**Original context (now historical)**: earlier in development, these were
temporarily commented out because Google Play Protect blocked sideloaded
APK installs requesting this permission pair during local testing. That
was always going to need revisiting before any real submission — the
policy research in ยง2.12 confirmed the correct resolution is permanent
removal, not re-enabling for a signed build as originally planned.

**What still works**: the entire primary capture path —
`NotificationCaptureService` → `ParseAndStoreWorker` → encrypted Room DB →
UI — is unaffected and has been the sole capture mechanism validated
against every real message sample collected in this project.



Run once per Codespace:
```bash
chmod +x setup-codespace.sh
./setup-codespace.sh
source ~/.bashrc
```

Manual equivalent, if you'd rather run it step by step:
```bash
# 1. JDK 17
sudo apt-get update -y
sudo apt-get install -y openjdk-17-jdk unzip

# 2. Android SDK command-line tools
export ANDROID_HOME="$HOME/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME/cmdline-tools"
curl -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q cmdline-tools.zip
mv cmdline-tools latest && rm cmdline-tools.zip

# 3. Env vars (add to ~/.bashrc to persist across Codespace restarts)
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

# 4. Licenses + platform/build-tools
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

### Preserving local data across reinstalls (added 2026-08-16)
Use `adb install -r app-debug.apk` to update the app in place, **not**
`adb uninstall` + `adb install`. Uninstalling always wipes app-private
storage (including the encrypted transaction DB) regardless of any
migration logic — this is standard Android behavior, not something the app
can override, since `allowBackup="false"` is intentional (see ยง3 Security).
As of the 2026-08-16 migration fix (ยง8 Decision Log), schema updates via
`-r` reinstall now preserve existing data; only a full uninstall wipes it.


```bash
# From project root (where settings.gradle lives)
chmod +x gradlew        # first time only, after gradle wrapper is generated — see note below
./gradlew assembleDebug             # build debug APK
./gradlew installDebug              # install to connected device/emulator
./gradlew test                      # unit tests
./gradlew connectedAndroidTest      # instrumented tests (needs device/emulator)
./gradlew lint                      # static analysis
./gradlew bundleRelease             # to create aab file to upload on play store
```

> **Note on the Gradle wrapper**: this scaffold does not include the
> `gradle-wrapper.jar` binary (binary files aren't hand-written). Generate it
> once inside the Codespace with a locally installed Gradle:
> ```bash
> sdk install gradle 8.5   # or: sudo apt-get install -y gradle
> gradle wrapper --gradle-version 8.5
> ```
> After that, commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/*` to the
> repo — future clones/Codespaces won't need Gradle pre-installed at all.

### Running on a device from Codespaces
Codespaces has no physical device or emulator by default. Two options:
1. **scrcpy/ADB over Wi-Fi** to a real Android phone on the same network as
   a port-forwarded Codespace — fiddly, not recommended as primary workflow.
2. **Build the APK in Codespaces (`assembleDebug`), download the `.apk`
   artifact, and sideload/install locally** on your own machine or phone —
   simplest reliable workflow for this project. The APK will be at
   `app/build/outputs/apk/debug/app-debug.apk` after a successful build.

### GitHub repo setup commands
```bash
git init
git add .
git commit -m "Initial scaffold: manifest, encrypted Room DB, notification listener, SMS fallback, regex parser, WorkManager pipeline"
gh repo create expense-tracker --private --source=. --remote=origin
git push -u origin main
```

---

## 5. Dependency Manifest (why each one is here)

| Dependency | Purpose | Weight note |
|---|---|---|
| androidx.compose (BOM 2024.02.00) | UI | ~1-2MB over classic Views, worth it |
| androidx.room + kapt compiler | Local DB ORM | standard |
| net.zetetic:android-database-sqlcipher | DB encryption | required for financial data at rest |
| androidx.security:security-crypto | EncryptedSharedPreferences, Keystore | required for key storage |
| androidx.work:work-runtime-ktx | Background job scheduling | battery-aware, avoids raw Service |
| androidx.biometric | App lock | planned |
| kotlinx-serialization-json | Parsing `bank_patterns.json` | small, no reflection needed |
| com.patrykandpatrick.vico:compose-m3 | Charts | chosen over MPAndroidChart for Compose-native + smaller footprint |

No Firebase, no analytics SDK, no crash reporter yet — deliberate, to keep
the "no network access, no data leaves device" story literally true until a
feature actually needs otherwise (see Open Items re: Remote Config).

---

## 6. Bank/UPI SMS Pattern Library

Location: `app/src/main/assets/bank_patterns.json`

Currently covers (India-focused, since that's the target market implied by
sender-ID format assumptions like `HDFCBK`, `ICICIB`, `SBIINB`):

| Bank | Status | Source |
|---|---|---|
| Kotak (`KOTAKB`) | **Verified** — both directions confirmed against real messages on the dev's own phone | Live capture, 2026-08-16 |
| HDFC (`HDFCBK`) | **Verified** — both directions confirmed against real messages | Live capture, 2026-08-16. Multi-line debit format required the DOTALL fix (ยง8). |
| Slice (`SLCBNK`) | **Verified** — both directions confirmed. Slice uses at least two different debit message formats (`"Rs.X sent from..."` and `"UPI payment of Rs.X ... is successful"`), only the latter required the `"payment"` keyword fix (ยง8) | Live capture, 2026-08-16 |
| Central Bank of India | **Verified** — credited side confirmed against a real message. No dedicated `senderMatch` entry added yet (exact SMS sender ID unconfirmed) — currently matched via the `GENERIC_UPI` fallback, which works correctly | Live capture, 2026-08-16 |
| ICICI (`ICICIB`) | Grounded, not device-verified | Structure inferred from public examples in the open-source [`transaction-sms-parser`](https://github.com/saurabhgupta050890/transaction-sms-parser) library's docs |
| SBI (`SBIINB`) | Grounded, not device-verified | Generic ATM/debit wording pattern, no bank-specific sample found |
| Axis (`AXISB`) | Grounded, not device-verified | Same source as ICICI; listed as tested by that library |
| Generic UPI fallback | Grounded + now also verified (handles CBI correctly) | Combines the widened match-window fix with a real ECS-style debit sample found via the same source |

**Known gap**: credit card "spend" notifications (e.g.
`"Rs.2000 spent on HDFC Visa Credit Card 4321. Avbl credit limit Rs.50000."`)
use a different structure (`"on CARD_NAME"` instead of `"to/from MERCHANT"`)
and are **not yet matched** by any pattern above — a naive fix (adding `on`
as a merchant-preposition alternative) risks misinterpreting the `"on DATE"`
clause present in most other message formats as a merchant name instead.
Needs a dedicated credit-card-specific pattern later, not a shared one.

**Lesson learned**: don't assume word order or match-window size from
generic examples — the original 40-char lazy-match window was too narrow
for real messages where the merchant/date clause appears further from the
amount than expected (see the ECS sample above, ~70 chars). Widened to an
unbounded lazy `.*?` (still guarded by the existing ReDoS timeout in
`TransactionParser.safeFind`).

**Process for adding a new bank format:**
1. Get 2-3 real (anonymized) sample SMS for the new sender.
2. Add a new entry to `patterns[]` with `senderMatch`, `debitedRegex`, `creditedRegex`.
3. Write a unit test in `TransactionParserTest` asserting correct extraction.
4. Keep regexes simple/anchored — avoid nested quantifiers (`(.*)+` etc.) — see ยง3.6.

This file currently ships bundled in the APK (requires app update to patch).
Moving it to Firebase Remote Config is an Open Item — would let pattern
fixes ship without a Play Store release, at the cost of adding network
access + a validation step server-side.

---

## 7. Open Items / Not Yet Built

- [x] ~~Onboarding flow: explain permissions before requesting them, deep-link to `ACTION_NOTIFICATION_LISTENER_SETTINGS`, request battery-optimization exemption~~ — done 2026-09-04, see ยง2.22 (battery step opens App Info settings rather than a direct exemption request — see that section for why)
- [x] ~~Biometric/PIN app lock + auto-lock on background~~ — done 2026-08-20, see ยง2.16
- [ ] Consider requiring re-authentication to *disable* app lock too, not just to enable it (currently a deliberate simplicity tradeoff)
- [x] ~~"Needs review" queue UI for low-confidence parses~~ — done 2026-08-18 as a banner + full-edit dialog (reuses existing filter infra rather than a separate screen), see ยง2.14
- [x] ~~Manual transaction entry + edit/correct flow~~ — done 2026-08-18 (add via FAB, edit/delete via tapping any transaction)
- [x] ~~Categorization (manual + rule-based auto-categorization)~~ — done, see ยง2.8
- [x] ~~Monthly summary / charts screen~~ — done 2026-08-18 as a custom Canvas chart (Vico dependency still unused, see item below)
- [x] ~~Delete-all-data action in Settings~~ — done 2026-08-18, see ยง2.13
- [ ] In-app privacy/data disclosure screen
- [ ] Play Console Data Safety form draft
- [x] ~~Re-enable SMS fallback path before any signed/release build~~ — superseded 2026-08-18: permanently removed instead, per Google Play policy (see ยง2.12)
- [ ] ~~Get Play Console's Permissions Declaration Form ready for Notification Listener access (justification + demo video) before submission~~ — corrected 2026-09-04: research indicates Notification Listener isn't in Play's enumerated Permissions Declaration Form list (unlike SMS/Accessibility/etc.) — see ยง10.2. Re-verify directly in Play Console before submission rather than trusting this note alone.
- [ ] Write and publish a Privacy Policy page (mandatory for Play Store submission, especially with sensitive permissions)
- [ ] Complete Play Console's Data Safety form
- [ ] Set up release signing (keystore) and test a real `buildTypes.release` build with R8/ProGuard — currently only ever built as debug
- [ ] Decide: bundle vs remote-config for `bank_patterns.json`
- [ ] Gradle wrapper generation + commit (see ยง4 note)
- [ ] Unit tests for `TransactionParser` per bank pattern
- [ ] OEM-specific "allow autostart" guidance screen for MIUI/ColorOS/FuntouchOS etc.
- [x] ~~App icon / branding assets~~ — done, real vector-based adaptive icon shipped (green badge + rupee glyph), see Decision Log 2026-08-15/16
- [ ] Handle multi-SIM / dual-SIM sender variations
- [ ] **Remove temporary `BuildConfig.DEBUG` raw-text logging** in `NotificationCaptureService` once parser accuracy is validated across more real bank/UPI samples — see ยง8 Decision Log 2026-08-16
- [x] ~~Replace `fallbackToDestructiveMigration()` with a real Room `Migration`~~ — done 2026-08-16, see Decision Log
- [ ] Encrypt the backup export file (passphrase-derived AES-GCM) — currently plain JSON, see ยง2.10
- [ ] Charts: add a month picker (currently fixed to "this month") and consider a spending-over-time trend line, not just category breakdown
- [ ] Consider removing the unused Vico dependency from `build.gradle` if the custom Canvas chart approach continues to suffice
- [x] ~~Auto-detect recurring merchants/amounts to suggest reminders automatically~~ — done 2026-08-18, see ยง2.11
- [ ] Verify debited-side (money sent) regex against a real sample — only the credited-side has been confirmed against real data so far
- [ ] Test additional banks/UPI apps beyond Kotak as real samples become available
- [ ] Tune unusual-spend alert thresholds (2.5x / min-3-history are untested starting guesses) once there's realistic usage volume to observe against
- [ ] Recurring detection only matches on exact-ish merchant name (trim+lowercase) — no fuzzy matching for slightly different spellings of the same merchant across messages
- [ ] Balance tracking shows only the single latest balance per source, no history/trend over time
- [x] ~~Fix cross-source duplicate transactions still appearing despite ยง2.15 matching logic~~ — done 2026-09-02: root cause was a check-then-insert race, not the matching criteria; fixed via a single `@Transaction` DAO method, see ยง2.15 amendment
- [x] ~~Restructure bottom nav to free space for the banner ad; surface needs-review count as a badge~~ — done 2026-09-02, see ยง2.20 (superseded 2026-09-03: Search moved off the nav bar entirely into a top-bar overlay, see amendment)
- [x] ~~Gate Backup & Restore + Export to CSV behind Pro subscription~~ — done 2026-09-02, see ยง2.21
- [x] ~~Show net spent/received total, color-coded~~ — done 2026-09-02, see ยง2.20
- [x] ~~Scope home screen to current month only; add a separate monthly-history screen with month/year picker~~ — done 2026-09-02, see ยง2.19
- [ ] **Backfill missing ยง2.17 (Subscriptions/Play Billing) and ยง2.18 (Banner Ads) sections** — this file is currently behind the actual code, which already has `isPro`/`requirePro{}`/`BannerAdView`/Play Billing wiring undocumented here. Found while making the 2026-09-02 changes; not caused by them.
- [ ] Add `CREATE INDEX idx_transactions_timestampMillis` if month/year filtering moves to a DB-level query and shows up as slow at realistic data volumes (currently client-side over already-loaded data, see ยง2.19)
- [ ] Unify `ChartsScreen`'s "this month only" limitation with `MonthlyHistoryScreen`'s month-selection state instead of having two independent month concepts (see ยง2.19)
- [ ] Decide: gray-out-with-upsell vs. full-hide for Pro-gated Settings rows (currently full-hide per explicit direction — see ยง2.21 tradeoff note)
- [x] ~~Bump `compileSdk`/`targetSdk` to 36 and AGP to 9.0+ for the Play target-API requirement~~ — done 2026-09-04, see ยง10.7 (AGP 9.1.0, Gradle 9.1.0, Compose BOM 2026.04.01) — **not yet compiled/tested**, budget a full click-through
- [ ] Regenerate and commit `gradle/wrapper/gradle-wrapper.jar` to match the Gradle 9.1.0 `distributionUrl` bump (ยง10.7) — the jar itself isn't hand-edited, needs a local `gradle wrapper --gradle-version 9.1.0` run
- [ ] Verify edge-to-edge display isn't obscuring content on any screen now that API 36 enforces it unconditionally (ยง10.7) — needs an actual device/emulator check, not just a successful build
- [x] ~~Add a way to hide/reveal account balances rather than always showing them~~ — done 2026-09-04, see ยง10.8

---

## 8. Decision Log

| Date | Decision | Reasoning |
|---|---|---|
| 2026-08-15 | Native Kotlin over Flutter/React Native | App is Android-only anyway (no cross-platform API for SMS/notification access on iOS); native gives smallest footprint and direct API access with no bridge overhead |
| 2026-08-15 | NotificationListenerService as primary, SMS receiver as fallback | Broader capture (covers UPI/banking app notifications, not just SMS) and a more established Play Store review path than raw SMS permission |
| 2026-08-15 | SQLCipher + Keystore over plain Room | Financial data must be encrypted at rest, not just access-controlled |
| 2026-08-15 | Discard raw message text after parsing | Single biggest privacy risk-reduction available; removes most of the value of a DB breach |
| 2026-08-15 | No backend / no INTERNET permission (yet) | Strongest privacy story available; also simplifies Play Store review |
| 2026-08-15 | Regex-based parsing over ML/NLP | Bank/UPI SMS formats are structured enough that regex is lighter, faster, fully explainable, and avoids shipping model weights |
| 2026-08-15 | Removed `allprojects { repositories {...} }` from root `build.gradle` | Conflicted with `settings.gradle`'s `dependencyResolutionManagement { repositoriesMode = FAIL_ON_PROJECT_REPOS }`, which requires repos to be declared only in settings.gradle. `buildscript { repositories {...} }` (for resolving the Gradle/AGP/Kotlin plugins) is unaffected and stays. |
| 2026-08-15 | Pinned `org.gradle.java.home` in `gradle.properties` to a JDK 17 path | Codespaces' default JDK was newer than Gradle 8.5 supports ("Unsupported class file major version 69" = Java 25). Relying on shell `JAVA_HOME` wasn't reliable across Gradle daemon restarts, so pinned explicitly in the project file instead. Path is environment-specific — update if your JDK 17 installs elsewhere. |
| 2026-08-15 | Temporary app icon: `@android:drawable/sym_def_app_icon` | No real icon assets exist yet, and `AndroidManifest.xml` referenced `@mipmap/ic_launcher` which didn't exist, failing `processDebugResources`. Swapped to a built-in system resource to unblock the build. Must be replaced with real branding before any release — tracked in Open Items. |
| 2026-08-15 | Added `androidx.lifecycle:lifecycle-runtime-compose:2.7.0` dependency | `MainActivity.kt` used `collectAsStateWithLifecycle`, which lives in this artifact, not in `lifecycle-runtime-ktx` (which was already present but insufficient). Missing import cascaded into "unresolved reference" errors on `Transaction` fields since the compiler couldn't infer `tx`'s type. |
| 2026-08-15 | Commented out `RECEIVE_SMS`/`READ_SMS` permissions + `SmsReceiver` registration (dev-mode only) | Play Protect fully blocked/canceled sideloaded APK installs requesting this permission pair. Primary capture path (`NotificationListenerService`) is unaffected and sufficient for pipeline testing. Must re-enable before any signed/release build — see ยง3.5. |
| 2026-08-16 | Added `kotlin-serialization` compiler plugin (root `build.gradle` classpath + `app/build.gradle` plugins block) | `kotlinx-serialization-json` runtime library alone is not enough — the compiler plugin is required to actually generate a serializer for `@Serializable` classes. Without it, `BankPatternsLoader.load()` threw `SerializationException: Serializer for class 'BankPatternConfig' is not found` at runtime, causing every `ParseAndStoreWorker` job to fail silently (visible only via `adb logcat`, not in the UI) — no transactions were ever reaching the DB despite notification access being correctly granted. |
| 2026-08-16 | `NotificationCaptureService` now uses notification **title** (not package name) as the sender-matching key | `bankOrSource`/pattern matching was comparing against `pkg` (e.g. `com.google.android.apps.messaging`), which never matches any `senderMatch` value like `HDFCBK`. The title field carries the actual SMS sender ID for default-messaging-app notifications. |
| 2026-08-16 | Rewrote `bank_patterns.json` regexes to handle keyword-before-amount phrasing (e.g. Kotak's `"Received Rs.1.00 ... from X"`), not just amount-before-keyword (`"Rs.500 debited ... to X"`) | Real captured sample (`JK-KOTAKB-S: "Received Rs.1.00 in your Kotak Bank AC 2863 from Mr GAURAV KUMAR on 16-08-26..."`) revealed our original assumption about word order was wrong for at least this bank. New regexes use optional non-capturing keyword groups on both sides of the amount so capture-group indices stay fixed regardless of which side matched. Verified against the real sample — see ยง6. Debit-side ordering is inferred/untested pending a real debit sample. |
| 2026-08-16 | Added `(?s)` DOTALL flag and made the counterparty clause fully optional in `bank_patterns.json` | Real HDFC sample was multi-line (`"Sent Rs.1.00\nFrom HDFC Bank...\nTo Mr GAURAV KUMAR"`) — `.` doesn't match `\n` by default, so the lazy `.*?` couldn't cross line breaks. Real Slice sample has no counterparty name at all (`"Received Rs. 1 on ... in your A/c ... via UPI"`), so the "from X" clause needed to become optional rather than mandatory. |
| 2026-08-16 | **Moved direction detection out of regex-match-order and into explicit keyword search** in `TransactionParser.parse()` | Making the counterparty clause optional (previous fix) had a side effect: `debitedRegex`'s "to X" clause could spuriously match credit messages too, since "to" is a generic preposition (e.g. real HDFC sample: `"Rs.1.00 credited **to** HDFC Bank A/c..."` — "to" here refers to the account, not a debit recipient). This caused a real credited transaction to be misclassified as SENT with merchant "Unknown". Fixed by determining `Direction` first via an explicit `\b(credited|received)\b` vs `\b(debited|sent|spent|withdrawn|paid)\b` keyword check, then running only the matching regex for extraction — debit and credit paths can no longer cross-match each other. Verified against all 5 real samples collected so far (Kotak×2, HDFC×2, Slice). |
| 2026-08-16 | Added `(` to the counterparty capture's stop-boundary lookahead | Real HDFC credit sample's counterparty is a VPA followed by `" (UPI ..."` — the `(` wasn't in the original stop-character set (`.`, `,`, `" on"`, end-of-string), so the 30-char-capped lazy capture ran out of room before finding a valid stop point and the whole optional clause failed to match, showing "Unknown" instead of the VPA. |
| 2026-08-16 | Widened stop-boundary to include `"via"` and `"is"`, and added `"payment"` as a SENT-direction keyword | Real Central Bank of India sample (`"...credited by Rs. 1.00 on ... via UPI from Mr GAURAV KUMAR           via Ref No..."`) had extra whitespace before a `"via"` clause the old boundary didn't recognize, so counterparty capture ran past its cap — same root cause as the HDFC `(` fix, different terminator word. Separately, a real Slice sample (`"UPI payment of Rs. 1 from a/c... to GAURAV KUMAR is successful..."`) contains no debited/sent/paid/spent/withdrawn keyword at all, only "payment", so it was being classified `UNKNOWN` outright — not a boundary issue, a missing direction signal. Both fixes verified together against all 5 previously-working real samples (no regressions) plus the 2 new ones. |
| 2026-08-16 | **Replaced `fallbackToDestructiveMigration()` with an explicit `Migration(1, 2)`** | User reported losing all transaction data on every app update, and traced it to two compounding causes: (1) using `adb uninstall` + `install` instead of `adb install -r`, which wipes app-private storage regardless of any DB-level fix; (2) `fallbackToDestructiveMigration()` meant *any* schema version bump — even via `-r` reinstall — dropped and recreated the entire database instead of upgrading it in place, which is what actually happened during the categories/notes/reminders update. Fixed by writing a real migration (`ALTER TABLE ... ADD COLUMN` + `CREATE TABLE reminders`) and removing the destructive fallback entirely. Past data lost before this fix is **not recoverable** — `allowBackup="false"` and Keystore-tied encryption mean there was never a copy anywhere outside the wiped app storage. Going forward, a missing migration now throws a crash during development rather than silently deleting data — a deliberate tradeoff (see ยง2.8). |
| 2026-08-16 | Added temporary `BuildConfig.DEBUG`-gated raw-text log in `NotificationCaptureService` | Needed to see real message formats to fix regex patterns, since our design intentionally never persists raw text. Gated so it can never ship in a release build (`if (BuildConfig.DEBUG)`); required adding `buildConfig true` to `buildFeatures`. Must be removed once parser accuracy is validated across more banks — tracked in Open Items. |
| 2026-08-18 | **Permanently removed `RECEIVE_SMS`/`READ_SMS` and `SmsReceiver`** (superseding the earlier "temporarily disabled for dev" state) | Web search confirmed Google's SMS and Call Log Permissions policy restricts these to default SMS/Phone/Assistant handler apps, and flags even inert/commented manifest declarations during review. An expense tracker cannot legitimately declare these. `NotificationListenerService` had already been validated as sufficient across every real message sample collected (7+ formats, 4 banks) — this change costs no functionality and removes a real Play Store rejection risk. See ยง2.12. |
| 2026-08-18 | Manual ("Cash") transactions get full edit (amount/direction/merchant) + delete; auto-captured transactions get category/note/tags-only edit + delete | User requested update/delete for manually-added spend entries. Restricted full-field editing to manual entries specifically — allowing arbitrary amount/direction edits on a real captured bank message would let the displayed data silently diverge from what the bank actually reported, undermining the app's core trust proposition. Delete is available on both types since removing a wrongly-parsed or unwanted row is a different, safer operation than editing its financial facts. |
| 2026-08-18 | Balance parsing implemented as one bank-agnostic regex rather than per-bank `bank_patterns.json` entries | Both real samples with balance info (ECS-style, Slice) use the same "Avl Bal"/"Avl. Bal." convention despite being different senders — a shared convention across banks, unlike the amount/direction wording which does vary by bank. Applying one regex to every message avoids duplicating it across every `bank_patterns.json` entry for no accuracy benefit. |
| 2026-08-18 | Recurring-suggestion dismissals stored in SharedPreferences, not a new Room table | Deliberately lightweight for a feature this minor — avoids another schema migration for data that's inherently low-stakes (a dismissed-suggestion list) and where the underlying merchant names are already visible elsewhere in the app's unencrypted UI, so this isn't a new privacy exposure. |
| 2026-08-18 | Unusual-spend threshold set to 2.5× category average with a minimum 3-transaction history gate | Mean-based and simple by design (no ML), consistent with the regex-based parser and Canvas-based charts elsewhere in the project. The minimum-history gate specifically prevents every category's first-ever transaction from being flagged as "unusual" relative to an undefined baseline. Thresholds are arbitrary starting points, not tuned against real usage data yet — may need adjustment once there's a realistic transaction volume to observe false-positive/negative rates against. |
| 2026-09-02 | Fixed cross-source duplicate insertion via a single `@Transaction` DAO method instead of a coroutine `Mutex` or app-level lock | Room already serializes concurrent `@Transaction` suspend-function callers through its internal transaction executor — no new locking primitive needed. Simpler and avoids a whole class of "forgot to acquire the lock at some other call site" bugs a hand-rolled `Mutex` would risk. |
| 2026-09-02 | Search/Filter/Sort/Needs-Review moved to a dedicated 5th nav tab rather than a top-app-bar overflow menu or bottom sheet | A persistent nav tab keeps the review-count badge always visible (parity with the previous always-visible banner), and frees the Transactions tab's top region for the banner ad without scroll-dependent show/hide logic for the search chrome. |
| 2026-09-02 | Home screen scoped to current month by filtering the existing in-memory `allTransactions` list via `MonthRange`, rather than adding a new DB-level range query | Keeps the change low-risk — `Charts`/`Budgets`/`Reminders` already depend on the same fully-loaded `allTransactions` Flow, so introducing a second, differently-scoped Flow risked subtle inconsistencies for a first pass. Revisit as a DB query (with a supporting index) if data volume makes the client-side filter a measured problem — tracked in Open Items. |
| 2026-09-02 | `MonthRange` built on `java.util.Calendar`, not `java.time.YearMonth` | minSdk 24 with no `coreLibraryDesugaring` configured in `app/build.gradle` means `java.time` isn't safely available on every supported device; using it would have been a build/runtime-compatibility mistake. Matches the `Calendar`-based logic already in `filterTransactions()`. |
| 2026-09-02 | `MonthRange` implements `Serializable` | Needed for `rememberSaveable` on `MonthlyHistoryScreen`'s selected-month state — a plain Kotlin data class isn't saveable across process death/config change without this, and would throw at runtime the first time Compose tried to save it. |
| 2026-09-02 | Monthly-vs-yearly summary preference stored in `SharedPreferences` (new `SummaryPeriodStore`), not a new Room column/table | Same reasoning as the 2026-08-18 dismissed-recurring-suggestions decision — a single low-stakes UI preference doesn't justify a schema migration. |
| 2026-09-02 | Backup & Restore + CSV Export fully hidden (not grayed-out-with-upsell) for free-tier users | Matches explicit product direction this session. Tracked as a reconsiderable tradeoff in Open Items, since it trades a conversion-nudge opportunity for simplicity/literalness. |
| 2026-09-03 | Restored tap-to-filter on Sent/Received as a local per-screen state, rather than routing taps to the Search overlay | Real usage showed people relied on the quick in-place toggle on the home screen itself; sending them elsewhere for something that used to be a single tap would have been a regression disguised as a simplification. Kept deliberately separate from `SearchReviewScreen`'s own filter state (no NEEDS_REVIEW option, doesn't persist as a saved search) since the two serve different purposes — quick glance vs. deliberate search. |
| 2026-09-03 | Restored a second banner ad at the bottom of `TransactionsScreen`, in addition to the new top placement | Moving one ad to the top to use the freed-up space was the actual ask; dropping the bottom placement entirely was an unrequested side effect of that change. The month-scoped list is short enough now that both fit without crowding the actual transaction data. |
| 2026-09-03 | Reworked Search from a 5th bottom-nav tab into a top-bar-icon-triggered overlay | A nav tab is a destination that replaces the current screen; search is a transient action people expect to dismiss back into whatever they were looking at (typically the current-month Transactions list). Reusing the existing `showSettings`/`showMonthlyHistory` overlay pattern kept the change small and consistent, and incidentally un-crowds the bottom nav back to 4 items. |
| 2026-09-04 | First-launch onboarding built as a fixed 3-page flow with a plain enum + Next buttons, no pager library | Consistent with the project's recurring preference for plain Compose over an unfamiliar library's exact API (same reasoning as the Canvas-based Charts screen over Vico) — three fixed pages don't need a pager's swipe/animation machinery. |
| 2026-09-04 | Onboarding tracks "has seen the disclosure," not "has granted the permission" | Forcing the full 3-page flow again on every launch until the user grants notification access would be worse than the existing `OnboardingBanner` reminder it falls back to after a "Skip for now." The disclosure only needs to happen once; the reminder can be ongoing and lighter-weight. |
| 2026-09-04 | Battery-settings onboarding step opens App Info, not a direct `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog | The direct exemption intent requires declaring `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the manifest, itself a Play Console-restricted permission needing a core-functionality justification. Battery exemption is a reliability nice-to-have here, not core functionality, so it wouldn't clear that bar — opening the plain App Info screen achieves the same end for a motivated user with zero manifest/Play-declaration risk. |
| 2026-09-04 | Compose BOM bumped to 2026.04.01, not the newest available (2026.08.00) | The newer BOM requires compileSdk 37/AGP 9.1.1+, beyond what Play's actual deadline requires (API 36). Matching the exact requirement rather than chasing the latest BOM avoided an unnecessary, unrequested second SDK-level jump bundled into the same change. |
| 2026-09-04 | `androidx.fragment:fragment-ktx` left unbumped during the AGP 9.1.0/API 36 migration | This app only uses `FragmentActivity` for `BiometricPrompt` compatibility, not fragment transactions — it isn't in the predictive-back-critical path the way `activity-compose` is, so bumping it preemptively without a specific reason would have been an unnecessary extra variable in an already large toolchain change. |
| 2026-09-04 | Account balances hidden by default (`BalanceVisibilityStore`), revealed via an explicit eye-icon tap, rather than always shown | A bank/account balance is a "how much money do I have in total" figure, materially more sensitive than any single transaction amount — masking by default protects against casual shoulder-surfing without requiring a settings trip to turn the feature off entirely. |

---

## 9. How to Update This Doc

Every PR/commit that changes architecture, adds a dependency, changes a
permission, or resolves/adds an Open Item should update the relevant section
above **in the same commit**. Treat this file as the single source of truth
above code comments — code comments should point back here (`see
REQUIREMENTS.md ยงX`) rather than duplicating the reasoning.

---

## 10. Play Store Submission Readiness (added 2026-09-04)

Researched live (web search) rather than from training memory, since Play
policy changes frequently and this app's history already includes one
policy-driven rewrite (the SMS-permission removal, ยง2.12). Current as of
**2026-09-04** — re-verify anything time-sensitive before actually
submitting, especially the dated items below.

### 10.1 Blockers — must resolve before this app can go live

- [ ] **Target API level is behind the current requirement.** As of
      **August 31, 2026** (already past as of this writing), Google Play
      requires *new* app submissions to target **Android 16 (API level
      36)**. This project currently targets **API 35** (ยง5 Dependency
      Manifest / build.gradle). A one-time extension to **November 1,
      2026** can be requested in Play Console if needed, but the app
      cannot be submitted as-is. Bumping `compileSdk`/`targetSdk` to 36
      needs **AGP โ‰ฅ 8.9.0** (current: 8.5.2) and very likely a matching
      Gradle bump (current: 8.7) — this is realistically **another
      coordinated dependency bump**, the same category of work as the
      Kotlin 2.3.20/Room 2.8.4/AGP 8.5.2 bump already done for Play
      Billing/AdMob. Budget real time for this; don't treat it as a
      one-line manifest edit. Test a full click-through after, same as
      last time.
- [ ] **Closed testing requirement for new personal developer accounts.**
      If the Play Console developer account used to publish this app is a
      **personal account created after November 13, 2023**, Google
      requires a closed test with **at least 12 testers opted in
      continuously for 14 days** before production access can even be
      *applied for* — this is a hard gate with no way around it short of
      using an organization account (which requires a legal business
      entity + D-U-N-S number and is exempt). This is a **timeline
      blocker, not a technical one**: budget at least 2 weeks, plus time
      to actually recruit 12 people who will open the app repeatedly
      during that window — Google's 2026 enforcement reportedly checks
      genuine engagement, not just opt-in count. Start this track early;
      it can run in parallel with other prep, not after it.
- [ ] **Release signing keystore + a tested release build** — per
      existing Open Items, only ever built debug so far. A release build
      with R8/ProGuard can behave differently (obfuscation breaking
      reflection-based libraries, resource shrinking removing something
      used only via reflection/name lookup) — test thoroughly, don't
      assume "debug worked" implies "release works."
- [ ] **Privacy Policy page** — mandatory, must be a real hosted URL (not
      a placeholder), and its content must **match the Data Safety form
      answers exactly**. Per current guidance, mismatches between the two
      are a common rejection/enforcement trigger — generate both from the
      same source facts rather than writing them independently.
- [ ] **Data Safety form** — must be completed accurately for every data
      category the app (and its SDKs) actually touch. For this app that
      includes: financial transaction data (on-device only, disclose that
      clearly), advertising/device identifiers (via AdMob), purchase
      history (via Play Billing). Google's stated enforcement approach
      cross-references declared answers against what the shipped APK
      actually does, including bundled SDK behavior — this is not a
      box-ticking formality with low consequences for getting it wrong.
- [ ] **AdMob real App ID + ad unit IDs** — currently test IDs (ยง2.18);
      shipping test IDs means the app publishes fine but earns nothing.
      Not a rejection risk, but effectively a launch blocker for the
      business reason the ads exist at all.
- [ ] **Play Billing subscription products** must exist in Play Console
      with the exact IDs already referenced in code
      (`expense_tracker_pro_monthly`, `expense_tracker_pro_yearly`), and
      the app needs to reach at least Internal Testing with a License
      Tester account added before Billing can be exercised at all (ยง2.17).

### 10.2 Researched and corrected: Notification Listener does *not* need the SMS-style Permissions Declaration Form

REQUIREMENTS.md's Open Items previously assumed a "Permissions Declaration
Form for Notification Listener access" would be needed (ยง7, pre-existing
item). Based on current Play Console documentation, **this appears to be
incorrect** — the Permissions Declaration Form process is specifically
enumerated for a fixed set of permissions: SMS/Call Log, Accessibility
Service, `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, background
location, exact alarms, full-screen intent, and (from 2027) broad Contacts
access. `BIND_NOTIFICATION_LISTENER_SERVICE` — the permission this app's
`NotificationCaptureService` declares — was not found in that enumerated
list in current documentation, and real published apps use this exact
pattern (a Kotlin/Flutter expense-tracker reading bank notification alerts
via `NotificationListenerService`, and a Wear OS companion app doing the
same for a specific bank, both found during this research) without it
being described as requiring that form.

**This is not a green light to be careless.** Notification access is
separately flagged by Google Play Protect as one of four permissions
"frequently abused for financial fraud" (alongside `RECEIVE_SMS`,
`READ_SMS`, `ACCESSIBILITY`) for apps installed via sideloading — this
shouldn't trigger for users who install through Play normally, but
reinforces that Play's manual/automated review will likely scrutinize
*what the notification listener actually does with the data* under the
general **User Data / Personal and Sensitive Information policy** —
prominent disclosure before access, ability to see what's collected, and
accurate Data Safety answers — rather than the SMS-specific declaration
form. The onboarding flow built this session (ยง2.22) and the existing
data-minimization design (raw text discarded immediately, ยง2.2/ยง3) are
exactly the substance of what that policy area is checking for; there's
no separate form-filling step analogous to the SMS one identified.

**Caveat**: Play policy is enforced partly through automated matching and
partly through discretionary human review, and enforcement has reportedly
tightened over time for exactly this class of permission (financial apps
reading device-wide signals). Budget for the possibility that Play's
review team asks a clarifying question specifically about notification
scope even without a formal declaration form — the onboarding disclosure
and this file's own data-minimization history are the evidence to point to
if that happens. Re-check the current Permissions Declaration Form list in
Play Console directly before submitting, since this was researched, not
assumed, but policy pages do change.

### 10.3 Standard submission checklist (not currently blockers, but not yet done)

- [ ] Play Console developer account registration (one-time $25 fee) if
      not already done, plus identity verification (government ID for
      personal accounts; D-U-N-S number for organization accounts) —
      Google has been rolling out mandatory identity verification for new
      developer accounts.
- [ ] Store listing assets: app icon (512×512 hi-res, plus the real
      adaptive icon already shipped per ยง Decision Log 2026-08-15/16),
      feature graphic (1024×500), at least 2 phone screenshots, short
      description (โ‰ค80 chars), full description (โ‰ค4000 chars).
- [ ] Content rating questionnaire (IARC) — straightforward for a finance
      utility app with no user-generated content, but still required.
- [ ] Target audience & content declaration — this app is not
      child-directed; declare accordingly, and note the app is not itself
      a regulated financial product (it doesn't move money, lend, or give
      financial advice) — worth stating that explicitly in the store
      listing per current guidance that financial-category apps face
      extra scrutiny on any language that could read as investment/credit
      claims.
- [ ] Ads declaration (the app shows ads — Play Console has a direct
      yes/no toggle plus a "contains ads" store-listing badge).
- [ ] Account deletion — Play policy requires apps that support account
      creation to offer in-app account *and data* deletion; this app has
      no account/login concept, but "Delete all data" already exists
      (ยง2.13) — confirm during Data Safety form completion whether this
      needs to be declared as satisfying that requirement or whether it's
      out of scope since there's no account system at all.

### 10.4 Release signing — what actually got wired up this session

`app/build.gradle` previously had a `buildTypes.release` block
(minification/shrinking) but **no `signingConfig` at all** — a
`bundleRelease`/`assembleRelease` build would have produced an unsigned
artifact. This session added:

- A `signingConfigs.release` block that reads from a **local, git-ignored
  `keystore.properties`** file (never the committed `build.gradle` itself)
  — `keystore.properties.example` at the repo root shows the format.
- If `keystore.properties` doesn't exist (fresh clone, CI without secrets
  configured), the release build type simply has no signing config.
  `bundleRelease` will still **succeed** but produce an **unsigned**
  `.aab` — Gradle doesn't hard-fail at build time for this, the failure
  only shows up later when you try to `adb install` it (rejected, no
  signature) or upload it to Play Console (rejected on upload). Don't
  mistake a clean build for a ready-to-upload one without
  `keystore.properties` actually present and correct.
- `.gitignore` updated to exclude `keystore.properties` alongside the
  already-ignored `*.jks`/`*.keystore`.

**Still a manual step, deliberately not automated**: actually generating
the keystore. This can't be done inside this repo/session — it's a secret
only you should generate and hold. One-time, from a terminal with a JDK
available:

```bash
keytool -genkeypair -v -keystore release-upload-key.jks \
  -alias expense-tracker-upload -keyalg RSA -keysize 2048 -validity 10000
```

Answers the prompts interactively (name, org, password — pick a strong
password and **store it somewhere durable**; losing this file or its
password with no backup means losing the ability to update this app on
Play ever again, since Google can't recover it for you). Place the
resulting `.jks` file outside the repo (or anywhere already covered by
`.gitignore`) and fill in `keystore.properties` from the `.example`
template to point at it.

**Play App Signing (recommended, not yet opted into — a Play Console
action, not a code change)**: when you create the app in Play Console and
upload your first release, opt into Play App Signing. Under this model,
the keystore above becomes your **upload key** only — Google holds a
separate **app signing key** and re-signs the distribution APKs it
generates from your uploaded AAB with that key instead. Benefit: if the
upload key is ever lost or compromised, Google can help you rotate to a
new one without losing the app, which isn't possible if you manage the
one-and-only signing key yourself outside this program.

### 10.5 What you actually upload / fill in, concretely

Two different kinds of "submission artifact" — worth being clear on which
is which:

**Files you upload:**
- The **signed `.aab`** (Android App Bundle) — **not** an `.apk`. Google
  has required AAB for all new app submissions since August 2021; a plain
  APK upload isn't accepted for a first submission at all. Build via
  `./gradlew bundleRelease` (parallel to the existing
  `./gradlew assembleDebug` used throughout this project so far) — output
  lands at `app/build/outputs/bundle/release/app-release.aab` once
  release signing (ยง10.4) is in place. This is what actually goes into
  each testing track / production release in Play Console.
- Store listing images: hi-res app icon (512×512 PNG), feature graphic
  (1024×500), at least 2 phone screenshots (per ยง10.3) — uploaded
  directly as image files in the Play Console store listing page, not
  part of the AAB.

**Information you fill in (Play Console web forms, nothing to "upload"
as a file for these):**
- Store listing text: app name, short description (โ‰ค80 chars), full
  description (โ‰ค4000 chars).
- Content rating questionnaire (IARC) answers.
- Target audience & content declaration.
- Ads declaration (yes, this app shows ads).
- Data Safety form answers.
- Privacy Policy **URL** (the policy itself is a hosted web page you
  control, e.g. on GitHub Pages or your own domain — Play Console only
  needs the link, not a file upload).

**Sequencing note**: the closed-testing requirement (ยง10.1) means your
first `.aab` upload realistically goes to an **Internal Testing** or
**Closed Testing** track, not Production directly — Play Console won't
let a new personal-account app reach Production until the 12-tester/
14-day closed test is satisfied. Build and upload early to that track
once you have a signed release build, rather than waiting until
everything else on this checklist is also done — the testing clock only
starts once real testers are opted in and using it.

### 10.7 Target API 36 / AGP 9.1.0 bump (2026-09-04)

Real Play Console feedback (a first Internal Testing upload) confirmed
what ยง10.1 flagged: Internal/Closed Testing tracks are **not** blocked by
the target-API-35 error — only Production publishing is. That upload also
surfaced a separate, more urgent issue (SQLCipher's 16 KB page size
incompatibility — see ยง10.6 once that fix lands) that got prioritized
first. This section is the deferred API 36 work itself.

**Versions chosen, and why** (cross-referenced against official sources
this session, not assumed):

| Component | Before | After | Why this exact version |
|---|---|---|---|
| AGP | 8.5.2 | **9.1.0** | AGP 9.0.0 was the first version to support compileSdk 36 at all; 9.1.0 is the documented stable AGP/Gradle pairing per developer.android.com's own compatibility table |
| Gradle | 8.7 | **9.1.0** | AGP 9.x has a hard requirement on Gradle 9.x — not optional, confirmed in AGP's own release notes |
| compileSdk / targetSdk | 35 | **36** | Exactly what Play's Aug 31, 2026 deadline requires — deliberately not 37 |
| Compose BOM | 2024.02.00 | **2026.04.01** | The last BOM still compileSdk-36-safe. The next one (2026.08.00, Compose 1.12) requires compileSdk **37** and AGP 9.1.1+ — chasing the newest BOM would have silently dragged this project into a higher, not-yet-required SDK target as a side effect |
| androidx.activity / activity-compose | 1.8.2 | **1.13.0** | Needed for correct predictive-back integration under API 36 (see below) |
| androidx.fragment:fragment-ktx | 1.6.2 | **unchanged** | Deliberately left alone — this app only uses `FragmentActivity` for `BiometricPrompt` compatibility, not fragment transactions, so it isn't in the API-36/predictive-back-critical path the way `activity-compose` is. Bump later only if a real build actually demands it. |
| Kotlin | 2.3.20 | **unchanged** | AGP 9.1.0 bundles Kotlin Gradle Plugin 2.2.10 by default, but this project already pins its own newer explicit version (2.3.20) via `buildscript.dependencies`, which should be fine as an override |

**Two Android 16 behavior changes worth knowing about, not just the SDK
number bump**:

1. **Predictive back / `onBackPressed()` removal** — apps targeting API 36
   no longer receive `Activity.onBackPressed()` or `KEYCODE_BACK` at all.
   This app never overrode `onBackPressed()` — it already used
   `androidx.activity.compose.BackHandler` throughout (built on
   `OnBackPressedDispatcher`, the mechanism predictive back actually
   integrates with), so the app's own back-handling code needed no
   changes. Added `android:enableOnBackInvokedCallback="true"` to
   `AndroidManifest.xml`'s `<application>` tag, which is what actually
   opts the app into the system's predictive-back animation/dispatch path.
2. **Edge-to-edge enforcement** — apps targeting Android 15+ can no longer
   opt out of edge-to-edge display; the system draws behind the status/
   navigation bars unconditionally. This app's `Scaffold`-based screens
   generally handle their own insets via Material3's defaults, but this
   has **not been visually verified** — a real device/emulator click-
   through (same as the practice already established after the Play
   Billing/AdMob dependency bump) needs to specifically check that no
   content is obscured behind system bars on every screen, not just that
   the app builds and launches.

**What was NOT verified, and can't be from this environment**: none of
this was compiled. This sandbox has no Android SDK/Gradle network access
— every version number above was cross-checked against multiple official/
authoritative sources (developer.android.com's AGP compatibility table,
the Jetpack Compose release blog, androidx release notes) rather than
recalled from training data, but that is not a substitute for an actual
`./gradlew assembleDebug` run. This is a bigger-than-usual toolchain
change (AGP major version, Gradle major version, Compose BOM two years
newer) — budget real time for a full click-through afterward, the same
way the Kotlin 2.3.20/Room 2.8.4/AGP 8.5.2 bump was handled originally.

**Also needed, not a code change**: the committed `gradle-wrapper.jar`
binary must match the new `distributionUrl`. Per this project's own setup
notes (ยง4), the wrapper jar isn't hand-generated — regenerate it with a
locally installed Gradle โ‰ฅ9.1: `gradle wrapper --gradle-version 9.1.0`,
then commit the updated `gradle/wrapper/gradle-wrapper.jar` alongside this
change. Android Studio can also do this automatically if it detects the
version mismatch when the project is opened.

### 10.8 Account balance visibility toggle (2026-09-04)

Not a Play Store requirement — a privacy improvement requested directly.
The "Account balances" section on `ChartsScreen` (ยง2.11) shows the latest
parsed bank/account balance per source, which is more sensitive than any
single transaction amount (a total-money-available figure, not one
purchase). New `BalanceVisibilityStore` (plain `SharedPreferences`,
matching the existing preference-store pattern) defaults balances to
**hidden** — masked as `₹••••••` — with an eye icon next to the section
header to reveal/hide them, persisted across sessions. Scoped specifically
to this summary section; the separate "Balance after: ₹X" line shown per
transaction in `TransactionDetailDialog` (a historical record tied to one
specific message, not a live "how much money do I have" figure) was left
as-is — a reasonable follow-up if the same masking is wanted there too,
but not assumed to be the same request.

To download the app
python3 -m http.server 8000 --directory app/build/outputs/apk/debug

adb shell pidof -s com.autoexpensetracker
adb logcat --pid=<new_pid>


adb install -r app-debug.apk

To download the app
python3 -m http.server 8000 --directory app/build/outputs/apk/debug

adb shell pidof -s com.autoexpensetracker
adb logcat --pid=<new_pid>


adb install -r app-debug.apk