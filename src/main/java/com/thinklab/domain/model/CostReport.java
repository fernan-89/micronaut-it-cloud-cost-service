package com.thinklab.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Secondary aggregate of the IT Cloud Cost Service Domain: what one day of an account cost, as the cost tool reported it (ADR-030). It is a fact,
 * never edited and never deleted by hand (old ones expire). The same account, day and amount reported again is the same report; a different
 * amount for a day already reported is a correction, a new fact, and the latest one of a day is the one that counts: billing exports do revise
 * the days before. A day may be at most one day after the registry's own (time zones).
 */
public class CostReport {

    private final UUID id;
    private final UUID organisationId;
    private final UUID accountId;
    private final LocalDate day;
    private final long amountMinor;
    private final String reportedBy;
    private final Instant reportedAt;

    private CostReport(UUID id, UUID organisationId, UUID accountId, LocalDate day, long amountMinor, String reportedBy, Instant reportedAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.accountId = accountId;
        this.day = day;
        this.amountMinor = amountMinor;
        this.reportedBy = reportedBy;
        this.reportedAt = reportedAt;
    }

    public static CostReport createNew(UUID id, UUID organisationId, UUID accountId, LocalDate day, long amountMinor, String executor, LocalDate today, Instant now) {
        if (id == null || organisationId == null || accountId == null) {
            throw new IllegalArgumentException("ID, Organisation ID and Account ID are mandatory for a CostReport.");
        }
        if (day == null) {
            throw new IllegalArgumentException("The day the cost is for is mandatory.");
        }
        if (day.isAfter(today.plusDays(1))) {
            throw new IllegalArgumentException("A cost cannot be reported for a day that has not come yet.");
        }
        if (amountMinor < 0 || amountMinor > Money.MAX_MINOR) {
            throw new IllegalArgumentException("A cost must be zero or more, and not absurdly large.");
        }
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory to report a CostReport.");
        }
        return new CostReport(id, organisationId, accountId, day, amountMinor, executor, now);
    }

    public static CostReport reconstitute(UUID id, UUID organisationId, UUID accountId, LocalDate day, long amountMinor, String reportedBy, Instant reportedAt) {
        if (id == null || organisationId == null || accountId == null || day == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Account ID and the day are mandatory to reconstitute a CostReport.");
        }
        return new CostReport(id, organisationId, accountId, day, amountMinor, reportedBy, reportedAt);
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getAccountId() { return accountId; }
    public LocalDate getDay() { return day; }
    public long getAmountMinor() { return amountMinor; }
    public String getReportedBy() { return reportedBy; }
    public Instant getReportedAt() { return reportedAt; }
}
