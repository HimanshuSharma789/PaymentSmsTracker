# Payment SMS Tracker

Local Android app that captures bank/UPI payment SMS, lets you review/split them end of day, and exports Money Manager (Realbyte) TSV — no cloud, data stays on your phone.

## Daily flow

1. SMS arrives → auto-parsed and saved as **Needs review**
2. Open **Review (Needs Attention)** → set account, category, split friends if needed → **Save as Reviewed**
3. Tap **Export Reviewed → Money Manager TSV** → file in Downloads
4. Import that `.tsv` in Money Manager

## Admin (non-daily)

Home → **Admin**:

- Accounts / participants / debt category
- SMS dedupe window + auto-save toggle
- Full config JSON (edit regex rules with named groups: `amount`, `merchant`, `date`, `reference`)
- Test paste-SMS parser
- Export raw CSV / Money Manager TSV / clear all / reset defaults

## Built-in SMS samples covered

- HDFC card spend (`Spent Rs… At … On yyyy-MM-dd`)
- SBI UPI debit (`debited by … trf to … Refno`)
- HSBC debited towards / paid to
- SBI YONO transfer

Credit-card bill style merchants (e.g. `HDFC Bank Credit`) are suggested as `Transfer-Out`.

## Split rule

Equal split across checked participants. Your share goes to the chosen category; others go to `Social Life / Dues` (configurable).

Example: ₹100 with `ME,PUNEET,KUSHAGRA,MUKUL` → ₹25 Movie + ₹75 Dues.

## Splitwise API (optional)

CSV export still works. To push splits:

1. **Admin → Splitwise → Sign in with Splitwise** — in-app WebView (MFA and CAPTCHA stay on Splitwise’s site). Session cookies (`_splitwise_session`, `user_credentials`) and CSRF are stored in EncryptedSharedPreferences (Android Keystore).
2. Or paste a **personal API key** / use OAuth consumer key+secret if you have a registered app.
3. Refresh groups / map friends / push as before.

On HTTP 401 the app clears cached tokens and prompts you to sign in again. Do not back up those prefs (they are excluded from Auto Backup).

## Build

Open in Android Studio, sync Gradle, run on device. Grant SMS + notification permissions from the home screen.
