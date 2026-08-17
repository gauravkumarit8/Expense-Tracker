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
| Fallback | `BroadcastReceiver` on `SMS_RECEIVED` | `RECEIVE_SMS`, `READ_SMS` | Catches SMS that never surface as a notification on some OEMs |

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
| Custom categories + auto-categorization | ✅ Have it (2026-08-16) |
| Manual cash-expense entry | ❌ Not yet — important gap, cash never generates an SMS |
| Charts/spending trends | ❌ Not yet — Vico dependency already added, unused |
| Budget limits per category | ❌ Not yet |
| Bill/subscription reminders | ✅ Have it (2026-08-16) — manual entry only, no auto-detection of recurring merchants yet |
| Notes/tags on transactions | ✅ Have it (2026-08-16) |
| Export to CSV/PDF | ❌ Not yet |
| App lock (biometric/PIN) | ❌ Not yet — already tracked as an Open Item |
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

---

## 3. Security & Privacy (see prior design discussion — this is the source of truth)

1. **Encryption at rest**: SQLCipher DB, Keystore-backed passphrase, never hardcoded/logged.
2. **Data minimization**: raw SMS/notification text discarded immediately after parsing; only structured fields persisted.
3. **No network transmission**: app currently has no `INTERNET` permission at all. `network_security_config.xml` blocks cleartext by default in case this changes.
4. **Permission discipline**: only `RECEIVE_SMS`, `READ_SMS`, notification-listener access, and battery-optimization-exemption request. No contacts/location/storage permissions.
5. **Backup exclusion**: `allowBackup="false"` + `data_extraction_rules.xml` explicitly excludes the DB file even if backup is ever re-enabled.
6. **ReDoS safety**: all regex evaluation wrapped in a timeout (see ยง2.2).
7. **App-level lock**: biometric/PIN lock via `BiometricPrompt` — **planned, not yet implemented** (see Open Items).
8. **Transparency**: in-app "what we read / what we store" disclosure screen and a delete-all-data action — **planned, not yet implemented**.

---

## 3.5 Dev Mode: SMS Fallback Temporarily Disabled

**Status as of 2026-08-15: `RECEIVE_SMS`/`READ_SMS` permissions and the
`SmsReceiver` registration are commented out in `AndroidManifest.xml`.**

**Why**: Google Play Protect blocks/cancels sideloaded (non-Play-Store) APK
installs that request `RECEIVE_SMS`/`READ_SMS`, since this exact permission
pair matches banking-fraud malware behavior patterns (see ยง3 threat model —
the same reasoning that makes us cautious about this permission in the first
place is what's now triggering the block during local dev testing). This
made it impossible to sideload a debug build onto a real phone for testing.

**What still works with this disabled**: the entire primary capture path —
`NotificationCaptureService` → `ParseAndStoreWorker` → encrypted Room DB →
UI — is untouched. This is sufficient to test the full pipeline end-to-end
for any bank/UPI app whose alerts arrive as Android notifications (which is
most of them). Only the narrower fallback case (SMS that never surfaces as a
notification on some OEM/dual-SIM setups) is untestable right now.

**How to re-enable**: uncomment the two `<uses-permission>` lines and the
`<receiver>` block in `AndroidManifest.xml` (both are clearly marked). Do
this before:
- Building a signed release build (Play Protect's sideload heuristic doesn't
  apply to properly signed + Play-Store-distributed apps)
- Submitting to Play Console for review

**Open item**: re-enable and re-test the SMS fallback path before any real
release. Tracked in ยง7 Open Items below.



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

### Build & run commands
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
- [ ] Biometric/PIN app lock (`BiometricPrompt`) + auto-lock on background
- [ ] "Needs review" queue UI for low-confidence parses (`needsReview = true`)
- [ ] Manual transaction entry + edit/correct flow
- [ ] Categorization (manual + rule-based auto-categorization)
- [ ] Monthly summary / charts screen (Vico)
- [ ] Delete-all-data action in Settings
- [ ] In-app privacy/data disclosure screen
- [ ] Play Console Data Safety form draft
- [ ] Play Console permissions declaration justification (for RECEIVE_SMS/READ_SMS)
- [ ] Decide: bundle vs remote-config for `bank_patterns.json`
- [ ] Gradle wrapper generation + commit (see ยง4 note)
- [ ] Unit tests for `TransactionParser` per bank pattern
- [ ] OEM-specific "allow autostart" guidance screen for MIUI/ColorOS/FuntouchOS etc.
- [ ] App icon / branding assets — currently using `@android:drawable/sym_def_app_icon` (a built-in system placeholder) so the build isn't blocked. Replace with real `mipmap-*dpi` assets + adaptive icon before any real device testing/release; see Decision Log 2026-08-15.
- [ ] Handle multi-SIM / dual-SIM sender variations
- [ ] **Remove temporary `BuildConfig.DEBUG` raw-text logging** in `NotificationCaptureService` once parser accuracy is validated across more real bank/UPI samples — see ยง8 Decision Log 2026-08-16
- [ ] **Replace `fallbackToDestructiveMigration()` with a real Room `Migration`** before any release build — currently wipes all local data on every schema version bump (see ยง2.8)
- [ ] Auto-detect recurring merchants/amounts to suggest reminders automatically, rather than requiring fully manual entry
- [ ] Verify debited-side (money sent) regex against a real sample — only the credited-side has been confirmed against real data so far
- [ ] Test additional banks/UPI apps beyond Kotak as real samples become available

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
| 2026-08-16 | Added temporary `BuildConfig.DEBUG`-gated raw-text log in `NotificationCaptureService` | Needed to see real message formats to fix regex patterns, since our design intentionally never persists raw text. Gated so it can never ship in a release build (`if (BuildConfig.DEBUG)`); required adding `buildConfig true` to `buildFeatures`. Must be removed once parser accuracy is validated across more banks — tracked in Open Items. |

---

## 9. How to Update This Doc

Every PR/commit that changes architecture, adds a dependency, changes a
permission, or resolves/adds an Open Item should update the relevant section
above **in the same commit**. Treat this file as the single source of truth
above code comments — code comments should point back here (`see
REQUIREMENTS.md ยงX`) rather than duplicating the reasoning.