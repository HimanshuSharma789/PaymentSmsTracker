# Architecture

Android app, package `com.example.paymentsmstracker`, minSdk 26, target/compile 36 (Play floor since 2025-08-31), version **1.3.0**. Kotlin, AppCompat/Material XML screens (Compose plugin is on the module but the product UI is Views). Room + KSP. Tests under `app/src/test`.

## Layers

```
UI (Activities, adapters, lan_web)
    → ViewModels (lists)
    → Domain helpers (SmsParser, SplitCalculator, MoneyManagerExporter, SplitwisePusher)
    → Persistence (Room, ConfigStore, SplitwiseAuthStore)
    → Android system (SMS broadcast, notifications, AlarmManager, FileProvider, Ktor CIO)
```

No DI framework. Singletons / `getDatabase(context)` / constructors from `Context`.

## Package map

| Package / file | Role |
|---|---|
| `SmsReceiver.kt` | SMS ingest |
| `sms/SmsParser.kt`, `sms/SmsDedupe.kt`, `sms/SmsRuleBuilder.kt` | Parse + duplicate rules + wizard pattern builder |
| `merchant/MerchantMemory.kt` | Last category/account per merchant |
| `data/` | Room `Transaction`, `MerchantPreference`, DAO, DB |
| `config/` | `AppConfig` + JSON prefs |
| `split/SplitCalculator.kt` | Equal / exact / percent / weight |
| `export/` | MM TSV, handoff, Splitwise CSV helper |
| `splitwise/` | Auth, HTTP, push |
| `server/` | LAN Ktor + `LanApi` |
| `reminder/` | Nightly review alarm |
| `theme/` | Light / system / dynamic |

## Screens

| Activity | Job |
|---|---|
| `MainActivity` | Home counts + primary CTA + permissions |
| `TransactionEntryActivity` | Create/edit one transaction |
| `TransactionListActivity` | Filter lists + bulk export/delete |
| `SplitsActivity` | Splitwise queue table |
| `AdminActivity` | Config, parser test, LAN, Splitwise |
| `SmsRuleWizardActivity` | Build SMS regex rule from a sample message |
| `SplitwiseWebAuthActivity` | Cookie session login |
| `SplitwiseOAuthActivity` | `paymentsmstracker://oauth/splitwise` |

## Persistence

| Store | What |
|---|---|
| Room `transaction_database` | Ledger drafts / reviewed / exported |
| Room `merchant_prefs` | Merchant → last category/account (v8) |
| SharedPreferences config JSON | Accounts, rules, flags |
| EncryptedSharedPreferences | Splitwise session / keys |
| Downloads + cache share file | TSV for MM |

Migrations 5→6 and 6→7 add Splitwise participant ids and push error. 7→8 creates `merchant_prefs`. Other version bumps still use `fallbackToDestructiveMigration()` — treat schema upgrades carefully.

## Process / system components

- `SmsReceiver` exported, `BROADCAST_SMS`, high priority.
- `NightlyReviewReceiver` not exported.
- `BootCompletedReceiver` re-applies reminder.
- `LanAccessService` foreground `specialUse` (local Wi-Fi review).
- FileProvider `${applicationId}.fileprovider`.

## LAN HTTP (same device data)

Assets: `/`, `/sheet`, `/api/status`, `/api/login`, `/api/meta`, `/api/transactions` (+ id, review, bulk). Auth: access code then `Authorization` session token (`LanServerState`).

## Splitwise HTTP

OkHttp + interceptors. Web session cookies and CSRF, or API key / OAuth. 401 clears store and surfaces re-login.

## Related repo (not this APK)

`expense-manager/convert_expenses.py` turns manual XLSX/CSV into the same kind of MM TSV for **history**. Category/account naming should stay aligned with `AppConfig` defaults and Money Manager.

## Conventions for changes

- SMS: add a named-group rule in `AppConfig` / wizard (`SmsRuleBuilder`) and a unit test rather than one-off if/else in the receiver.
- Merchant memory: use `MerchantMemory` only (prefs + history fallback).
- Splits: never re-implement share math outside `SplitCalculator`.
- MM rows: never bypass `MoneyManagerExporter.expandTransaction`.
- Secrets: never log Splitwise cookies/tokens; keep backup excludes intact.
- Review loop: new captures start as `needs_review`; do not auto-mark `exported`.
