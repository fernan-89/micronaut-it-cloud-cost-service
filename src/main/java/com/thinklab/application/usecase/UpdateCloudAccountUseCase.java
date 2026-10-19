package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdateCloudAccountRequest;
import com.thinklab.domain.model.Money;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for changing an account (BIAN Behavior Qualifier: {@code update}); the provider, its account id and the currency never change. */
@Singleton
public class UpdateCloudAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateCloudAccountUseCase.class);

    private final CloudAccountWorkflow workflow;

    public UpdateCloudAccountUseCase(CloudAccountWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, UpdateCloudAccountRequest request, String executor, String role) {
        log.info("[USE CASE] Updating CloudAccount ID: {}", id);
        return workflow.apply(id, organisationId, role, "change a cloud account",
                account -> account.update(request.name(), Money.toMinor(request.monthlyBudget()), request.warnPercent(), request.reportEveryHours(), request.vendorId(), executor));
    }
}
