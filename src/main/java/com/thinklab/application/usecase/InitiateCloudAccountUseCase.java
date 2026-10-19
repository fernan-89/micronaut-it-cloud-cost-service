package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateCloudAccountRequest;
import com.thinklab.application.dto.response.CloudAccountResponse;
import com.thinklab.application.mapper.CostMapper;
import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.Money;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.CloudAccountRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for registering a cloud account and its monthly budget (BIAN Behavior Qualifier: {@code initiate}). Staff only. */
@Singleton
public class InitiateCloudAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateCloudAccountUseCase.class);

    private final HashServicePort hashServicePort;
    private final CloudAccountRepository repository;
    private final CostAssessor assessor;

    public InitiateCloudAccountUseCase(HashServicePort hashServicePort, CloudAccountRepository repository, CostAssessor assessor) {
        this.hashServicePort = hashServicePort;
        this.repository = repository;
        this.assessor = assessor;
    }

    public Mono<CloudAccountResponse> execute(UUID organisationId, InitiateCloudAccountRequest request, String executor, String role) {
        log.info("[USE CASE] Creating the CloudAccount [{}] for organisation: {}", request.name(), organisationId);
        return Mono.fromRunnable(() -> CostAccess.requireStaff(role, "register a cloud account"))
                .then(Mono.fromCallable(() -> Money.toMinor(request.monthlyBudget())))
                .flatMap(budget -> hashServicePort.generateSovereignId("cloud-account-creation")
                        .map(id -> CloudAccount.createNew(id, organisationId, request.name(), request.provider(), request.externalId(), request.currency(), budget,
                                request.warnPercent(), request.reportEveryHours(), request.vendorId(), executor)))
                .flatMap(repository::create)
                .flatMap(assessor::judge)
                .map(judged -> CostMapper.toResponse(judged.account(), judged.assessment()));
    }
}
