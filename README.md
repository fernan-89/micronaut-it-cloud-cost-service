# micronaut-it-cloud-cost-service

BIAN-aligned Service Domain **it-cloud-cost** (Control Record: `CloudAccount`), port `8111`.

A slice of Journey 15 (breadth): a **registry of cloud costs**. A `CloudAccount` is a provider account the organisation pays for, with one
currency and a monthly budget. A `CostReport` is what a billing export or FinOps tool says one day of that account cost. The registry says,
**when you ask**, how much of this month's budget is spent, where the month is heading, and whether the account is fine, near its budget or over it.

## What it guarantees, and what it does not

- **It only records** (ADR-030): the tool reports the cost of each day (`cost/initiate`). The registry never calls a cloud, reads a billing API
  or holds a credential. An account may name its vendor (a vendor registry id, not checked).
- **One currency per account, no conversion** (ADR-030): `BRL`, `USD` or `EUR`, fixed when the account is created. Amounts are decimals with at
  most two places, kept in minor units (cents); the registry never converts and never sums two currencies.
- **A total per day, nothing else** (ADR-030): a report is an account, a UTC day and an amount. No resource, tag or person is kept. A day may be at
  most one day ahead of the registry's UTC day. Never edited, never deleted by hand; reports expire after
  `thinklab.cloud-cost.report-retention-days` (default 400).
- **Idempotent, with corrections** (ADR-030, ADR-033): the same account, day and amount is the same report (200; a new one is 201), even 20 at
  once. Another amount for a day is a correction and the latest stated amount of each day counts; going back to an earlier amount counts again.
- **An account is judged when read** (ADR-031), never stored, over the current UTC month: `spent` (the month so far), `projected` (a straight
  line to the month's end), `usedPercent`, `level` (`OK`, `NEAR_BUDGET` from `warnPercent` on or when `projected` is above the budget,
  `OVER_BUDGET` when `spent` is above it, `NO_DATA`, `PAUSED`) and `stale` (the tool silent for more than twice its expected interval; still judged).
- **Staff only** (ADR-032): every route refuses a `REQUESTER` with 403 `ERR-CLD-00403`; another tenant's account or report answers 404.
  There is no delete (an account is paused).
- **Safe with several instances** (ADR-033): one account per name and per provider account in an organisation, one report per
  `(account, day, amount)`, every change guarded by the status loaded, and moments that only move forward whatever the order of the reports.
- **Not here yet:** cost per resource or tag, currency conversion, past months' verdicts, telling someone when an account goes over (a later
  pairing with alerting).

## BIAN Behavior Qualifier Contract

`X-Tenant-Id` is mandatory on every call; `X-Executor` is mandatory on the actions (for a report, the name of the tool); `X-Role` is optional
and, with platform security on, comes from the verified token.

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /it-cloud-cost/v1/initiate` `{"name":"Production","provider":"AWS","externalId":"123456789012","currency":"USD","monthlyBudget":3100.00,"warnPercent":80,"reportEveryHours":24,"vendorId":"<uuid, optional>"}` (`provider`: AWS, AZURE, GCP, OCI, OTHER) |
| retrieve | `GET /it-cloud-cost/v1/{id}/retrieve` (judged now) |
| retrieve (collection) | `GET /it-cloud-cost/v1/retrieve?status=&provider=&currency=&level=` (oldest first) |
| update | `PUT /it-cloud-cost/v1/{id}/update` `{"name":…,"monthlyBudget":…,"warnPercent":…,"reportEveryHours":…,"vendorId":…}` (provider, account id and currency stay) |
| control/pause | `PUT /it-cloud-cost/v1/{id}/control/pause` (ACTIVE -> PAUSED: no longer judged) |
| control/resume | `PUT /it-cloud-cost/v1/{id}/control/resume` (PAUSED -> ACTIVE) |
| audit-log/retrieve | `GET /it-cloud-cost/v1/{id}/audit-log/retrieve` |
| cost/initiate | `POST /it-cloud-cost/v1/cost/initiate` `{"accountId":"<uuid>","day":"2026-10-18","amount":102.37}` |
| cost/retrieve | `GET /it-cloud-cost/v1/cost/{id}/retrieve` |
| cost/retrieve (collection) | `GET /it-cloud-cost/v1/cost/retrieve?accountId=&limit=` (newest day first, 50 unless asked, at most 200) |

```text
account: ACTIVE <-> PAUSED ; judged when read: NO_DATA / OK / NEAR_BUDGET / OVER_BUDGET
report:  a fact (what a day cost), its moment restated on a repeat, expires
```

```bash
curl -X POST "http://localhost:8111/it-cloud-cost/v1/cost/initiate" -H "X-Tenant-Id: <organisationId>" -H "X-Executor: billing-export" \
  -H "Content-Type: application/json" \
  -d '{"accountId":"<accountId>","day":"2026-10-18","amount":102.37}'
```

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-CLD-00403` | 403 | A REQUESTER used the cloud cost registry (ADR-032) |
| `ERR-CLD-00404` | 404 | Account or report not found (another tenant's answers the same) |
| `ERR-CLD-00409` | 409 | A second account with the same name or provider account, illegal transition (pause a paused account), or it changed while the write was applied (retry) |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure, an amount with more than two decimals or negative, a day that has not come yet, a date that cannot be read |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## Configuration

`HASH_SERVICE_URL` (default `http://localhost:8080`, the sovereign ids), `MICRONAUT_SERVER_PORT` (default 8111), `mongodb.uri`,
`thinklab.mongo.create-indexes` (default on), `THINKLAB_CLOUD_COST_REPORT_RETENTION_DAYS` (default 400). The service calls no other service and
has no scheduler.

## Architecture decisions

001 hexagonal architecture · 005 UUID identity sovereignty and audit tracing · 013 BIAN conventions · 019 HTTP 409 for state conflicts ·
030 the tool reports, the registry keeps the costs · 031 an account is judged when read · 032 staff only · 033 guarded writes, unique backstops and expiring reports.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
