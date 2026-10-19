package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.CloudAccountResponse;
import com.thinklab.application.mapper.CostMapper;
import com.thinklab.domain.exception.CloudAccountNotFoundException;
import com.thinklab.domain.repository.CloudAccountRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for reading one account of the tenant, its month judged now (BIAN Behavior Qualifier: {@code retrieve}). Staff only; another tenant is a 404. */
@Singleton
public class RetrieveCloudAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveCloudAccountUseCase.class);

    private final CloudAccountRepository repository;
    private final CostAssessor assessor;

    public RetrieveCloudAccountUseCase(CloudAccountRepository repository, CostAssessor assessor) {
        this.repository = repository;
        this.assessor = assessor;
    }

    public Mono<CloudAccountResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving CloudAccount ID: {}", id);
        return Mono.fromRunnable(() -> CostAccess.requireStaff(role, "read a cloud account"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new CloudAccountNotFoundException("CloudAccount " + id + " not found.")))
                .flatMap(assessor::judge)
                .map(judged -> CostMapper.toResponse(judged.account(), judged.assessment()));
    }
}
