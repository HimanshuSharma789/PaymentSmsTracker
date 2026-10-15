# Data flow

## Capture (SMS → Room)

```
Telephony SMS_RECEIVED
    → SmsReceiver (goAsync + IO coroutine)
    → ConfigStore.load()
    → SmsParser.parse(body, receivedAtMs)
         1. looksLikePayment (keywords: debited, spent, paid, UPI, cards, …)
         2. enabled smsParseRules in order (Java regex, named groups)
         3. fallback: first INR/Rs/₹ amount + heuristic merchant/ref
    → timestamp: datetime in body > rule date (+ SMS clock if date-only) > received time
    → credit-card bill merchants like “HDFC Bank Credit” / SBI credit → Transfer-Out
    → SmsDedupe.isDuplicate
         identical originalSms OR same bank reference OR (no ref) amount+merchant in window
    → insert Transaction reviewStatus=needs_review (if autoSaveFromSms)
         category/account may be prefilled from merchant_prefs (or recent reviewed history)
    → notification → TransactionEntryActivity (id extra, or parsed fields if not saved)
```

Built-in rule ids (see `AppConfig.DEFAULT_SMS_RULES`): `hdfc_card_spend`, `icici_card_spend`, `sbi_upi_debit`, `hsbc_debited_towards`, `hsbc_paid_to`, `hsbc_debit_card`, `sbi_yono_transfer`, `airtel_apb_debit`, `generic_paid_to_on`.

Custom rules: Admin → Test SMS → **Create rule from this SMS** (`SmsRuleWizardActivity` + `SmsRuleBuilder`) prepends a user rule to `smsParseRules` after verify.

## Review (Room → categorized Transaction)

`TransactionEntryActivity` writes:

- amount, merchant, account, category, subcategory, notes, timestamp
- `transactionType`: Expense | Income | Transfer-Out
- `participants` comma list (`ME,PUNEET,…`)
- `splitMode`: equal | exact | percent | weight
- `splitAllocations`: `Name:value` or Splitwise-id keys for non-equal modes
- Splitwise group id + participant id map
- `reviewStatus` → `reviewed` when saved as reviewed

On save with a non-blank category, `MerchantMemory.remember` upserts `merchant_prefs` so the next SMS for that merchant prefills category/subcategory (and account if the parse left it blank).

`SplitCalculator.resolve` is the single source of share amounts. Rounding leftover paisa goes to **ME** (or the first participant).

## Money Manager export (Transaction → TSV rows)

`MoneyManagerExporter.expandTransaction` may emit **one or two** MM rows per expense:

| Case | MM rows |
|---|---|
| Income / Transfer-Out | One row. Transfer-Out: Account = from, Category = destination account name |
| Expense, only ME | One row: chosen category |
| Expense + friends, default | Your share → chosen category; friends’ total → `debtCategory` / `debtSubcategory` (default Social Life / Dues) |
| Expense + friends, `mmFullSplitAsOwnExpense` | One row for the **full** amount; note includes `Split: …` |

TSV columns: `Date`, `Account`, `Category`, `Subcategory`, `Note`, `Amount`, `Income/Expense`, `Description`.

Date format in TSV is `MM/dd/yyyy` (US-style, what this exporter writes today).

`MoneyManagerSync` writes Downloads + a share URI, optionally marks those ids `exported`, then `MoneyManagerHandoff` tries to open `com.realbyteapps.moneymanagerfree` / `com.realbyteapps.moneya`.

## Splitwise (Transaction → Splitwise expense)

Queue for the Splits table:

- has participants other than only `ME`
- `reviewStatus = exported` and `splitwiseExpenseId = 0` (pending, including failed pushes)
- or `splitwiseExpenseId != 0` (done: real API id `> 0`, or manual success `-1`)

`SplitwisePusher`: paid by current user; each participant must have a Splitwise user id; shares from `SplitCalculator`. Success stores `splitwiseExpenseId`; failure stores `splitwisePushError`.

Splits screen actions on Pending:

1. **Export CSV** — writes file only; state unchanged
2. **Push to Splitwise** — API create; pending → Success (real expense id)
3. **Mark as success** — no API call; pending → Success (`splitwiseExpenseId = -1`) when the user already updated Splitwise themselves

Auth lives in EncryptedSharedPreferences (`SplitwiseAuthStore`); excluded from Auto Backup.

## LAN (browser ↔ same Room)

Ktor on the phone (`LocalHttpServer` + `LanAccessService`):

- `POST /api/login` → session token
- CRUD `/api/transactions`, bulk review/delete, `/api/meta` (accounts, categories, Splitwise groups)

Static UI: `app/src/main/assets/lan_web/` (`index.html`, `sheet.html`).

## Config

`ConfigStore` JSON in SharedPreferences (`AppConfig`): accounts, categories, SMS rules, dedupe, reminders, theme, MM split behavior.

## Entity (Room `transactions`, version 8)

Important fields: `amount`, `merchantName`, `category`, `subcategory`, `account`, `notes`, `timestamp`, `originalSms`, `referenceNumber`, `participants`, `splitMode`, `splitAllocations`, `reviewStatus`, `transactionType`, `splitwiseGroupId`, `splitwiseExpenseId`, `splitwiseParticipantIds`, `splitwisePushError`, `lastUpdatedTimestamp`.

Also `merchant_prefs`: last category/subcategory/account/type per normalized merchant key (survives export purge).
