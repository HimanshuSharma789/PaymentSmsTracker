# Agent memory — Payment SMS Tracker

Start here, then read `docs/INDEX.md` and the page that matches the task.

This is a **local-first Android app** that turns bank/UPI payment SMS into reviewed transactions, exports **Realbyte Money Manager** TSV, and optionally pushes splits to **Splitwise**. Data stays on the phone. The Python converter in the parent `expense-manager/` repo is a separate historical-import tool.

## Always true

- Source of truth for the in-app ledger: Room table `transactions`.
- Lifecycle: `needs_review` → `reviewed` → `exported` (MM). Splitwise is a later optional step on shared exported rows.
- Split amounts: `split/SplitCalculator.kt` only.
- MM TSV shape: `export/MoneyManagerExporter.kt` only.
- SMS rules: JSON in `AppConfig` / Admin, named groups `amount`, `merchant`, `date`, `reference`, `accountHint`. Wizard: `SmsRuleWizardActivity` + `sms/SmsRuleBuilder.kt`.
- Merchant memory: `merchant/MerchantMemory.kt` + Room `merchant_prefs` (survives export purge).
- Default split-to-MM: your share → chosen category; friends → `debtCategory`/`debtSubcategory` (Social Life / Dues). Flag `mmFullSplitAsOwnExpense` changes MM only.
- MM-exported rows are kept until the user deletes them (Admin → clear all MM exported, or delete older than 30 days).
- AI Assist (`agent/`, `AgentChatActivity`; settings in Admin → AI Assist tab) is the one opt-in cloud exception: off by default, user's own key for Claude, OpenRouter or any OpenAI-compatible endpoint (`agent_secure` prefs, excluded from backup). What the model may read is chosen per field in `AgentPrivacy`; `AgentData` is the only place app data becomes model JSON (reference numbers and tokens never). The model only proposes via tools; `AgentProposal.validate` checks each proposal and `AgentApplier` writes Room only when the user taps Apply. Money math is local (`SplitCalculator`, summarize tools).

## Where to look

| Task | Files |
|---|---|
| SMS ingest | `SmsReceiver.kt`, `sms/SmsParser.kt`, `sms/SmsDedupe.kt` |
| SMS rule wizard | `SmsRuleWizardActivity.kt`, `sms/SmsRuleBuilder.kt` |
| Merchant memory | `merchant/MerchantMemory.kt`, `data/MerchantPreference*.kt` |
| Review UI | `TransactionEntryActivity.kt`, `TransactionListActivity.kt` |
| Config | `config/AppConfig.kt`, `config/ConfigStore.kt`, `AdminActivity.kt` |
| Splits / Splitwise | `SplitsActivity.kt`, `splitwise/` |
| LAN web | `server/`, `app/src/main/assets/lan_web/` |
| Tests | `app/src/test/java/com/example/paymentsmstracker/` |

## Do not

- Add a cloud backend for the ledger.
- Store Splitwise secrets in Auto Backup–backed prefs.
- Invent new MM columns; the TSV must stay import-compatible.
- Auto-purge MM-exported rows; cleanup is a user action only.

Full write-ups: `docs/PROBLEM.md`, `docs/USER_FLOW.md`, `docs/DATA_FLOW.md`, `docs/ARCHITECTURE.md`.
