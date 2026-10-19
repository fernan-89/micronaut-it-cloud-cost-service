# ADR-031: An Account Is Judged When Read

## Status
Accepted. Same principle as the other registries (capacity ADR-031, licence ADR-031).

## Decision
- **Nothing of the judgment is stored.** Every read of an account judges the current UTC month from the latest report of each day of that month:
  - `PAUSED`: not judged;
  - `NO_DATA`: no day of this month reported yet;
  - `OVER_BUDGET`: the month so far (`spent`) is above the budget;
  - `NEAR_BUDGET`: `spent` reached `warnPercent` of the budget, **or** the month's end (`projected`) is above the budget;
  - `OK`: otherwise.
- `projected` is a straight line: `spent` spread over the days up to the last day reported, carried to the last day of the month. Days not reported before the last reported one count as zero (the tool reports them when it can, and the line moves). It is a hint, not a forecast model; it warns early in the month when the pace is too fast.
- `usedPercent` is `spent` over the budget (0 when the budget is 0; then any cost is over budget). `stale` is true when the tool has been silent for more than twice its expected interval: a stale account is still judged, because costs keep running when nobody reports them.
- The month is the UTC month of the moment of reading; a new month starts at `NO_DATA`. Past months stay in the reports.
- The list filters by `status`, `provider`, `currency` and `level`; the level is judged when read, so it is filtered in memory over the tenant's accounts, oldest first.

## Consequences
- Positive: it cannot go stale; a later alerting rule can ask "which account is over or heading over its budget" without a job keeping a flag true.
- Negative: no history of past verdicts; one month at a time; the line misleads when costs are lumpy (a monthly charge on day 1 projects thirty of them).
