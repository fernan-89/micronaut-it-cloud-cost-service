package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.Sorts;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.model.CloudAccount.DayCost;
import com.thinklab.domain.model.CostReport;
import com.thinklab.domain.repository.CostReportRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CostReportDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CostReportDocument.CostReportPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the CostReport aggregate, raw reactive-streams driver. Reports are inserted, and only their moment
 * is ever moved forward. The unique {@code (organisationId, accountId, day, amountMinor)} index is what makes a repeated report the same report:
 * the loser of an insert restates the stored one (ADR-030). A different amount for a day is another report, and the latest one of each day is the one a month is summed from.
 */
@Singleton
public class CostReportMongoRepositoryAdapter implements CostReportRepository {

    static final String COLLECTION_NAME = "cost_reports";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_ACCOUNT = "accountId";
    private static final String FIELD_DAY = "day";
    private static final String FIELD_REPORTED_AT = "reportedAt";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public CostReportMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : CloudAccountMongoRepositoryAdapter.DEFAULT_DATABASE;
    }

    private MongoCollection<CostReportDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, CostReportDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Stored> create(CostReport report) {
        return Mono.from(getCollection().insertOne(CostReportPersistenceMapper.toDocument(report)))
                .map(result -> new Stored(report, true))
                .onErrorResume(MongoWriteException.class, error -> error.getError().getCode() == CloudAccountMongoRepositoryAdapter.DUPLICATE_KEY
                        ? restate(report.getOrganisationId(), report.getAccountId(), report.getDay(), report.getAmountMinor(), report.getReportedAt())
                                .map(existing -> new Stored(existing, false))
                        : Mono.error(error));
    }

    @Override
    public Mono<CostReport> restate(UUID organisationId, UUID accountId, LocalDate day, long amountMinor, Instant at) {
        Bson filter = Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq(FIELD_ACCOUNT, accountId),
                Filters.eq(FIELD_DAY, day.toString()), Filters.eq("amountMinor", amountMinor));
        return Mono.from(getCollection().findOneAndUpdate(filter, Updates.max(FIELD_REPORTED_AT, at), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)))
                .map(CostReportPersistenceMapper::toDomain);
    }

    @Override
    public Mono<CostReport> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(CostReportPersistenceMapper::toDomain);
    }

    @Override
    public Flux<CostReport> findAll(UUID organisationId, UUID accountId, int limit) {
        Bson filter = accountId == null ? Filters.eq(FIELD_ORGANISATION, organisationId)
                : Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq(FIELD_ACCOUNT, accountId));
        return Flux.from(getCollection().find(filter).sort(Sorts.descending(FIELD_DAY, FIELD_REPORTED_AT)).limit(limit)).map(CostReportPersistenceMapper::toDomain);
    }

    @Override
    public Mono<List<DayCost>> latestOfMonth(UUID organisationId, UUID accountId, YearMonth month) {
        Bson filter = Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq(FIELD_ACCOUNT, accountId),
                Filters.gte(FIELD_DAY, month.atDay(1).toString()), Filters.lte(FIELD_DAY, month.atEndOfMonth().toString()));
        return Flux.from(getCollection().find(filter).sort(Sorts.ascending(FIELD_DAY, FIELD_REPORTED_AT)))
                .collectList()
                .map(CostReportMongoRepositoryAdapter::latestPerDay);
    }

    /** Of the reports of each day, ascending by day and arrival, the last one counts. */
    static List<DayCost> latestPerDay(List<CostReportDocument> reports) {
        Map<String, Long> latest = new LinkedHashMap<>();
        for (CostReportDocument report : reports) {
            latest.put(report.getDay(), report.getAmountMinor());
        }
        List<DayCost> days = new ArrayList<>();
        latest.forEach((day, amount) -> days.add(new DayCost(LocalDate.parse(day), amount)));
        return days;
    }
}
