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

## 4. Dev Environment Setup (GitHub Codespaces)

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
- HDFC Bank
- ICICI Bank
- SBI
- Generic UPI fallback pattern

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
- [ ] App icon / branding assets (currently using default mipmap reference, not provided)
- [ ] Handle multi-SIM / dual-SIM sender variations
- [ ] CI: GitHub Actions workflow to run `./gradlew test lint assembleDebug` on push

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

---

## 9. How to Update This Doc

Every PR/commit that changes architecture, adds a dependency, changes a
permission, or resolves/adds an Open Item should update the relevant section
above **in the same commit**. Treat this file as the single source of truth
above code comments — code comments should point back here (`see
REQUIREMENTS.md ยงX`) rather than duplicating the reasoning.
