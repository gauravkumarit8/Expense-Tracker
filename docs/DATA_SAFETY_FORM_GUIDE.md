# Play Console Data Safety Form — Answer Key

This exists for one reason: `REQUIREMENTS.md` §10.1 flags mismatches
between the Privacy Policy and the Data Safety form as **the most common
rejection/enforcement trigger** for exactly this kind of app. Both now
come from this one set of facts. If you ever change what the app collects
or which SDK does what, update this file, then re-check the policy and
the actual Play Console form against it — in that order.

**Not legal advice** — this is a best-effort mapping to Google Play's
current public Data Safety documentation as of September 2026, based on
the app's actual manifest, dependencies, and code. Play Console's exact
question wording and category list can change; treat this as a drafting
aid to fill the real form faster and more consistently, not a substitute
for reading the live form yourself before submitting.

---

## Does your app collect or share any of the required user data types?

**Yes** — three categories apply. Everything else in Play Console's list
(Location, Personal info, Messages, Photos/Videos, Audio, Contacts,
Calendar, App activity beyond what's below, App info and performance,
Web browsing) should be answered **"No data collected"** — this app has
no analytics SDK, no crash reporter, no location access, and reads
notification *content* only for financial alerts (see the Financial info
row below for how to describe that).

### 1. Financial info → "Purchase history"
| Field | Answer |
|---|---|
| Collected? | Yes |
| Shared with third parties? | No |
| Processed ephemerally? | No |
| Optional or required? | Optional — only applies if the user starts a Pro subscription |
| Purpose(s) | App functionality (managing the subscription) |
| Data handled by | Google Play Billing — the app receives only a subscription status (active/expired/which tier), never card numbers, UPI IDs, or other payment instrument details |

### 2. Financial info → "Other financial info" (the actual transaction data — amount, direction, merchant, category, balance)
| Field | Answer |
|---|---|
| Collected? | Yes |
| Shared with third parties? | **No** |
| Processed ephemerally? | No — stored persistently, but only on-device |
| Optional or required? | Required for the app's core function |
| Purpose(s) | App functionality only |
| Data handled by | The app itself, on-device, encrypted (SQLCipher). No backend server exists to send this to. |

**This is the row most worth getting exactly right.** Say "not shared" here
and mean it — if any future SDK you add ever transmits transaction data
anywhere, this answer and the policy both need updating before that build
ships, not after.

### 3. Device or other IDs → "Advertising ID"
| Field | Answer |
|---|---|
| Collected? | Yes |
| Shared with third parties? | Yes — with Google (AdMob) for ad serving |
| Processed ephemerally? | Depends on Google's own AdMob declaration — check AdMob's current Data Safety guidance in Play Console when filling this specific sub-field, it's pre-filled/suggested based on your linked AdMob account in most cases |
| Optional or required? | Optional — only present for free-tier (non-Pro) users; Pro subscribers see no ads and this stops applying to them going forward |
| Purpose(s) | Advertising or marketing |
| Data handled by | Google AdMob SDK |

## Security practices section

| Question | Answer |
|---|---|
| Is data encrypted in transit? | Not applicable for financial data (never transmitted). For the Advertising ID / purchase-status traffic to Google, that goes over Google's own encrypted (HTTPS) connections — answer **Yes** if the form asks about the app's traffic in general rather than per-data-type. |
| Can users request data deletion? | **Yes** — in-app, immediately, via Settings → Delete All Data. There is no account/server-side data to separately request deletion of, since none exists. |
| Does your app comply with Play's Families Policy? | Not applicable — this app is not directed at children and is not part of Play's Families program. |

## Independent security review

Answer **No** unless you've actually commissioned one — don't check this
box on the assumption that SQLCipher + Android Keystore counts as a
third-party review of *your* app; it doesn't. It's a true statement about
the encryption library, not an audit of this codebase.

## Committed to following Play's Families Policy?

**No** — this app targets general adult users (personal finance), not
children, and isn't designed or marketed for the Families program.

---

## Cross-check before you submit

1. Every "shared with third parties: Yes" answer above should trace to an
   actual SDK in `app/build.gradle` (AdMob, Play Billing) — if the form
   ever shows a data type you don't recognize collecting, that's usually a
   transitive SDK behavior worth investigating before answering blindly.
2. Re-read `docs/privacy-policy.html` after filling the real form and
   confirm every data type mentioned there also appears here, and vice
   versa. The two should never describe different facts.
3. If you add any new dependency later — analytics, crash reporting, a
   new ad network, cloud backup — **stop and update both this file and
   the policy before that build ships**, not after. This is exactly the
   drift `REQUIREMENTS.md` warns is the actual rejection trigger, not a
   hypothetical one.