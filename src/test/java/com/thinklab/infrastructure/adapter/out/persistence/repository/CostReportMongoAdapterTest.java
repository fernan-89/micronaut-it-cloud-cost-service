package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.model.CloudAccount.DayCost;
import com.thinklab.domain.model.CostReport;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CostReportDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CostReportDocument.CostReportPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class CostReportMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/cap_test";

    private final UUID org = UUID.randomUUID();
    private MongoClient client;
    private MongoCollection<CostReportDocument> collection;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase("cap_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_cloud_cost_db")).thenReturn(database);
        when(database.getCollection("cost_reports", CostReportDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
    }

    private CostReport report(long amountMinor, LocalDate day) {
        return CostReport.createNew(UUID.randomUUID(), org, UUID.randomUUID(), day, amountMinor, "billing", LocalDate.parse("2026-10-10"), Instant.parse("2026-10-10T12:00:00Z"));
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private FindPublisher<CostReportDocument> finds(CostReport... found) {
        FindPublisher<CostReportDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        when(publisher.limit(anyInt())).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<CostReportDocument> subscriber = invocation.getArgument(0);
            Flux.fromArray(found).map(CostReportPersistenceMapper::toDocument).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
        return publisher;
    }

    private void restates(CostReport... found) {
        when(collection.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(Flux.fromArray(found).map(CostReportPersistenceMapper::toDocument).next());
    }

    @Test
    @DisplayName("a report survives the round trip, with its day as text, its amount in minor units and who reported it")
    void roundTrip() {
        CostReport report = report(12_345, LocalDate.parse("2026-10-09"));
        CostReportDocument document = CostReportPersistenceMapper.toDocument(report);
        CostReport back = CostReportPersistenceMapper.toDomain(document);

        assertEquals(12_345, back.getAmountMinor());
        assertEquals(LocalDate.parse("2026-10-09"), back.getDay());
        assertEquals("billing", back.getReportedBy());
        assertEquals(report.getReportedAt(), back.getReportedAt());
        assertEquals(org, document.getOrganisationId());
        assertEquals(report.getAccountId(), document.getAccountId());
        assertEquals(report.getId(), document.getId());
        assertEquals("2026-10-09", document.getDay());
        assertEquals(12_345, document.getAmountMinor());
    }

    @Test
    @DisplayName("the persistence mapper is a utility class")
    void mapperIsUtility() throws ReflectiveOperationException {
        Constructor<CostReportPersistenceMapper> constructor = CostReportPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create answers the report as created; the lookups are scoped to the organisation; the URI may omit the database and must not be null")
    void lookups() {
        CostReport report = report(100, LocalDate.parse("2026-10-09"));
        when(collection.insertOne(any(CostReportDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        finds(report);
        CostReportMongoRepositoryAdapter adapter = new CostReportMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(report)).assertNext(stored -> assertTrue(stored.created())).verifyComplete();
        StepVerifier.create(adapter.findById(report.getId(), org)).assertNext(found -> assertEquals(report.getId(), found.getId())).verifyComplete();
        restates(report);
        StepVerifier.create(adapter.restate(org, report.getAccountId(), report.getDay(), report.getAmountMinor(), Instant.parse("2026-10-11T00:00:00Z")))
                .expectNextCount(1).verifyComplete();
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection).findOneAndUpdate(any(Bson.class), update.capture(), any(FindOneAndUpdateOptions.class));
        assertTrue(update.getValue().toString().contains("$max") && update.getValue().toString().contains("reportedAt"));
        assertThrows(NullPointerException.class, () -> new CostReportMongoRepositoryAdapter(client, null));
    }

    @Test
    @DisplayName("a duplicate key means the report was already reported: the stored one comes back restated, not created; any other error passes through")
    void duplicate() {
        CostReport report = report(100, LocalDate.parse("2026-10-09"));
        when(collection.insertOne(any(CostReportDocument.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        restates(report);
        CostReportMongoRepositoryAdapter adapter = new CostReportMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(report)).assertNext(stored -> {
            assertFalse(stored.created());
            assertEquals(report.getId(), stored.report().getId());
        }).verifyComplete();
        StepVerifier.create(adapter.create(report)).expectError(MongoWriteException.class).verify();
    }

    @Test
    @DisplayName("the list is scoped to the organisation, to the account when one is given, newest first and limited")
    void list() {
        CostReport report = report(100, LocalDate.parse("2026-10-09"));
        FindPublisher<CostReportDocument> publisher = finds(report);
        CostReportMongoRepositoryAdapter adapter = new CostReportMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.findAll(org, null, 5)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(org, report.getAccountId(), 7)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).find(filter.capture());
        assertTrue(filter.getAllValues().get(0).toString().contains("organisationId") && !filter.getAllValues().get(0).toString().contains("accountId"));
        assertTrue(filter.getAllValues().get(1).toString().contains("accountId"));
        verify(publisher).limit(5);
        verify(publisher).limit(7);
    }

    @Test
    @DisplayName("the month reads the account's reports of that month, by day and arrival, and keeps the latest of each day")
    void latestOfMonth() {
        UUID account = UUID.randomUUID();
        CostReport first = CostReport.reconstitute(UUID.randomUUID(), org, account, LocalDate.parse("2026-10-01"), 1_000, "b", Instant.parse("2026-10-02T00:00:00Z"));
        CostReport corrected = CostReport.reconstitute(UUID.randomUUID(), org, account, LocalDate.parse("2026-10-01"), 1_200, "b", Instant.parse("2026-10-03T00:00:00Z"));
        CostReport second = CostReport.reconstitute(UUID.randomUUID(), org, account, LocalDate.parse("2026-10-02"), 900, "b", Instant.parse("2026-10-03T00:00:00Z"));
        finds(first, corrected, second);
        CostReportMongoRepositoryAdapter adapter = new CostReportMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.latestOfMonth(org, account, YearMonth.of(2026, 10)))
                .expectNext(List.of(new DayCost(LocalDate.parse("2026-10-01"), 1_200), new DayCost(LocalDate.parse("2026-10-02"), 900))).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).find(filter.capture());
        String text = filter.getValue().toString();
        assertTrue(text.contains("2026-10-01") && text.contains("2026-10-31") && text.contains("accountId"));
    }
}
