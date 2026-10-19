# ADR-033: Guarded Writes, Unique Backstops and Expiring Reports

## Status
Accepted. Same mechanics as the capacity and licence registries (their ADR-033).

## Decision
- Every change to an account is **one atomic update** guarded by the status that was loaded, which also appends the audit entry; the loser of a race gets a 409 (`ERR-CLD-00409`) and the trail never records a change that did not happen.
- **Unique indexes are the atomic backstops**: one account per `(organisationId, name)` and per `(organisationId, provider, externalId)`; one report per `(organisationId, accountId, day, amountMinor)`. The loser of twenty simultaneous identical reports gets the stored report back.
- A repeat **restates** the stored report with `$max` on its moment, so it only moves forward: the latest stated amount of a day wins whatever the order, and a retry after a crash between storing the report and touching the account repairs the account.
- The account's last report moment also only moves forward (an update guarded by "none yet, or an older one").
- An index on `(organisationId, accountId, day, reportedAt)` serves the month read; a **TTL index** on `reportedAt` expires reports after the retention (default 400 days).

## Consequences
- Positive: no lost update, no duplicate under concurrency, no lock; the store does not grow forever.
- Negative: a caller that loses a race must read again and retry; reports older than the retention are gone, and a restated report lives longer.
