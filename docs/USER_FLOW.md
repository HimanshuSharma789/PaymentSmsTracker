# User flow

Home is `MainActivity`. It shows **what’s left**: N to review · M ready for Money Manager · K splits pending, plus one primary CTA (review → export → splits). Grant **SMS** and **notifications** from Home before expecting auto-capture (permission buttons hide once granted).

## Daily path

```
SMS arrives
    → notification “New transaction”
    → (optional) tap to open TransactionEntryActivity
    → otherwise it sits in Needs review

Evening
    → Home (counts) → primary CTA / Review
    → for each row: account, category/subcategory, type, notes, participants/split
    → Save as Reviewed

    → Home shows ready-for-MM count → Reviewed list → **Export to Money Manager**
    → file lands in Downloads as money_manager_import_yyyyMMdd_HHmmss.tsv
    → app offers to open Money Manager if installed
    → import that .tsv in Money Manager
    → shared expenses in that batch appear under Splits → Pending (not pushed yet)

Splits (after MM export)
    → Home (splits pending) → Splits
    → pending = exported shared expenses not yet on Splitwise
    → Export CSV (no state change), **or** push to Splitwise, **or** mark as success (already updated outside the app)
```

### Transaction states the user sees

| Status | Meaning | Typical next action |
|---|---|---|
| Needs review | Parsed or typed, not finalized | Edit → Save as Reviewed |
| Reviewed | Ready for Money Manager | Export TSV (status becomes Exported) |
| Exported | Already handed to MM | Optional Splitwise push if it has friends |

MM-exported rows are kept until you delete them. Admin → Maintenance offers **Clear MM exported** (all) or **Delete MM exported older than 30 days**.

### Manual entry

Home → **Add transaction** (`TransactionEntryActivity`) with no SMS. Same review/export path.

### Lists from Home

- Count row / CTA → Needs review, Reviewed (ready for MM), or Splits
- All transactions (secondary)
- Admin (toolbar) for setup

## Admin (not daily)

Toolbar → **Admin** (`AdminActivity`):

- Accounts, categories, debt category (`Social Life` / `Dues` by default)
- `mmFullSplitAsOwnExpense`: if on, MM gets the **full** amount as your expense; Splitwise/split table still use shares
- SMS dedupe window and auto-save toggle
- Full config JSON (SMS regex rules with named groups `amount`, `merchant`, `date`, `reference`, `accountHint`)
- Paste-SMS parser tester + **Create rule from this SMS** wizard (`SmsRuleWizardActivity`)
- Theme, nightly review reminder hour
- Raw CSV / TSV export, clear data, reset defaults
- Splitwise sign-in (WebView session, personal API key, or OAuth)
- **LAN access**: start a timed local HTTP server so a laptop on the same Wi-Fi can review in a browser (access code + session token)

## Merchant memory

When you save a transaction with a category, the app remembers category/subcategory/account for that merchant (`merchant_prefs`). The next SMS (or draft open) prefills blank fields. Rule `defaultAccount` still wins over memory when the parser sets an account.

## Splitwise (optional)

1. Admin → Splitwise → **Sign in with Splitwise** (recommended) or paste an API token. OAuth app credentials are under **Advanced**.
2. Refresh groups. Map chips to Splitwise user ids on the transaction.
3. Push from Splits after the row is reviewed (or exported).

On HTTP 401 the app clears cached tokens and asks you to sign in again.

## LAN browser (optional)

1. Admin starts LAN access (foreground service, timed).
2. Phone shows URL + access code.
3. Browser: enter code → `/` list or `/sheet` spreadsheet-style editor.
4. Same Room data as the Android UI. Server binds `0.0.0.0` on a local port; it is **not** a public internet API.

## Nightly reminder

If enabled in config, an alarm around the configured hour notifies you to review leftover drafts. Re-scheduled after boot / app update (`BootCompletedReceiver`).
