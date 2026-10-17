# Google Play release checklist

Status as of 2026-10-16, branch `release/play-store`. Policy facts were checked against Play Console Help and developer.android.com on that date. Check them again before each submission, because Play changes deadlines every year.

## 1. Already done in the repo

| Area | Change | Where |
|---|---|---|
| Target API | `compileSdk`/`targetSdk` 35 → **36**. Since 2025-08-31, Play rejects new apps and updates below 36 (an extension to 2026-11-01 can be requested in Play Console). | `app/build.gradle.kts` |
| Background location | **Removed `ACCESS_BACKGROUND_LOCATION`.** Play approves it only for core features with real user benefit (safety, health). Tagging a payment with a place counts as "convenience", and Play would reject it. The permissions panel now asks only for while-in-use location. | `AndroidManifest.xml`, `PermissionsPanel.kt`, `location/LocationCapture.kt` |
| Release logging | The R8 rule now uses `return 0`, so `Log.v/d/i` calls are removed even when their Int result is used (for example a `Log.d` as a `when` branch). Before this, strings like "Ignored SMS: …" were still in the release dex. Verified with `strings` on the dex. | `app/proguard-rules.pro` |
| Locale bug | The LAN address was built with `String.format` in the default locale, which prints non-ASCII digits in Arabic and similar locales and breaks the URL. It now uses `Locale.ROOT`. | `server/NetworkUtils.kt` |
| Dependencies | `security-crypto` `1.1.0-alpha06` → `1.1.0` (stable). | `gradle/libs.versions.toml` |
| CI | Runs `testDebugUnitTest lintRelease assembleDebug bundleRelease`. Uploads the unsigned AAB with `mapping.txt`, and uploads reports every time. | `.github/workflows/ci.yml` |
| Docs | SDK level in ARCHITECTURE, link from INDEX. | `docs/` |

These were checked and are already correct, so nothing changed:
- **AAB + R8:** `isMinifyEnabled` and `isShrinkResources` are on. The AAB already carries `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`, so Play deobfuscates crashes in Android vitals automatically. Room, OkHttp, kotlinx and Ktor ship their own consumer rules, and the app has no reflection-based JSON (it uses `org.json`), so no extra keep rules are needed.
- **R8 smoke test:** a debug-signed R8 build (package `….r8check`) was installed on an API 36 emulator, then uninstalled. Home, list, AI chat and Admin all opened. An SMS was injected and the receiver ran. The Ktor LAN server started as a `specialUse` foreground service, served `/` with 200, `/api/status` returned JSON, and `/api/transactions` returned 401 without a token. No crashes.
- **16 KB page size:** the AAB contains no `.so` files, so the requirement is met automatically. Re-check if you add a native dependency: `unzip -l app-release.aab | grep '\.so$'`.
- **Backup:** `backup_rules.xml` and `data_extraction_rules.xml` exclude `agent_secure*`, `splitwise_auth*` and the WebView cookies. They also keep the ledger out of cloud backup. A device-to-device transfer moves the ledger but not the secrets.
- **Exported components:** only the launcher, `SmsReceiver` (guarded by `BROADCAST_SMS`), the boot receiver (protected broadcasts) and the OAuth deep-link activity are exported. Everything else is `false` or has no intent filter.
- **Permissions:** every one that is declared is used. `WRITE_EXTERNAL_STORAGE` is capped at `maxSdkVersion=28` (legacy Downloads path on API 26–28). The nightly alarm is inexact (`setAndAllowWhileIdle`), so neither `SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM` is needed. `<queries>` lists only the two Money Manager packages.
- **Cleartext:** `base-config cleartextTrafficPermitted="true"` is intentional. It lets AI Assist reach a local model server (Ollama / LM Studio) on a LAN IP, and `AgentSettings.endpointProblem()` limits http to local or private hosts. Splitwise and cloud AI endpoints are https constants. Lint reports `InsecureBaseConfiguration` as a warning; see open decision D3.
- **debuggable:** the release default is `false`. The LAN server is a user feature, so it stays in release.

## 2. Owner decisions and to-dos

### D1. applicationId (required, one line)
`app/build.gradle.kts` → `defaultConfig { applicationId = "…" }`. **Play rejects any id that starts with `com.example`**, and the id can never change after the first upload. Leave `namespace` and the Kotlin packages as they are. The FileProvider authority follows automatically through `${applicationId}`. The OAuth scheme `paymentsmstracker://oauth/splitwise` does not depend on the id.

### D2. Upload key and signing (Play App Signing is mandatory for new apps)
```sh
keytool -genkeypair -v -keystore ~/keys/pst-upload.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
cat > keystore.properties <<'EOF'      # repo root; already in .gitignore
storeFile=/Users/<you>/keys/pst-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
EOF
./gradlew bundleRelease   # → app/build/outputs/bundle/release/app-release.aab (signed with the upload key)
```
Back up the `.jks` file and its passwords outside the repo. In Play Console, choose "Use Google-generated app signing key". If the upload key is lost, Play support can reset it. That only works because Google holds the app signing key.

### D3. Cleartext to local AI servers
- **Keep it (current):** Ollama/LM Studio on the LAN keeps working. In Data safety, answer "Is all user data encrypted in transit?" with **No**, or at least do not claim Yes without that caveat.
- **Drop it:** set `cleartextTrafficPermitted="false"` and remove the http branch in `AgentSettings.endpointProblem()`. Then the encryption answer is **Yes**.

### D4. versionCode strategy
`versionCode` and `versionName` live only in `app/build.gradle.kts`. Every upload to any track (internal, closed or production) needs a higher `versionCode` than any earlier upload. Suggested scheme: `major*10000 + minor*100 + patch`, so 1.3.0 becomes 10300. A rejected upload still uses up its code.

### D5. Crash reporting (local-first)
- **Recommended:** no SDK. **Android vitals** in Play Console shows crashes and ANRs from users who opted into Android usage and diagnostics, already deobfuscated through the bundled mapping. It needs no code and nothing extra in Data safety.
- **Optional, if vitals is not enough:** ACRA with the *email* or *share-intent* sender, where the user sends the report. No backend is involved. Add "Crash logs: collected, optional, user-initiated" to Data safety.
- Do **not** add Firebase Crashlytics or Analytics. That breaks the local-first promise in AGENTS.md and the privacy policy.

### D6. UI work (for the UI engineer; nothing below is built yet)
1. **SMS prominent disclosure** before the first `RECEIVE_SMS`/`READ_SMS` request (MainActivity's first-launch request and the Permissions tab). Use a full-screen card or dialog with the copy in §5. Ask for the permission only after the user taps "Continue". "Not now" must leave the app usable (manual entry).
2. **Location disclosure** before `ACCESS_FINE/COARSE_LOCATION` (Permissions tab), with the copy in §5.
3. **Fix the existing location copy:** `@string/perm_location_reason` (activity_admin.xml) still says *"Choose 'Allow all the time' so it works while the app is closed."* That option no longer exists. Replace the text with the location copy in §5. Also, `perm_status_location_always` and `perm_action_allow_always` are now unused and can be deleted.
4. **Privacy policy link in the app** (Settings / About). Play requires the link both in the app and in Play Console.
5. **AI Assist "Report response" action** on each model reply, for example an overflow item that opens an email to the developer with the reply text prefilled. Play's AI-Generated Content policy requires in-app reporting of offensive output from AI chatbots. Assist may count as a "minor feature", but the action costs little and removes the risk.
6. **AI Assist first-use consent:** show `@string/ai_disclosure` (existing) as a dialog the first time the user enables AI Assist, not only inline.
7. Target 36 enforces edge-to-edge (the opt-out attribute is ignored), predictive back, and resizable layouts on screens ≥600dp. Check that insets are correct on every screen. Back handling already uses `OnBackPressedDispatcher`.

### D7. Splitwise WebView hardening (small code change in `SplitwiseWebAuthActivity.kt`, not done because *Activity.kt files were out of scope)
- In `shouldOverrideUrlLoading`, allow only `https` hosts ending in `splitwise.com`, plus any third-party sign-in hosts Splitwise's login page actually redirects to (check by testing). Open anything else in the external browser.
- Add `settings.allowContentAccess = false` and `settings.allowFileAccess = false`. File access is already off by default at target 30+, but set it explicitly anyway.
- Keep `setAcceptThirdPartyCookies(true)` only if Google sign-in needs it. Test without it.

## 3. Play Console steps (in order)

1. **Create app:** name, default language, App, Free. Accept the declarations.
2. **Personal developer account (created after 2023-11-13):** run a **closed test with ≥ 12 testers opted in for 14 days in a row** before you can apply for production access. Start this early.
3. **Internal testing track:** upload `app-release.aab`, add release notes, and roll out to yourself. Install from Play to confirm SMS capture works.
4. **App content** (Policy → App content):
   - **Privacy policy:** a public URL (see §7).
   - **App access:** "All functionality is available without special access." Add instructions such as: *"No login needed. To test without a real bank SMS: Settings (gear) → Test SMS tab, paste a sample bank message such as `Rs.250.00 debited from A/c XX1234 on 16-10-26 to VPA swiggy@icici UPI Ref 612345678901`, tap "Run parse test", then "Inject as Needs Review" to create a transaction. Splitwise and AI Assist are optional and need the reviewer's own account or API key."*
   - **Ads:** No.
   - **Content rating:** see §6.
   - **Target audience:** 18+ only (a finance app, not for children).
   - **Data safety:** see §4.
   - **Financial features:** declare "Personal finance management / budgeting / expense tracking". The app does *not* offer loans, payments, banking, crypto or trading.
   - **Sensitive app permissions → SMS and Call Log:** the Permissions Declaration Form (§4.1).
   - **Foreground service permissions:** declare `FOREGROUND_SERVICE_SPECIAL_USE` (§4.2).
   - **Location permissions:** none needed now (no background location).
   - **Government apps, News, Health, COVID, Gambling:** No.
5. **Store listing:** see §8. The full description **must describe the SMS-reading feature**, because the SMS exception needs it.
6. **Countries:** pick the markets you support. Bank SMS formats are India-centric (UPI), so start with India.
7. **Pre-launch report:** check it after the first upload. The robo crawler cannot receive SMS, so expect empty screens.
8. **Promote to closed, then production.** Use a staged rollout (for example 20%) and watch Android vitals: user-perceived crash rate < 1.09%, ANR rate < 0.47%.

## 4. Declarations, drafts based on the code

### 4.1 Permissions Declaration Form: SMS (`READ_SMS`, `RECEIVE_SMS`)
- **Core functionality:** *SMS-based money management (budgeting/expense tracking)*. This is an exception Play lists for `READ_SMS` and `RECEIVE_SMS`, and the app does not have to be the default SMS handler.
- **Description (draft):**
  > Payment SMS Tracker is a personal expense tracker. Its core feature reads the transaction alert SMS that banks and UPI apps send (debits, credits, card spends), extracts the amount, merchant, date and account, and creates a draft transaction for the user to review and export to their budgeting app. RECEIVE_SMS captures new alerts as they arrive. READ_SMS is used only when the user runs "SMS backfill" for a chosen date range. Only messages from senders matching the user's bank-sender rules are parsed; all other SMS are skipped without being stored. Parsed transactions stay on the device. The app never sends SMS, never uploads SMS to a server we operate, and has no ads or analytics.
- **Why no alternative:** Indian banks and UPI apps send transaction alerts only by SMS. There is no consumer API that gives a third-party budgeting app per-transaction bank data. The RBI Account Aggregator framework requires a regulated FIU licence and does not deliver real-time alerts. Manual entry stays available, but it defeats the product's purpose.
- **Video (≤ 30 s, YouTube unlisted):** fresh install → SMS disclosure screen → system permission prompt → a bank SMS arrives (send one from a second phone) → notification "New transaction" → open the draft → review → export. Then Settings → Maintenance → SMS backfill for a date range.
- **Spyware/User Data:** no non-financial SMS is kept (`config.isBankSender` filters before the body is read). The original SMS text leaves the phone only if the user turns on **AI Assist → What the AI can read → Original SMS text**, which is off by default.

### 4.2 Foreground service: `specialUse`
- **Feature:** "PC Manager". The user taps Start server, and the phone serves a local web page on the same Wi-Fi so they can review and edit transactions on their computer's browser. It stops automatically after the chosen time (15 min – 2 h), or when the user taps Stop.
- **Why no other type fits:** it is an inbound local HTTP server. That is not data sync to a remote server (`dataSync`), not media, and not connected-device communication over BLE or USB.
- **User impact if delayed or killed:** the browser tab on the PC loses connection mid-edit. No data is lost, because every save writes to Room immediately.
- **Manifest subtype property** (already present): "Local Wi-Fi browser access for reviewing transactions on the same network".
- **Video:** Admin → PC Manager → Start server → ongoing notification → open the address in a desktop browser → enter the access code → edit a row → Stop.

### 4.3 Data safety form
"Collected" means the data leaves the device. The only things that leave are the optional, user-started Splitwise push and AI Assist. Both go directly from the phone to a service the user chose, using the user's own account or key. Nothing goes to a developer server, because there is none. Under Play's definitions this is **collected, not shared** (sharing has an exemption for user-initiated transfers the user expects), **optional**, and **not ephemeral** (the third party keeps it under its own policy).

| Data type | Collected? | Sent to | Optional | Purpose |
|---|---|---|---|---|
| Financial info → *Purchase history* (amount, merchant, date) | Yes | Splitwise (push), AI provider (if enabled) | Yes | App functionality |
| Financial info → *Other financial info* (category, account name, bank reference no.) | Yes | Splitwise (category, notes, reference no. in "details"), AI provider (category/account; **never** reference no.) | Yes | App functionality |
| Messages → *SMS or MMS* | Yes | AI provider only, only with the "Original SMS text" toggle (default off) | Yes | App functionality |
| Messages → *Other in-app messages* (AI chat text the user types) | Yes | AI provider | Yes | App functionality |
| Location → *Approximate* and *Precise* | Yes | AI provider only, only with the "Location" toggle (default off) | Yes | App functionality |
| Personal info → *Name* (friend names in splits) | Yes | AI provider (if "Friends" toggle on) | Yes | App functionality |
| Personal info → *User IDs* (Splitwise member IDs) | Yes | Splitwise | Yes | App functionality |
| App activity → *Other user-generated content* (notes) | Yes | Splitwise, AI provider | Yes | App functionality |
| Contacts, photos, files, audio, health, web history, device IDs, crash logs, diagnostics, analytics | **No** | – | – | – |

- **Encrypted in transit:** see D3. Answer Yes only if the local-http AI option is removed.
- **Users can request deletion:** Yes. Everything on the device can be deleted in the app (single delete, bulk delete, Admin → clear exported, or uninstall). Data already sent to Splitwise or an AI provider is deleted through that service.
- **Data stored on the device only, not declared as collected:** the SMS-derived ledger, merchant memory, settings, location tags and the exported TSV in Downloads.
- **LAN web (PC Manager):** the data goes to the user's own browser on their own network, it is never sent to a third party, and it is protected by an access code. We treat it as not collected. If a reviewer disagrees, add the same rows as above with "Purpose: App functionality".

## 5. Prominent disclosure copy (exact text)

Rules followed: show it in the app during normal use, before the runtime prompt, separate from the privacy policy, and say what is collected, how it is used and whether it is shared.

**SMS** (before the first SMS permission request)
> **Read your bank payment SMS**
>
> Payment SMS Tracker reads the SMS that banks and UPI apps send when money leaves or enters your account. It uses them to fill in the amount, merchant, date and account of each transaction for you to review.
>
> • Only messages from senders that look like banks are read; personal messages are skipped and never stored.
> • Transactions are saved only on this phone. They are not uploaded to us, and we don't run any server.
> • They leave the phone only if you choose to: pushing a split to Splitwise, or turning on AI Assist with your own API key.
>
> You can turn this off any time in Android Settings → Apps → Payment SMS Tracker → Permissions.
>
> [Not now]  [Continue]

Strings, if wanted: `disclosure_sms_title`, `disclosure_sms_body`, `disclosure_continue` ("Continue"), `disclosure_not_now` ("Not now").

**Location** (before the location permission request, Permissions tab)
> **Save where you paid**
>
> Payment SMS Tracker collects location data to tag a transaction with where you were when its payment SMS arrived, only while you are using the app. Location is not collected when the app is closed or not in use.
>
> Location is stored only on this phone and is never sent anywhere unless you turn on "Location" under AI Assist → What the AI can read.
>
> [Not now]  [Continue]

**AI Assist** (first time the user turns it on): use the existing `@string/ai_disclosure` as the dialog body, titled "Send data to your AI provider?", with [Cancel] and [Turn on].

## 6. Content rating (IARC questionnaire)
Category: **Utility, Productivity, Communication or Other**. Answer No to violence, sexuality, language, controlled substances and gambling. No user-to-user interaction or sharing between users. No sharing of user location with other users. No digital purchases. If asked about generative AI: yes, an optional text chat that uses the user's own model key, with a report action (D6.5). Expected rating: Everyone / PEGI 3 / IARC 3+. Target audience stays 18+ anyway.

## 7. Privacy policy hosting
`PRIVACY_POLICY.md` at the project root is a draft that matches the code. Before publishing:
1. Fill in `<Developer name>`, `<contact email>` and the effective date.
2. Host it at a public, unauthenticated, non-PDF URL. The simplest option is GitHub Pages on the repo, or a Google Sites page. It must not be geo-blocked or editable by others.
3. Put the URL in Play Console → App content → Privacy policy, in the store listing, and in the app (D6.4).
4. Update it whenever a data flow changes (new provider, new field sent).

## 8. Store listing assets

| Asset | Spec |
|---|---|
| App name | ≤ 30 characters |
| Short description | ≤ 80 characters, e.g. "Turn bank & UPI payment SMS into reviewed expenses. Private, on-device." |
| Full description | ≤ 4000 characters. Must say plainly that the app reads bank/UPI payment SMS to create transactions, that data stays on the phone, and that Splitwise and AI Assist are optional. No keyword stuffing and no "best/#1" claims. |
| App icon | 512 × 512 PNG, 32-bit, ≤ 1 MB, full-bleed square (Play applies the mask) |
| Feature graphic | 1024 × 500 JPG or 24-bit PNG (no alpha) |
| Phone screenshots | 2–8; PNG/JPG; 9:16 or 16:9; each side 320–3840 px. For promotion eligibility, provide ≥ 4 at ≥ 1080 px on the short side. Use demo data, never real SMS or account numbers. |
| 7" and 10" tablet screenshots | Optional. Note that `android.hardware.telephony required="true"` already hides the app from Wi-Fi-only tablets and Chromebooks. |
| Promo video | Optional YouTube URL |
| Contact | Developer email (required), website (optional) |
| Themed icon | Optional. `mipmap-anydpi-v26/ic_launcher.xml` has no `<monochrome>` layer (lint `MonochromeLauncherIcon`). The foreground is a colour PNG, so the designer needs to supply a single-colour vector before you add `<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>`. |

## 9. Build, test and lint commands
```sh
./gradlew testDebugUnitTest assembleDebug bundleRelease lintRelease
unzip -l app/build/outputs/bundle/release/app-release.aab | grep -E '\.so$|proguard.map'   # no .so, map present
```
`lintRelease` passes with 0 errors. The remaining warnings are not release blockers: unused resources, KTX suggestions, newer dependency versions (AGP 8.10.1 → 8.13.x / 9.x, Gradle 8.11.1 → 8.14.x), `ObsoleteSdkInt`, the intentional `InsecureBaseConfiguration`, and the intentional telephony `required="true"`. Plan dependency upgrades in their own PR, not mixed into a release.

## 10. Every release
1. Bump `versionCode` (and `versionName`).
2. `./gradlew testDebugUnitTest lintRelease bundleRelease` with `keystore.properties` present.
3. Upload to internal testing, install from Play, and send a real bank SMS.
4. If any data flow changed, update Data safety, the privacy policy and the SMS declaration.
5. Do a staged rollout and watch Android vitals for 48 h.
