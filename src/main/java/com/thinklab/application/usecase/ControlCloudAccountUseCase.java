package com.thinklab.application.usecase;

import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.CloudAccount.AccountAuditEntry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for pausing and resuming a account (BIAN Behavior Qualifier: {@code control/*}). */
@Singleton
public class ControlCloudAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlCloudAccountUseCase.class);

    /** What a person can ask for; each constant says how the aggregate performs it. */
    public enum Action {
        PAUSE {
            @Override AccountAuditEntry apply(CloudAccount account, String executor) { return account.pause(executor); }
        },
        RESUME {
            @Override AccountAuditEntry apply(CloudAccount account, String executor) { return account.resume(executor); }
        };

        abstract AccountAuditEntry apply(CloudAccount account, String executor);
    }

    private final CloudAccountWorkflow workflow;

    public ControlCloudAccountUseCase(CloudAccountWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, Action action, String executor, String role) {
        log.info("[USE CASE] {} on CloudAccount ID: {}", action, id);

        return workflow.apply(id, organisationId, role, "pause or resume a cloud account", account -> action.apply(account, executor));
    }
}
