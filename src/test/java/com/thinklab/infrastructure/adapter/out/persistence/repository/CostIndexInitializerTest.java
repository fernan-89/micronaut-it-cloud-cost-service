package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class CostIndexInitializerTest {

    private final MongoClient client = mock(MongoClient.class);

    @Test
    @DisplayName("startup creates the unique account name, the unique provider account, the unique report key, the month index and the expiry (TTL) index")
    void indexes() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> accounts = mock(MongoCollection.class);
        MongoCollection<Document> reports = mock(MongoCollection.class);
        when(client.getDatabase("tenant_cost")).thenReturn(database);
        when(database.getCollection("cloud_accounts")).thenReturn(accounts);
        when(database.getCollection("cost_reports")).thenReturn(reports);
        when(accounts.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));
        when(reports.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));

        new CostIndexInitializer(client, "mongodb://mongo:27017/tenant_cost", 400).onApplicationEvent(mock(StartupEvent.class));

        ArgumentCaptor<IndexOptions> accountOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(accounts, times(2)).createIndex(any(), accountOptions.capture());
        assertTrue(accountOptions.getAllValues().get(0).isUnique());
        assertEquals("organisationId_1_name_1", accountOptions.getAllValues().get(0).getName());
        assertTrue(accountOptions.getAllValues().get(1).isUnique());
        assertEquals("organisationId_1_provider_1_externalId_1", accountOptions.getAllValues().get(1).getName());
        ArgumentCaptor<IndexOptions> reportOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(reports, times(3)).createIndex(any(), reportOptions.capture());
        assertTrue(reportOptions.getAllValues().get(0).isUnique());
        assertEquals("organisationId_1_accountId_1_day_1_amountMinor_1", reportOptions.getAllValues().get(0).getName());
        assertFalse(reportOptions.getAllValues().get(1).isUnique());
        assertEquals("organisationId_1_accountId_1_day_1_reportedAt_1", reportOptions.getAllValues().get(1).getName());
        assertEquals("reportedAt_1", reportOptions.getAllValues().get(2).getName());
        assertEquals(400L * 24 * 3600, reportOptions.getAllValues().get(2).getExpireAfter(TimeUnit.SECONDS));
        assertEquals(null, reportOptions.getAllValues().get(1).getExpireAfter(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated; the URI may omit the database; arguments are null-checked")
    void failOpen() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase("thinklab_it_cloud_cost_db")).thenReturn(database);
        when(database.getCollection(any(String.class))).thenReturn(collection);
        when(collection.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new MongoTimeoutException("no server")))
                .thenReturn(Mono.error(new IllegalStateException("rejected"))).thenReturn(Mono.just("ok"));
        var initializer = new CostIndexInitializer(client, "mongodb://mongo:27017", 400);

        initializer.onApplicationEvent(mock(StartupEvent.class));

        verify(collection, times(5)).createIndex(any(), any(IndexOptions.class));
        assertThrows(NullPointerException.class, () -> initializer.onApplicationEvent(null));
        assertThrows(NullPointerException.class, () -> new CostIndexInitializer(null, "mongodb://mongo:27017", 400));
        assertThrows(NullPointerException.class, () -> new CostIndexInitializer(client, null, 400));
    }
}
