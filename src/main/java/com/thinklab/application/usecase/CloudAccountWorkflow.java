package com.thinklab.application.usecase;

import com.thinklab.domain.exception.CloudAccountNotFoundException;
import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.CloudAccount.AccountAuditEntry;
import com.thinklab.domain.repository.CloudAccountRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** The shape every staff change to a account shares: staff only, load (a 404 if it is not the tenant's), apply, save with the status loaded. */
@Singleton
public class CloudAccountWorkflow {

    private final CloudAccountRepository repository;

    public CloudAccountWorkflow(CloudAccountRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> apply(UUID id, UUID organisationId, String role, String operation, Function<CloudAccount, AccountAuditEntry> action) {
        return Mono.fromRunnable(() -> CostAccess.requireStaff(role, operation))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new CloudAccountNotFoundException("CloudAccount " + id + " not found.")))
                .flatMap(account -> {
                    var statusBefore = account.getStatus();
                    AccountAuditEntry entry = action.apply(account);
                    return repository.save(account, statusBefore, entry);
                });
    }
}
