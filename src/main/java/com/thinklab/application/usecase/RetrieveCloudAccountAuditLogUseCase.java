package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.CostMapper;
import com.thinklab.domain.exception.CloudAccountNotFoundException;
import com.thinklab.domain.repository.CloudAccountRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of a account (BIAN Behavior Qualifier: {@code audit-log/retrieve}). Staff only. */
@Singleton
public class RetrieveCloudAccountAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveCloudAccountAuditLogUseCase.class);

    private final CloudAccountRepository repository;

    public RetrieveCloudAccountAuditLogUseCase(CloudAccountRepository repository) {
        this.repository = repository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of CloudAccount ID: {}", id);

        return Mono.fromRunnable(() -> CostAccess.requireStaff(role, "read the audit trail of a cloud account"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new CloudAccountNotFoundException("CloudAccount " + id + " not found.")))
                .map(account -> account.getAuditTrail().stream().map(CostMapper::toResponse).collect(Collectors.toList()));
    }
}
