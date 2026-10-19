package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateCloudAccountException;
import com.thinklab.domain.exception.InvalidCloudAccountStatusException;
import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.CloudAccount.Provider;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.CloudAccount.AccountStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CloudAccountDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CloudAccountDocument.CloudAccountPersistenceMapper;
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
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class CloudAccountMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/cap_test";

    private final UUID org = UUID.randomUUID();
    private MongoClient client;
    private MongoCollection<CloudAccountDocument> collection;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase("cap_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_cloud_cost_db")).thenReturn(database);
        when(database.getCollection("cloud_accounts", CloudAccountDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
    }

    private CloudAccount account() {
        CloudAccount account = CloudAccount.createNew(UUID.randomUUID(), org, "Production", Provider.AWS, "123456789012", Currency.USD, 310_000, 80, 48, UUID.randomUUID(), "op");
        account.pause("op");
        return account;
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private void finds(CloudAccount found) {
        FindPublisher<CloudAccountDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<CloudAccountDocument> subscriber = invocation.getArgument(0);
            Flux.just(CloudAccountPersistenceMapper.toDocument(found)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
    }

    @Test
    @DisplayName("an account survives the round trip, with its budget in minor units, its last report and its audit trail")
    void roundTrip() {
        CloudAccount account = account();
        Instant at = Instant.parse("2026-10-10T10:00:00Z");
        CloudAccount reported = CloudAccount.reconstitute(account.getId(), org, account.getName(), Provider.AZURE, "sub-1", Currency.EUR, 123_45, 70, 12, account.getVendorId(),
                AccountStatus.PAUSED, at, at, at, account.getAuditTrail());

        CloudAccountDocument document = CloudAccountPersistenceMapper.toDocument(reported);
        CloudAccount back = CloudAccountPersistenceMapper.toDomain(document);

        assertEquals(AccountStatus.PAUSED, back.getStatus());
        assertEquals(Provider.AZURE, back.getProvider());
        assertEquals("sub-1", back.getExternalId());
        assertEquals(Currency.EUR, back.getCurrency());
        assertEquals(12_345, back.getBudgetMinor());
        assertEquals(70, back.getWarnPercent());
        assertEquals(12, back.getReportEveryHours());
        assertEquals(account.getVendorId(), back.getVendorId());
        assertEquals(at, back.getLastReportedAt());
        assertEquals(2, back.getAuditTrail().size());
        assertNull(back.getAuditTrail().get(0).fromStatus());
        assertEquals(AccountStatus.ACTIVE, back.getAuditTrail().get(1).fromStatus());
        assertEquals("Production", document.getName());
        assertEquals("AZURE", document.getProvider());
        assertEquals("EUR", document.getCurrency());
        assertEquals(12_345, document.getBudgetMinor());
        assertEquals("PAUSED", document.getStatus());
        assertEquals(org, document.getOrganisationId());
        assertEquals(account.getId(), document.getId());
        assertEquals(at, document.getCreatedAt());
        assertEquals(at, document.getUpdatedAt());
    }

    @Test
    @DisplayName("the document setters are plain, and the persistence mapper is a utility class")
    void plainDocument() throws ReflectiveOperationException {
        CloudAccountDocument document = new CloudAccountDocument();
        document.setLastReportedAt(null);
        document.setAuditTrail(new ArrayList<>());
        assertNull(document.getLastReportedAt());
        assertTrue(document.getAuditTrail().isEmpty());

        Constructor<CloudAccountPersistenceMapper> constructor = CloudAccountPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create and the lookups are scoped to the organisation, the list oldest first")
    void lookups() {
        CloudAccount account = account();
        when(collection.insertOne(any(CloudAccountDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        finds(account);
        CloudAccountMongoRepositoryAdapter adapter = new CloudAccountMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(account)).expectNext(account).verifyComplete();
        StepVerifier.create(adapter.findById(account.getId(), org)).assertNext(found -> assertEquals(account.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findAll(org)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).find(filter.capture());
        assertTrue(filter.getAllValues().get(0).toString().contains("organisationId"));
    }

    @Test
    @DisplayName("a duplicate name is a 409 of the domain, on create or on a rename; any other write error passes through; the URI may omit the database")
    void duplicates() {
        CloudAccount account = account();
        when(collection.insertOne(any(CloudAccountDocument.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(2)));
        CloudAccountMongoRepositoryAdapter adapter = new CloudAccountMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(account)).expectError(DuplicateCloudAccountException.class).verify();
        StepVerifier.create(adapter.create(account)).expectError(MongoWriteException.class).verify();
        StepVerifier.create(adapter.save(account, AccountStatus.ACTIVE, account.getAuditTrail().get(1))).expectError(DuplicateCloudAccountException.class).verify();
        StepVerifier.create(adapter.save(account, AccountStatus.ACTIVE, account.getAuditTrail().get(1))).expectError(MongoWriteException.class).verify();
        assertThrows(NullPointerException.class, () -> new CloudAccountMongoRepositoryAdapter(client, null));
    }

    @Test
    @DisplayName("save is guarded by the status loaded and pushes the audit entry; a lost race is a conflict")
    void save() {
        CloudAccount account = account();
        when(collection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        CloudAccountMongoRepositoryAdapter adapter = new CloudAccountMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(account, AccountStatus.ACTIVE, account.getAuditTrail().get(1))).verifyComplete();
        StepVerifier.create(adapter.save(account, AccountStatus.ACTIVE, account.getAuditTrail().get(1))).expectError(InvalidCloudAccountStatusException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getAllValues().get(0).toString().contains("ACTIVE") && guard.getAllValues().get(0).toString().contains("organisationId"));
        assertTrue(update.getAllValues().get(0).toString().contains("auditTrail"));
    }

    @Test
    @DisplayName("save writes the whole definition next to the status and the audit entry")
    void saveWritesTheDefinition() {
        CloudAccount account = account();
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        CloudAccountMongoRepositoryAdapter adapter = new CloudAccountMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(account, AccountStatus.ACTIVE, account.getAuditTrail().get(1))).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(any(Bson.class), update.capture());
        String text = update.getValue().toString();
        for (String field : new String[]{"name", "budgetMinor", "warnPercent", "reportEveryHours", "vendorId"}) {
            assertTrue(text.contains(field), field);
        }
        assertFalse(text.contains("currency") || text.contains("externalId"));
    }

    @Test
    @DisplayName("a report moves the last report forward only when it is newer than the one kept (or none is kept)")
    void recordReport() {
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        CloudAccountMongoRepositoryAdapter adapter = new CloudAccountMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.recordReport(UUID.randomUUID(), org, Instant.parse("2026-10-10T10:00:00Z"))).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(filter.capture(), update.capture());
        String guard = filter.getValue().toString();
        assertTrue(guard.contains("organisationId") && guard.contains("lastReportedAt") && guard.contains("$lt"));
        assertTrue(update.getValue().toString().contains("lastReportedAt"));
    }
}
