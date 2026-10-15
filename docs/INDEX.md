# Payment SMS Tracker — project memory

Read this first. Then open the page that matches the question.

| If you need… | Open |
|---|---|
| Why this app exists | [PROBLEM.md](PROBLEM.md) |
| What a human does day to day | [USER_FLOW.md](USER_FLOW.md) |
| How SMS becomes Money Manager / Splitwise rows | [DATA_FLOW.md](DATA_FLOW.md) |
| Modules, storage, screens, APIs | [ARCHITECTURE.md](ARCHITECTURE.md) |

This Android app lives in `PaymentSmsTracker/`. The parent repo (`expense-manager/`) also has a Python CSV/XLSX converter for historical spreadsheets. That converter is a sibling, not part of this app.

**One-line summary:** capture bank/UPI payment SMS on the phone, review and categorize locally, export Realbyte Money Manager TSV, optionally push shared expenses to Splitwise.

**Privacy default:** transaction data stays on-device (Room + SharedPreferences). Network is used only for optional Splitwise and optional LAN review on the same Wi-Fi.
