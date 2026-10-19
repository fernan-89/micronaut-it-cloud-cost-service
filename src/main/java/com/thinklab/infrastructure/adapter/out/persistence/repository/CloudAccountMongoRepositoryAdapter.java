package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateCloudAccountException;
import com.thinklab.domain.exception.InvalidCloudAccountStatusException;
import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.CloudAccount.AccountAuditEntry;
import com.thinklab.domain.model.CloudAccount.AccountStatus;
import com.thinklab.domain.repository.CloudAccountRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CloudAccountDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CloudAccountDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CloudAccountDocument.CloudAccountPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the CloudAccount aggregate, raw reactive-streams driver. Every change is one atomic update guarded by
 * the status loaded (ADR-033) that also appends the audit entry; a name unique per organisation is the atomic backstop of a duplicate. The
 * latest report only ever moves forward in time (a compare on the moment), so it does not matter in
 * which order, or how many times, the reports arrive (ADR-030).
 */
@Singleton
public class CloudAccountMongoRepositoryAdapter implements CloudAccountRepository {

    private static final Logger log = LoggerFactory.getLogger(CloudAccountMongoRepositoryAdapter.class);
    static final String DEFAULT_DATABASE = "thinklab_it_cloud_cost_db";
    static final String COLLECTION_NAME = "cloud_accounts";
    static final int DUPLICATE_KEY = 11000;
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_LAST_REPORTED = "lastReportedAt";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public CloudAccountMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<CloudAccountDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, CloudAccountDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<CloudAccount> create(CloudAccount account) {
        log.debug("[PERSISTENCE] Monolithic create for CloudAccount Aggregate: {}", account.getId());

        return Mono.from(getCollection().insertOne(CloudAccountPersistenceMapper.toDocument(account)))
                .map(result -> account)
                .onErrorMap(MongoWriteException.class, error -> duplicate(error, account));
    }

    @Override
    public Mono<CloudAccount> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(CloudAccountPersistenceMapper::toDomain);
    }

    @Override
    public Flux<CloudAccount> findAll(UUID organisationId) {
        return Flux.from(getCollection().find(Filters.eq(FIELD_ORGANISATION, organisationId)).sort(Sorts.ascending("createdAt"))).map(CloudAccountPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(CloudAccount account, AccountStatus expectedStatus, AccountAuditEntry auditEntry) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, account.getId()), Filters.eq(FIELD_ORGANISATION, account.getOrganisationId()), Filters.eq(FIELD_STATUS, expectedStatus.name()));
        Bson update = Updates.combine(
                Updates.set("name", account.getName()),
                Updates.set("budgetMinor", account.getBudgetMinor()),
                Updates.set("warnPercent", account.getWarnPercent()),
                Updates.set("reportEveryHours", account.getReportEveryHours()),
                Updates.set("vendorId", account.getVendorId()),
                Updates.set(FIELD_STATUS, account.getStatus().name()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidCloudAccountStatusException("CloudAccount was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty())
                .onErrorMap(MongoWriteException.class, error -> duplicate(error, account));
    }

    @Override
    public Mono<Void> recordReport(UUID accountId, UUID organisationId, Instant reportedAt) {
        Bson older = Filters.and(Filters.eq(FIELD_ID, accountId), Filters.eq(FIELD_ORGANISATION, organisationId),
                Filters.or(Filters.eq(FIELD_LAST_REPORTED, null), Filters.lt(FIELD_LAST_REPORTED, reportedAt)));
        return Mono.from(getCollection().updateOne(older, Updates.set(FIELD_LAST_REPORTED, reportedAt))).then();
    }

    private static Throwable duplicate(MongoWriteException error, CloudAccount account) {
        return error.getError().getCode() == DUPLICATE_KEY
                ? new DuplicateCloudAccountException("An account named '" + account.getName() + "', or the same " + account.getProvider() + " account, already exists in this organisation.") : error;
    }
}
