# Problem this app solves

## The gap

Personal spending in India often lands as SMS from banks and UPI. The source of truth for the user’s books is **Realbyte Money Manager** on Android. Shared meals and trips also need to land in **Splitwise**.

Doing that by hand is slow and error-prone:

- Copy amount, merchant, date, and account from SMS into Money Manager.
- Remember which UPI went to a credit-card bill (should be a transfer, not an expense).
- Split a bill equally (or unevenly) so *your* share is a real category (e.g. Movie) and friends’ shares become **Dues**.
- Later re-type the same split into Splitwise.

The older workflow in this monorepo was a **Python spreadsheet converter** (`expense-data-manual/` → `converted/import.tsv`). That is good for backfilling history. It is not useful for *today’s* SMS.

## What this app is

A **local-first capture and review layer** between the SMS inbox and Money Manager (and optionally Splitwise).

It does **not** replace Money Manager. It produces a TSV that Money Manager already knows how to import (`More → Backup → Import Excel File`). Account, category, and subcategory names must match the names configured in Money Manager.

## Success looks like

1. A debit SMS arrives → a draft transaction exists without typing.
2. End of day: open **Needs review**, set category / splits, mark reviewed.
3. Export TSV from the Reviewed list → import in Money Manager in one shot.
4. Shared expenses that were exported wait on the **Splits** screen until pushed to Splitwise (export does not push to Splitwise).

## Non-goals

- Cloud sync of the ledger (no backend of our own).
- Being a full accounting app (no reports, budgets, or MM-style dashboards).
- Parsing every bank in India out of the box (rules are JSON-configurable; unknown SMS fall back to a generic amount/merchant parse or stay uncaptured).
- Storing Splitwise credentials in Android Auto Backup (auth prefs are excluded on purpose).
