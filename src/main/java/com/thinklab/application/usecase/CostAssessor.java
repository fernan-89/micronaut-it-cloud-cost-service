package com.thinklab.application.usecase;

import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.CloudAccount.Assessment;
import com.thinklab.domain.repository.CostReportRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

/** Judges an account now (ADR-031): reads what each reported day of the current month (UTC) cost and lets the account assess itself. Nothing is stored. */
@Singleton
public class CostAssessor {

    /** An account and how its month was judged. */
    public record Judged(CloudAccount account, Assessment assessment) {
    }

    private final CostReportRepository reports;
    private final Clock clock;

    public CostAssessor(CostReportRepository reports, Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    public Mono<Judged> judge(CloudAccount account) {
        return Mono.defer(() -> {
            Instant now = Instant.now(clock);
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            return reports.latestOfMonth(account.getOrganisationId(), account.getId(), YearMonth.from(today))
                    .map(days -> new Judged(account, account.assess(today, now, days)));
        });
    }
}
