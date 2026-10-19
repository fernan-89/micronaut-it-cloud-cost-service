package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.CloudAccountResponse;
import com.thinklab.application.mapper.CostMapper;
import com.thinklab.domain.model.CloudAccount.AccountStatus;
import com.thinklab.domain.model.CloudAccount.Level;
import com.thinklab.domain.model.CloudAccount.Provider;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.repository.CloudAccountRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for listing the tenant's accounts, oldest first, each month judged now, filterable by status, provider, currency and level (BIAN
 * Behavior Qualifier: {@code retrieve}, collection). The level is judged when read, so it is filtered in memory (ADR-031).
 */
@Singleton
public class RetrieveCloudAccountsUseCase {

    static final int JUDGED_AT_ONCE = 4;
    private static final Logger log = LoggerFactory.getLogger(RetrieveCloudAccountsUseCase.class);

    private final CloudAccountRepository repository;
    private final CostAssessor assessor;

    public RetrieveCloudAccountsUseCase(CloudAccountRepository repository, CostAssessor assessor) {
        this.repository = repository;
        this.assessor = assessor;
    }

    public Flux<CloudAccountResponse> execute(UUID organisationId, AccountStatus status, Provider provider, Currency currency, Level level, String role) {
        log.info("[USE CASE] Retrieving the CloudAccounts of organisation: {}", organisationId);
        return Mono.fromRunnable(() -> CostAccess.requireStaff(role, "list cloud accounts"))
                .thenMany(Flux.defer(() -> repository.findAll(organisationId)
                        .filter(account -> status == null || account.getStatus() == status)
                        .filter(account -> provider == null || account.getProvider() == provider)
                        .filter(account -> currency == null || account.getCurrency() == currency)
                        .flatMapSequential(assessor::judge, JUDGED_AT_ONCE)
                        .filter(judged -> level == null || judged.assessment().level() == level)
                        .map(judged -> CostMapper.toResponse(judged.account(), judged.assessment()))));
    }
}
