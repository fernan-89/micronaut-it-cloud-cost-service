package com.thinklab.domain.repository;

import com.thinklab.domain.model.CloudAccount.DayCost;
import com.thinklab.domain.model.CostReport;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Outbound Port for CostReport persistence. A report is what the tool said a day cost; it is never edited, only its last-stated moment moves
 * forward when the tool says the same again. Every lookup is tenant-scoped.
 */
public interface CostReportRepository {

    /**
     * Inserts the report. The same account, day and amount is the same report (a unique index): the loser of a simultaneous report gets the
     * stored one back, restated at its moment, and {@code created} false. A different amount for the same day is a new report (a correction).
     */
    Mono<Stored> create(CostReport report);

    /**
     * The tool said that account, day and amount again: the stored report's moment moves forward to {@code at} (never back), so going back to
     * an earlier amount of a day counts again. Empty when there is no such report.
     */
    Mono<CostReport> restate(UUID organisationId, UUID accountId, LocalDate day, long amountMinor, Instant at);

    Mono<CostReport> findById(UUID id, UUID organisationId);

    /** The tenant's reports, newest first (by day, then by arrival), at most {@code limit}, optionally of one account. */
    Flux<CostReport> findAll(UUID organisationId, UUID accountId, int limit);

    /** What each reported day of the month cost the account: the latest report of each day. */
    Mono<List<DayCost>> latestOfMonth(UUID organisationId, UUID accountId, YearMonth month);

    record Stored(CostReport report, boolean created) {
    }
}
