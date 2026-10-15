# Production-readiness review — 2026-10-15

This review is read-only. Nothing in the code was changed. Paths are relative to `app/src/main/java/com/example/paymentsmstracker/` unless noted. The unit tests pass (71 tests). The items marked ✔ were rechecked by hand against the source.

## P0: data loss, wrong money, security

**Status (2026-10-16): all 13 fixed.**
- Tests: `SmsParserAndExportTest.fallbackReferenceIsTheNumberNotAWord`, `ConfigStoreTest`, `SplitCalculatorTest.sharesAreNeverNegativeAndAlwaysSumToTotal`, `DuplicatePreventionTest` (Splitwise retry marker, exported status kept), `LanSecurityTest` (lockout, expiry, host, OAuth state).
- Not unit-tested:
  - #2: the schema is now exported to `app/schemas/`, but a migration test needs an instrumented `MigrationTestHelper` test.
  - #8: an Activity button guard.
  - #9: escaping in the web JS, plus the CSP header.
  - #13: backup XML.
- Existing users: rows already deduped by a junk reference before #1 are not recovered. Re-run Admin → backfill to pick those SMS up again.

| # | Area | Issue | Where | Fix |
|---|---|---|---|---|
| 1 ✔ | SMS | The fallback reference regex captures junk: `Ref No: 4123` gives `"No"`, `Refund` gives `"und"`. Dedupe trusts any non-blank reference, so real SMS are **silently dropped** as duplicates. | `sms/SmsParser.kt:250`, `sms/SmsDedupe.kt:27` | `\bRef(?:\s*No\.?\|erence(?:\s*No\.?)?)?\s*[:#]?\s*(\d[\w-]{5,})`; ignore references shorter than 6 characters in dedupe. |
| 2 ✔ | DB | `fallbackToDestructiveMigration()` plus `exportSchema=false`: a missed migration wipes the ledger and `merchant_prefs`. | `data/AppDatabase.kt:13,74` | Remove the fallback, export the schema, add a migration test. |
| 3 ✔ | Config | When `load()` fails it returns `AppConfig()`. The next `load().copy(...).save()` then **overwrites the user's config with defaults**. | `config/ConfigStore.kt:14-18` | Parse rules leniently and skip bad ones. Never save over a config that failed to load. |
| 4 ✔ | Config | The "migration" runs on every load. Deleted accounts and built-in rules come back, and edits to `hdfc_card_spend` are overwritten every time. | `config/ConfigStore.kt:74-76,114-128` | Add `configVersion` and run each migration once. |
| 5 ✔ | Splits | The rounding remainder always goes to ME. With ME at 0% the share can go **negative** (100.05 split 50/50 between friends gives ME -0.01). MM then gets a Dues row larger than the payment, and Splitwise rejects the push. | `split/SplitCalculator.kt:233-247` | Largest-remainder method; assert every share ≥ 0 and that shares sum to the total. |
| 6 | Splitwise | **Duplicate expenses.** There is no idempotency key, so after a timeout or a cancelled save (`lifecycleScope`) the row stays Pending and gets pushed again. The push menu item isn't guarded while a push is running. | `splitwise/SplitwiseApi.kt:121`, `SplitsActivity.kt:219,277,343-379` | One in-flight guard; `NonCancellable` push+save; a targeted `UPDATE` of `splitwiseExpenseId`; put a `[pst:<id>]` marker in `details` and check for it before re-pushing. |
| 7 ✔ | Entry | "Save draft" on an exported row sets it back to `needs_review`, so it gets **exported to MM again** as a duplicate. | `TransactionEntryActivity.kt:1015-1020` | Hide Save draft on exported rows; add an explicit "re-export". |
| 8 | Entry | No double-submit guard: double-tapping Save on a new row inserts two rows. | `TransactionEntryActivity.kt:198,954` | Disable the buttons while saving. |
| 9 ✔ | LAN | **XSS**: Splitwise member names (which friends control) and allocation values go into `innerHTML` unescaped, which can leak the session token. | `assets/lan_web/app.js:244` (+250,272-317) | `escapeHtml` everywhere; add a CSP header `script-src 'self'`. |
| 10 ✔ | LAN | The 6-digit code has no attempt limit and uses `kotlin.random` (not a CSPRNG). Expiry is never checked on the server, and `postDelayed` pauses during sleep. | `server/LanServerState.kt:66-96`, `LocalHttpServer.kt:79` | `SecureRandom`; lock out after 5 failures; check `expiresAtMs` in `requireAuth`. |
| 11 | LAN | Open mode is vulnerable to CSRF and DNS rebinding (a `text/plain` POST works, and `Host` isn't checked). The server binds to `0.0.0.0`, and write endpoints accept `Infinity` or any status string. | `LocalHttpServer.kt:42,203`, `LanApi.kt:176-196` | Require JSON plus a `Host` check; bind to the Wi-Fi IP; validate inputs. |
| 12 ✔ | OAuth | The state check fails open when no state was saved. Since the deep link is exported, an attacker can link the app to their own Splitwise account. | `splitwise/SplitwiseOAuthActivity.kt:49`, `SplitwiseWebAuthActivity.kt:161` | Reject when the expected state is null. |
| 13 ✔ | Privacy | Auto Backup uploads the Room DB (full SMS text, location) and the WebView cookies (Splitwise session) to Google Drive. This breaks the "data stays on phone" promise. | `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Exclude `database` and `app_webview`, or make backup opt-in. |

## P1: wrong data, misleading UI

- **Credits are recorded as spends.** Refunds, "credited", OTPs and "is due" SMS are parsed as Expense or Transfer-Out (`SmsParser.kt:28-38,107-113,155`). Add a negative-keyword gate, or map credits to Income.
- **Live receiver accepts any sender.** `SmsReceiver.kt:43` doesn't check the sender, while backfill does (`AdminActivity.kt:1322`). Move `isKnownBankSender` into `sms/`, use it in both places, and make the list configurable.
- **Wizard rules don't generalize.** They match only the sample, because the literal tail (balance, last 4 digits) is baked in (`SmsRuleBuilder.kt:121-137`). Drop the tail and turn digit runs into `\d+`.
- **Generic rule's reference group is dead** because of a lazy optional group (`AppConfig.kt:150`).
- **Dedupe is implemented twice, with different rules** (live vs backfill, `SmsDedupe.kt`). The window dedupe also merges genuine repeat ₹20 "Unknown" payments. Use one ingest path, `SmsIngest.ingest()`, which also removes the parse→Transaction code repeated 3 times (`SmsReceiver.kt:83`, `AdminActivity.kt:885,1156`).
- **"Clear MM exported" also deletes splits not yet pushed to Splitwise** (`TransactionDao.kt:193`), and the dialog doesn't warn about it.
- **Invalid splits fall back silently to an equal split** in the MM export, while Splitwise refuses the same row (`MoneyManagerExporter.kt:129`). The fallback is copied in 3 places. Make it one `SplitCalculator.resolveOrEqual`, or block the export.
- **Stale Admin config JSON overwrites rules saved in the wizard** (`AdminActivity.kt:712` isn't rebound in `onResume`).
- **Exports show success on failure.** The null `Uri` is ignored (`AdminActivity.kt:948,991,1008`, `SplitsActivity.kt:330`), and the CSV is written on the main thread.
- **Entry's Push to Splitwise mixes saved and unsaved data** (`TransactionEntryActivity.kt:1176`). Save first.
- **State is lost on rotation or process death.** There is no `onSaveInstanceState` anywhere; move form state into a ViewModel.
- **Amount input** rejects "1,250.00", and `1.0E7` is displayed for ₹1 crore (`TransactionEntryActivity.kt:776,964`).
- **TSV issues.** Account, Category and Subcategory aren't escaped. Rows are sorted by the `MM/dd/yyyy` string, which breaks across years. The `SimpleDateFormat` is shared (`MoneyManagerExporter.kt:25,176,189`).
- **Year-less SMS dates use the current year** instead of the SMS receive time (`SmsParser.kt:300`).

## P2: AI Assist

- **Privacy bypass.** `propose_update` returns the *before* values (notes, merchant, split names) even when those fields are turned off (`AgentTools.kt:167`, `AgentProposal.kt:48`). Return only "card shown".
- **Prompt injection.** SMS merchant text can steer up to 50 proposals, and "Apply all" writes them all with one tap. Add a confirm dialog and highlight free-text changes.
- **Unbounded history.** It is re-sent every turn, so cost grows quadratically (`AgentChatViewModel.kt:38`). Trim to the last N turns.
- **Empty assistant turn.** One is stored as `content: []`, after which Anthropic rejects every request and the chat is stuck (`AgentClient.kt:73`).
- **No 429 backoff, and a new OkHttpClient per message** (`AgentSettings.kt:76`).
- **Local Ollama / LM Studio over http is blocked** by default cleartext rules. Add a network-security-config for local hosts only.
- **Unverified default model id.** `qwen/qwen3.8-27b` (`AgentSettings.kt:10`) may not exist.

## P3: build / release

- `isMinifyEnabled=false`. `Log.d` stays in release builds and logs reference numbers (`SmsReceiver.kt:79`), and the proguard rules are the stock template.
- `applicationId = com.example.*`: Play rejects it. There is also no signing config and no CI.
- **Compose is entirely unused** but still declared (plugin, BOM, ui, material3, tooling). Delete it: a smaller APK and a faster build.
- `security-crypto 1.1.0-alpha06` is deprecated upstream, and encryption failure silently falls back to plain prefs (`SplitwiseAuthStore.kt:199`).
- `fgs`: if `http.start()` throws, `stopSelf()` runs before `startForeground()`, which crashes the app (`LanAccessService.kt:83`).
- **Play policy.** `READ_SMS`/`RECEIVE_SMS` and background location need declarations. A sideload or F-Droid release is the realistic channel.

## Simplify (delete first)

- Compose dependencies (above).
- Dead DAO methods: `getTransactionsForDay`, `getPendingSplitTransactions` (Flow), `getSplitTransactions`, `getSplitTransactionsList`, `deleteTransactionById`.
- `SmsDedupe.normalizeSms` and `normalizeMerchant` are identical.
- The "mark reviewed + `MerchantMemory.remember`" sequence is repeated 3 times (`TransactionListActivity.kt:346`, Entry `:1064`, `AgentApplier.kt:93`).
- `SplitTableAdapter.isSelectionMode()` and `initialId` are unused; `SplitsActivity.kt:365,376` assigns a field to itself.
- `preferExistingAccount` branches are identical (Entry `:840`).
- **God activities.** Split `AdminActivity` (1346 lines) into panels the way `PermissionsPanel` already is: Splitwise, LAN, AI, Maintenance, Backfill → `sms/`. Move Entry's split editor (~290 lines) into a helper.

## Add / customize

- **Auto catch-up scan** on app start from the last processed SMS timestamp, reusing backfill. This means a killed process never loses an SMS.
- **In-app editing of categories and subcategories** (today they're editable only as raw JSON); a configurable bank-sender list; currency and locale (INR and `₹` are hardcoded); an explicit Dark theme; better dark-mode contrast (`values-night/colors.xml`).
- **Search** in the list and in `OptionPickerBottomSheet`, plus **undo** for deletes.
- **Indices** on `referenceNumber`, `timestamp` and `reviewStatus`, a unique hash of the SMS body, and a `COUNT(*)` query for the nightly reminder.
- **Strings into `strings.xml`.** "Expense", "Income" and "Transfer-Out" serve as both data keys and labels; split them before i18n.
- **Accessibility.** The picker's selected state is shown only by stroke width; use `isChecked`.

## Missing tests (one each)

- Fallback reference parsing.
- Refunds and credits are not spends.
- Wizard rule matches a second SMS.
- Split shares are ≥ 0 and sum to the total (property-style test).
- OAuth missing state is rejected.
- TSV escaping of tabs/newlines in account and category.
- `SplitwisePusher` owed shares sum to cost.
- LAN auth lockout.
- Room migration.

## Lint (`lintDebug`)

- 1 error, 153 warnings.
- **The error:** `split_hint_percent` uses a bare `%` (`res/values/strings.xml:56`). Escape it as `%%`, or mark the string `formatted="false"`.
- **Top warnings:**

| Warning | Count |
|---|---|
| UnusedResources | 40 |
| UseKtx | 26 |
| PluralsCandidate | 19 |
| GradleDependency (outdated deps) | 17 |
| NotifyDataSetChanged | 10 |
