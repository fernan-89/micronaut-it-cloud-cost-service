package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Creates the indexes at startup. The UNIQUE ones are the atomic backstops (ADR-033): an account name per organisation, one registration of a
 * provider account per organisation, and one report per {@code (account, day, amount)} (ADR-030: the same report twice is the same report). The
 * list index serves the month and the report list, and a TTL index expires reports after
 * {@code thinklab.cloud-cost.report-retention-days} (default 400). Fail-open: {@code createIndex} is idempotent, a failure is logged and the
 * application still starts. Turn it off with {@code thinklab.mongo.create-indexes=false}.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class CostIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String ACCOUNT_NAME_INDEX = "organisationId_1_name_1";
    static final String ACCOUNT_EXTERNAL_INDEX = "organisationId_1_provider_1_externalId_1";
    static final String REPORT_KEY_INDEX = "organisationId_1_accountId_1_day_1_amountMinor_1";
    static final String REPORT_LIST_INDEX = "organisationId_1_accountId_1_day_1_reportedAt_1";
    static final String REPORT_EXPIRY_INDEX = "reportedAt_1";

    private static final Logger log = LoggerFactory.getLogger(CostIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;
    private final long retentionDays;

    @Inject
    public CostIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri,
                                    @Property(name = "thinklab.cloud-cost.report-retention-days", defaultValue = "400") long retentionDays) {
        this(mongoClient, mongoUri, TIMEOUT, retentionDays);
    }

    CostIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout, long retentionDays) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : CloudAccountMongoRepositoryAdapter.DEFAULT_DATABASE;
        this.timeout = timeout;
        this.retentionDays = retentionDays;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        String accounts = CloudAccountMongoRepositoryAdapter.COLLECTION_NAME;
        ensureIndex(accounts, ACCOUNT_NAME_INDEX, new Document("organisationId", 1).append("name", 1), new IndexOptions().unique(true));
        ensureIndex(accounts, ACCOUNT_EXTERNAL_INDEX, new Document("organisationId", 1).append("provider", 1).append("externalId", 1), new IndexOptions().unique(true));
        String reports = CostReportMongoRepositoryAdapter.COLLECTION_NAME;
        ensureIndex(reports, REPORT_KEY_INDEX, new Document("organisationId", 1).append("accountId", 1).append("day", 1).append("amountMinor", 1), new IndexOptions().unique(true));
        ensureIndex(reports, REPORT_LIST_INDEX, new Document("organisationId", 1).append("accountId", 1).append("day", 1).append("reportedAt", 1), new IndexOptions());
        ensureIndex(reports, REPORT_EXPIRY_INDEX, new Document("reportedAt", 1), new IndexOptions().expireAfter(retentionDays, TimeUnit.DAYS));
    }

    private void ensureIndex(String collection, String indexName, Document keys, IndexOptions options) {
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(collection).createIndex(keys, options.name(indexName))).block(timeout);
            log.info("[MONGO_INDEXES] Ensured index [{}] on [{}.{}]", indexName, database, collection);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", indexName, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index [{}] on [{}.{}]: {}", indexName, database, collection, e.getMessage());
        }
    }
}
