# Privacy Policy: Payment SMS Tracker

_Effective date: <YYYY-MM-DD>_ · _Developer: <Developer name>_ · _Contact: <contact email>_

> DRAFT. Fill in the placeholders, host this at a public URL, and link it from Play Console and from inside the app. Keep it in sync with `docs/PLAY_STORE.md` §4.3.

Payment SMS Tracker ("the app") is a personal expense tracker. It turns the payment SMS your bank or UPI app sends into transactions that you review, and it exports them to Realbyte Money Manager. **The app is local-first.** We (the developer) do not operate any server. We do not receive, store, sell or share your data, and the app has no ads, analytics or tracking.

## 1. Data the app uses on your phone

| Data | Why | Where it is kept |
|---|---|---|
| **SMS from bank/UPI senders** (sender, text, time) | To read the amount, merchant, date, account and reference number of a payment. Messages from senders that do not match your bank-sender rules are skipped and not stored. | On your phone, in the app's private database. |
| **Transactions** you create or review (amount, merchant, category, account, notes, splits with friends' names) | The core purpose of the app. | On your phone. |
| **Location** (optional, off unless you grant it) | To tag a transaction with where you were when its payment SMS arrived. Collected only while you are using the app, never in the background. | On your phone, attached to that transaction. |
| **Settings and merchant memory** (your accounts, categories, SMS rules, last category per merchant) | To fill in future transactions. | On your phone. |
| **Exported files** | When you tap export, a TSV/CSV file is written to your Downloads folder so you can import it into Money Manager or share it. | Your Downloads folder, controlled by you. |
| **Notifications** | To alert you about a new transaction and remind you nightly to review. | Shown on your phone only. |

**Backups:** the transaction database, Splitwise credentials, AI API keys and the Splitwise web-login cookies are excluded from Android cloud backup. If you use Android's phone-to-phone transfer, your transactions and settings move to your new phone, but credentials and keys do not.

**Deleting data:** you can delete single transactions or many at once, clear exported transactions in Settings, or uninstall the app. Uninstalling removes everything the app stored except files you exported to Downloads.

## 2. Data that leaves your phone, only when you turn a feature on

The app sends data off your phone in exactly three optional cases. Each one is started by you, and in each case the data goes **directly from your phone to the service you chose**, never to us.

### a) Splitwise (optional)
If you connect your Splitwise account (sign in on Splitwise's own web page, OAuth, or your own API key) and push a shared expense, the app sends Splitwise:
- the expense description (merchant name), amount, currency and date,
- the category, your notes and the bank reference number (in the expense "details"),
- who paid and each person's share, identified by Splitwise user IDs.

The app also downloads your Splitwise profile, groups and friends' names and emails so you can choose who to split with. These are stored only on your phone. Splitwise handles your data under its own privacy policy: https://www.splitwise.com/privacy. You can sign out in Settings → Splitwise, which deletes the stored session from the phone.

### b) AI Assist (optional, off by default)
AI Assist works only after you enable it and enter **your own** API key for Anthropic (Claude), OpenRouter, or an OpenAI-compatible endpoint you choose. When you use it, the app sends that provider the message you type plus **only the fields you switch on** under Settings → AI Assist → "What the AI can read":
- amounts, merchant names, dates, categories and accounts, notes, friends' names and split details, merchant-memory hints, other transactions (for search) (each can be switched off),
- original SMS text, and location (**off by default**).

The app **never** sends bank reference numbers, Splitwise tokens, or API keys to the model (your API key goes only to its own provider, to authenticate). The AI can only *suggest* changes. Nothing in your data changes unless you tap Apply. The provider handles the data under its own terms:
- Anthropic: https://www.anthropic.com/legal/privacy
- OpenRouter: https://openrouter.ai/privacy (OpenRouter forwards requests to the model's vendor)
- Any other endpoint you enter: that operator's policy.

Cloud endpoints are always contacted over HTTPS. Plain HTTP is allowed only to a model server on your own phone or local network (for example Ollama), and no API key is sent over HTTP. Your API key is stored encrypted on the phone and is never backed up.

### c) PC Manager (optional, local Wi-Fi only)
When you tap **Start server**, the phone serves a web page on your local Wi-Fi so you can review transactions in a browser on your own computer. While the server runs, anyone on the same network who has the access code shown on your phone can view and edit transactions. If you turn off "Require access code", anyone on that network can. The server stops on its own after the time you choose, or when you tap Stop. The data does not go to the internet.

## 3. Permissions and why

| Permission | Use |
|---|---|
| Receive SMS, Read SMS | Create transactions from bank/UPI payment SMS. Read SMS is used for "SMS backfill" over a date range you pick. The app never sends SMS. |
| Location (while in use, optional) | Tag a transaction with where it happened. |
| Notifications | New-transaction alerts and the nightly review reminder. |
| Internet, network/Wi-Fi state | Optional Splitwise and AI Assist, and finding the phone's Wi-Fi address for PC Manager. |
| Foreground service | Keeps PC Manager running while it is on, with an ongoing notification. |
| Run at startup | Re-schedules the nightly reminder after a reboot. |
| Storage (Android 9 and older only) | Save export files to Downloads. |

## 4. Children
The app is for adults managing their own finances and is not directed at children under 13 (or the minimum age in your country).

## 5. Security
Your data stays in the app's private storage, which other apps cannot read. Splitwise credentials and AI keys are stored with Android Keystore-backed encryption where the device supports it. Release builds remove debug logging so SMS content is not written to system logs.

## 6. Changes
If what the app collects or sends changes, we will update this policy and the effective date above before releasing that version.

## 7. Contact
Questions or requests: <contact email>.
