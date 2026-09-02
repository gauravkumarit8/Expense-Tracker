# Expense Tracker — Requirements & Implementation Log

Living document. Every architectural decision, tradeoff, and open question
goes here as it's made — update this file in the same commit as the code
change it describes.

Last updated: 2026-08-15

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

- [ ] Onboarding flow: explain permissions before requesting them, deep-link to `ACTION_NOTIFICATION_LISTENER_SETTINGS`, request battery-optimization exemption
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
- [ ] Get Play Console's Permissions Declaration Form ready for Notification Listener access (justification + demo video) before submission
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

---

## 9. How to Update This Doc

Every PR/commit that changes architecture, adds a dependency, changes a
permission, or resolves/adds an Open Item should update the relevant section
above **in the same commit**. Treat this file as the single source of truth
above code comments — code comments should point back here (`see
REQUIREMENTS.md ยงX`) rather than duplicating the reasoning.

To download the app
python3 -m http.server 8000 --directory app/build/outputs/apk/debug

adb shell pidof -s com.expensetracker
adb logcat --pid=<new_pid>


adb install -r app-debug.apk