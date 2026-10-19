# ADR-030: The Tool Reports, the Registry Keeps the Costs

## Status
Accepted. Same shape as the capacity and licence registries (their ADR-030).

## Context
Journey 15 needs to know whether each cloud account is on its way past its monthly budget. What an account cost on a day is what the provider's billing export or a FinOps tool knows; the user chose that the **tool reports the cost of each day**, and that each account has **one currency, with no conversion**.

## Decision
- **The registry only records.** A `CloudAccount` is a provider account the organisation pays for: a name (unique per organisation), the provider (`AWS`, `AZURE`, `GCP`, `OCI`, `OTHER`), the provider's own account id (`externalId`, up to 64 letters, digits, `.`, `_`, `:` or `-`, unique per organisation and provider), its currency (`BRL`, `USD`, `EUR`, fixed once created), a monthly budget, the use at which to warn (`warnPercent`, 1 to 100) and how often the tool is expected to report (1 to 720 hours). It may name its vendor (a vendor registry id, **not checked**: the registries stay independent).
- **Money is kept in minor units** (`long`, cents): an amount is sent as a decimal with at most two places, never negative, at most 100 billion; anything else is a 400. Every amount of an account is in the account's currency; the registry never converts and never adds two currencies.
- **A cost report is what a day cost**: the account, the UTC day and the amount. No resource, tag, service name or person is kept: a total per day, nothing that could leak an owner or a secret. A day may be at most one day after the registry's UTC day (the provider's day may be ahead).
- **Reporting is idempotent**: the same account, day and amount is the same report (unique index). A repeat answers 200 with the stored report, restated at this moment; a new one 201.
- **Corrections are new reports**: another amount for a day already reported is a new report, and the latest stated amount of each day is the one that counts. Billing exports revise days for a while; the tool simply reports the day again. Going back to an earlier amount restates that report (its moment moves forward), so it counts again.
- Reports are never edited by hand and never deleted by hand; they expire after `thinklab.cloud-cost.report-retention-days` (default 400).
- The account keeps the moment of the last report, only ever moving forward, to tell whether the tool has gone silent.

## Consequences
- Positive: no cloud credentials, no access to billing APIs, no personal data; any tool that can send a number per day can report.
- Negative: the registry trusts the totals; it cannot say which resource costs too much, only that the account does. Accounts in different currencies are never summed.
