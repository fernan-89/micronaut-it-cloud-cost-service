# ADR-032: The Cloud Cost Registry Is for IT Staff and Their Tools

## Status
Accepted

## Decision
- Every route refuses a `REQUESTER` with 403 `ERR-CLD-00403`; any other role, or none, is staff (the same rule as the other operations services). The tools that report costs are service identities, not requesters.
- Every lookup is scoped by `X-Tenant-Id`: another tenant's account or report is a 404, never a 403.
- **There is no delete.** An account is paused (its history stays); a report is a fact.
- Audit is kept on the account for real events (initiated, updated, paused, resumed) with the executor id. Reports are not audited: they are the record themselves, with `reportedBy`.
- **Nothing a tool says is kept as text**: a report is a day, an amount and the executor.

## Consequences
- Positive: one access rule across the operations services; no free text that could leak a secret or a name.
- Negative: a report cannot carry a note; that stays in the tool.
