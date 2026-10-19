package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidCloudAccountStatusException;
import com.thinklab.domain.model.CloudAccount.AccountAuditEntry;
import com.thinklab.domain.model.CloudAccount.AccountStatus;
import com.thinklab.domain.model.CloudAccount.Assessment;
import com.thinklab.domain.model.CloudAccount.DayCost;
import com.thinklab.domain.model.CloudAccount.Level;
import com.thinklab.domain.model.CloudAccount.Provider;
import com.thinklab.domain.model.Money.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudAccountTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-10");
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final UUID org = UUID.randomUUID();
    private final UUID vendor = UUID.randomUUID();

    /** A budget of 3100.00 USD a month, warning at 80 percent, a report expected every 48 hours. */
    private CloudAccount account() {
        return CloudAccount.createNew(UUID.randomUUID(), org, "Production", Provider.AWS, "123456789012", Currency.USD, 310_000, 80, 48, vendor, "op");
    }

    private static DayCost day(int dayOfMonth, long amountMinor) {
        return new DayCost(TODAY.withDayOfMonth(dayOfMonth), amountMinor);
    }

    private static void rejects(Executable action) {
        assertThrows(IllegalArgumentException.class, action);
    }

    @Test
    @DisplayName("a new account is ACTIVE, keeps its provider, id, currency and budget in minor units, and starts its trail")
    void createNew() {
        CloudAccount account = account();

        assertEquals(AccountStatus.ACTIVE, account.getStatus());
        assertEquals(Provider.AWS, account.getProvider());
        assertEquals("123456789012", account.getExternalId());
        assertEquals(Currency.USD, account.getCurrency());
        assertEquals(310_000, account.getBudgetMinor());
        assertEquals(80, account.getWarnPercent());
        assertEquals(48, account.getReportEveryHours());
        assertEquals(vendor, account.getVendorId());
        assertNull(account.getLastReportedAt());
        assertEquals("AWS account with a monthly budget of 3100.00 USD.", account.getAuditTrail().get(0).detail());
        assertNull(account.getAuditTrail().get(0).fromStatus());
        assertEquals(account.getCreatedAt(), account.getUpdatedAt());
    }

    @Test
    @DisplayName("an account needs an identity, a name, a provider, a currency, a provider id that is an identifier, a sane budget, warning and interval, and an executor")
    void validation() {
        UUID id = UUID.randomUUID();
        rejects(() -> CloudAccount.createNew(null, org, "n", Provider.AWS, "x", Currency.BRL, 0, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, null, "n", Provider.AWS, "x", Currency.BRL, 0, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, null, Provider.AWS, "x", Currency.BRL, 0, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, " ", Provider.AWS, "x", Currency.BRL, 0, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "x".repeat(81), Provider.AWS, "x", Currency.BRL, 0, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", null, "x", Currency.BRL, 0, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, "x", null, 0, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, null, Currency.BRL, 0, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, "has space", Currency.BRL, 0, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, "x".repeat(65), Currency.BRL, 0, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, "x", Currency.BRL, -1, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, "x", Currency.BRL, Money.MAX_MINOR + 1, 80, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, "x", Currency.BRL, 0, 0, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, "x", Currency.BRL, 0, 101, 48, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, "x", Currency.BRL, 0, 80, 0, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, "x", Currency.BRL, 0, 80, 721, null, "op"));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, "x", Currency.BRL, 0, 80, 48, null, null));
        rejects(() -> CloudAccount.createNew(id, org, "n", Provider.AWS, "x", Currency.BRL, 0, 80, 48, null, " "));
        assertEquals("sub:1234-abcd_ef.9", CloudAccount.createNew(id, org, "x".repeat(80), Provider.AZURE, "sub:1234-abcd_ef.9", Currency.EUR, Money.MAX_MINOR, 100, 720, null, "op").getExternalId());
    }

    @Test
    @DisplayName("update changes the name, budget, warning, interval and vendor in any status; provider, id and currency never change")
    void update() {
        CloudAccount account = account();

        AccountAuditEntry entry = account.update("Prod", 500_000, 90, 24, null, "op-2");

        assertEquals("Prod", account.getName());
        assertEquals(500_000, account.getBudgetMinor());
        assertEquals(90, account.getWarnPercent());
        assertEquals(24, account.getReportEveryHours());
        assertNull(account.getVendorId());
        assertEquals("123456789012", account.getExternalId());
        assertEquals("Account updated: a monthly budget of 5000.00 USD.", entry.detail());
        account.pause("op");
        account.update("Prod", 1, 90, 24, null, "op");
        assertEquals(AccountStatus.PAUSED, account.getStatus());
        rejects(() -> account.update("Prod", -1, 90, 24, null, "op"));
        rejects(() -> account.update("Prod", 1, 90, 24, null, null));
    }

    @Test
    @DisplayName("pause and resume go one way each; the wrong state is a conflict")
    void lifecycle() {
        CloudAccount account = account();

        assertThrows(InvalidCloudAccountStatusException.class, () -> account.resume("op"));
        assertEquals("PAUSED", account.pause("op").action());
        assertThrows(InvalidCloudAccountStatusException.class, () -> account.pause("op"));
        assertEquals(AccountStatus.PAUSED, account.resume("op").fromStatus());
        rejects(() -> account.pause(null));
        account.pause("op");
        rejects(() -> account.resume(" "));
    }

    @Test
    @DisplayName("the month so far is the sum of its days, carried to the month end in a straight line from the last day reported")
    void assess() {
        CloudAccount account = account();

        Assessment ok = account.assess(TODAY, NOW, List.of(day(1, 5_000), day(2, 5_000), day(3, 5_000), day(4, 5_000), day(5, 5_000)));
        assertEquals(new Assessment(YearMonth.of(2026, 10), Level.OK, 25_000L, 155_000L, 25_000 * 100.0 / 310_000, false), ok);

        Assessment heading = account.assess(TODAY, NOW, List.of(day(1, 20_000), day(2, 20_000)));
        assertEquals(Level.NEAR_BUDGET, heading.level());
        assertEquals(620_000L, heading.projectedMinor());

        assertEquals(Level.NEAR_BUDGET, account.assess(TODAY, NOW, List.of(day(25, 250_000))).level());
        Assessment over = account.assess(TODAY, NOW, List.of(day(9, 310_001)));
        assertEquals(Level.OVER_BUDGET, over.level());
        assertEquals(310_001L, over.spentMinor());
    }

    @Test
    @DisplayName("days of another month are left out; no day this month is NO_DATA; a paused account is not judged; a budget of zero is over with any spend")
    void assessEdges() {
        CloudAccount account = account();

        assertEquals(new Assessment(YearMonth.of(2026, 10), Level.NO_DATA, null, null, null, false),
                account.assess(TODAY, NOW, List.of(new DayCost(LocalDate.parse("2026-09-30"), 99_999))));
        CloudAccount free = CloudAccount.createNew(UUID.randomUUID(), org, "Free", Provider.GCP, "proj-1", Currency.BRL, 0, 80, 48, null, "op");
        assertEquals(Level.OK, free.assess(TODAY, NOW, List.of(day(1, 0))).level());
        assertEquals(0.0, free.assess(TODAY, NOW, List.of(day(1, 0))).usedPercent());
        assertEquals(Level.OVER_BUDGET, free.assess(TODAY, NOW, List.of(day(1, 1))).level());
        account.pause("op");
        assertEquals(new Assessment(YearMonth.of(2026, 10), Level.PAUSED, null, null, null, false), account.assess(TODAY, NOW, List.of(day(1, 1))));
    }

    @Test
    @DisplayName("the last report only moves forward, and an account silent for more than twice its interval is stale, still judged")
    void stale() {
        CloudAccount account = account();
        assertFalse(account.assess(TODAY, NOW, List.of()).stale());

        account.reported(NOW.minus(Duration.ofHours(97)));
        account.reported(NOW.minus(Duration.ofHours(200)));
        assertEquals(NOW.minus(Duration.ofHours(97)), account.getLastReportedAt());
        assertTrue(account.assess(TODAY, NOW, List.of(day(1, 1))).stale());
        assertTrue(account.assess(TODAY, NOW, List.of()).stale());
        account.reported(NOW.minus(Duration.ofHours(96)));
        assertFalse(account.assess(TODAY, NOW, List.of(day(1, 1))).stale());
    }

    @Test
    @DisplayName("reconstitute keeps what was stored, defaults what is missing, and refuses a missing identity")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        CloudAccount full = CloudAccount.reconstitute(id, org, "n", Provider.OCI, "ocid1", Currency.EUR, 5, 80, 48, vendor, AccountStatus.PAUSED, NOW, NOW.plusSeconds(1), NOW,
                List.of(new AccountAuditEntry(NOW, "INITIATED", "op", null, AccountStatus.ACTIVE, "d")));
        assertEquals(AccountStatus.PAUSED, full.getStatus());
        assertEquals(NOW, full.getLastReportedAt());
        assertEquals(NOW.plusSeconds(1), full.getUpdatedAt());

        CloudAccount bare = CloudAccount.reconstitute(id, org, "n", Provider.OTHER, "x", Currency.BRL, 5, 80, 48, null, null, null, null, null, null);
        assertEquals(AccountStatus.ACTIVE, bare.getStatus());
        assertTrue(bare.getAuditTrail().isEmpty());
        assertEquals(bare.getCreatedAt(), bare.getUpdatedAt());
        rejects(() -> CloudAccount.reconstitute(null, org, "n", Provider.OTHER, "x", Currency.BRL, 5, 80, 48, null, null, null, null, null, null));
        rejects(() -> CloudAccount.reconstitute(id, null, "n", Provider.OTHER, "x", Currency.BRL, 5, 80, 48, null, null, null, null, null, null));
        rejects(() -> CloudAccount.reconstitute(id, org, null, Provider.OTHER, "x", Currency.BRL, 5, 80, 48, null, null, null, null, null, null));
        rejects(() -> CloudAccount.reconstitute(id, org, "n", null, "x", Currency.BRL, 5, 80, 48, null, null, null, null, null, null));
        rejects(() -> CloudAccount.reconstitute(id, org, "n", Provider.OTHER, null, Currency.BRL, 5, 80, 48, null, null, null, null, null, null));
        rejects(() -> CloudAccount.reconstitute(id, org, "n", Provider.OTHER, "x", null, 5, 80, 48, null, null, null, null, null, null));
        assertThrows(UnsupportedOperationException.class, () -> full.getAuditTrail().clear());
    }
}
