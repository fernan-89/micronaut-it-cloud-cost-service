package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.CostReportResponse;
import com.thinklab.application.mapper.CostMapper;
import com.thinklab.domain.exception.CostReportNotFoundException;
import com.thinklab.domain.repository.CostReportRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for reading one report of the tenant (BIAN Behavior Qualifier: {@code cost/retrieve}). Staff only; another tenant is a 404. */
@Singleton
public class RetrieveCostReportUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveCostReportUseCase.class);

    private final CostReportRepository repository;

    public RetrieveCostReportUseCase(CostReportRepository repository) {
        this.repository = repository;
    }

    public Mono<CostReportResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving CostReport ID: {}", id);

        return Mono.fromRunnable(() -> CostAccess.requireStaff(role, "read a usage report"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new CostReportNotFoundException("CostReport " + id + " not found.")))
                .map(CostMapper::toResponse);
    }
}
