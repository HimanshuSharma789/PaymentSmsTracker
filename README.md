# Payment SMS Tracker

Local Android app that captures bank/UPI payment SMS, lets you review/split them end of day, and exports Money Manager (Realbyte) TSV — no cloud, data stays on your phone.

## Project memory (humans and agents)

Longer write-ups live in [`docs/`](docs/INDEX.md): problem, user flow, data flow, architecture.

- Start: [docs/INDEX.md](docs/INDEX.md)
- Agents: [AGENTS.md](AGENTS.md)

## Daily flow

1. SMS arrives → auto-parsed and saved as **Needs review**
2. Home shows counts → **Review spends** → set account, category, split if needed → **Save as Reviewed**
3. Home → **Export to Money Manager** (Reviewed list) → file in Downloads
4. Import that `.tsv` in Money Manager
5. Shared expenses appear under **Splits → Pending** until you push to Splitwise

## Admin (non-daily)

Home → **Admin**:

- Accounts / participants / debt category
- SMS dedupe window + auto-save toggle
- Full config JSON (edit regex rules with named groups: `amount`, `merchant`, `date`, `reference`)
- Test paste-SMS parser + **Create rule from this SMS** wizard
- Merchant memory learns category/account when you save a reviewed (or categorized) transaction
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
