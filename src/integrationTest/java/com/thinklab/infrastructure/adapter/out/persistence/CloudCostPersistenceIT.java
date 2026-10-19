package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.DuplicateCloudAccountException;
import com.thinklab.domain.exception.InvalidCloudAccountStatusException;
import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.CloudAccount.AccountStatus;
import com.thinklab.domain.model.CloudAccount.DayCost;
import com.thinklab.domain.model.CloudAccount.Provider;
import com.thinklab.domain.model.CostReport;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.repository.CloudAccountRepository;
import com.thinklab.domain.repository.CostReportRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cloud cost against a real MongoDB: the unique account name and provider account, the guarded save, the last report that only moves forward,
 * one report per (account, day, amount) under twenty simultaneous reports, corrections whose latest counts for the month, restating an earlier amount, and the indexes.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudCostPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "cloud_cost_it";
    private static final LocalDate TODAY = LocalDate.parse("2026-10-10");

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject CloudAccountRepository accounts;
    @Inject CostReportRepository reports;
    @Inject MongoClient mongoClient;

    private CloudAccount newAccount(UUID organisation, String name, String externalId) {
        return CloudAccount.createNew(UUID.randomUUID(), organisation, name, Provider.AWS, externalId, Currency.USD, 310_000, 80, 48, null, "op-1");
    }

    private CostReport newReport(UUID organisation, UUID account, String day, long amount, Instant at) {
        return CostReport.createNew(UUID.randomUUID(), organisation, account, LocalDate.parse(day), amount, "billing", TODAY, at);
    }

    @Test
    @DisplayName("an account is read back whole, only for its own organisation; the name and the provider account are unique per organisation")
    void accountRoundTrip() {
        UUID organisation = UUID.randomUUID();
        CloudAccount first = accounts.create(newAccount(organisation, "Production", "111")).block();
        accounts.create(newAccount(organisation, "Data", "222")).block();

        CloudAccount found = accounts.findById(first.getId(), organisation).block();

        assertEquals(310_000, found.getBudgetMinor());
        assertEquals(Currency.USD, found.getCurrency());
        assertEquals("111", found.getExternalId());
        assertNull(accounts.findById(first.getId(), UUID.randomUUID()).block());
        assertEquals(List.of("Production", "Data"), accounts.findAll(organisation).map(CloudAccount::getName).collectList().block());
        assertThrows(DuplicateCloudAccountException.class, () -> accounts.create(newAccount(organisation, "Production", "333")).block());
        assertThrows(DuplicateCloudAccountException.class, () -> accounts.create(newAccount(organisation, "Other", "111")).block());
        accounts.create(newAccount(UUID.randomUUID(), "Production", "111")).block();
    }

    @Test
    @DisplayName("a change is guarded by the status loaded and saves the definition; the last report only moves forward")
    void guardedSaveAndLastReport() {
        UUID organisation = UUID.randomUUID();
        CloudAccount created = accounts.create(newAccount(organisation, "Guarded", "444")).block();
        CloudAccount one = accounts.findById(created.getId(), organisation).block();
        CloudAccount two = accounts.findById(created.getId(), organisation).block();
        accounts.save(one, AccountStatus.ACTIVE, one.update("Guarded 2", 500_000, 90, 24, null, "op-1")).block();
        var pause = two.pause("op-2");
        two.update("x", 1, 90, 24, null, "op-2");
        accounts.save(two, AccountStatus.ACTIVE, pause).block();
        CloudAccount loaded = accounts.findById(created.getId(), organisation).block();
        var pauseAgain = loaded.resume("op-1");
        assertThrows(InvalidCloudAccountStatusException.class, () -> accounts.save(loaded, AccountStatus.ACTIVE, pauseAgain).block());

        Instant t1 = Instant.parse("2026-10-10T10:00:00Z");
        accounts.recordReport(created.getId(), organisation, t1).block();
        accounts.recordReport(created.getId(), organisation, t1.minusSeconds(3600)).block();
        assertEquals(t1, accounts.findById(created.getId(), organisation).block().getLastReportedAt());
        accounts.recordReport(created.getId(), organisation, t1.plusSeconds(60)).block();
        assertEquals(t1.plusSeconds(60), accounts.findById(created.getId(), organisation).block().getLastReportedAt());
    }

    @Test
    @DisplayName("twenty simultaneous reports of one day and amount store one; a correction is another report, the month keeps the latest of each day, and going back to an earlier amount restates it")
    void reportsAndMonth() {
        UUID organisation = UUID.randomUUID();
        UUID account = accounts.create(newAccount(organisation, "Reports", "555")).block().getId();
        Instant at = Instant.parse("2026-10-10T10:00:00Z");

        List<Boolean> created = Flux.range(0, 20).flatMap(i -> reports.create(newReport(organisation, account, "2026-10-01", 1_000, at)).map(stored -> stored.created())
                .subscribeOn(Schedulers.parallel()), 20).collectList().block();
        assertEquals(1, created.stream().filter(c -> c).count());

        assertTrue(reports.create(newReport(organisation, account, "2026-10-01", 1_200, at.plusSeconds(60))).block().created());
        reports.create(newReport(organisation, account, "2026-10-02", 900, at)).block();
        reports.create(newReport(organisation, account, "2026-09-30", 99_999, at)).block();

        assertEquals(List.of(new DayCost(LocalDate.parse("2026-10-01"), 1_200), new DayCost(LocalDate.parse("2026-10-02"), 900)),
                reports.latestOfMonth(organisation, account, YearMonth.of(2026, 10)).block());
        assertEquals(List.of(new DayCost(LocalDate.parse("2026-09-30"), 99_999)), reports.latestOfMonth(organisation, account, YearMonth.of(2026, 9)).block());
        assertEquals(4, reports.findAll(organisation, account, 50).count().block());
        assertEquals("2026-10-02", reports.findAll(organisation, account, 1).blockFirst().getDay().toString());
        CostReport stored = reports.restate(organisation, account, LocalDate.parse("2026-10-01"), 1_000, at.plusSeconds(120)).block();
        assertEquals(at.plusSeconds(120), stored.getReportedAt());
        assertEquals(List.of(new DayCost(LocalDate.parse("2026-10-01"), 1_000), new DayCost(LocalDate.parse("2026-10-02"), 900)),
                reports.latestOfMonth(organisation, account, YearMonth.of(2026, 10)).block());
        assertEquals(at.plusSeconds(120), reports.restate(organisation, account, LocalDate.parse("2026-10-01"), 1_000, at).block().getReportedAt());
        assertNull(reports.restate(organisation, account, LocalDate.parse("2026-10-01"), 7, at).block());
        assertEquals(stored.getId(), reports.findById(stored.getId(), organisation).block().getId());
        assertNull(reports.findById(stored.getId(), UUID.randomUUID()).block());
    }

    @Test
    @DisplayName("startup created the indexes, the unique ones and the expiry (TTL)")
    void indexesExist() {
        List<Document> accountIndexes = indexes("cloud_accounts");
        List<Document> reportIndexes = indexes("cost_reports");
        assertTrue(accountIndexes.stream().anyMatch(index -> "organisationId_1_name_1".equals(index.getString("name")) && Boolean.TRUE.equals(index.getBoolean("unique"))));
        assertTrue(accountIndexes.stream().anyMatch(index -> "organisationId_1_provider_1_externalId_1".equals(index.getString("name")) && Boolean.TRUE.equals(index.getBoolean("unique"))));
        assertTrue(reportIndexes.stream().anyMatch(index -> "organisationId_1_accountId_1_day_1_amountMinor_1".equals(index.getString("name")) && Boolean.TRUE.equals(index.getBoolean("unique"))));
        assertTrue(reportIndexes.stream().anyMatch(index -> "reportedAt_1".equals(index.getString("name")) && index.get("expireAfterSeconds") != null));
    }

    private List<Document> indexes(String collection) {
        return Flux.from(mongoClient.getDatabase(DATABASE).getCollection(collection).listIndexes()).map(index -> (Document) index).collectList().block();
    }
}
