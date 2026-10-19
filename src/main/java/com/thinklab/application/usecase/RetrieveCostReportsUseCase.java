package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.CostReportResponse;
import com.thinklab.application.mapper.CostMapper;
import com.thinklab.domain.repository.CostReportRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for listing the tenant's reports, newest first, optionally of one account, at most {@value #MAX_LIMIT} (BIAN Behavior Qualifier: {@code cost/retrieve}, collection). */
@Singleton
public class RetrieveCostReportsUseCase {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(RetrieveCostReportsUseCase.class);

    private final CostReportRepository repository;

    public RetrieveCostReportsUseCase(CostReportRepository repository) {
        this.repository = repository;
    }

    public Flux<CostReportResponse> execute(UUID organisationId, UUID accountId, Integer limit, String role) {
        log.info("[USE CASE] Retrieving the CostReports of organisation: {}", organisationId);

        return Mono.fromRunnable(() -> CostAccess.requireStaff(role, "list usage reports"))
                .then(Mono.fromCallable(() -> effectiveLimit(limit)))
                .flatMapMany(effective -> repository.findAll(organisationId, accountId, effective))
                .map(CostMapper::toResponse);
    }

    private static int effectiveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("The limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
    }
}
