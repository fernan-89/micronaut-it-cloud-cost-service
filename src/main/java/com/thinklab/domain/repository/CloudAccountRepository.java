package com.thinklab.domain.repository;

import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.CloudAccount.AccountAuditEntry;
import com.thinklab.domain.model.CloudAccount.AccountStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Outbound Port for CloudAccount persistence. {@link #save} is a guarded write on the status loaded (ADR-033); every lookup is tenant-scoped. */
public interface CloudAccountRepository {

    /**
     * Inserts the account. A name already used in the organisation, or the same provider account registered twice, is
     * {@link com.thinklab.domain.exception.DuplicateCloudAccountException}: unique indexes are the atomic backstops.
     */
    Mono<CloudAccount> create(CloudAccount account);

    Mono<CloudAccount> findById(UUID id, UUID organisationId);

    /** The tenant's accounts, oldest first. */
    Flux<CloudAccount> findAll(UUID organisationId);

    Mono<Void> save(CloudAccount account, AccountStatus expectedStatus, AccountAuditEntry auditEntry);

    /** A report arrived for the account at that moment: the moment kept only moves forward. */
    Mono<Void> recordReport(UUID accountId, UUID organisationId, Instant reportedAt);
}
