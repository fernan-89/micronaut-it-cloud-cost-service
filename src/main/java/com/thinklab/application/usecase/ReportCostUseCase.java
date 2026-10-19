package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.ReportCostRequest;
import com.thinklab.application.dto.response.CostReportResponse;
import com.thinklab.application.mapper.CostMapper;
import com.thinklab.domain.exception.CloudAccountNotFoundException;
import com.thinklab.domain.model.CostReport;
import com.thinklab.domain.model.Money;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.CloudAccountRepository;
import com.thinklab.domain.repository.CostReportRepository;
import com.thinklab.domain.repository.CostReportRepository.Stored;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Use Case for what the cost tool reports (BIAN Behavior Qualifier: {@code cost/initiate}), ADR-030. Idempotent: the same account, day and amount is
 * the same report, so a tool may retry; the stored report comes back restated at this moment, with {@code created} false. Another amount for the
 * same day is a correction (a new report; the latest of a day counts), and going back to an earlier amount restates it, so it counts again. Either way the moment of the last report is moved forward. A sovereign id is spent only for a new report.
 */
@Singleton
public class ReportCostUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReportCostUseCase.class);

    /** The report as stored, and whether this call created it (201) or found it already there (200). */
    public record Reported(CostReportResponse report, boolean created) {
    }

    private final HashServicePort hashServicePort;
    private final CloudAccountRepository accounts;
    private final CostReportRepository reports;
    private final Clock clock;

    public ReportCostUseCase(HashServicePort hashServicePort, CloudAccountRepository accounts, CostReportRepository reports, Clock clock) {
        this.hashServicePort = hashServicePort;
        this.accounts = accounts;
        this.reports = reports;
        this.clock = clock;
    }

    public Mono<Reported> execute(UUID organisationId, ReportCostRequest request, String executor, String role) {
        log.info("[USE CASE] Reporting a cost for CloudAccount ID: {}", request.accountId());
        return Mono.fromRunnable(() -> CostAccess.requireStaff(role, "report a cost"))
                .then(Mono.fromCallable(() -> Money.toMinor(request.amount())))
                .flatMap(amount -> accounts.findById(request.accountId(), organisationId)
                        .switchIfEmpty(Mono.error(new CloudAccountNotFoundException("CloudAccount " + request.accountId() + " not found.")))
                        .flatMap(account -> reports.restate(organisationId, account.getId(), request.day(), amount, Instant.now(clock))
                                .map(existing -> new Stored(existing, false))
                                .switchIfEmpty(Mono.defer(() -> store(organisationId, request, amount, executor)))))
                .flatMap(stored -> accounts.recordReport(request.accountId(), organisationId, stored.report().getReportedAt())
                        .thenReturn(new Reported(CostMapper.toResponse(stored.report()), stored.created())));
    }

    private Mono<Stored> store(UUID organisationId, ReportCostRequest request, long amount, String executor) {
        Instant now = Instant.now(clock);
        return Mono.fromCallable(() -> CostReport.createNew(UUID.randomUUID(), organisationId, request.accountId(), request.day(), amount, executor,
                        LocalDate.ofInstant(now, ZoneOffset.UTC), now))
                .then(Mono.defer(() -> hashServicePort.generateSovereignId("cost-report-creation")))
                .map(id -> CostReport.createNew(id, organisationId, request.accountId(), request.day(), amount, executor, LocalDate.ofInstant(now, ZoneOffset.UTC), now))
                .flatMap(reports::create);
    }
}
